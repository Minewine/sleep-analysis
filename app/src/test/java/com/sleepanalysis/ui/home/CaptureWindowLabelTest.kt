package com.sleepanalysis.ui.home

import com.sleepanalysis.ui.components.Formatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Home last-night card's "Captured X – Y" pairing: capture start (post-start-delay) to
 * session end, null while the night is open — the same fallback rule as the report's
 * SessionHealthCard (capture_started_at from the stats JSON, else the tap time). The clock
 * formatting itself is Formatting's; these pin WHICH instants get paired.
 */
class CaptureWindowLabelTest {

    private val startedAt = 1_000_000L
    private val endedAt = startedAt + 7 * 3_600_000L

    @Test
    fun `open night has no capture window line`() {
        assertNull(captureWindowLabel(null, startedAt, null))
        assertNull(
            captureWindowLabel(
                """{"schema_version":8,"windows_processed":40000,"capture_started_at":$startedAt}""",
                startedAt,
                null,
            ),
        )
    }

    @Test
    fun `without stats the tap time is the window start`() {
        assertEquals(
            Formatting.time(startedAt) to Formatting.time(endedAt),
            captureWindowLabel(null, startedAt, endedAt),
        )
        // Stats exist but predate capture_started_at (pre-v8) — same fallback.
        assertEquals(
            Formatting.time(startedAt) to Formatting.time(endedAt),
            captureWindowLabel("""{"schema_version":2,"windows_processed":40000}""", startedAt, endedAt),
        )
    }

    @Test
    fun `capture_started_at moves the window start past the delay`() {
        val captureStart = startedAt + 30 * 60_000L
        val json = """{"schema_version":8,"windows_processed":40000,"capture_started_at":$captureStart}"""
        assertEquals(
            Formatting.time(captureStart) to Formatting.time(endedAt),
            captureWindowLabel(json, startedAt, endedAt),
        )
    }

    @Test
    fun `malformed stats JSON falls back to the tap time`() {
        assertEquals(
            Formatting.time(startedAt) to Formatting.time(endedAt),
            captureWindowLabel("not json", startedAt, endedAt),
        )
    }

    @Test
    fun `a zero-length window pairs the same instant twice`() {
        // endedAt == startedAt (finalize immediately after start): the line still renders,
        // "22:14 – 22:14" — no special case, and never null.
        assertEquals(
            Formatting.time(startedAt) to Formatting.time(startedAt),
            captureWindowLabel(null, startedAt, startedAt),
        )
    }

    @Test
    fun `a no-delay capture_started_at equals the tap-time fallback`() {
        // capture_started_at == startedAt (0 ms start delay): the stats value and the
        // fallback produce the identical window.
        val json = """{"schema_version":8,"windows_processed":40000,"capture_started_at":$startedAt}"""
        assertEquals(
            captureWindowLabel(null, startedAt, endedAt),
            captureWindowLabel(json, startedAt, endedAt),
        )
    }

    @Test
    fun `a corrupt capture_started_at after the end is not clamped`() {
        // Garbage stats (capture start an hour AFTER the session ended): the pairing is
        // display-only, so the labels pass through unclamped — start renders after end.
        val corrupt = endedAt + 3_600_000L
        val json = """{"schema_version":8,"windows_processed":40000,"capture_started_at":$corrupt}"""
        assertEquals(
            Formatting.time(corrupt) to Formatting.time(endedAt),
            captureWindowLabel(json, startedAt, endedAt),
        )
    }

    @Test
    fun `a capture_started_at of zero falls back to the tap time`() {
        // 0 (or any non-positive value) is payload garbage, treated as absent: the fallback
        // kicks in and the epoch never renders as the start label.
        val json = """{"schema_version":8,"windows_processed":40000,"capture_started_at":0}"""
        assertEquals(
            Formatting.time(startedAt) to Formatting.time(endedAt),
            captureWindowLabel(json, startedAt, endedAt),
        )
    }
}
