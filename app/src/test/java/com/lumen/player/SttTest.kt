package com.lumen.player

import com.lumen.player.data.settings.AiSettings
import com.lumen.player.data.settings.SttProvider
import com.lumen.player.subtitle.ai.OpenAiCompatibleClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SttTest {
    @Test fun openAiCompatibleRequestAndParse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(
            MockResponse().setBody(
                """{"text":"…","segments":[
                  {"start":0.0,"end":2.5,"text":" 안녕하세요 여러분","no_speech_prob":0.01,"avg_logprob":-0.2},
                  {"start":3.0,"end":4.0,"text":" 시청해 주셔서 감사합니다","no_speech_prob":0.9,"avg_logprob":-1.5},
                  {"start":5.0,"end":6.0,"text":" 다음 대사","no_speech_prob":0.1,"avg_logprob":-0.3}],
                 "words":[{"word":"안녕하세요","start":0.0,"end":1.0},{"word":"여러분","start":1.1,"end":2.4},{"word":"다음","start":5.0,"end":5.4}]}""",
            ),
        )
        server.start()
        val wav = File.createTempFile("chunk", ".wav").apply { writeBytes(ByteArray(64)) }
        val ai = AiSettings(provider = SttProvider.GROQ, language = "ko", vocabularyPrompt = "루멘")
        val client = OpenAiCompatibleClient(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/openai/v1").toString(), "KEY", ai)
        val segs = client.transcribe(wav, "이전 대사")
        assertEquals(listOf("안녕하세요 여러분", "다음 대사"), segs.map { it.text })
        assertEquals(2, segs[0].words.size)
        assertEquals(2500, segs[0].endMs)
        server.takeRequest() // 429
        val req = server.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", req.path)
        assertEquals("Bearer KEY", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        listOf("whisper-large-v3", "verbose_json", "timestamp_granularities[]", "name=\"language\"", "루멘 이전 대사").forEach {
            assertTrue(body.contains(it), "missing $it")
        }
        server.shutdown()
    }
}
