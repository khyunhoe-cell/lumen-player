package com.lumen.player

import com.lumen.player.subtitle.LoadedSubtitle
import com.lumen.player.subtitle.SubtitleCue
import com.lumen.player.subtitle.SubtitleParser
import com.lumen.player.subtitle.ai.Segment
import com.lumen.player.subtitle.ai.SegmentFormatter
import com.lumen.player.subtitle.ai.Word
import com.lumen.player.subtitle.ai.HallucinationFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleTests {
    @Test fun srt() {
        val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>안녕하세요</i>\r\n두 번째 줄\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\n끝\r\n"
        val cues = SubtitleParser.parse(srt.toByteArray(), "a.srt")
        assertEquals(listOf(SubtitleCue(1000, 3500, "안녕하세요\n두 번째 줄"), SubtitleCue(4000, 5000, "끝")), cues)
    }

    @Test fun vttWithoutHours() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.500 align:start\nHello\n\n01:00:00.000 --> 01:00:01.000\nBye"
        val cues = SubtitleParser.parse(vtt.toByteArray(), "a.vtt")
        assertEquals(SubtitleCue(1000, 2500, "Hello"), cues[0])
        assertEquals(3_600_000, cues[1].startMs)
    }

    @Test fun samiCp949TwoLanguages() {
        val smi = """<SAMI><HEAD><STYLE>.KRCC {Name:Korean;} .ENCC {Name:English;}</STYLE></HEAD><BODY>
<SYNC Start=1000><P Class=KRCC>그 여름,<br>우리는
<SYNC Start=1000><P Class=ENCC>That summer
<SYNC Start=3000><P Class=KRCC>&nbsp;
<SYNC Start=4000><P Class=KRCC>끝
</BODY></SAMI>"""
        val bytes = smi.toByteArray(charset("MS949"))
        val cues = SubtitleParser.parse(bytes, "a.smi")
        assertEquals(listOf(SubtitleCue(1000, 3000, "그 여름,\n우리는"), SubtitleCue(4000, 9000, "끝")), cues)
    }

    @Test fun ass() {
        val ass = """[Script Info]
Title: x

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\an8\b1}첫 줄\N둘째, 쉼표 포함
"""
        val cues = SubtitleParser.parse(ass.toByteArray(), "a.ass")
        assertEquals(listOf(SubtitleCue(1500, 3000, "첫 줄\n둘째, 쉼표 포함")), cues)
    }

    @Test fun srtRoundTrip() {
        val cues = listOf(SubtitleCue(61_001, 3_725_500, "a\nb"))
        val out = SubtitleParser.toSrt(cues)
        assertTrue(out.contains("00:01:01,001 --> 01:02:05,500"))
        assertEquals(cues, SubtitleParser.parse(out.toByteArray()))
    }

    @Test fun lookupOverlapping() {
        val sub = LoadedSubtitle("t", "", listOf(SubtitleCue(0, 10_000, "long"), SubtitleCue(2000, 3000, "short"), SubtitleCue(11_000, 12_000, "next")))
        assertEquals(listOf("long", "short"), sub.at(2500).map { it.text })
        assertEquals(listOf("long"), sub.at(5000).map { it.text })
        assertEquals(emptyList(), sub.at(10_500))
        assertEquals(listOf("next"), sub.at(11_000).map { it.text })
    }

    @Test fun formatterSplitsLongSegmentsByWords() {
        val words = "이것은 아주 긴 문장으로 한 화면에 다 들어가지 않기 때문에 단어 시간에 맞춰 여러 개의 자막으로 나뉘어야 합니다".split(" ")
            .mapIndexed { i, w -> Word(i * 500L, i * 500L + 400, w) }
        val seg = Segment(0, words.last().endMs, words.joinToString(" ") { it.text }, words)
        val cues = SegmentFormatter.toCues(listOf(seg), maxChars = 16)
        assertTrue(cues.size >= 2, "cues=$cues")
        cues.forEach { c -> c.text.lines().forEach { assertTrue(it.length <= 20, "line too long: $it") } }
        cues.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs, "overlap $a $b") }
        assertEquals(words.joinToString(" ") { it.text }, cues.joinToString(" ") { it.text.replace("\n", " ") })
    }

    @Test fun formatterMinimumDuration() {
        val cues = SegmentFormatter.toCues(listOf(Segment(1000, 1100, "네"), Segment(5000, 6000, "좋아요")), 32)
        assertTrue(cues[0].endMs - cues[0].startMs >= 900)
    }

    @Test fun wordsToSegmentsBreaksOnPause() {
        val segs = SegmentFormatter.wordsToSegments(
            listOf(Word(0, 300, "안녕"), Word(350, 700, "하세요."), Word(800, 1000, "다음"), Word(3000, 3300, "문장")),
        )
        assertEquals(listOf("안녕 하세요.", "다음", "문장"), segs.map { it.text })
    }

    @Test fun hallucination() {
        assertTrue(HallucinationFilter.isHallucination("시청해 주셔서 감사합니다."))
        assertTrue(!HallucinationFilter.isHallucination("오늘 회의는 여기까지입니다"))
    }
}
