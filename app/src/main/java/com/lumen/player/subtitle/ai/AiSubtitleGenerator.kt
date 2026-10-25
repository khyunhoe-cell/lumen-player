package com.lumen.player.subtitle.ai

import android.content.Context
import com.lumen.player.data.media.MediaEntry
import com.lumen.player.data.settings.SettingsRepository
import com.lumen.player.data.settings.SttProvider
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
            try {
                _job.value = AiJob(entry.uri, entry.title, AiJob.Stage.PREPARING, 0f, "준비 중")
                val ai = settings.current().ai
                val key = settings.apiKey(ai)
                if (ai.provider != SttProvider.CUSTOM && key.isBlank()) {
                    throw IllegalStateException("설정 › AI 자막에서 ${ai.provider.label} API 키를 넣어 주세요")
                }
                val client: SttClient = when (ai.provider) {
                    SttProvider.GROQ -> OpenAiCompatibleClient(http, "https://api.groq.com/openai/v1", key, ai)
                    SttProvider.OPENAI -> OpenAiCompatibleClient(http, "https://api.openai.com/v1", key, ai)
                    SttProvider.CUSTOM -> OpenAiCompatibleClient(http, ai.customEndpoint, key, ai)
                    SttProvider.DEEPGRAM -> DeepgramClient(http, key, ai)
                    SttProvider.ELEVENLABS -> ElevenLabsClient(http, key, ai)
                }
                val label = "AI · ${ai.provider.label} ${ai.model()}"
                val chunks = Channel<AudioChunk>(capacity = 2)
                var extractProgress = 0f
                val cues = mutableListOf<SubtitleCue>()
                var transcribedMs = 0L
                val totalMs = entry.durationMs.coerceAtLeast(1)

                fun report(msg: String) {
                    // 추출 30%, 인식 70% 비중으로 합친 진행률
                    val p = (extractProgress * 0.3f + (transcribedMs.toFloat() / totalMs).coerceAtMost(1f) * 0.7f).coerceIn(0f, 0.99f)
                    _job.value = AiJob(entry.uri, entry.title, AiJob.Stage.RUNNING, p, msg, cues.size)
                }

                extractor = launch(Dispatchers.IO) {
                    // 추출 오류는 채널로 넘겨 소비 쪽 for 루프에서 다시 던지게 한다 (부모 취소로 번지지 않게)
                    try {
                        AudioExtractor(context, remotes).extract(
                            entry.uri, workDir,
                            onProgress = { extractProgress = it },
                            onChunk = { chunks.send(it) },
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
                report("오디오를 추출하는 중")
                for (chunk in chunks) {
                    report("음성을 인식하는 중 · ${formatTime(chunk.startMs)}부터")
                    val segments = withContext(Dispatchers.IO) { client.transcribe(chunk.file, previousText) }
                    chunk.file.delete()
                    val shifted = segments.map { s ->
                        s.copy(
                            startMs = s.startMs + chunk.startMs,
                            endMs = s.endMs + chunk.startMs,
                            words = s.words.map { w -> w.copy(startMs = w.startMs + chunk.startMs, endMs = w.endMs + chunk.startMs) },
                        )
                    }
                    cues += SegmentFormatter.toCues(shifted, ai.maxCharsPerLine)
                    previousText = segments.lastOrNull()?.text.orEmpty()
                    transcribedMs = chunk.startMs + chunk.durationMs
                    if (entry.durationMs <= 0) extractProgress = 1f
                    onPartial(LoadedSubtitle("$label (생성 중)", "", cues.toList()))
                    report("음성을 인식하는 중 · ${formatTime(transcribedMs)}까지 완료")
                }
                extractor?.join()
                if (cues.isEmpty()) throw IllegalStateException("인식된 대사가 없습니다. 언어 설정을 확인하세요")

                val file = subtitles.saveSrt(entry.uri, "${entry.title.substringBeforeLast('.')}.ai.${ai.language.ifBlank { "auto" }}", cues)
                val result = LoadedSubtitle(label, file.absolutePath, cues.toList())
                _job.value = AiJob(entry.uri, entry.title, AiJob.Stage.DONE, 1f, "자막 ${cues.size}줄을 만들었습니다", cues.size)
                onDone(result)
            } catch (e: CancellationException) {
                _job.value = _job.value?.copy(stage = AiJob.Stage.CANCELLED, message = "생성을 취소했습니다")
                throw e
            } catch (e: Throwable) {
                _job.value = AiJob(entry.uri, entry.title, AiJob.Stage.FAILED, 0f, e.message ?: e.javaClass.simpleName)
            } finally {
                extractor?.cancel()
                workDir.deleteRecursively()
            }
        }
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
