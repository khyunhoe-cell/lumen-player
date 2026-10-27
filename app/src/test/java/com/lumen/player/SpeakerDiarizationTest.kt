package com.lumen.player

import com.lumen.player.subtitle.SubtitleCue
import com.lumen.player.subtitle.ai.Segment
import com.lumen.player.subtitle.ai.SegmentFormatter
import com.lumen.player.subtitle.ai.SpeakerSplitter
import com.lumen.player.subtitle.ai.SpeakerTurn
import com.lumen.player.subtitle.ai.Word
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakerDiarizationTest {
    @Test fun wordsWithSpeakersSplitKeepingOriginalText() {
        val seg = Segment(
            0, 3000, "Really? Yes, really.",
            listOf(Word(0, 800, "Really?", 0), Word(1200, 1600, "Yes,", 1), Word(1700, 2400, "really.", 1)),
        )
        val out = SpeakerSplitter.apply(listOf(seg))
        assertEquals(listOf("Really?", "Yes, really."), out.map { it.text })
        assertEquals(listOf(0, 1), out.map { it.speaker })
        assertEquals(listOf(0L to 800L, 1200L to 2400L), out.map { it.startMs to it.endMs })
    }

    @Test fun japaneseWordsWithoutSpacesCutAtWordBoundary() {
        val seg = Segment(
            0, 2000, "本当？うん、本当。",
            listOf(Word(0, 500, "本当", null), Word(500, 600, "？", null), Word(900, 1200, "うん", null), Word(1200, 1900, "本当。", null)),
        )
        val turns = listOf(SpeakerTurn(0, 700, 3), SpeakerTurn(800, 2000, 5))
        val out = SpeakerSplitter.apply(listOf(seg), turns)
        assertEquals(listOf("本当？", "うん、本当。"), out.map { it.text })
        assertEquals(listOf(3, 5), out.map { it.speaker })
    }

    @Test fun segmentWithoutWordsSplitsAtPunctuationNearTurnChange() {
        val seg = Segment(10_000, 14_000, "そうなの？ううん、違うよ。")
        val turns = listOf(SpeakerTurn(10_000, 11_700, 0), SpeakerTurn(11_900, 14_000, 1))
        val out = SpeakerSplitter.apply(listOf(seg), turns)
        assertEquals(listOf("そうなの？", "ううん、違うよ。"), out.map { it.text })
        assertEquals(listOf(0, 1), out.map { it.speaker })
        assertEquals(11_800L, out[0].endMs)
    }

    @Test fun shortInterjectionDoesNotSplitSegment() {
        val seg = Segment(0, 4000, "I told you already, it's fine.")
        val turns = listOf(SpeakerTurn(0, 1800, 0), SpeakerTurn(1800, 2200, 1), SpeakerTurn(2200, 4000, 0))
        val out = SpeakerSplitter.apply(listOf(seg), turns)
        assertEquals(1, out.size)
        assertEquals(0, out[0].speaker)
    }

    @Test fun quickExchangeBecomesDashedTwoLineCue() {
        val cues = SegmentFormatter.toCues(
            listOf(
                Segment(0, 900, "정말이야?", speaker = 0),
                Segment(1100, 2300, "응, 진짜야.", speaker = 1),
                Segment(2400, 5400, "그럼 내일 다시 이야기하자.", speaker = 0),
            ),
            maxChars = 20,
        )
        assertEquals("- 정말이야?\n- 응, 진짜야.", cues[0].text)
        assertEquals(0L, cues[0].startMs)
        assertEquals("그럼 내일 다시 이야기하자.", cues[1].text)
        assertEquals(2, cues.size)
    }

    @Test fun longLinesFromDifferentSpeakersStaySeparate() {
        val cues = SegmentFormatter.toCues(
            listOf(
                Segment(0, 3000, "오늘은 정말 긴 하루였어", speaker = 0),
                Segment(3200, 6000, "나도 그래, 너무 피곤하다", speaker = 1),
            ),
            maxChars = 20,
        )
        assertEquals(listOf("오늘은 정말 긴 하루였어", "나도 그래, 너무 피곤하다"), cues.map { it.text })
    }

    @Test fun sameSpeakerOrNoSpeakerIsNeverDashed() {
        val pairs = listOf(SubtitleCue(0, 800, "어?") to 0, SubtitleCue(900, 1500, "뭐야") to 0, SubtitleCue(1600, 2000, "응") to null)
        assertEquals(listOf("어?", "뭐야", "응"), SegmentFormatter.mergeDialogue(pairs, 20).map { it.text })
    }
}
