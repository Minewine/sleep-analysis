package com.sleepanalysis.monitoring.audio

/**
 * Cheap per-window level summary (docs/NIGHT_REPORT.md §Quiet stretches): window dBFS values
 * folded into fixed [bucketMs] epoch buckets as (sum, count), snapshotted as mean-dBFS spans.
 * Purpose: let the report distinguish "quiet sleep" (windows processed, level at the ambient
 * bed) from "no capture" (no buckets at all — process death) inside an event-free gap.
 *
 * Capture-thread-only writer; [snapshot] may be called from the heartbeat thread. Memory is
 * bounded by the night length: a 12 h night at the 5-minute default is 144 buckets.
 */
class AmbientLevelBuckets(private val bucketMs: Long = BUCKET_MS) {

    data class Bucket(val startMs: Long, val endMs: Long, val meanDbfs: Double)

    private val sums = HashMap<Long, Double>()
    private val counts = HashMap<Long, Int>()

    fun add(epochMs: Long, dbfs: Double) {
        val key = epochMs.toULong().div(bucketMs.toULong()).toLong()
        synchronized(this) {
            sums[key] = (sums[key] ?: 0.0) + dbfs
            counts[key] = (counts[key] ?: 0) + 1
        }
    }

    fun snapshot(): List<Bucket> = synchronized(this) {
        sums.entries.map { (key, sum) ->
            val count = counts.getValue(key)
            val start = key * bucketMs
            Bucket(start, start + bucketMs, sum / count)
        }.sortedBy { it.startMs }
    }

    companion object {
        /** 5-minute buckets: 12/night-hour, fine enough to classify ≥10-min gaps. */
        const val BUCKET_MS = 5 * 60_000L
    }
}
