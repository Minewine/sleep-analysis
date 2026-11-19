package com.sleepanalysis.domain.scoring

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.UserLabel

/**
 * Pure snore-metric math shared by the measurement writer (SessionRepository) and the
 * report assembler (ReportAssembler) so both surfaces always agree.
 */
object SnoreMath {

    /**
     * The events that count as SNORING for every snore aggregate (finalize-time measurements,
     * label-corrected rewrites, the report's live recompute) — one filter so the surfaces can
     * never drift. A user label overrides the detector's class (docs/DATA_MODEL.md §3.5,
     * docs/MEASUREMENT_RELIABILITY.md §7.1): SNORE includes the event, any non-SNORE label
     * (COUGH / SPEECH / MOVEMENT / OTHER_NOT_SNORE) excludes it, no label keeps the detector's
     * type. The stored event type and label are untouched — filtering happens at aggregation
     * only, never in the database.
     */
    fun eventsCountingAsSnore(events: List<AcousticEventEntity>): List<AcousticEventEntity> =
        events.filter {
            it.userLabel == UserLabel.SNORE ||
                (it.userLabel == null && it.type == EventType.SNORE)
        }

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
