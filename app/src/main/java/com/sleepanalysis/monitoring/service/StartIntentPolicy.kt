package com.sleepanalysis.monitoring.service

/**
 * Pure decision table for what a delivered [MonitorService.onStartCommand] intent instructs the
 * service to do (JVM-testable, like [canBeginCaptureAfterDelay]).
 *
 * The load-bearing entry is the null/unknown branch: a null intent is the START_STICKY restart
 * after process death, and it must NOT auto-resume capture — a microphone foreground service
 * started from the background gets NO while-in-use mic access on Android 11+, AudioRecord
 * initializes cleanly and returns silence (so `onCaptureError` never fires), and the night
 * would "complete" empty. Capture starts only on the explicit, user-driven actions, whose tap
 * is the user-interaction path that grants mic access.
 */
internal enum class StartCommand {
    /** Fresh session (Home button, bedtime-notification Start action). */
    START_NEW_SESSION,

    /** Resume the open interrupted session (interrupted-notification Resume action). */
    RESUME_OPEN_SESSION,

    /** Finalize the running session with COMPLETED. */
    STOP,

    /**
     * Null intent (START_STICKY restart after process death) or an unrecognized action:
     * surface the interruption if a session is open, otherwise stop — never start capture.
     */
    SURFACE_INTERRUPTED,
}

internal fun startCommandFor(intentAction: String?): StartCommand = when (intentAction) {
    MonitorService.ACTION_START -> StartCommand.START_NEW_SESSION
    MonitorService.ACTION_RESUME -> StartCommand.RESUME_OPEN_SESSION
    MonitorService.ACTION_STOP -> StartCommand.STOP
    else -> StartCommand.SURFACE_INTERRUPTED
}

/**
 * What [MonitorService.resumeInterruptedSession] does with a Resume tap, given the facts at
 * delivery time (JVM-testable decision table, like [startCommandFor]).
 */
internal enum class ResumeDecision {
    /** Capture resumes for the open session (the user tap is the mic-access path). */
    RESUME,

    /**
     * Master recording switch is off: capture must NOT start — the same switch gates manual
     * starts, scheduled starts and resume. The session stays open and the interrupted
     * notification stays up: it remains the user's re-entry point (Home banner flow).
     */
    BLOCKED,

    /** The open session is gone (already finalized elsewhere): the notification is stale — cancel it. */
    STALE,
}

internal fun resumeDecision(openSessionId: Long, recordingEnabled: Boolean): ResumeDecision = when {
    openSessionId <= 0L -> ResumeDecision.STALE
    !recordingEnabled -> ResumeDecision.BLOCKED
    else -> ResumeDecision.RESUME
}
