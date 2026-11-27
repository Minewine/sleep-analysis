package com.sleepanalysis.monitoring.service

/**
 * The interrupted-night stop sequence, extracted from MonitorService.surfaceInterruptionOrStop
 * so the ordering contract is JVM-testable. On a START_STICKY restart after process death the
 * service runs in the background, where Android 12+ can refuse the foreground promotion with a
 * throw (ForegroundServiceStartNotAllowedException, or the Android 14+ microphone while-in-use
 * check) that the service coroutine's crash handler would swallow — silently skipping
 * everything after it. The notification therefore goes out FIRST (it survives a failed
 * promotion), the promotion stays best-effort (kept for the "did not then call startForeground"
 * rationale — see MonitorService's KDoc), and the stop runs unconditionally.
 */
internal object InterruptionStopSequence {

    fun run(
        showInterruptedNotification: () -> Unit,
        promoteToForeground: () -> Unit,
        onPromotionFailure: (Throwable) -> Unit,
        stop: () -> Unit,
    ) {
        showInterruptedNotification()
        runCatching(promoteToForeground).onFailure(onPromotionFailure)
        stop()
    }
}
