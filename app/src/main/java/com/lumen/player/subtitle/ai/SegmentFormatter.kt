package com.lumen.player.subtitle.ai

import com.lumen.player.subtitle.SubtitleCue

/**
 * 음성 인식 결과를 읽기 좋은 자막으로 다듬는다.
 * 한 자막은 최대 2줄, 줄마다 maxChars 이하, 최대 7초. 단어 시간이 있으면 그 지점에서 자른다.
 */
object SegmentFormatter {
    private const val MAX_CUE_MS = 7_000L
    private const val MIN_CUE_MS = 900L
    private val sentenceEnd = Regex("""[.!?。？！…]$""")

    fun wordsToSegments(words: List<Word>): List<Segment> {
        val out = mutableListOf<Segment>()
        var cur = mutableListOf<Word>()
        fun flush() {
            if (cur.isEmpty()) return
            out += Segment(cur.first().startMs, cur.last().endMs, cur.joinToString(" ") { it.text.trim() }, cur.toList())
            cur = mutableListOf()
        }
        for (w in words) {
            val gap = cur.lastOrNull()?.let { w.startMs - it.endMs } ?: 0
            if (cur.isNotEmpty() && (gap > 800 || w.endMs - cur.first().startMs > MAX_CUE_MS)) flush()
            cur.add(w)
            if (sentenceEnd.containsMatchIn(w.text.trim())) flush()
        }
        flush()
        return out
    }

    fun toCues(segments: List<Segment>, maxChars: Int): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        for (seg in segments) {
            val text = seg.text.replace(Regex("""\s+"""), " ").trim()
            if (text.isEmpty()) continue
            val fits = text.length <= maxChars * 2 && seg.endMs - seg.startMs <= MAX_CUE_MS
            if (fits) {
                cues += SubtitleCue(seg.startMs, seg.endMs, wrap(text, maxChars))
                continue
            }
            if (seg.words.size >= 2) {
                cues += splitByWords(seg.words, maxChars)
            } else {
                cues += splitProportionally(seg.startMs, seg.endMs, text, maxChars)
            }
        }
        return fixTiming(cues)
    }

    private fun splitByWords(words: List<Word>, maxChars: Int): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        var group = mutableListOf<Word>()
        fun len() = group.sumOf { it.text.trim().length + 1 }
        for (w in words) {
            val wouldBe = len() + w.text.trim().length
            if (group.isNotEmpty() && (wouldBe > maxChars * 2 || w.endMs - group.first().startMs > MAX_CUE_MS)) {
                out += SubtitleCue(group.first().startMs, group.last().endMs, wrap(group.joinToString(" ") { it.text.trim() }, maxChars))
                group = mutableListOf()
            }
            group.add(w)
        }
        if (group.isNotEmpty()) out += SubtitleCue(group.first().startMs, group.last().endMs, wrap(group.joinToString(" ") { it.text.trim() }, maxChars))
        return out
    }

    private fun splitProportionally(start: Long, end: Long, text: String, maxChars: Int): List<SubtitleCue> {
        val pieces = mutableListOf<String>()
        var rest = text
        while (rest.length > maxChars * 2) {
            val cut = bestBreak(rest, maxChars * 2)
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        val total = pieces.sumOf { it.length }.coerceAtLeast(1)
        var t = start
        return pieces.map { p ->
            val dur = (end - start) * p.length / total
            SubtitleCue(t, t + dur, wrap(p, maxChars)).also { t += dur }
        }
    }

    /** 문장 부호 > 쉼표 > 공백 순으로 limit 안쪽의 자연스러운 끊는 위치를 고른다. */
    private fun bestBreak(s: String, limit: Int): Int {
        val window = s.take(limit)
        for (marks in listOf(".!?…", ",，、", " ")) {
            val idx = window.indexOfLast { it in marks }
            if (idx > limit / 3) return idx + 1
        }
        return limit
    }

    fun wrap(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        // 두 줄 길이가 비슷해지도록 가운데에 가까운 공백에서 나눈다
        val mid = text.length / 2
        val spaces = text.indices.filter { text[it] == ' ' }
        val split = spaces.minByOrNull { kotlin.math.abs(it - mid) } ?: return text
        return text.substring(0, split).trim() + "\n" + text.substring(split + 1).trim()
    }

    /** 너무 짧은 자막은 늘리고, 다음 자막과 겹치지 않게 한다. */
    private fun fixTiming(cues: List<SubtitleCue>): List<SubtitleCue> {
        val sorted = cues.sortedBy { it.startMs }
        return sorted.mapIndexed { i, c ->
            val next = sorted.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            val end = maxOf(c.endMs, c.startMs + MIN_CUE_MS).coerceAtMost(next - 40).coerceAtLeast(c.startMs + 200)
            c.copy(endMs = end)
        }
    }
}
