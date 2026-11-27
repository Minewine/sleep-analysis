package com.sleepanalysis.monitoring.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Level-bucket accumulation: fixed epoch buckets, means, capture-thread/heartbeat split. */
class AmbientLevelBucketsTest {

    private val bucketMs = AmbientLevelBuckets.BUCKET_MS

    @Test
    fun `windows fold into fixed 5-minute epoch buckets as means`() {
        val buckets = AmbientLevelBuckets()
        buckets.add(0, -90.0)
        buckets.add(60_000, -80.0) // same bucket
        buckets.add(bucketMs.toLong(), -60.0) // next bucket
        val snap = buckets.snapshot()
        assertEquals(2, snap.size)
        assertEquals(0L, snap[0].startMs)
        assertEquals(bucketMs.toLong(), snap[0].endMs)
        assertEquals(-85.0, snap[0].meanDbfs, 0.0001)
        assertEquals(-60.0, snap[1].meanDbfs, 0.0001)
        assertTrue(snap == snap.sortedBy { it.startMs })
    }

    @Test
    fun `snapshot is a copy - later adds do not mutate it`() {
        val buckets = AmbientLevelBuckets()
        buckets.add(0, -90.0)
        val snap = buckets.snapshot()
        buckets.add(0, -10.0) // same bucket: live mean changes, the snapshot must not
        assertEquals(-90.0, snap[0].meanDbfs, 0.0001)
        buckets.add(bucketMs.toLong(), -10.0) // new bucket: live snapshot grows
        assertEquals(2, buckets.snapshot().size)
        assertEquals(1, snap.size)
    }

    // ---- honest coverage (P3-16) and variance (P2-4) ----

    @Test
    fun `a partly filled bucket reports its real covered time not the full 5 minutes`() {
        val buckets = AmbientLevelBuckets()
        buckets.add(0, -90.0)
        buckets.add(500, -89.0)
        buckets.add(1_000, -91.0)
        val snap = buckets.snapshot()
        assertEquals(1, snap.size)
        // 3 windows × 500 ms hop = 1.5 s of evidence, never the bucket's whole 5 minutes.
        assertEquals(1_500L, snap[0].coveredMs)
    }

    @Test
    fun `a fully filled bucket covers its span`() {
        val buckets = AmbientLevelBuckets()
        val windows = (bucketMs / 500).toInt()
        repeat(windows) { i -> buckets.add(i * 500L, -85.0) }
        assertEquals(bucketMs.toLong(), buckets.snapshot().single().coveredMs)
    }

    @Test
    fun `bucket variance is tracked for digital-silence detection`() {
        val buckets = AmbientLevelBuckets()
        buckets.add(0, -90.0)
        buckets.add(500, -80.0)
        assertEquals(25.0, buckets.snapshot().single().dbfsVariance!!, 0.0001)
        // A silenced client delivers exact zeros: constant −100 dBFS, variance exactly 0.
        val silent = AmbientLevelBuckets()
        silent.add(0, -100.0)
        silent.add(500, -100.0)
        assertEquals(0.0, silent.snapshot().single().dbfsVariance!!, 0.0001)
    }
}
