package com.sleepanalysis.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/** Delay math for the configurable start delay (see [StartDelayPolicy]). */
class StartDelayPolicyTest {

    private val startedAt = 1_793_628_000_000L // 2026-11-02 15:00:00 local-ish
    private val minuteMs = 60_000L

    @Test
    fun `sanitize clamps to the valid range`() {
        assertEquals(0, StartDelayPolicy.sanitizeDelayMinutes(null))
        assertEquals(0, StartDelayPolicy.sanitizeDelayMinutes(-5))
        assertEquals(0, StartDelayPolicy.sanitizeDelayMinutes(0))
        assertEquals(60, StartDelayPolicy.sanitizeDelayMinutes(60))
        assertEquals(StartDelayPolicy.MAX_DELAY_MINUTES, StartDelayPolicy.sanitizeDelayMinutes(500))
    }

    @Test
    fun `zero delay starts capture at session start`() {
        assertEquals(startedAt, StartDelayPolicy.captureStartAtMs(0, startedAt))
        assertEquals(0L, StartDelayPolicy.remainingMs(0, startedAt, startedAt + minuteMs))
    }

    @Test
    fun `capture starts after the configured delay`() {
        assertEquals(startedAt + 60 * minuteMs, StartDelayPolicy.captureStartAtMs(60, startedAt))
        assertEquals(
            30 * minuteMs,
            StartDelayPolicy.remainingMs(60, startedAt, startedAt + 30 * minuteMs),
        )
    }

    @Test
    fun `remaining never goes negative`() {
        assertEquals(0L, StartDelayPolicy.remainingMs(30, startedAt, startedAt + 45 * minuteMs))
    }

    @Test
    fun `over-max preference is clamped in the math too`() {
        assertEquals(startedAt + StartDelayPolicy.MAX_DELAY_MINUTES * minuteMs,
            StartDelayPolicy.captureStartAtMs(999, startedAt))
    }

    @Test
    fun `restart mid-delay recomputes the remainder from the session start`() {
        // Process died 10 min into a 60 min delay; sticky restart at T+10 must wait 50 more.
        val restartAt = startedAt + 10 * minuteMs
        assertEquals(50 * minuteMs, StartDelayPolicy.remainingMs(60, startedAt, restartAt))
    }
}
