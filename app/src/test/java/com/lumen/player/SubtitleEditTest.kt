package com.lumen.player

import com.lumen.player.subtitle.LoadedSubtitle
import com.lumen.player.subtitle.SubtitleCue
import com.lumen.player.subtitle.SubtitleParser
import com.lumen.player.subtitle.SubtitleRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleEditTest {
    private val sub = LoadedSubtitle(
        "t", "/x.srt",
        listOf(SubtitleCue(5_000, 7_000, "B"), SubtitleCue(1_000, 3_000, "A"), SubtitleCue(10_000, 12_000, "C")),
    )

    @Test fun nextCue() {
        assertEquals(1_000L, sub.nextStart(0))
        assertEquals(5_000L, sub.nextStart(1_000)) // 대사 시작에 막 이동한 뒤에도 다음 대사로
        assertEquals(10_000L, sub.nextStart(6_000))
        assertNull(sub.nextStart(10_500))
    }

    @Test fun previousCueStepsBackOneAtATime() {
        assertEquals(1_000L, sub.previousStart(6_000)) // B가 나오는 중이면 앞 대사 A로
        assertEquals(5_000L, sub.previousStart(8_000)) // 대사 사이: 방금 지나간 B
        assertEquals(1_000L, sub.previousStart(5_000)) // B 시작에 있으면 A로
        assertEquals(1_000L, sub.previousStart(1_000)) // 첫 대사에서는 그대로
        assertEquals(1_000L, sub.previousStart(200))
    }

    @Test fun ordersAndIndexes() {
        assertEquals(listOf("A", "B", "C"), sub.ordered.map { it.text })
        assertEquals(1, sub.indexAt(6_000))
        assertEquals(-1, sub.indexAt(500))
    }

    @Test fun parsesTimes() {
        assertEquals(62_345L, SubtitleParser.parseTime("00:01:02,345"))
        assertEquals(62_300L, SubtitleParser.parseTime("1:02.3"))
        assertEquals(3_723_400L, SubtitleParser.parseTime("1:02:03.4"))
        assertEquals(62_500L, SubtitleParser.parseTime("62.5"))
        assertNull(SubtitleParser.parseTime("1:75"))
        assertNull(SubtitleParser.parseTime("abc"))
        assertEquals("01:02:03,004", SubtitleParser.srtTime(3_723_004))
    }

    @Test fun srtIsWrittenInTimeOrder() {
        val srt = SubtitleParser.toSrt(sub.cues)
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:03,000\nA"))
        assertEquals(listOf("A", "B", "C"), SubtitleParser.parse(srt.toByteArray(), "x.srt").map { it.text })
    }

    @Test fun exportNames() {
        assertEquals("Mai.HiME 01.ko.srt", SubtitleRepository.defaultExportName("Mai.HiME 01.avi", "Mai.HiME 01.ai.ko.srt"))
        assertEquals("Movie.eng.srt", SubtitleRepository.defaultExportName("Movie.mkv", "Movie.2004.eng.srt"))
        assertEquals("Movie.srt", SubtitleRepository.defaultExportName("Movie.mkv", "Movie.ai.auto.srt"))
        assertEquals("Movie.srt", SubtitleRepository.defaultExportName("Movie.mkv", "subtitle.smi"))
        assertEquals("Movie.ko-2.srt", SubtitleRepository.alternativeName("Movie.ko.srt", setOf("movie.ko.srt")))
        assertEquals("Movie.ko-3.srt", SubtitleRepository.alternativeName("Movie.ko.srt", setOf("movie.ko.srt", "movie.ko-2.srt")))
    }

    @Test fun storageFolders() {
        assertEquals("primary:Movies/Anime", SubtitleRepository.externalStorageDocId("/storage/emulated/0/Movies/Anime"))
        assertEquals("primary:", SubtitleRepository.externalStorageDocId("/storage/emulated/0"))
        assertEquals("1234-ABCD:Video", SubtitleRepository.externalStorageDocId("/storage/1234-ABCD/Video"))
        assertNull(SubtitleRepository.externalStorageDocId("/data/user/0/x"))
        assertTrue(SubtitleRepository.covers("primary:Movies", "primary:Movies/Anime"))
        assertTrue(SubtitleRepository.covers("primary:", "primary:Movies"))
        assertTrue(SubtitleRepository.covers("primary:Movies", "primary:Movies"))
        assertFalse(SubtitleRepository.covers("primary:Movies", "primary:MoviesOld"))
    }
}
