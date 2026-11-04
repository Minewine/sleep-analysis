package com.sleepanalysis.monitoring.schedule

/**
 * Pure gate decisions for the scheduled-recording flow — unit-testable truth table.
 */
object SchedulePolicy {

    /**
     * Whether the bedtime notification should be posted for this start occurrence:
     * master switch on, schedule on, the night not skipped, and nothing already recording.
     */
    fun shouldPostBedtimeNotification(
        recordingEnabled: Boolean,
        scheduleEnabled: Boolean,
        skipSet: Boolean,
        alreadyRecording: Boolean,
    ): Boolean = recordingEnabled && scheduleEnabled && !skipSet && !alreadyRecording
}
