package com.sleepanalysis.ui.home

import com.sleepanalysis.monitoring.service.MonitorState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for the user-confirmed finalize of an interrupted night: it may only act while the live
 * engine cannot own the session. A service restart racing the stale banner must never have its
 * session row closed underneath it.
 */
class InterruptedFinalizeGuardTest {

    private val sessionId = 7L

    @Test
    fun `idle and interrupted states allow finalize`() {
        assertTrue(canFinalizeInterrupted(MonitorState.Idle))
        assertTrue(canFinalizeInterrupted(MonitorState.Interrupted(sessionId, startedAt = 1L)))
    }

    @Test
    fun `live engine states refuse finalize`() {
        // Concurrent start racing the finalize tap: whoever wins, a live engine owns the session.
        assertFalse(canFinalizeInterrupted(MonitorState.Starting))
        assertFalse(
            canFinalizeInterrupted(
                MonitorState.Delayed(sessionId, startedAt = 1L, captureStartsAtMs = 2L),
            )
        )
        assertFalse(canFinalizeInterrupted(MonitorState.Recording(sessionId, startedAt = 1L)))
    }

    @Test
    fun `transient states refuse finalize`() {
        // Not dead-session states: restarting the app re-surfaces the interrupted banner instead
        // of guessing here (Finished is mid-navigation; StartError may still hide an open row).
        assertFalse(canFinalizeInterrupted(MonitorState.StartError("storage")))
        assertFalse(canFinalizeInterrupted(MonitorState.Finished(sessionId)))
    }
}
