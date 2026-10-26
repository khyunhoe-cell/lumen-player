package com.lumen.player.subtitle.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/**
 * 인식한 대사를 OpenAI 호환 chat API로 번역한다. 줄 수를 그대로 지키게 하고,
 * 모델이 줄을 합치거나 쪼개서 개수가 어긋나면 묶음을 반으로 나눠 다시 보낸다.
 */
/** 자막 번역 엔진 공통 인터페이스. 줄 수를 그대로 지켜 돌려준다. */
interface TranslationEngine {
    /** 번역하지 못해 원문으로 남긴 줄 수와 마지막 원인 */
    val failedLines: Int
    val lastError: String?

    /** 작업 중 사용자에게 알릴 일(예: 쓸 수 없는 모델을 다른 모델로 바꿈). 없으면 null */
    val notice: String? get() = null

    suspend fun translate(
        lines: List<String>,
        target: String,
        source: String = "",
        glossary: String = "",
        context: List<String> = emptyList(),
    ): List<String>

    fun close() {}
}

/** 번역 요청 한 번(시스템 지시문 + 요청 글)을 보내고 답 글을 받는 통로. 공급자마다 하나씩 둔다 */
interface ChatBackend {
    /** 답을 해석하지 못하면 null */
    suspend fun complete(model: String, system: String, user: String, jsonMode: Boolean): String?

    /** 모델이 없어졌을 때 대신 쓸 모델을 고르려고 서버가 가진 모델 ID를 받는다 */
    suspend fun listModels(): List<String>

    fun close() {}
}

/** OpenAI 호환 /chat/completions (Groq, OpenAI, DeepSeek, 내 서버) */
class OpenAiChatBackend(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) : ChatBackend {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(model: String, system: String, user: String, jsonMode: Boolean): String? {
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
        }.toString()
        val text = http.executeWithRetry("자막 번역") {
            Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .post(body.toRequestBody(jsonType))
                .build()
        }
        return runCatching {
            json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }.getOrNull()
    }

    override suspend fun listModels(): List<String> {
        val text = http.executeWithRetry("모델 목록") {
            Request.Builder().url(baseUrl.trimEnd('/') + "/models")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()
        }
        return json.parseToJsonElement(text).jsonObject["data"]!!.jsonArray
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    }
}

/**
 * 여러 줄을 한 번에 보내 번역한다. 줄 수를 그대로 지키게 하고,
 * 모델이 줄을 합치거나 쪼개서 개수가 어긋나면 묶음을 반으로 나눠 다시 보낸다.
 */
