package com.lumen.player.subtitle

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** 앱이 직접 파싱해 그리는 외부 자막. 싱크 조절과 영상 밖 배치가 가능하다. */
data class LoadedSubtitle(
    val label: String,
    /** 다시 불러올 때 쓰는 원본 위치(uri 문자열 또는 앱 내부 파일 경로) */
    val source: String,
    val cues: List<SubtitleCue>,
) {
    private val sorted = cues.sortedBy { it.startMs }
    private val maxDuration = sorted.maxOfOrNull { it.endMs - it.startMs } ?: 0

    /** positionMs에 보여야 할 대사들. 겹치는 대사도 모두 돌려준다. */
    fun at(positionMs: Long): List<SubtitleCue> {
        if (sorted.isEmpty()) return emptyList()
        // startMs <= position 인 마지막 인덱스
        var lo = 0
        var hi = sorted.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].startMs <= positionMs) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (last < 0) return emptyList()
        val out = ArrayList<SubtitleCue>(2)
        var i = last
        while (i >= 0 && positionMs - sorted[i].startMs <= maxDuration) {
            if (sorted[i].endMs > positionMs) out.add(0, sorted[i])
            i--
        }
        return out
    }
}
