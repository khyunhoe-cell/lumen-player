package com.lumen.player.subtitle.ai

import android.content.Context
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.data.settings.AiSettings
import com.lumen.player.data.settings.SettingsRepository
import com.lumen.player.data.settings.SttProvider
import com.lumen.player.data.settings.TranslationProvider
import com.lumen.player.data.settings.WhisperModel
import com.lumen.player.remote.RemoteRegistry
import com.lumen.player.subtitle.LoadedSubtitle
import com.lumen.player.subtitle.SubtitleCue
import com.lumen.player.subtitle.SubtitleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

data class AiJob(
    val mediaUri: String,
    val title: String,
    val stage: Stage,
    /** 0..1 */
    val progress: Float,
    val message: String,
    val cueCount: Int = 0,
) {
    enum class Stage { PREPARING, RUNNING, DONE, FAILED, CANCELLED }

    val isActive get() = stage == Stage.PREPARING || stage == Stage.RUNNING
}

/**
 * 오디오 추출과 음성 인식을 동시에 돌린다. 10분 조각 하나가 끝날 때마다
 * 지금까지의 자막을 onPartial로 내보내므로, 생성 중에도 앞부분 자막을 바로 볼 수 있다.
 */
class AiSubtitleGenerator(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val remotes: RemoteRegistry,
    private val whisperModels: WhisperModelManager,
    private val scope: CoroutineScope,
) {
    private val _job = MutableStateFlow<AiJob?>(null)
    val job: StateFlow<AiJob?> = _job.asStateFlow()
    private var running: Job? = null

    fun start(
        entry: MediaEntry,
        subtitles: SubtitleRepository,
        onPartial: (LoadedSubtitle) -> Unit,
        onDone: (LoadedSubtitle) -> Unit,
    ) {
        running?.cancel()
        running = scope.launch {
            val workDir = File(context.cacheDir, "ai_audio/${System.currentTimeMillis()}")
            var extractor: Job? = null
            var ticker: Job? = null
            var translatorRef: TranslationEngine? = null
            var clientRef: SttClient? = null
            // 끝난 뒤에는 진행률 갱신이 완료·실패 상태를 덮어쓰지 않게 한다
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                _job.value = AiJob(entry.uri, entry.title, AiJob.Stage.PREPARING, 0f, "준비 중")
                val ai = settings.current().ai
                val key = settings.apiKey(ai)
                if (ai.provider.needsKey && key.isBlank()) {
                    throw IllegalStateException("설정 › AI 자막에서 ${ai.provider.label} API 키를 넣어 주세요")
                }
                val client: SttClient = when (ai.provider) {
                    SttProvider.GROQ -> OpenAiCompatibleClient(http, "https://api.groq.com/openai/v1", key, ai)
                    SttProvider.OPENAI -> OpenAiCompatibleClient(http, "https://api.openai.com/v1", key, ai)
                    SttProvider.CUSTOM -> OpenAiCompatibleClient(http, ai.customEndpoint, key, ai)
                    SttProvider.DEEPGRAM -> DeepgramClient(http, key, ai)
                    SttProvider.ELEVENLABS -> ElevenLabsClient(http, key, ai)
                    SttProvider.ON_DEVICE -> {
                        WhisperNative.unavailableReason()?.let { throw IllegalStateException(it) }
                        val model = WhisperModel.byId(ai.model())
                        if (!whisperModels.isDownloaded(model)) {
                            throw IllegalStateException("설정 › AI 자막에서 기기 안 Whisper 모델(${model.id}, 약 ${model.sizeMb}MB)을 먼저 받아 주세요")
                        }
                        OnDeviceWhisperClient(whisperModels.file(model), ai)
                    }
                }
                clientRef = client
                val target = ai.target()
                var statusHook: (String) -> Unit = {}
                val translator = if (target.isBlank()) null else translatorFor(ai) { statusHook(it) }
                translatorRef = translator
                val targetName = if (target.isBlank()) "" else java.util.Locale.forLanguageTag(target).displayLanguage.ifBlank { target }
                val label = "AI · ${ai.provider.label} ${ai.model()}" + if (translator != null) " → $targetName" else ""
                val chunks = Channel<AudioChunk>(capacity = 2)
                var extractProgress = 0f
                val cues = mutableListOf<SubtitleCue>()
                var transcribedMs = 0L
                val totalMs = entry.durationMs.coerceAtLeast(1)
                var stageText = "오디오를 추출하는 중"
                var waitUntil = 0L

                fun publish() {
                    if (finished.get()) return
                    // 추출 20%, 인식(번역 포함) 80% 비중으로 합친 진행률
                    val p = (extractProgress * 0.2f + (transcribedMs.toFloat() / totalMs).coerceAtMost(1f) * 0.8f).coerceIn(0f, 0.99f)
                    val waitLeft = waitUntil - System.currentTimeMillis()
                    val msg = buildString {
                        append(stageText)
                        if (extractProgress < 1f) append(" · 오디오 추출 ${(extractProgress * 100).toInt()}%")
                        if (waitLeft > 0) append(" · 사용 한도로 ${waitLeft / 1000 + 1}초 기다리는 중")
                    }
                    _job.value = AiJob(entry.uri, entry.title, AiJob.Stage.RUNNING, p, msg, cues.size)
                }

                fun report(msg: String) {
                    stageText = msg
                    publish()
                }
                statusHook = ::report

                // 추출 진행률과 한도 대기 시간을 0.5초마다 화면에 반영한다
                ticker = launch {
                    while (true) {
                        publish()
                        delay(500)
                    }
                }
                val rateLimit = RateLimitListener { waitMs, _ -> waitUntil = System.currentTimeMillis() + waitMs }

                extractor = launch(Dispatchers.IO) {
                    // 추출 오류는 채널로 넘겨 소비 쪽 for 루프에서 다시 던지게 한다 (부모 취소로 번지지 않게)
                    try {
                        AudioExtractor(context, remotes).extract(
                            entry.uri, workDir,
                            onProgress = { extractProgress = it },
                            onChunk = { chunks.send(it) },
                            durationHintMs = entry.durationMs,
                        )
                        chunks.close()
                    } catch (e: CancellationException) {
                        chunks.close()
                        throw e
                    } catch (e: Throwable) {
                        chunks.close(e)
                    }
                }

                var previousText = ""
                var previousLines = emptyList<String>()
                report("오디오를 추출하는 중")
                for (chunk in chunks) {
                    report("음성을 인식하는 중 · ${formatTime(chunk.startMs)}부터")
                    val chunkProgress = SttProgressListener { pct ->
                        transcribedMs = chunk.startMs + chunk.durationMs * pct / 100
                        stageText = "음성을 인식하는 중 · ${formatTime(chunk.startMs)}부터 · 이 조각 $pct%"
                    }
                    val segments = withContext(Dispatchers.IO + rateLimit + chunkProgress) { client.transcribe(chunk.file, previousText) }
                    chunk.file.delete()
                    val shifted = segments.map { s ->
                        s.copy(
                            startMs = s.startMs + chunk.startMs,
                            endMs = s.endMs + chunk.startMs,
                            words = s.words.map { w -> w.copy(startMs = w.startMs + chunk.startMs, endMs = w.endMs + chunk.startMs) },
                        )
                    }
                    val forCues = if (translator == null || shifted.isEmpty()) shifted else {
                        report("$targetName(으)로 번역하는 중 · ${formatTime(chunk.startMs)}부터")
                        val translated = withContext(Dispatchers.IO + rateLimit) {
                            translator.translate(shifted.map { it.text }, target, ai.language, ai.vocabularyPrompt, previousLines)
                        }
                        // 단어 시간은 원문 기준이라 버리고, 구간 시간 안에서 글자 수 비율로 나눈다
                        shifted.zip(translated) { s, t -> s.copy(text = t, words = emptyList()) }
                    }
                    cues += SegmentFormatter.toCues(forCues, ai.maxCharsPerLine)
                    previousLines = shifted.takeLast(3).map { it.text }
                    previousText = segments.lastOrNull()?.text.orEmpty()
                    transcribedMs = chunk.startMs + chunk.durationMs
                    if (entry.durationMs <= 0) extractProgress = 1f
                    onPartial(LoadedSubtitle("$label (생성 중)", "", cues.toList()))
                    report("음성을 인식하는 중 · ${formatTime(transcribedMs)}까지 완료")
                }
                extractor?.join()
                finished.set(true)
                ticker?.cancel()
                if (cues.isEmpty()) throw IllegalStateException("인식된 대사가 없습니다. 언어 설정을 확인하세요")

                val lang = if (translator != null) target else ai.language.ifBlank { "auto" }
                val file = subtitles.saveSrt(entry.uri, "${entry.title.substringBeforeLast('.')}.ai.$lang", cues)
                val result = LoadedSubtitle(label, file.absolutePath, cues.toList())
                val failed = translator?.failedLines ?: 0
                val doneMsg = if (failed > 0) {
                    "자막 ${cues.size}줄을 만들었습니다. ${failed}줄은 번역하지 못해 원문으로 남겼습니다 (${translator?.lastError})"
                } else {
                    "자막 ${cues.size}줄을 만들었습니다"
                }
                _job.value = AiJob(entry.uri, entry.title, AiJob.Stage.DONE, 1f, doneMsg, cues.size)
                onDone(result)
            } catch (e: CancellationException) {
                finished.set(true)
                _job.value = _job.value?.copy(stage = AiJob.Stage.CANCELLED, message = "생성을 취소했습니다")
                throw e
            } catch (e: Throwable) {
                finished.set(true)
                _job.value = AiJob(entry.uri, entry.title, AiJob.Stage.FAILED, 0f, e.message ?: e.javaClass.simpleName)
            } finally {
                translatorRef?.close()
                clientRef?.close()
                ticker?.cancel()
                extractor?.cancel()
                workDir.deleteRecursively()
            }
        }
    }

    private fun translatorFor(ai: AiSettings, onStatus: (String) -> Unit): TranslationEngine {
        val p = ai.translator
        if (p == TranslationProvider.ON_DEVICE) return MlKitTranslator(onStatus)
        val key = settings.apiKey(ai, p.keyOf)
        if (p.needsKey && key.isBlank()) {
            throw IllegalStateException("설정 › AI 자막 › 자막 번역에서 ${p.label} API 키를 넣어 주세요")
        }
        val baseUrl = when (p) {
            TranslationProvider.GROQ -> "https://api.groq.com/openai/v1"
            TranslationProvider.OPENAI -> "https://api.openai.com/v1"
            TranslationProvider.CUSTOM -> ai.translationEndpoint
            TranslationProvider.ON_DEVICE -> error("위에서 처리")
        }
        return SubtitleTranslator(http, baseUrl, key, ai.translationModel(), jsonMode = p != TranslationProvider.CUSTOM)
    }

    /** 생성에 필요한데 비어 있는 키의 공급자. 없으면 null */
    fun missingKey(ai: AiSettings): SttProvider? {
        if (ai.provider.needsKey && settings.apiKey(ai).isBlank()) return ai.provider
        val keyOf = ai.translator.keyOf
        if (ai.target().isNotBlank() && ai.translator.needsKey && settings.apiKey(ai, keyOf).isBlank()) return keyOf
        return null
    }

    fun cancel() {
        running?.cancel()
        running = null
    }

    fun dismiss() {
        if (_job.value?.isActive != true) _job.value = null
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }
}
