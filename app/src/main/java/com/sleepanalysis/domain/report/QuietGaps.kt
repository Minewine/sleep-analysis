package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity

/**
 * Quiet stretches of a night (report-only): spans inside the CAPTURE window during which no
 * acoustic event was detected. The window must be the mic-live capture window —
 * capture_started_at when persisted, session start otherwise — never tap-to-stop, or a
 * configured start delay would present itself as one giant quiet gap.
 *
 * Leading (window start → first event) and trailing (last event → window end) spans are
 * included: falling-asleep silence and quiet wake-up are exactly what they look like.
 * Limitation: inter-event gaps are the only verifiable quiet — a capture outage that produced
 * no events is indistinguishable from silence (no heartbeat timeline is persisted).
 */
data class QuietGap(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    /**
     * Evidence-based classification from the capture's level buckets (schema v3
     * capture_stats): QUIET = the gap's windows were processed and sat at the ambient bed;
     * UNCAPTURED = no level evidence (process death); null = no bucket data (pre-v3 nights).
     */
    val kind: String? = null,
    /** Mean window dBFS of the level evidence inside the gap (null without buckets). */
    val meanDbfs: Double? = null,
)

/** Gap kinds (stored in quiet_gap.kind, schema v10). */
object QuietGapKind {
    const val QUIET = "QUIET"
    const val UNCAPTURED = "UNCAPTURED"
}

/**
 * Classifies each [gaps]' span against the capture's [buckets]: the gap is QUIET when level
 * buckets cover ≥ [COVERED_FRACTION_THRESHOLD] of it (their overlap-weighted mean level is
 * the evidence), UNCAPTURED when they cover none of it, and left unclassified in between
 * (partial evidence — e.g. a process death inside an otherwise quiet stretch). Pure; the
 * same function drives finalize-time storage and the legacy report-time fallback.
 */
fun classifyQuietGaps(
    gaps: List<QuietGap>,
    buckets: List<AmbientLevelBucket>,
    coveredFractionThreshold: Double = 0.5,
): List<QuietGap> {
    if (buckets.isEmpty()) return gaps
    return gaps.map { gap ->
        var coveredMs = 0.0
        var weightedSum = 0.0
        for (bucket in buckets) {
            val ov = (minOf(gap.endMs, bucket.endMs) - maxOf(gap.startMs, bucket.startMs))
                .coerceAtLeast(0)
            if (ov > 0) {
                coveredMs += ov
                weightedSum += bucket.meanDbfs * ov
            }
        }
        val fraction = coveredMs / gap.durationMs.coerceAtLeast(1)
        when {
            fraction >= coveredFractionThreshold ->
                gap.copy(kind = QuietGapKind.QUIET, meanDbfs = weightedSum / coveredMs)
            coveredMs <= 0.0 ->
                gap.copy(kind = QuietGapKind.UNCAPTURED)
            else -> gap
        }
    }
}

/** Minimum gap duration to count as a quiet stretch (night-review threshold: ≥10 min). */
const val QUIET_GAP_MIN_MS: Long = 10 * 60_000L

/**
 * Quiet stretches ≥ [minGapMs] within [windowStartMs, windowEndMs], chronological. Events
 * outside the window are ignored; overlapping/degenerate spans contribute nothing.
 */
fun quietGaps(
    events: List<AcousticEventEntity>,
    windowStartMs: Long,
    windowEndMs: Long,
    minGapMs: Long = QUIET_GAP_MIN_MS,
): List<QuietGap> {
    if (windowEndMs <= windowStartMs) return emptyList()
    val ordered = events
        .filter { it.endMs > windowStartMs && it.startMs < windowEndMs }
        .sortedBy { it.startMs }
    val gaps = mutableListOf<QuietGap>()
    var cursor = windowStartMs
    for (event in ordered) {
        val start = maxOf(event.startMs, cursor)
        if (start - cursor >= minGapMs) {
            gaps += QuietGap(cursor, start, start - cursor)
        }
        cursor = maxOf(cursor, event.endMs.coerceAtMost(windowEndMs))
        if (cursor >= windowEndMs) break
    }
    if (windowEndMs - cursor >= minGapMs) {
        gaps += QuietGap(cursor, windowEndMs, windowEndMs - cursor)
    }
    return gaps
}
