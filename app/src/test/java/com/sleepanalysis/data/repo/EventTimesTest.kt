package com.sleepanalysis.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class EventTimesTest {

    private val sessionStart = 1_793_564_621_693L // night 1, 2026-11-01T20:23:41.693Z
    private val segment2Base = 1_793_592_524_177L // resumed engine, 2026-11-02T04:08:44.177Z

    @Test
    fun `fresh engine base near session start yields engine offset`() {
        val t = resolveEventTimes(
            sessionStartEpochMs = sessionStart,
            captureTimeBaseEpochMs = sessionStart + 320,
            draftStartOffsetMs = 7_500,
            draftEndOffsetMs = 9_500,
        )
        assertEquals(sessionStart + 320 + 7_500, t.startMs)
        assertEquals(sessionStart + 320 + 9_500, t.endMs)
        assertEquals(320 + 7_500, t.offsetMs)
    }

    @Test
    fun `resumed engine times are absolute, not session-relative`() {
        val t = resolveEventTimes(
            sessionStartEpochMs = sessionStart,
            captureTimeBaseEpochMs = segment2Base,
            draftStartOffsetMs = 7_500,
            draftEndOffsetMs = 9_500,
        )
        // Must land in the morning of Nov 2, NOT backdated into the session's first minutes.
        assertEquals(segment2Base + 7_500, t.startMs)
        assertEquals(27_909_984L, t.offsetMs) // ~7h45m into the session
    }

    @Test
    fun `offset is clamped when the capture base precedes the session row`() {
        val t = resolveEventTimes(
            sessionStartEpochMs = sessionStart,
            captureTimeBaseEpochMs = sessionStart - 1_000,
            draftStartOffsetMs = 500,
            draftEndOffsetMs = 2_500,
        )
        assertEquals(0L, t.offsetMs)
        assertEquals(sessionStart - 500, t.startMs)
    }
}
