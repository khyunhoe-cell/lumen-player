package com.lumen.player.subtitle.ai

import com.lumen.player.data.settings.AiSettings
import com.lumen.player.data.settings.SttProvider
import kotlinx.coroutines.delay
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

/** 단어 시간이 있으면 자막을 더 정확한 지점에서 자를 수 있다. */
data class Word(val startMs: Long, val endMs: Long, val text: String)
data class Segment(val startMs: Long, val endMs: Long, val text: String, val words: List<Word> = emptyList())

interface SttClient {
    suspend fun transcribe(chunk: File, previousText: String): List<Segment>
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }
private val wav = "audio/wav".toMediaType()

/** 키가 거절된 경우. 다시 시도해도 소용없으니 작업 전체를 멈춘다 */
class ApiKeyRejectedException(message: String) : IOException(message)

/** 사용 한도 때문에 오래 기다려야 하는 경우 */
class RateLimitException(message: String) : IOException(message)

/** 한도에 걸려 기다릴 때 화면에 알리고 싶으면 코루틴 컨텍스트에 넣는다 */
class RateLimitListener(val onWait: (waitMs: Long, what: String) -> Unit) :
    AbstractCoroutineContextElement(RateLimitListener) {
    companion object Key : CoroutineContext.Key<RateLimitListener>
}

/** "12", "7.66s", "2m59.56s", "1h2m3s", "try again in 1m30.5s" 같은 표기를 밀리초로 바꾼다 */
internal fun parseWaitMs(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    text.trim().toDoubleOrNull()?.let { return (it * 1000).toLong() }
    val m = Regex("""(?:(\d+)h)?(?:(\d+)m(?!s))?(?:([\d.]+)s)?""").findAll(text)
        .firstOrNull { it.value.isNotEmpty() } ?: return null
    val h = m.groupValues[1].toLongOrNull() ?: 0
    val min = m.groupValues[2].toLongOrNull() ?: 0
    val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
    return h * 3_600_000 + min * 60_000 + (sec * 1000).toLong()
}

/** 이보다 오래 기다려야 하면 기다리지 않고 알린다 */
private const val MAX_WAIT_MS = 15 * 60_000L

/**
 * 요청 한도(429)나 서버 오류(5xx)면 공급자가 알려 준 시간만큼 기다렸다가 다시 보낸다.
 * Groq 무료 등급은 시간당 오디오 길이에도 한도가 있어 몇 분씩 기다려야 할 때가 있다.
 */
internal suspend fun OkHttpClient.executeWithRetry(what: String = "음성 인식", build: () -> Request): String {
    var attempt = 0
    while (true) {
        val resp: Response = newCall(build()).execute()
        val body = resp.use { it.body?.string().orEmpty() }
        when {
            resp.isSuccessful -> return body
            (resp.code == 429 || resp.code >= 500) && attempt < 6 -> {
                val hinted = parseWaitMs(resp.header("Retry-After"))
                    ?: parseWaitMs(Regex("""try again in ([\dhms.]+)""").find(body)?.groupValues?.get(1))
                val wait = (hinted ?: (2_000L shl attempt)) + 500
                if (wait > MAX_WAIT_MS) {
                    throw RateLimitException("$what 사용 한도를 다 썼습니다. 약 ${wait / 60_000 + 1}분 뒤에 다시 시도하세요")
                }
                coroutineContext[RateLimitListener]?.onWait?.invoke(wait, what)
                delay(wait)
                attempt++
            }
            resp.code == 429 -> throw RateLimitException("$what 사용 한도에 걸렸습니다. 잠시 뒤 다시 시도하세요")
            resp.code == 401 || resp.code == 403 -> throw ApiKeyRejectedException("$what: API 키가 올바르지 않습니다 (${resp.code})")
            resp.code == 413 -> throw IOException("오디오 조각이 공급자의 크기 제한을 넘었습니다")
            else -> throw IOException("$what 요청 실패 (${resp.code}): ${body.take(300)}")
        }
    }
}

