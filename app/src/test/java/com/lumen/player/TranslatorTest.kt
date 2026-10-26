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

    @Test fun switchesToAvailableModelWhenModelWasRemoved() = runBlocking {
        val server = MockWebServer()
        val gone = """{"error":{"message":"The model `llama-3.3-70b-versatile` does not exist or you do not have access to it.","type":"invalid_request_error","code":"model_not_found"}}"""
        server.enqueue(MockResponse().setResponseCode(404).setBody(gone))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"whisper-large-v3"},{"id":"openai/gpt-oss-20b"},{"id":"openai/gpt-oss-120b"}]}"""))
        server.enqueue(chat("""{"lines": ["하나", "둘"]}"""))
        server.enqueue(chat("""{"lines": ["셋"]}"""))
        server.start()
        val t = SubtitleTranslator(http, server.url("/v1").toString(), "KEY", "llama-3.3-70b-versatile")
        assertEquals(listOf("하나", "둘"), t.translate(listOf("one", "two"), "ko"))
        assertEquals(listOf("셋"), t.translate(listOf("three"), "ko"))
        assertEquals(0, t.failedLines)
        assertTrue(t.notice!!.contains("openai/gpt-oss-120b"))
        server.takeRequest()
        assertEquals("/v1/models", server.takeRequest().path)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"openai/gpt-oss-120b\""))
        assertEquals(4, server.requestCount)
        server.shutdown()
    }

    @Test fun stopsWithClearMessageWhenNoReplacementModel() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"model_not_found"}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"whisper-large-v3"}]}"""))
        server.start()
        val t = SubtitleTranslator(http, server.url("/v1").toString(), "KEY", "gone-model")
        val e = kotlin.runCatching { t.translate(listOf("one", "two", "three"), "ko") }.exceptionOrNull()
        assertTrue(e is com.lumen.player.subtitle.ai.ModelNotFoundException && e.message!!.contains("gone-model"))
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test fun contextKeepsLastFourPairsAndPromptMentionsSpeechRecognition() {
        val ctx = SubtitleTranslator.contextLines(listOf("a", "b", "c", "d", "e"), listOf("A", "b", "C", "D", "E"))
        assertEquals(listOf("b", "c → C", "d → D", "e → E"), ctx)
        val prompt = SubtitleTranslator.systemPrompt("ko", "ja", "")
        listOf("speech recognition", "misheard", "Korean script", "original → translation").forEach {
            assertTrue(prompt.contains(it), "missing $it")
        }
    }

    @Test fun targetLanguageRules() {
        assertEquals("", AiSettings().target())
        assertEquals("ko", AiSettings(targetLanguage = "ko").target())
        assertEquals("en", AiSettings(translateToEnglish = true).target()) // 예전 "영어로 번역" 설정
        assertEquals("", AiSettings(language = "ko", targetLanguage = "ko").target())
        assertEquals("Japanese", SubtitleTranslator.languageName("ja"))
    }
}

class TranslatorRobustnessTest {
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()

    private fun chat(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}]}""",
    )

    @Test fun retriesWithoutJsonModeOn400() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"json_validate_failed"}}"""))
        server.enqueue(chat("""{"lines": ["하나", "둘"]}"""))
        server.start()
        val t = SubtitleTranslator(http, server.url("/v1").toString(), "KEY", "m", jsonMode = true)
        assertEquals(listOf("하나", "둘"), t.translate(listOf("one", "two"), "ko"))
        assertTrue(server.takeRequest().body.readUtf8().contains("response_format"))
        assertTrue(!server.takeRequest().body.readUtf8().contains("response_format"))
        assertEquals(0, t.failedLines)
        server.shutdown()
    }

    @Test fun keepsOriginalsWhenServerFails() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("model not found"))
        server.start()
        val t = SubtitleTranslator(http, server.url("/v1").toString(), "", "m", jsonMode = false)
        val out = t.translate(listOf("one", "two"), "ko")
        assertEquals(listOf("one", "two"), out)
        assertEquals(2, t.failedLines)
        server.shutdown()
    }

    @Test fun rejectedKeyStopsTheJob() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val t = SubtitleTranslator(http, server.url("/v1").toString(), "BAD", "m")
        val e = runCatching { runBlocking { t.translate(listOf("one"), "ko") } }.exceptionOrNull()
        assertTrue(e is com.lumen.player.subtitle.ai.ApiKeyRejectedException, "$e")
        server.shutdown()
    }

    @Test fun parsesWaitHints() {
        assertEquals(12_000L, com.lumen.player.subtitle.ai.parseWaitMs("12"))
        assertEquals(7_660L, com.lumen.player.subtitle.ai.parseWaitMs("7.66s"))
        assertEquals(179_560L, com.lumen.player.subtitle.ai.parseWaitMs("2m59.56s"))
        assertEquals(3_723_000L, com.lumen.player.subtitle.ai.parseWaitMs("1h2m3s"))
        assertEquals(null, com.lumen.player.subtitle.ai.parseWaitMs(""))
    }
}
