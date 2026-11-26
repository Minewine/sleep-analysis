package com.sleepanalysis.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The orphan-sweep guards (RetentionWorker step 4): never delete clips an active night may not have inserted yet. */
class OrphanSweepPolicyTest {

    @Test
    fun `sweep runs only when no session is open`() {
        assertTrue(
            OrphanSweepPolicy.shouldSweep(openSessionCount = 0, fileAgeMs = OrphanSweepPolicy.MIN_FILE_AGE_MS),
        )
        assertFalse(OrphanSweepPolicy.shouldSweep(openSessionCount = 1, fileAgeMs = Long.MAX_VALUE))
        assertFalse(OrphanSweepPolicy.shouldSweep(openSessionCount = 2, fileAgeMs = Long.MAX_VALUE))
    }

    @Test
    fun `freshly written files are never swept even with no open session`() {
        assertFalse(OrphanSweepPolicy.shouldSweep(openSessionCount = 0, fileAgeMs = 0))
        assertFalse(
            OrphanSweepPolicy.shouldSweep(
                openSessionCount = 0,
                fileAgeMs = OrphanSweepPolicy.MIN_FILE_AGE_MS - 1,
            ),
        )
        // Clock skew: a future-dated lastModified yields a negative age and is kept.
        assertFalse(OrphanSweepPolicy.shouldSweep(openSessionCount = 0, fileAgeMs = -1))
    }

    @Test
    fun `negative ages from a bogus clock are never swept — map defect D-07 locked here`() {
        // Future timestamps (bogus clock) yield negative ages; the existing >= MIN_FILE_AGE_MS
        // bound already rejects them — pinned so it stays that way.
        assertFalse(OrphanSweepPolicy.shouldSweep(openSessionCount = 0, fileAgeMs = -1L))
        assertFalse(OrphanSweepPolicy.shouldSweep(openSessionCount = 0, fileAgeMs = -3_600_000L))
    }

    @Test
    fun `the hour boundary itself is sweepable`() {
        assertTrue(
            OrphanSweepPolicy.shouldSweep(openSessionCount = 0, fileAgeMs = OrphanSweepPolicy.MIN_FILE_AGE_MS),
        )
    }
}
