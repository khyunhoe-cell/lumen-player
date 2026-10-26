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

    suspend fun translate(
        lines: List<String>,
        target: String,
        source: String = "",
        glossary: String = "",
        context: List<String> = emptyList(),
    ): List<String>

    fun close() {}
}

class SubtitleTranslator(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** response_format=json_object를 보낼지. 일부 로컬 서버는 이 옵션을 거부한다 */
    private val jsonMode: Boolean = true,
) : TranslationEngine {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override var failedLines = 0
        private set
    override var lastError: String? = null
        private set

    /** 서버가 JSON 모드를 거절하면(400) 이후로는 끄고 보낸다 */
    private var useJsonMode = jsonMode

    /**
     * @param context 직전 대사(원문). 번역하지 않고 문맥으로만 쓴다
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
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            if (useJsonMode) putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt(target, source, glossary))
                }
                addJsonObject {
                    put("role", "user")
                    put("content", buildJsonObject {
                        if (context.isNotEmpty()) putJsonArray("context") { context.forEach { add(it) } }
                        putJsonArray("lines") { lines.forEach { add(it) } }
                    }.toString())
                }
            }
        }.toString()
        val text = http.executeWithRetry("자막 번역") {
            Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .post(body.toRequestBody(jsonType))
                .build()
        }
        val content = runCatching {
            json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }.getOrNull() ?: return null
        return parseLines(content)
    }

    companion object {
        private const val BATCH = 40

        fun languageName(code: String): String =
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }

        internal fun systemPrompt(target: String, source: String, glossary: String) = buildString {
            append("You translate video subtitles")
            if (source.isNotBlank()) append(" from ").append(languageName(source))
            append(" into ").append(languageName(target)).append(" (").append(target).append(").\n")
            append("The user sends a JSON object with \"lines\" (and optionally \"context\", earlier lines for reference only). ")
            append("Reply with ONLY a JSON object {\"lines\": [...]} containing exactly one translation per input line, in the same order. ")
            append("Never merge, split, skip or explain lines. Keep each line short and natural for on-screen subtitles, ")
            append("keep names and terms consistent, and leave a line unchanged if it is already in the target language.")
            if (glossary.isNotBlank()) append("\nGlossary (proper nouns and terms to keep consistent): ").append(glossary.trim())
        }

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
