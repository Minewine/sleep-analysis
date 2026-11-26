package com.sleepanalysis.ui.home

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Home schedule row shows, as a pure decision. Hidden until the first schedule read
 * completes (the off-row must never flash for an on-schedule user) and whenever recording is
 * disabled; then an armed start shows it, and no armed start — the scheduler answers null when
 * the schedule is off — is the explicit off-state. Together these cover the full truth table.
 */
class ScheduleRowStateTest {

    private val tonight = ZonedDateTime.of(2026, 11, 24, 23, 0, 0, 0, ZoneId.of("Europe/Zurich"))
    private val tomorrow = tonight.plusDays(1)

    @Test
    fun `unresolved schedule hides the row whatever the other inputs`() {
        for (recordingEnabled in listOf(true, false)) {
            for (armedStart in listOf(tonight, null)) {
                assertEquals(
                    "recordingEnabled=$recordingEnabled armedStart=$armedStart",
                    ScheduleRowState.Hidden,
                    scheduleRowState(
                        scheduleResolved = false,
                        recordingEnabled = recordingEnabled,
                        armedStart = armedStart,
                    ),
                )
            }
        }
    }

    @Test
    fun `recording disabled hides the row with or without an armed start`() {
        // The armed start can be a moment stale after the master switch is turned off — the
        // switch wins.
        assertEquals(
            ScheduleRowState.Hidden,
            scheduleRowState(scheduleResolved = true, recordingEnabled = false, armedStart = null),
        )
        assertEquals(
            ScheduleRowState.Hidden,
            scheduleRowState(scheduleResolved = true, recordingEnabled = false, armedStart = tonight),
        )
    }

    @Test
    fun `an armed start is carried as given`() {
        assertEquals(
            ScheduleRowState.Armed(tonight),
            scheduleRowState(scheduleResolved = true, recordingEnabled = true, armedStart = tonight),
        )
        // After "Skip tonight" the scheduler answers tomorrow's start — carried unchanged too.
        assertEquals(
            ScheduleRowState.Armed(tomorrow),
            scheduleRowState(scheduleResolved = true, recordingEnabled = true, armedStart = tomorrow),
        )
    }

    @Test
    fun `recording enabled without an armed start is the explicit off-state`() {
        assertEquals(
            ScheduleRowState.Off,
            scheduleRowState(scheduleResolved = true, recordingEnabled = true, armedStart = null),
        )
    }
}
