package com.sleepanalysis.monitoring.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The interrupted-night stop sequence (MonitorService.surfaceInterruptionOrStop): on a
 * START_STICKY restart after process death the service runs in the background, where Android
 * 12+ can refuse the foreground promotion with a throw (ForegroundServiceStartNotAllowedException,
 * or the Android 14+ microphone while-in-use check). That throw must not swallow the user's
 * only same-night signal (notification 1043) nor the stop itself — the notification goes out
 * FIRST, the promotion is best-effort, the stop is unconditional.
 */
class InterruptionStopSequenceTest {

    @Test
    fun `a failed promotion does not prevent the notification or the stop`() {
        val calls = mutableListOf<String>()
        val refusal = IllegalStateException("FGS start not allowed from the background")

        InterruptionStopSequence.run(
            showInterruptedNotification = { calls += "notify" },
            promoteToForeground = { calls += "promote"; throw refusal },
            onPromotionFailure = { calls += "log" },
            stop = { calls += "stop" },
        )

        // notify FIRST (it must survive the promotion attempt), the failure logged, stop runs.
        assertEquals(listOf("notify", "promote", "log", "stop"), calls)
    }

    @Test
    fun `a successful promotion is followed by notification and stop in order`() {
        val calls = mutableListOf<String>()

        InterruptionStopSequence.run(
            showInterruptedNotification = { calls += "notify" },
            promoteToForeground = { calls += "promote" },
            onPromotionFailure = { calls += "log" },
            stop = { calls += "stop" },
        )

        // The promotion succeeds on the live path: the FGS notification is removed again by
        // the stop step and 1043 remains the user-facing signal.
        assertEquals(listOf("notify", "promote", "stop"), calls)
    }
}
