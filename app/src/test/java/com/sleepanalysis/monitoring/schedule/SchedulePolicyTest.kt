package com.sleepanalysis.monitoring.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePolicyTest {

    @Test
    fun `notification posts when every gate allows it`() {
        assertTrue(SchedulePolicy.shouldPostBedtimeNotification(recordingEnabled = true, scheduleEnabled = true, skipSet = false, alreadyRecording = false))
    }

    @Test
    fun `master switch off suppresses the notification`() {
        assertFalse(SchedulePolicy.shouldPostBedtimeNotification(recordingEnabled = false, scheduleEnabled = true, skipSet = false, alreadyRecording = false))
    }

    @Test
    fun `schedule off suppresses the notification`() {
        assertFalse(SchedulePolicy.shouldPostBedtimeNotification(recordingEnabled = true, scheduleEnabled = false, skipSet = false, alreadyRecording = false))
    }

    @Test
    fun `skipped night suppresses the notification`() {
        assertFalse(SchedulePolicy.shouldPostBedtimeNotification(recordingEnabled = true, scheduleEnabled = true, skipSet = true, alreadyRecording = false))
    }

    @Test
    fun `already recording suppresses the notification`() {
        assertFalse(SchedulePolicy.shouldPostBedtimeNotification(recordingEnabled = true, scheduleEnabled = true, skipSet = false, alreadyRecording = true))
    }

    @Test
    fun `full truth table over all sixteen gate combinations`() {
        // Hardcoded expectations (not derived from the implementation), indexed in loop order
        // recordingEnabled, scheduleEnabled, skipSet, alreadyRecording:
        // the notification posts only when both switches are on AND neither a skip marker
        // nor an active recording applies.
        val expected = listOf(
            //  rec   sched  skip   recording
            true,   // T     T      F      F
            false,  // T     T      F      T
            false,  // T     T      T      F
            false,  // T     T      T      T
            false,  // T     F      F      F
            false,  // T     F      F      T
            false,  // T     F      T      F
            false,  // T     F      T      T
            false,  // F     T      F      F
            false,  // F     T      F      T
            false,  // F     T      T      F
            false,  // F     T      T      T
            false,  // F     F      F      F
            false,  // F     F      F      T
            false,  // F     F      T      F
            false,  // F     F      T      T
        )
        var index = 0
        for (recordingEnabled in listOf(true, false)) {
            for (scheduleEnabled in listOf(true, false)) {
                for (skipSet in listOf(false, true)) {
                    for (alreadyRecording in listOf(false, true)) {
                        assertEquals(
                            "recordingEnabled=$recordingEnabled scheduleEnabled=$scheduleEnabled " +
                                "skipSet=$skipSet alreadyRecording=$alreadyRecording",
                            expected[index],
                            SchedulePolicy.shouldPostBedtimeNotification(
                                recordingEnabled = recordingEnabled,
                                scheduleEnabled = scheduleEnabled,
                                skipSet = skipSet,
                                alreadyRecording = alreadyRecording,
                            ),
                        )
                        index++
                    }
                }
            }
        }
        assertEquals(16, index)
    }
}
