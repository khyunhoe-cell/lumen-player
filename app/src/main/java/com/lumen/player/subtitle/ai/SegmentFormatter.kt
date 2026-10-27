package com.lumen.player.subtitle.ai

import com.lumen.player.subtitle.SubtitleCue

/**
 * 음성 인식 결과를 읽기 좋은 자막으로 다듬는다.
 * 한 자막은 최대 2줄, 줄마다 maxChars 이하, 최대 7초. 단어 시간이 있으면 그 지점에서 자른다.
 * 화자를 구분했으면 화자가 바뀔 때 새 자막으로 넘기고, 짧은 주고받기는 대시 두 줄로 묶는다.
 */
object SegmentFormatter {
    private const val MAX_CUE_MS = 7_000L
    private const val MIN_CUE_MS = 900L
    /** 대시 두 줄로 묶을 짧은 대사: 둘 중 하나가 이보다 짧고, 사이가 DIALOGUE_GAP_MS 이하(말을 주고받는 짧은 숨) */
    private const val SHORT_LINE_MS = 1_500L
    private const val DIALOGUE_GAP_MS = 1_000L
    private val sentenceEnd = Regex("""[.!?。？！…]$""")

    fun wordsToSegments(words: List<Word>): List<Segment> {
        val out = mutableListOf<Segment>()
        var cur = mutableListOf<Word>()
        fun flush() {
            if (cur.isEmpty()) return
            out += Segment(cur.first().startMs, cur.last().endMs, cur.joinToString(" ") { it.text.trim() }, cur.toList(), cur.first().speaker)
            cur = mutableListOf()
        }
        for (w in words) {
            val gap = cur.lastOrNull()?.let { w.startMs - it.endMs } ?: 0
            val speakerChanged = w.speaker != null && cur.lastOrNull()?.speaker.let { it != null && it != w.speaker }
            if (cur.isNotEmpty() && (gap > 800 || w.endMs - cur.first().startMs > MAX_CUE_MS || speakerChanged)) flush()
            cur.add(w)
            if (sentenceEnd.containsMatchIn(w.text.trim())) flush()
        }
        flush()
        return out
    }

    fun toCues(segments: List<Segment>, maxChars: Int): List<SubtitleCue> {
        // 화자가 다른 구간은 이미 나뉘어 있으므로 구간마다 따로 자막을 만들면 화자가 바뀔 때 다음 자막으로 넘어간다
        val cues = mutableListOf<Pair<SubtitleCue, Int?>>()
        for (seg in segments) {
            val text = seg.text.replace(Regex("""\s+"""), " ").trim()
            if (text.isEmpty()) continue
            val fits = text.length <= maxChars * 2 && seg.endMs - seg.startMs <= MAX_CUE_MS
            val made = when {
                fits -> listOf(SubtitleCue(seg.startMs, seg.endMs, wrap(text, maxChars)))
                seg.words.size >= 2 -> splitByWords(seg.words, maxChars)
                else -> splitProportionally(seg.startMs, seg.endMs, text, maxChars)
            }
            made.forEach { cues += it to seg.speaker }
        }
        return fixTiming(mergeDialogue(cues, maxChars))
    }

    /**
     * 화자가 다른 짧은 대사 두 개가 바로 이어지면 자막이 너무 빨리 바뀌지 않게 한 자막에 대시 두 줄로 묶는다.
     * "- 정말이야?\n- 응, 진짜야."
     */
    internal fun mergeDialogue(cues: List<Pair<SubtitleCue, Int?>>, maxChars: Int): List<SubtitleCue> {
        val out = mutableListOf<SubtitleCue>()
        var i = 0
        while (i < cues.size) {
            val (a, sa) = cues[i]
            val next = cues.getOrNull(i + 1)
            if (next != null && sa != null && next.second != null && sa != next.second && isQuickExchange(a, next.first, maxChars)) {
                val b = next.first
                out += SubtitleCue(a.startMs, b.endMs, "- ${a.text}\n- ${b.text}")
                i += 2
            } else {
                out += a
                i++
            }
        }
        return out
    }

    private fun isQuickExchange(a: SubtitleCue, b: SubtitleCue, maxChars: Int): Boolean {
        val oneLine = '\n' !in a.text && '\n' !in b.text && a.text.length <= maxChars && b.text.length <= maxChars
        val short = minOf(a.endMs - a.startMs, b.endMs - b.startMs) <= SHORT_LINE_MS
        return oneLine && short && b.startMs - a.endMs <= DIALOGUE_GAP_MS && b.endMs - a.startMs <= MAX_CUE_MS
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
