package com.sleepanalysis.monitoring.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePolicyTest {

    @Test
    fun `notification posts when every gate allows it`() {
        assertTrue(
            SchedulePolicy.shouldPostBedtimeNotification(
                recordingEnabled = true, scheduleEnabled = true, skipSet = false,
                alreadyRecording = false, hasOpenSession = false,
            )
        )
    }

    @Test
    fun `master switch off suppresses the notification`() {
        assertFalse(
            SchedulePolicy.shouldPostBedtimeNotification(
                recordingEnabled = false, scheduleEnabled = true, skipSet = false,
                alreadyRecording = false, hasOpenSession = false,
            )
        )
    }

    @Test
    fun `schedule off suppresses the notification`() {
        assertFalse(
            SchedulePolicy.shouldPostBedtimeNotification(
                recordingEnabled = true, scheduleEnabled = false, skipSet = false,
                alreadyRecording = false, hasOpenSession = false,
            )
        )
    }

    @Test
    fun `skipped night suppresses the notification`() {
        assertFalse(
            SchedulePolicy.shouldPostBedtimeNotification(
                recordingEnabled = true, scheduleEnabled = true, skipSet = true,
                alreadyRecording = false, hasOpenSession = false,
            )
        )
    }

    @Test
    fun `already recording suppresses the notification`() {
        assertFalse(
            SchedulePolicy.shouldPostBedtimeNotification(
                recordingEnabled = true, scheduleEnabled = true, skipSet = false,
                alreadyRecording = true, hasOpenSession = false,
            )
        )
    }

    @Test
    fun `an open interrupted night suppresses the bedtime notification (P2-1)`() {
        // An open session means the Resume/Finalize decision is pending — the bedtime Start
        // action must not offer to silently finalize it behind a new night.
        assertFalse(
            SchedulePolicy.shouldPostBedtimeNotification(
                recordingEnabled = true, scheduleEnabled = true, skipSet = false,
                alreadyRecording = false, hasOpenSession = true,
            )
        )
    }

    @Test
    fun `full truth table over all 32 gate combinations`() {
        // Hardcoded expectations (not derived from the implementation), indexed in loop order
        // recordingEnabled, scheduleEnabled, skipSet, alreadyRecording, hasOpenSession:
        // the notification posts only when both switches are on AND none of a skip marker,
        // an active recording, or an open interrupted session applies. The 16 pre-P2-1 rows
        // (hasOpenSession = false) keep their original expectations; every open-session row
        // suppresses.
        val expected = listOf(
            //  rec   sched  skip   rec2   open
            true, false,  // T T F F  F/T
            false, false, // T T F T
            false, false, // T T T F
            false, false, // T T T T
            false, false, // T F F F
            false, false, // T F F T
            false, false, // T F T F
            false, false, // T F T T
            false, false, // F T F F
            false, false, // F T F T
            false, false, // F T T F
            false, false, // F T T T
            false, false, // F F F F
            false, false, // F F F T
            false, false, // F F T F
            false, false, // F F T T
        )
        var index = 0
        for (recordingEnabled in listOf(true, false)) {
            for (scheduleEnabled in listOf(true, false)) {
                for (skipSet in listOf(false, true)) {
                    for (alreadyRecording in listOf(false, true)) {
                        for (hasOpenSession in listOf(false, true)) {
                            assertEquals(
                                "recordingEnabled=$recordingEnabled scheduleEnabled=$scheduleEnabled " +
                                    "skipSet=$skipSet alreadyRecording=$alreadyRecording " +
                                    "hasOpenSession=$hasOpenSession",
                                expected[index],
                                SchedulePolicy.shouldPostBedtimeNotification(
                                    recordingEnabled = recordingEnabled,
                                    scheduleEnabled = scheduleEnabled,
                                    skipSet = skipSet,
                                    alreadyRecording = alreadyRecording,
                                    hasOpenSession = hasOpenSession,
                                ),
                            )
                            index++
                        }
                    }
                }
            }
        }
        assertEquals(32, index)
    }
}
