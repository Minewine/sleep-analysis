package com.sleepanalysis.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/** Setter- and read-boundary clamps: negative retention must never future-date the retention cutoff, and legacy out-of-range stored values must be safe at the reads too. */
class PreferenceBoundsTest {

    @Test
    fun `retention days clamp to 1-365`() {
        assertEquals(1, PreferenceBounds.sanitizeRetentionDays(-30))
        assertEquals(1, PreferenceBounds.sanitizeRetentionDays(0))
        assertEquals(7, PreferenceBounds.sanitizeRetentionDays(7))
        assertEquals(365, PreferenceBounds.sanitizeRetentionDays(365))
        assertEquals(365, PreferenceBounds.sanitizeRetentionDays(3650))
    }

    @Test
    fun `schedule minutes clamp to the minutes-of-day clock`() {
        assertEquals(0, PreferenceBounds.sanitizeMinuteOfDay(-1))
        assertEquals(0, PreferenceBounds.sanitizeMinuteOfDay(0))
        assertEquals(22 * 60 + 30, PreferenceBounds.sanitizeMinuteOfDay(22 * 60 + 30))
        assertEquals(24 * 60 - 1, PreferenceBounds.sanitizeMinuteOfDay(24 * 60))
        assertEquals(24 * 60 - 1, PreferenceBounds.sanitizeMinuteOfDay(10_000))
    }

    @Test
    fun `read-time clamp contains legacy out-of-range stored values, defaults only when absent`() {
        // Legacy 0 would future-date the retention cutoff (mass deletion on the next
        // RetentionWorker run); an absent value falls back to the default, not the floor.
        assertEquals(1, PreferenceBounds.sanitizeRetentionDaysOrDefault(0, default = 7))
        assertEquals(365, PreferenceBounds.sanitizeRetentionDaysOrDefault(10_000, default = 7))
        assertEquals(90, PreferenceBounds.sanitizeRetentionDaysOrDefault(null, default = 90))

        assertEquals(0, PreferenceBounds.sanitizeMinuteOfDayOrDefault(-5, default = 22 * 60 + 30))
        assertEquals(24 * 60 - 1, PreferenceBounds.sanitizeMinuteOfDayOrDefault(2_000, default = 22 * 60 + 30))
        assertEquals(22 * 60 + 30, PreferenceBounds.sanitizeMinuteOfDayOrDefault(null, default = 22 * 60 + 30))
    }
}
