package com.sleepanalysis.monitoring.audio

/**
 * Cheap per-window level summary (docs/NIGHT_REPORT.md §Quiet stretches): window dBFS values
 * folded into fixed [bucketMs] epoch buckets as (sum, count, sum-of-squares), snapshotted as
 * mean-dBFS spans. Purpose: let the report distinguish "quiet sleep" (windows processed, level
 * at the ambient bed) from "no capture" (no buckets at all — process death) inside an
 * event-free gap — and, since the silenced-mic fix (P2-4), "the mic delivered digital
 * silence" (constant floor-level windows) from real quiet too: a silenced client's zeros give
 * a bucket whose dBFS variance is exactly 0.
 *
 * Each [add] covers exactly [windowSpanMs] of wall time (one analysis hop); [snapshot]
 * reports that honest extent per bucket as [AmbientLevelBuckets.Bucket.coveredMs] — a partly
 * filled bucket (capture started mid-bucket, hops lost to a stall) must not claim its whole
 * span as capture evidence (P3-16).
 *
 * Capture-thread-only writer; [snapshot] may be called from the heartbeat thread. Memory is
 * bounded by the night length: a 12 h night at the 5-minute default is 144 buckets.
 */
class AmbientLevelBuckets(
    private val bucketMs: Long = BUCKET_MS,
    /** Wall time one [add] covers — the engine passes its analysis hop (500 ms). */
    private val windowSpanMs: Long = DEFAULT_WINDOW_SPAN_MS,
) {

    data class Bucket(
        val startMs: Long,
        val endMs: Long,
        val meanDbfs: Double,
        /** Sum of the windows actually folded into this bucket — the real evidence extent. */
        val coveredMs: Long,
        /** Per-window dBFS variance within the bucket (0.0 = a constant level: digital input). */
        val dbfsVariance: Double,
    )

    private val sums = HashMap<Long, Double>()
    private val sumSquares = HashMap<Long, Double>()
    private val counts = HashMap<Long, Int>()

    fun add(epochMs: Long, dbfs: Double) {
        val key = epochMs.toULong().div(bucketMs.toULong()).toLong()
        synchronized(this) {
            sums[key] = (sums[key] ?: 0.0) + dbfs
            sumSquares[key] = (sumSquares[key] ?: 0.0) + dbfs * dbfs
            counts[key] = (counts[key] ?: 0) + 1
        }
    }

    fun snapshot(): List<Bucket> = synchronized(this) {
        sums.entries.map { (key, sum) ->
            val count = counts.getValue(key)
            val start = key * bucketMs
            val mean = sum / count
            // Population variance of the per-window dBFS values; clamp the floating-point
            // residue of sumSquares − count·mean² (exact-constant buckets give exactly 0).
            val variance = ((sumSquares.getValue(key) / count) - mean * mean).coerceAtLeast(0.0)
            Bucket(
                startMs = start,
                endMs = start + bucketMs,
                meanDbfs = mean,
                coveredMs = (count * windowSpanMs).coerceAtMost(bucketMs),
                dbfsVariance = variance,
            )
        }.sortedBy { it.startMs }
    }

    companion object {
        /** 5-minute buckets: 12/night-hour, fine enough to classify ≥10-min gaps. */
        const val BUCKET_MS = 5 * 60_000L

        /** Default per-window extent — the shipped analysis hop (DetectorConfig.HOP_MS). */
        const val DEFAULT_WINDOW_SPAN_MS = 500L
    }
}
