package com.sleepanalysis.monitoring.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-transition guard for the delayed capture (start-delay feature): when the delay elapses,
 * capture may only begin if the world is still as the waiter left it.
 */
class DelayedCaptureGuardTest {

    private val sessionId = 7L
    private val captureStartAt = 1_793_631_600_000L

    private fun delayed(sessionId: Long = this.sessionId, captureStartsAt: Long = captureStartAt) =
        MonitorState.Delayed(sessionId, startedAt = captureStartsAt - 3_600_000L, captureStartsAtMs = captureStartsAt)

    @Test
    fun `matching delayed state allows capture`() {
        assertTrue(canBeginCaptureAfterDelay(delayed(), sessionId, captureStartAt))
    }

    @Test
    fun `stop during the delay blocks capture`() {
        // endRecording finalized the session while the delay was running.
        assertFalse(canBeginCaptureAfterDelay(MonitorState.Idle, sessionId, captureStartAt))
        assertFalse(canBeginCaptureAfterDelay(MonitorState.Finished(sessionId), sessionId, captureStartAt))
        assertFalse(canBeginCaptureAfterDelay(MonitorState.StartError("storage"), sessionId, captureStartAt))
    }

    @Test
    fun `session already recording blocks a second capture start`() {
        // Delay expiry racing with a concurrent start: whoever wins, the loser aborts.
        assertFalse(
            canBeginCaptureAfterDelay(
                MonitorState.Recording(sessionId, startedAt = captureStartAt - 3_600_000L),
                sessionId, captureStartAt,
            )
        )
    }

    @Test
    fun `different session or recomputed capture start blocks capture`() {
        assertFalse(canBeginCaptureAfterDelay(delayed(sessionId = 8L), sessionId, captureStartAt))
        // Sticky restart recomputed a different capture start (e.g. the delay pref changed
        // mid-wait): the stale waiter must not fire.
        assertFalse(canBeginCaptureAfterDelay(delayed(captureStartsAt = captureStartAt + 1), sessionId, captureStartAt))
    }
}