class SubtitleTranslator(
    private val backend: ChatBackend,
    private val model: String,
    /** response_format=json_object를 보낼지. 일부 로컬 서버는 이 옵션을 거부한다 */
    private val jsonMode: Boolean = true,
) : TranslationEngine {

    constructor(http: OkHttpClient, baseUrl: String, apiKey: String, model: String, jsonMode: Boolean = true) :
        this(OpenAiChatBackend(http, baseUrl, apiKey), model, jsonMode)

    override var failedLines = 0
        private set
    override var lastError: String? = null
        private set

    override var notice: String? = null
        private set

    /** 서버가 JSON 모드를 거절하면(400) 이후로는 끄고 보낸다 */
    private var useJsonMode = jsonMode

    /** 설정한 모델이 없어지면 서버가 가진 모델 중 하나로 바꿔 쓴다 */
    private var currentModel = model
    private var triedReplacement = false

    /**
     * @param context 직전 대사("원문 → 번역"). 번역하지 않고 문맥으로만 쓴다
     * @return lines와 같은 개수의 번역문
     */
    override suspend fun translate(
        lines: List<String>,
        target: String,
        source: String,
        glossary: String,
        context: List<String>,
    ): List<String> = lines.chunked(BATCH).flatMap { translateBatch(it, target, source, glossary, context) }

    private suspend fun translateBatch(
        lines: List<String>, target: String, source: String, glossary: String, context: List<String>,
    ): List<String> {
        val out = try {
            request(lines, target, source, glossary, context)
        } catch (e: ApiKeyRejectedException) {
            throw e
        } catch (e: ModelNotFoundException) {
            // 모델이 없어졌으면 줄마다 실패하지 말고 한 번만 대체 모델을 찾는다. 없으면 작업을 멈춘다
            val replacement = if (triedReplacement) null else {
                triedReplacement = true
                runCatching { pickReplacement(backend.listModels(), currentModel) }.getOrNull()
            }
            if (replacement == null) {
                throw ModelNotFoundException(
                    "번역 모델 '$currentModel'을(를) 쓸 수 없습니다. 설정 › AI 자막 › 자막 번역에서 다른 모델을 고르세요",
                )
            }
            notice = "번역 모델 '$currentModel'을(를) 쓸 수 없어 '$replacement'(으)로 번역했습니다. 설정에서 모델을 바꿔 두세요"
            currentModel = replacement
            return translateBatch(lines, target, source, glossary, context)
        } catch (e: java.io.IOException) {
            // 모델이 JSON을 제대로 못 만들면 Groq·OpenAI는 400으로 거절한다. JSON 모드를 끄고 한 번 더 보낸다
            if (useJsonMode && e.message?.contains("(400)") == true) {
                useJsonMode = false
                return translateBatch(lines, target, source, glossary, context)
            }
            // 한도·네트워크 오류: 이 묶음은 원문으로 두고 다음으로 넘어간다
            failedLines += lines.size
            lastError = e.message
            return lines
        }
        if (out != null && out.size == lines.size) return out
        if (lines.size == 1) {
            val joined = out?.joinToString(" ")?.takeIf { it.isNotBlank() }
            if (joined == null) {
                failedLines++
                lastError = "번역 응답을 해석하지 못했습니다"
            }
            return listOf(joined ?: lines[0])
        }
        val half = lines.size / 2
        return translateBatch(lines.subList(0, half), target, source, glossary, context) +
            translateBatch(lines.subList(half, lines.size), target, source, glossary, context)
    }

    /** 응답을 해석하지 못하면 null */
    private suspend fun request(
        lines: List<String>, target: String, source: String, glossary: String, context: List<String>,
    ): List<String>? {
        val user = buildJsonObject {
            if (context.isNotEmpty()) putJsonArray("context") { context.forEach { add(it) } }
            putJsonArray("lines") { lines.forEach { add(it) } }
        }.toString()
        val content = backend.complete(currentModel, systemPrompt(target, source, glossary), user, useJsonMode) ?: return null
        return parseLines(content)
    }

    override fun close() = backend.close()

    companion object {
        private const val BATCH = 40

        /** 번역에 쓸 만한 순서. 앞에 있을수록 먼저 고른다 */
        private val preferredModels = listOf(
            "openai/gpt-oss-120b", "gpt-4.1-mini", "gpt-4o-mini", "deepseek-flash", "claude-opus-5-5",
            "openai/gpt-oss-20b", "gpt-4.1", "gpt-4o", "deepseek-v4-pro", "claude-sonnet-5-5", "claude-haiku-5-5",
        )

        /** 음성·임베딩·안전 검사처럼 글 번역에 쓸 수 없는 모델 */
        private val nonChat = Regex("whisper|tts|orpheus|guard|embed|moderation|transcribe|audio|realtime|image|dall-e|distil", RegexOption.IGNORE_CASE)

        /** 서버가 가진 모델 중 번역에 쓸 대체 모델. 쓸 만한 게 없으면 null */
        internal fun pickReplacement(available: List<String>, failed: String): String? {
            val candidates = available.filter { it != failed && !nonChat.containsMatchIn(it) }
            return preferredModels.firstOrNull { it in candidates } ?: candidates.firstOrNull()
        }

        fun languageName(code: String): String =
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

        internal fun systemPrompt(target: String, source: String, glossary: String) = buildString {
            val name = languageName(target)
            append("You translate video subtitles")
            if (source.isNotBlank()) append(" from ").append(languageName(source))
            append(" into ").append(name).append(" (").append(target).append(").\n")
            // 실시간 자막 번역 앱에서 쓰던 지시: 음성 인식 결과라 오인식이 있을 수 있음을 알리고 말투를 살리게 한다
            append("The lines come from automatic speech recognition of the video's audio, so they may contain misheard words, ")
            append("missing punctuation, or a sentence cut off mid-way. Use the surrounding lines and common sense to infer what was actually said, ")
            append("and translate that meaning. Write the way professional ").append(name).append(" subtitles read: natural, concise, ")
            append("and matching the speaker's tone (casual speech stays casual, polite speech stays polite). ")
            append("Write people's names in ").append(name).append(" script.\n")
            append("The user sends a JSON object with \"lines\" (and optionally \"context\", earlier lines as \"original → translation\" for reference only). ")
            append("Reply with ONLY a JSON object {\"lines\": [...]} containing exactly one translation per input line, in the same order. ")
            append("Never merge, split, skip or explain lines; a line may continue the previous one, so translate only its own part. ")
            append("Keep names and terms consistent with the context, and leave a line unchanged if it is already in the target language.")
            if (glossary.isNotBlank()) append("\nGlossary (proper nouns and terms to keep consistent): ").append(glossary.trim())
        }

        /** 다음 조각 번역에 문맥으로 넘길 앞 대사. 번역문도 함께 보여 줘야 이름·말투가 이어진다 */
        fun contextLines(originals: List<String>, translations: List<String>): List<String> =
            originals.zip(translations).takeLast(CONTEXT_LINES).map { (o, t) -> if (o == t) o else "$o → $t" }

        private const val CONTEXT_LINES = 4

        /** 코드 블록이나 앞뒤 설명이 붙어 와도 {"lines": [...]} 부분만 읽는다 */
        internal fun parseLines(content: String): List<String>? {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching {
                (Json.parseToJsonElement(content.substring(start, end + 1)).jsonObject["lines"] as JsonArray)
                    .map { (it as JsonPrimitive).contentOrNull.orEmpty().trim() }
            }.getOrNull()
        }
    }
}
