package com.sleepanalysis.monitoring.schedule

/**
 * Pure gate decisions for the scheduled-recording flow — unit-testable truth table.
 */
object SchedulePolicy {

    /**
     * Whether the bedtime notification should be posted for this start occurrence:
     * master switch on, schedule on, the night not skipped, nothing already recording, and —
     * since P2-1 — no open interrupted session awaiting the user's Resume/Finalize decision:
     * its Start action must not silently finalize that night behind a new one (the
     * interrupted notification is the re-entry point instead).
     */
    fun shouldPostBedtimeNotification(
        recordingEnabled: Boolean,
        scheduleEnabled: Boolean,
        skipSet: Boolean,
        alreadyRecording: Boolean,
        hasOpenSession: Boolean,
    ): Boolean = recordingEnabled && scheduleEnabled && !skipSet && !alreadyRecording && !hasOpenSession
}
