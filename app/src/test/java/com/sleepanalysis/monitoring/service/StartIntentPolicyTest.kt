package com.sleepanalysis.monitoring.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision tables for [startCommandFor] and [resumeDecision]: the START_STICKY restart (null
 * intent) must never auto-resume capture — a background-started microphone foreground service
 * gets no while-in-use mic access on Android 11+ and would record silence all night without
 * any error — and the interrupted-notification Resume tap is gated by the master recording
 * switch like every other start.
 */
class StartIntentPolicyTest {

    @Test
    fun `null intent is a sticky restart and never starts capture`() {
        assertEquals(StartCommand.SURFACE_INTERRUPTED, startCommandFor(null))
    }

    @Test
    fun `explicit start action starts a new session`() {
        // Home button / bedtime-notification Start action: user-tap path with mic access.
        assertEquals(StartCommand.START_NEW_SESSION, startCommandFor(MonitorService.ACTION_START))
    }

    @Test
    fun `resume action resumes the open interrupted session`() {
        assertEquals(StartCommand.RESUME_OPEN_SESSION, startCommandFor(MonitorService.ACTION_RESUME))
    }

    @Test
    fun `stop action never starts capture`() {
        assertEquals(StartCommand.STOP, startCommandFor(MonitorService.ACTION_STOP))
    }

    @Test
    fun `unknown action is treated as a sticky restart and never starts capture`() {
        assertEquals(StartCommand.SURFACE_INTERRUPTED, startCommandFor("com.other.WHOOPS"))
    }

    @Test
    fun `resume with an open session and recording enabled resumes capture`() {
        assertEquals(ResumeDecision.RESUME, resumeDecision(openSessionId = 7L, recordingEnabled = true))
    }

    @Test
    fun `resume with the master switch off never starts capture and keeps the banner`() {
        // The session stays open and the interrupted notification stays up: it remains the
        // user's re-entry point (same switch that gates manual and scheduled starts).
        assertEquals(ResumeDecision.BLOCKED, resumeDecision(openSessionId = 7L, recordingEnabled = false))
    }

    @Test
    fun `resume without an open session is stale regardless of the switch`() {
        assertEquals(ResumeDecision.STALE, resumeDecision(openSessionId = 0L, recordingEnabled = true))
        assertEquals(ResumeDecision.STALE, resumeDecision(openSessionId = -1L, recordingEnabled = false))
    }
}