private fun JsonObject.num(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

/** OpenAI, Groq, 그리고 whisper.cpp·faster-whisper 같은 OpenAI 호환 서버 */
class OpenAiCompatibleClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val settings: AiSettings,
) : SttClient {
    override suspend fun transcribe(chunk: File, previousText: String): List<Segment> {
        // 번역은 Whisper 번역 모드(영어만 가능, 단어 시간 없음) 대신 SubtitleTranslator가 인식 뒤에 한다
        val endpoint = "audio/transcriptions"
        // Whisper는 프롬프트의 마지막 224토큰만 본다. 용어집 + 직전 대사로 문맥을 이어 준다.
        val prompt = listOf(settings.vocabularyPrompt.trim(), previousText.takeLast(200)).filter { it.isNotEmpty() }.joinToString(" ")
        val body = {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", chunk.name, chunk.asRequestBody(wav))
                .addFormDataPart("model", settings.model())
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("timestamp_granularities[]", "word")
                .apply {
                    if (settings.language.isNotBlank()) addFormDataPart("language", settings.language)
                    if (prompt.isNotEmpty()) addFormDataPart("prompt", prompt)
                }
                .build()
        }
        val text = http.executeWithRetry {
            Request.Builder().url(baseUrl.trimEnd('/') + "/" + endpoint)
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .post(body())
                .build()
        }
        val root = json.parseToJsonElement(text).jsonObject
        val words = root["words"]?.jsonArray.orEmpty().mapNotNull {
            val o = it.jsonObject
            Word(((o.num("start") ?: return@mapNotNull null) * 1000).toLong(), ((o.num("end") ?: 0.0) * 1000).toLong(), o["word"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        val segments = root["segments"]?.jsonArray.orEmpty().mapNotNull {
            val o = it.jsonObject
            val noSpeech = o.num("no_speech_prob") ?: 0.0
            val logprob = o.num("avg_logprob") ?: 0.0
            // Whisper가 무음 구간에 지어내는 문장("시청해 주셔서 감사합니다" 등)을 걸러낸다
            if (noSpeech > 0.6 && logprob < -1.0) return@mapNotNull null
            val start = ((o.num("start") ?: return@mapNotNull null) * 1000).toLong()
            val end = ((o.num("end") ?: return@mapNotNull null) * 1000).toLong()
            Segment(start, end, o["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(), words.filter { w -> w.startMs >= start - 50 && w.endMs <= end + 50 })
        }
        return segments.filterNot { HallucinationFilter.isHallucination(it.text) }
    }
}

class DeepgramClient(private val http: OkHttpClient, private val apiKey: String, private val settings: AiSettings) : SttClient {
    override suspend fun transcribe(chunk: File, previousText: String): List<Segment> {
        val url = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", settings.model(SttProvider.DEEPGRAM))
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("punctuate", "true")
            .addQueryParameter("utterances", "true")
            .addQueryParameter("utt_split", "0.8")
            .apply {
                if (settings.language.isNotBlank()) addQueryParameter("language", settings.language)
                else addQueryParameter("detect_language", "true")
                settings.vocabularyPrompt.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.take(50)
                    .forEach { addQueryParameter("keyterm", it) }
            }.build()
        val text = http.executeWithRetry {
            Request.Builder().url(url).header("Authorization", "Token $apiKey").post(chunk.asRequestBody(wav)).build()
        }
        val root = json.parseToJsonElement(text).jsonObject
        return root["results"]?.jsonObject?.get("utterances")?.jsonArray.orEmpty().mapNotNull {
            val o = it.jsonObject
            val words = o["words"]?.jsonArray.orEmpty().mapNotNull { w ->
                val wo = w.jsonObject
                Word(
                    ((wo.num("start") ?: return@mapNotNull null) * 1000).toLong(),
                    ((wo.num("end") ?: 0.0) * 1000).toLong(),
                    (wo["punctuated_word"] ?: wo["word"])?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            Segment(
                ((o.num("start") ?: return@mapNotNull null) * 1000).toLong(),
                ((o.num("end") ?: return@mapNotNull null) * 1000).toLong(),
                o["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                words,
            )
        }
    }
}

class ElevenLabsClient(private val http: OkHttpClient, private val apiKey: String, private val settings: AiSettings) : SttClient {
    override suspend fun transcribe(chunk: File, previousText: String): List<Segment> {
        val text = http.executeWithRetry {
            Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text")
                .header("xi-api-key", apiKey)
                .post(
                    MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("model_id", settings.model(SttProvider.ELEVENLABS))
                        .addFormDataPart("file", chunk.name, chunk.asRequestBody(wav))
                        .addFormDataPart("timestamps_granularity", "word")
                        .addFormDataPart("tag_audio_events", "false")
                        .apply { if (settings.language.isNotBlank()) addFormDataPart("language_code", settings.language) }
                        .build(),
                )
                .build()
        }
        val root = json.parseToJsonElement(text).jsonObject
        val words = root["words"]?.jsonArray.orEmpty().mapNotNull {
            val o = it.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull != "word") return@mapNotNull null
            Word(((o.num("start") ?: return@mapNotNull null) * 1000).toLong(), ((o.num("end") ?: 0.0) * 1000).toLong(), o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        return SegmentFormatter.wordsToSegments(words)
    }
}

object HallucinationFilter {
    private val phrases = listOf(
        "시청해 주셔서 감사합니다", "시청해주셔서 감사합니다", "구독과 좋아요", "자막 제공", "MBC 뉴스",
        "Thanks for watching", "Thank you for watching", "Subtitles by", "please subscribe",
    )

    fun isHallucination(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        return phrases.any { t.contains(it, ignoreCase = true) } && t.length < 40
    }
}
