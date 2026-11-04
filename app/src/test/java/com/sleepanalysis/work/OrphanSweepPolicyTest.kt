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
    fun `the hour boundary itself is sweepable`() {
        assertTrue(
            OrphanSweepPolicy.shouldSweep(openSessionCount = 0, fileAgeMs = OrphanSweepPolicy.MIN_FILE_AGE_MS),
        )
    }
}
