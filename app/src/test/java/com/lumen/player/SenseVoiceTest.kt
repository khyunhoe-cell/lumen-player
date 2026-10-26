package com.lumen.player

import com.lumen.player.data.settings.SttProvider
import com.lumen.player.subtitle.ai.SenseVoice
import com.lumen.player.subtitle.ai.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenseVoiceTest {
    @Test fun mapsLanguageSetting() {
        assertEquals("auto", SenseVoice.modelLanguage(""))
        assertEquals("auto", SenseVoice.modelLanguage("  "))
        assertEquals("auto", SenseVoice.modelLanguage("auto"))
        assertEquals("ko", SenseVoice.modelLanguage("ko"))
        assertEquals("en", SenseVoice.modelLanguage("EN"))
        assertEquals("zh", SenseVoice.modelLanguage("zh-TW"))
        assertEquals("yue", SenseVoice.modelLanguage("yue"))
        assertEquals("ja", SenseVoice.modelLanguage("ja_JP"))
        assertNull(SenseVoice.modelLanguage("es"))
        assertNull(SenseVoice.modelLanguage("vi"))
    }

    @Test fun convertsVadSamplesToChunkMillis() {
        // 16kHz: 16000샘플 = 1초
        assertEquals(Segment(1500, 3750, "안녕하세요."), SenseVoice.toSegment(24_000, 36_000, " 안녕하세요. "))
        assertEquals(Segment(0, 32, "Hi"), SenseVoice.toSegment(0, 512, "Hi"))
        // 10분 조각 끝부분도 Int 넘침 없이 계산한다
        assertEquals(599_000L, SenseVoice.toSegment(9_584_000, 16_000, "끝")!!.startMs)
        assertEquals(600_000L, SenseVoice.toSegment(9_584_000, 16_000, "끝")!!.endMs)
    }

    @Test fun dropsEmptyAndHallucinatedSegments() {
        assertNull(SenseVoice.toSegment(0, 16_000, "   "))
        assertNull(SenseVoice.toSegment(0, 16_000, "시청해 주셔서 감사합니다"))
    }

    @Test fun modelFilesArePinned() {
        assertEquals(238_075_295L, SenseVoice.totalSize)
        assertTrue(SenseVoice.FILES.all { it.sha256.length == 64 })
        assertTrue(SenseVoice.MODEL.url.contains("/resolve/355f4d4884d8afd08aef04b9007a8556d7b463b2/"))
        assertFalse(SttProvider.SENSE_VOICE.needsKey)
    }
}
