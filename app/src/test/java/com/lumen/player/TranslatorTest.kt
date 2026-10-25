package com.lumen.player

import com.lumen.player.data.settings.AiSettings
import com.lumen.player.subtitle.ai.SubtitleTranslator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslatorTest {
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()

    private fun chat(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}]}""",
    )

    @Test fun translatesLinesAndSendsPrompt() = runBlocking {
        val server = MockWebServer()
        server.enqueue(chat("""```json
            {"lines": ["안녕하세요, 여러분", "다음 대사"]}
            ```"""))
        server.start()
        val t = SubtitleTranslator(http, server.url("/v1").toString(), "KEY", "llama-3.3-70b-versatile")
        val out = t.translate(listOf("Hello everyone", "Next line"), "ko", "en", "Lumen", listOf("Earlier line"))
        assertEquals(listOf("안녕하세요, 여러분", "다음 대사"), out)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer KEY", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("llama-3.3-70b-versatile", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        val system = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        listOf("from English", "into Korean (ko)", "Glossary", "Lumen").forEach { assertTrue(system.contains(it), "missing $it") }
        val user = Json.parseToJsonElement(messages[1].jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals(listOf("Hello everyone", "Next line"), user["lines"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Earlier line"), user["context"]!!.jsonArray.map { it.jsonPrimitive.content })
        server.shutdown()
    }

    @Test fun splitsBatchWhenLineCountMismatches() = runBlocking {
        val server = MockWebServer()
        server.enqueue(chat("""{"lines": ["하나 둘", "셋"]}""")) // 줄을 합쳐 버린 응답
        server.enqueue(chat("""{"lines": ["하나"]}"""))
        server.enqueue(chat("""{"lines": ["둘", "셋"]}"""))
        server.start()
        val t = SubtitleTranslator(http, server.url("/v1").toString(), "", "m", jsonMode = false)
        assertEquals(listOf("하나", "둘", "셋"), t.translate(listOf("one", "two", "three"), "ko"))
        val first = server.takeRequest()
        assertEquals(null, first.getHeader("Authorization"))
        assertTrue(!first.body.readUtf8().contains("response_format"))
        assertEquals(3, server.requestCount)
        server.shutdown()
    }

    @Test fun keepsOriginalWhenSingleLineCannotBeParsed() = runBlocking {
        val server = MockWebServer()
        server.enqueue(chat("죄송하지만 번역할 수 없습니다"))
        server.start()
        val t = SubtitleTranslator(http, server.url("/v1").toString(), "", "m")
        assertEquals(listOf("Hello"), t.translate(listOf("Hello"), "ko"))
        server.shutdown()
    }

    @Test fun targetLanguageRules() {
        assertEquals("", AiSettings().target())
        assertEquals("ko", AiSettings(targetLanguage = "ko").target())
        assertEquals("en", AiSettings(translateToEnglish = true).target()) // 예전 "영어로 번역" 설정
        assertEquals("", AiSettings(language = "ko", targetLanguage = "ko").target())
        assertEquals("Japanese", SubtitleTranslator.languageName("ja"))
    }
}
