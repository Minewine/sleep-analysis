package com.sleepanalysis.monitoring.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision table for [clearsInterruptedBanner]: the interrupted-night banner is obsolete once
 * the service is capturing (Recording) or about to capture after a resume-with-delay (Delayed).
 * A resume with the start delay armed flips to Delayed, not Recording — without the Delayed
 * case the banner would linger over a live, about-to-record session.
 */
class InterruptedBannerClearTest {

    @Test
    fun `banner clears when recording`() {
        assertTrue(clearsInterruptedBanner(MonitorState.Recording(sessionId = 1L, startedAt = 100L)))
    }

    @Test
    fun `banner clears when delayed — resume-with-delay must not leave the banner up`() {
        assertTrue(clearsInterruptedBanner(MonitorState.Delayed(sessionId = 1L, startedAt = 100L, captureStartsAtMs = 200L)))
    }

    @Test
    fun `banner stays for every other state`() {
        assertFalse(clearsInterruptedBanner(MonitorState.Idle))
        assertFalse(clearsInterruptedBanner(MonitorState.Starting))
        assertFalse(clearsInterruptedBanner(MonitorState.StartError("boom")))
        assertFalse(clearsInterruptedBanner(MonitorState.Interrupted(sessionId = 1L, startedAt = 100L)))
        assertFalse(clearsInterruptedBanner(MonitorState.Finished(sessionId = 1L)))
    }
}
