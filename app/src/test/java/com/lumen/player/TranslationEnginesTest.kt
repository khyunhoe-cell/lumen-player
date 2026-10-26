package com.lumen.player

import com.lumen.player.data.settings.KeySlot
import com.lumen.player.data.settings.SttProvider
import com.lumen.player.data.settings.TranslationProvider
import com.lumen.player.subtitle.ai.ApiKeyRejectedException
import com.lumen.player.subtitle.ai.ClaudeChatBackend
import com.lumen.player.subtitle.ai.LocalLlmPrompt
import com.lumen.player.subtitle.ai.ModelNotFoundException
import com.lumen.player.subtitle.ai.ScriptCheck
import com.lumen.player.subtitle.ai.SubtitleTranslator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationEnginesTest {
    @Test fun keySlots() {
        assertEquals(KeySlot.Stt(SttProvider.GROQ), TranslationProvider.GROQ.keySlot)
        assertEquals(KeySlot.Translation(TranslationProvider.DEEPSEEK), TranslationProvider.DEEPSEEK.keySlot)
        assertEquals("Anthropic", TranslationProvider.CLAUDE.keySlot.label)
        assertFalse(TranslationProvider.LOCAL_LLM.needsKey)
    }

    private fun claudeReply(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
           "content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(text)}}],
           "stop_reason":"end_turn","stop_sequence":null,
           "usage":{"input_tokens":10,"output_tokens":5}}""",
    )

    @Test fun claudeTranslatesBatchWithLowEffortAndFallback() = runBlocking {
        val server = MockWebServer()
        server.enqueue(claudeReply("""{"lines": ["안녕", "잘 가"]}"""))
        server.start()
        val t = SubtitleTranslator(ClaudeChatBackend("sk-ant-test", server.url("/").toString()), "claude-opus-5-5", jsonMode = false)
        assertEquals(listOf("안녕", "잘 가"), t.translate(listOf("Hi", "Bye"), "ko"))
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path?.substringBefore('?'))
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertTrue(req.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("temperature"))
        t.close()
        server.shutdown()
    }

    @Test fun claudeErrorsMapToKeyAndModelProblems() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"not_found_error","message":"model: claude-x"}}"""))
        server.start()
        val backend = ClaudeChatBackend("bad", server.url("/").toString())
        assertTrue(runCatching { backend.complete("claude-haiku-5-5", "s", "u", false) }.exceptionOrNull() is ApiKeyRejectedException)
        assertTrue(runCatching { backend.complete("claude-x", "s", "u", false) }.exceptionOrNull() is ModelNotFoundException)
        backend.close()
        server.shutdown()
    }

    @Test fun localPromptAndCleanup() {
        val user = LocalLlmPrompt.user("ありがとう", "ja", "Lumen", listOf("a" to "A", "b" to "B", "c" to "C", "d" to "D", "e" to "E"))
        assertTrue(user.contains("Glossary") && user.contains("Source language: Japanese") && user.contains("<line>ありがとう</line>"))
        assertFalse(user.contains("- a → A"))
        assertTrue(LocalLlmPrompt.system("ko").contains("into Korean"))
        assertEquals("고마워", LocalLlmPrompt.clean("\"고마워\"<|im_end|>\n설명"))
        assertEquals("고마워", LocalLlmPrompt.clean("<line>고마워</line>"))
        assertTrue(LocalLlmPrompt.shouldStop("고마워\n다음"))
        assertFalse(LocalLlmPrompt.shouldStop("고마"))
        assertTrue(LocalLlmPrompt.chatMl("S", "U").endsWith("<|im_start|>assistant\n"))
    }

    @Test fun scriptCheck() {
        assertTrue(ScriptCheck.matchesTarget("정말 고마워, Lumen!", "ko"))
        assertFalse(ScriptCheck.matchesTarget("어느 부서ก็ได้ 재미있었네", "ko"))
        assertFalse(ScriptCheck.matchesTarget("어느 부서 mica도", "ko"))
        assertEquals("요리짱", ScriptCheck.repair("요리ちゃん", "ko"))
        assertTrue(ScriptCheck.matchesTarget("Thank you", "en"))
    }
}
