package com.sleepanalysis.monitoring.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision table for [shouldPromoteOnSkip]: the "start capture now" tap only owes a foreground
 * promotion when the service is not already foreground. Delayed is already foreground with the
 * countdown notification — re-promoting would re-post a fresh chronometer-anchored notification
 * and reset the countdown mid-wait.
 */
class StartIntentPolicySkipPromotionTest {

    @Test
    fun `delayed is already foreground and never re-promotes`() {
        val delayed = MonitorState.Delayed(sessionId = 1L, startedAt = 100L, captureStartsAtMs = 200L)
        assertFalse(shouldPromoteOnSkip(delayed))
    }

    @Test
    fun `recording and starting return true but are unreachable — skipStartDelay early-returns on them`() {
        // Pure function says !is Delayed; the service never consults it in these states
        // (skipStartDelay logs and returns before promotion for Recording/Starting).
        assertTrue(shouldPromoteOnSkip(MonitorState.Recording(sessionId = 1L, startedAt = 100L)))
        assertTrue(shouldPromoteOnSkip(MonitorState.Starting))
    }

    @Test
    fun `terminal and error states still owe the promotion`() {
        // Idle/Finished/StartError/Interrupted: tap after a stop during the delay — the
        // startForegroundService delivery is still owed its startForeground.
        assertTrue(shouldPromoteOnSkip(MonitorState.Idle))
        assertTrue(shouldPromoteOnSkip(MonitorState.Finished(sessionId = 1L)))
        assertTrue(shouldPromoteOnSkip(MonitorState.StartError("boom")))
        assertTrue(shouldPromoteOnSkip(MonitorState.Interrupted(sessionId = 1L, startedAt = 100L)))
    }
}
