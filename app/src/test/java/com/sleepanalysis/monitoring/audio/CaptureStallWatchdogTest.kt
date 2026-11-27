package com.sleepanalysis.monitoring.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stall watchdog (P2-4): a capture whose clip ring stops advancing (wedged AudioRecord.read,
 * a reader that never delivers samples) must trip after the stall limit — driven here with a
 * fake clock on the JVM, exactly like the engine drives it per hop.
 */
class CaptureStallWatchdogTest {

    @Test
    fun `a stalled reader trips the watchdog exactly at the limit on a fake clock`() {
        var now = 0L
        val watchdog = CaptureStallWatchdog(stallLimitMs = 60_000, clock = { now })
        watchdog.arm()
        now = 59_999
        assertFalse(watchdog.isStalled())
        now = 60_000
        assertTrue(watchdog.isStalled())
    }

    @Test
    fun `progress resets the stall window`() {
        var now = 0L
        val watchdog = CaptureStallWatchdog(stallLimitMs = 60_000, clock = { now })
        watchdog.arm()
        now = 50_000
        watchdog.onProgress()
        now = 100_000 // 50 s since the last progress — inside the limit
        assertFalse(watchdog.isStalled())
        now = 110_000 // 60 s since the last progress — stalled
        assertTrue(watchdog.isStalled())
    }

    @Test
    fun `never armed never stalls`() {
        var now = 0L
        val watchdog = CaptureStallWatchdog(stallLimitMs = 60_000, clock = { now })
        now = 1_000_000
        assertFalse(watchdog.isStalled()) // init may take arbitrarily long on OEM devices
        watchdog.arm()
        watchdog.onProgress()
        assertFalse(watchdog.isStalled())
    }
}
