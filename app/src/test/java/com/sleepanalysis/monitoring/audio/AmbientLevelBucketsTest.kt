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
}
