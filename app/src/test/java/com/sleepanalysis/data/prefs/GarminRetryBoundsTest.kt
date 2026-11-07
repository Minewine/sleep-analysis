package com.sleepanalysis.data.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure auto-backfill gate: one attempt per date + exactly one cooldown retry. */
class GarminRetryBoundsTest {

    private val date = "2026-11-06"
    private val hour = 3_600_000L

    @Test
    fun `a new date always allows`() {
        assertTrue(
            UserPreferences.GarminRetryBounds.retryAllowed(
                lastDate = "2026-11-05", lastMs = 100 * hour, lastCount = 5, date = date, nowMs = 100 * hour,
            ),
        )
        assertTrue(
            UserPreferences.GarminRetryBounds.retryAllowed(
                lastDate = null, lastMs = 0, lastCount = 0, date = date, nowMs = 0,
            ),
        )
    }

    @Test
    fun `same date is blocked before the cooldown`() {
        assertFalse(
            UserPreferences.GarminRetryBounds.retryAllowed(
                lastDate = date, lastMs = 100 * hour, lastCount = 1, date = date, nowMs = 102 * hour,
            ),
        )
    }

    @Test
    fun `cooldown elapsed allows exactly one retry, then never again`() {
        val last = 100 * hour
        val afterCooldown = last + UserPreferences.GarminRetryBounds.AUTO_RETRY_COOLDOWN_MS
        val bounds = UserPreferences.GarminRetryBounds
        // First attempt used → one retry allowed after the cooldown.
        assertTrue(bounds.retryAllowed(date, last, 1, date, afterCooldown))
        // Both attempts used → no more, however long the cooldown.
        assertFalse(bounds.retryAllowed(date, last, 2, date, afterCooldown * 10))
        // …and a new date resets the budget.
        assertTrue(bounds.retryAllowed(date, last, 2, "2026-11-07", afterCooldown))
    }
}
