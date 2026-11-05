package com.sleepanalysis.domain.scoring

/**
 * Pure snore-metric math shared by the measurement writer (SessionRepository) and the
 * report assembler (ReportAssembler) so both surfaces always agree.
 */
object SnoreMath {

    /**
     * Total covered time of possibly-overlapping `[startMs, endMs]` intervals, counting
     * overlaps once. Summing raw durations double-counts merged multi-window events — a
     * 30 s snore delivered as three overlapping windows would count as 90 s. Degenerate
     * intervals (end before start, from corrupt rows) contribute zero.
     */
    fun unionDurationMs(intervals: List<Pair<Long, Long>>): Long {
        if (intervals.isEmpty()) return 0L
        var total = 0L
        var curStart = 0L
        var curEnd = -1L
        for ((start, end) in intervals.sortedBy { it.first }) {
            val s = start
            val e = maxOf(start, end)
            when {
                curEnd < curStart -> { curStart = s; curEnd = e } // first interval
                s > curEnd -> { total += curEnd - curStart; curStart = s; curEnd = e } // gap
                e > curEnd -> curEnd = e // overlap: extend, contained intervals add nothing
            }
        }
        return total + (curEnd - curStart).coerceAtLeast(0)
    }
}
