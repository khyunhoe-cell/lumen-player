package com.lumen.player.subtitle.ai

import com.lumen.player.data.settings.AiSettings
import com.lumen.player.data.settings.SttProvider
import kotlinx.coroutines.delay
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

/** 무료 등급의 분당 요청 제한(429)에 걸리면 Retry-After 만큼 기다렸다가 다시 보낸다. */
private suspend fun OkHttpClient.executeWithRetry(build: () -> Request): String {
    var attempt = 0
    while (true) {
        val resp: Response = newCall(build()).execute()
        val body = resp.use { it.body?.string().orEmpty() }
        when {
            resp.isSuccessful -> return body
            (resp.code == 429 || resp.code >= 500) && attempt < 5 -> {
                val wait = resp.header("Retry-After")?.toDoubleOrNull()?.times(1000)?.toLong() ?: (2_000L shl attempt)
                delay(wait.coerceAtMost(60_000))
                attempt++
            }
            resp.code == 401 || resp.code == 403 -> throw IOException("API 키가 올바르지 않습니다 (${resp.code})")
            resp.code == 413 -> throw IOException("오디오 조각이 공급자의 크기 제한을 넘었습니다")
            else -> throw IOException("음성 인식 요청 실패 (${resp.code}): ${body.take(300)}")
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
        val endpoint = if (settings.translateToEnglish) "audio/translations" else "audio/transcriptions"
        // Whisper는 프롬프트의 마지막 224토큰만 본다. 용어집 + 직전 대사로 문맥을 이어 준다.
        val prompt = listOf(settings.vocabularyPrompt.trim(), previousText.takeLast(200)).filter { it.isNotEmpty() }.joinToString(" ")
        val body = {
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", chunk.name, chunk.asRequestBody(wav))
                .addFormDataPart("model", settings.model())
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
                .apply {
                    if (!settings.translateToEnglish) {
                        addFormDataPart("timestamp_granularities[]", "segment")
                        addFormDataPart("timestamp_granularities[]", "word")
                        if (settings.language.isNotBlank()) addFormDataPart("language", settings.language)
                    }
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
