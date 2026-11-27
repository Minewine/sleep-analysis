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
 * Verifiable quiet comes from the capture's level buckets (and the silenced-client spans):
 * a capture outage that produced no events classifies as UNCAPTURED, not quiet.
 */
data class QuietGap(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    /**
     * Evidence-based classification from the capture's level buckets (schema v3
     * capture_stats): QUIET = the gap's windows were processed and sat at the ambient bed;
     * UNCAPTURED = no usable level evidence (process death, or the mic delivered digital
     * silence / was OS-silenced — P2-4); null = partial or contradictory evidence.
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
 * A bucket at or below this mean dBFS is digital silence (an Android-silenced or dead client
 * delivers exact zeros at −100 dBFS; a real bedroom's ambient bed sits above it), not a quiet
 * bedroom — P2-4.
 */
const val DIGITAL_SILENCE_DBFS = -95.0

/** Per-window dBFS variance at or below this (stddev ≤ 0.1 dB over the whole bucket) is a constant level — only digital input does that. */
const val DIGITAL_SILENCE_MAX_VARIANCE_DB2 = 0.01

private fun isDigitalSilence(bucket: AmbientLevelBucket): Boolean =
    bucket.meanDbfs <= DIGITAL_SILENCE_DBFS ||
        (bucket.dbfsVariance != null && bucket.dbfsVariance <= DIGITAL_SILENCE_MAX_VARIANCE_DB2)

/** A bucket's evidence extent: the honest covered time (v6), else the legacy full-span claim. */
private fun coveredFraction(bucket: AmbientLevelBucket): Double {
    val span = (bucket.endMs - bucket.startMs).coerceAtLeast(1)
    return ((bucket.coveredMs ?: span).coerceAtMost(span)).toDouble() / span
}

/**
 * Classifies each [gaps]' span against the capture's [buckets] and [clientSilencedSpans]:
 * the gap is QUIET when usable (non-digital-silence) bucket evidence covers ≥
 * [coveredFractionThreshold] of it (their overlap-weighted mean level is the evidence),
 * UNCAPTURED when there is none — no buckets, digital-silence buckets, or an OS-silenced
 * span ([ClientSilencedSpan] — the mic delivered zeros) — and left unclassified in between
 * (partial evidence, e.g. a process death inside an otherwise quiet stretch, or a gap that
 * was partly silenced and partly verifiably quiet). A silenced-client span covering the
 * whole gap is UNCAPTURED regardless of the buckets: the OS flag outranks derived levels.
 *
 * A bucket counts toward coverage only in proportion to its [AmbientLevelBucket.coveredMs] —
 * a partly filled bucket contributes evidence for the windows it actually holds, never its
 * whole span (P3-16). Pure; the same function drives finalize-time storage and the legacy
 * report-time fallback.
 */
fun classifyQuietGaps(
    gaps: List<QuietGap>,
    buckets: List<AmbientLevelBucket>,
    coveredFractionThreshold: Double = 0.5,
    clientSilencedSpans: List<ClientSilencedSpan> = emptyList(),
): List<QuietGap> {
    // No evidence at all (pre-v3 nights): leave every gap unclassified rather than guessing.
    if (buckets.isEmpty() && clientSilencedSpans.isEmpty()) return gaps
    return gaps.map { gap ->
        var silencedMs = 0.0
        var quietMs = 0.0
        var quietWeighted = 0.0
        var silenceMs = 0.0
        var silenceWeighted = 0.0
        for (span in clientSilencedSpans) {
            silencedMs += (minOf(gap.endMs, span.endEpochMs) - maxOf(gap.startMs, span.startEpochMs))
                .coerceAtLeast(0)
        }
        for (bucket in buckets) {
            val ov = (minOf(gap.endMs, bucket.endMs) - maxOf(gap.startMs, bucket.startMs))
                .coerceAtLeast(0) * coveredFraction(bucket)
            if (ov <= 0) continue
            if (isDigitalSilence(bucket)) {
                silenceMs += ov
                silenceWeighted += bucket.meanDbfs * ov
            } else {
                quietMs += ov
                quietWeighted += bucket.meanDbfs * ov
            }
        }
        val silenceMean = if (silenceMs > 0) silenceWeighted / silenceMs else null
        val fraction = quietMs / gap.durationMs.coerceAtLeast(1)
        when {
            silencedMs >= gap.durationMs ->
                gap.copy(kind = QuietGapKind.UNCAPTURED)
            silencedMs > 0 && quietMs <= 0 ->
                gap.copy(kind = QuietGapKind.UNCAPTURED, meanDbfs = silenceMean)
            fraction >= coveredFractionThreshold && silencedMs <= 0 ->
                gap.copy(kind = QuietGapKind.QUIET, meanDbfs = quietWeighted / quietMs)
            quietMs <= 0 ->
                gap.copy(kind = QuietGapKind.UNCAPTURED, meanDbfs = silenceMean)
            else -> gap
        }
    }
}

/** Minimum gap duration to count as a quiet stretch (night-review threshold: ≥10 min). */
const val QUIET_GAP_MIN_MS: Long = 10 * 60_000L

/**
 * Aggregated quiet/not-captured totals for the TL;DR surfaces (P2-5). Only `kind == QUIET`
 * counts as a quiet stretch; UNCAPTURED gaps are reported separately — hours the mic never
 * heard must not present themselves as measured quiet. Unclassified gaps (null kind: partial
 * or pre-v3 evidence) count as neither.
 */
data class QuietStretchTotals(
    val quietCount: Int,
    val quietTotalMs: Long,
    val quietLongestMs: Long?,
    val uncapturedCount: Int,
    val uncapturedTotalMs: Long,
    val uncapturedLongestMs: Long?,
) {
    companion object {
        fun of(gaps: List<QuietGap>): QuietStretchTotals {
            val quiet = gaps.filter { it.kind == QuietGapKind.QUIET }
            val uncaptured = gaps.filter { it.kind == QuietGapKind.UNCAPTURED }
            return QuietStretchTotals(
                quietCount = quiet.size,
                quietTotalMs = quiet.sumOf { it.durationMs },
                quietLongestMs = quiet.maxOfOrNull { it.durationMs },
                uncapturedCount = uncaptured.size,
                uncapturedTotalMs = uncaptured.sumOf { it.durationMs },
                uncapturedLongestMs = uncaptured.maxOfOrNull { it.durationMs },
            )
        }
    }
}

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
