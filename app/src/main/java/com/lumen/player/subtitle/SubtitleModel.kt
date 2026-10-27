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

    /** 정렬된 대사 목록(목록 보기·편집용) */
    val ordered: List<SubtitleCue> get() = sorted

    /** positionMs 뒤에 시작하는 첫 대사의 시작 시각. 없으면 null */
    fun nextStart(positionMs: Long): Long? = sorted.firstOrNull { it.startMs > positionMs + NAV_SLACK_MS }?.startMs

    /**
     * 이전 대사의 시작 시각. 대사가 나오는 중이면 그 바로 앞 대사로, 대사 사이의 빈 곳이면 방금 지나간 대사로 간다.
     * 그래서 여러 번 누르면 한 대사씩 거슬러 올라간다. 더 앞 대사가 없으면 첫 대사.
     */
    fun previousStart(positionMs: Long): Long? {
        val started = sorted.indexOfLast { it.startMs <= positionMs + NAV_SLACK_MS }
        if (started < 0) return sorted.firstOrNull()?.startMs
        val showing = sorted[started].endMs > positionMs
        val target = if (showing) started - 1 else started
        return sorted[target.coerceAtLeast(0)].startMs
    }

    /** positionMs에 보이는(없으면 바로 앞) 대사의 [ordered] 안 위치. 목록에서 지금 대사를 표시할 때 쓴다 */
    fun indexAt(positionMs: Long): Int = sorted.indexOfLast { it.startMs <= positionMs }

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

    private companion object {
        /** 대사 시작 시각으로 이동한 직후의 위치 오차(재생 위치는 몇 ms 앞뒤로 흔들린다) */
        const val NAV_SLACK_MS = 120L
    }
}
