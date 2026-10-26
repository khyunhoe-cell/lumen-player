package com.lumen.player

import com.lumen.player.data.settings.AiSettings
import com.lumen.player.data.settings.SttProvider
import com.lumen.player.subtitle.ai.ApiKeyChecker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiKeyCheckerTest {
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()

    private fun withServer(vararg responses: MockResponse, block: suspend (MockWebServer, AiSettings) -> Unit) = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, AiSettings(provider = SttProvider.CUSTOM, customEndpoint = server.url("/v1").toString()))
        } finally {
            server.shutdown()
        }
    }

    @Test fun validKeyAndModel() = withServer(
        MockResponse().setBody("""{"data":[{"id":"whisper-large-v3"},{"id":"llama-3.3-70b-versatile"}]}"""),
    ) { server, ai ->
        val r = ApiKeyChecker(http).check(SttProvider.CUSTOM, "KEY", ai)
        assertEquals("키가 확인되었습니다 · 모델 whisper-large-v3", r.getOrThrow())
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer KEY", req.getHeader("Authorization"))
    }

    @Test fun missingModelListsAlternatives() = withServer(
        MockResponse().setBody("""{"data":[{"id":"whisper-large-v3-turbo"},{"id":"llama-3.3-70b-versatile"}]}"""),
    ) { _, ai ->
        val msg = ApiKeyChecker(http).check(SttProvider.CUSTOM, "KEY", ai).getOrThrow()
        assertTrue(msg.contains("목록에 없습니다") && msg.contains("whisper-large-v3-turbo") && !msg.contains("llama"), msg)
    }

    @Test fun rejectedKey() = withServer(MockResponse().setResponseCode(401).setBody("""{"error":"invalid"}""")) { _, ai ->
        val e = ApiKeyChecker(http).check(SttProvider.CUSTOM, "BAD", ai).exceptionOrNull()
        assertTrue(e?.message?.contains("키가 올바르지 않습니다") == true, e?.message)
    }
}
