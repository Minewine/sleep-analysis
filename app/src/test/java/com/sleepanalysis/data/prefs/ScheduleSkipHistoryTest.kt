package com.sleepanalysis.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The skip-history mirroring rule (UserPreferences.setScheduleSkipEpochDay): every skip write
 * adds its epoch day to an additive string set, and the read side parses the strings back,
 * dropping malformed entries. The DataStore itself needs an Android context and lives behind
 * instrumentation, so the pure seam is pinned here — same discipline as
 * [UserPreferencesDefaultsTest].
 */
class ScheduleSkipHistoryTest {

    @Test
    fun `mirroring is additive and starts from nothing`() {
        assertEquals(setOf("20453"), UserPreferences.skipHistoryWith(null, 20453))
        assertEquals(setOf("20453", "20454"), UserPreferences.skipHistoryWith(setOf("20453"), 20454))
        // A set: re-skipping the same night cannot duplicate the entry.
        assertEquals(setOf("20453"), UserPreferences.skipHistoryWith(setOf("20453"), 20453))
    }

    @Test
    fun `parsing tolerates absence and drops malformed entries`() {
        assertEquals(emptySet<Long>(), UserPreferences.skipHistoryEpochDays(null))
        assertEquals(emptySet<Long>(), UserPreferences.skipHistoryEpochDays(emptySet()))
        assertEquals(setOf(20453L, 20454L), UserPreferences.skipHistoryEpochDays(setOf("20453", "20454")))
        assertEquals(setOf(20453L), UserPreferences.skipHistoryEpochDays(setOf("20453", "junk", "")))
    }

    @Test
    fun `an existing empty set behaves like absence when mirroring`() {
        // Null (never stored) and SetOf() (cleared) must land on the same additive result.
        assertEquals(setOf("20453"), UserPreferences.skipHistoryWith(emptySet(), 20453))
    }

    @Test
    fun `parsing survives hostile stored values`() {
        // Negative values parse and survive — harmless, since verdicts look up positive
        // wake-day/START-evening epoch days that can never equal a negative.
        assertEquals(setOf(-5L), UserPreferences.skipHistoryEpochDays(setOf("-5")))
        // A sign prefix parses (java Long semantics) and is honored as its value.
        assertEquals(setOf(20453L), UserPreferences.skipHistoryEpochDays(setOf("+20453")))
        // Beyond Long range and whitespace-padded strings are dropped, not crashes.
        assertEquals(emptySet<Long>(), UserPreferences.skipHistoryEpochDays(setOf("99999999999999999999")))
        assertEquals(emptySet<Long>(), UserPreferences.skipHistoryEpochDays(setOf(" 20453", "20453 ")))
    }

    @Test
    fun `non-positive days never enter the history`() {
        // A malformed occurrence day (0 / negative) must not persist a permanent "0" entry;
        // existing entries stay untouched. The single-night key keeps its own contract (the
        // setter writes it as before; its callers already enforce >= 0).
        assertEquals(emptySet<String>(), UserPreferences.skipHistoryWith(null, 0))
        assertEquals(setOf("20453"), UserPreferences.skipHistoryWith(setOf("20453"), 0))
        assertEquals(setOf("20453"), UserPreferences.skipHistoryWith(setOf("20453"), -1))
    }
}
