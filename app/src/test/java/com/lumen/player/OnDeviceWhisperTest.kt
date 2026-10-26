package com.lumen.player

import com.lumen.player.data.settings.WhisperModel
import com.lumen.player.subtitle.ai.OnDeviceWhisperClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class OnDeviceWhisperTest {
    @Test fun parsesNativeSegments() {
        val segs = OnDeviceWhisperClient.parseSegments(
            listOf(
                "0\t2500\t 안녕하세요 여러분",
                "2600\t3000\t ",
                "3000\t4000\t 시청해 주셔서 감사합니다",
                "4100\t5200\t 다음 대사�",
                "broken line",
            ),
        )
        assertEquals(listOf("안녕하세요 여러분", "다음 대사"), segs.map { it.text })
        assertEquals(4100L to 5200L, segs[1].startMs to segs[1].endMs)
    }

    @Test fun readsExtractorWav() {
        val samples = shortArrayOf(0, 16384, -32768, 32767)
        val buf = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(44)
        samples.forEach { buf.putShort(it) }
        val f = File.createTempFile("chunk", ".wav").apply { writeBytes(buf.array()) }
        val pcm = OnDeviceWhisperClient.readWav(f)
        assertEquals(listOf(0f, 0.5f, -1f, 32767 / 32768f), pcm.toList())
    }

    @Test fun modelUrlsAndDefaults() {
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", WhisperModel.TURBO_Q5.url)
        assertEquals(WhisperModel.TURBO_Q5, WhisperModel.byId("unknown"))
        assertEquals(WhisperModel.SMALL_Q5, WhisperModel.byId("small-q5_1"))
    }
}
