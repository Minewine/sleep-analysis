package com.sleepanalysis.data.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure auto-backfill gate: one attempt per date + exactly one cooldown retry. */
class GarminRetryBoundsTest {

    private val date = "2026-11-06"
    private val hour = 3_600_000L
    private val t0 = 100 * hour

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

    /** The combined backfill gate: general + not-ready counters, the last attempt's kind decides. */
    private val notReady = UserPreferences.GarminNotReadyBounds

    @Test
    fun `a date with no attempt of either kind always allows`() {
        assertTrue(
            notReady.backfillAllowed(
                generalDate = "2026-11-04", generalMs = 10 * hour, generalCount = 2,
                notReadyDate = "2026-11-04", notReadyMs = 10 * hour, notReadyCount = 4,
                date = date, nowMs = 11 * hour,
            ),
        )
    }

    @Test
    fun `a not-ready morning retries on its own 1 h cooldown, never the general 6 h`() {
        // Morning attempt came back not-ready → marked on the not-ready counter only; the
        // general counter is untouched. Half an hour later: blocked (cooldown not elapsed).
        assertFalse(
            notReady.backfillAllowed(
                generalDate = null, generalMs = 0, generalCount = 0,
                notReadyDate = date, notReadyMs = t0, notReadyCount = 1,
                date = date, nowMs = t0 + hour / 2,
            ),
        )
        // After the hour it retries — although the general 6 h cooldown has NOT elapsed.
        assertTrue(
            notReady.backfillAllowed(
                generalDate = null, generalMs = 0, generalCount = 0,
                notReadyDate = date, notReadyMs = t0, notReadyCount = 1,
                date = date, nowMs = t0 + hour,
            ),
        )
    }

    @Test
    fun `four not-ready attempts exhaust only the not-ready budget`() {
        // The general cap was never consumed, but this budget is full: no more that date…
        assertFalse(
            notReady.backfillAllowed(
                generalDate = null, generalMs = 0, generalCount = 0,
                notReadyDate = date, notReadyMs = t0, notReadyCount = 4,
                date = date, nowMs = t0 + 24 * hour,
            ),
        )
        // …and a new date resets it.
        assertTrue(
            notReady.backfillAllowed(
                generalDate = null, generalMs = 0, generalCount = 0,
                notReadyDate = date, notReadyMs = t0, notReadyCount = 4,
                date = "2026-11-07", nowMs = t0,
            ),
        )
    }

    @Test
    fun `a real failure keeps the general budget, cooldown and cap`() {
        // One general Error: blocked after 1 h — the not-ready cooldown would have allowed,
        // proving the counters are independent — allowed after the 6 h one.
        assertFalse(
            notReady.backfillAllowed(
                generalDate = date, generalMs = t0, generalCount = 1,
                notReadyDate = null, notReadyMs = 0, notReadyCount = 0,
                date = date, nowMs = t0 + hour,
            ),
        )
        assertTrue(
            notReady.backfillAllowed(
                generalDate = date, generalMs = t0, generalCount = 1,
                notReadyDate = null, notReadyMs = 0, notReadyCount = 0,
                date = date, nowMs = t0 + 6 * hour,
            ),
        )
        // Both general attempts used → no more that date, not on the not-ready budget either.
        assertFalse(
            notReady.backfillAllowed(
                generalDate = date, generalMs = t0, generalCount = 2,
                notReadyDate = null, notReadyMs = 0, notReadyCount = 0,
                date = date, nowMs = t0 + 24 * hour,
            ),
        )
    }

    @Test
    fun `the gate follows the kind of the last attempt on the date`() {
        // General attempt at t0, not-ready at t0+6 h: from then on the not-ready budget decides.
        assertFalse(
            notReady.backfillAllowed(
                generalDate = date, generalMs = t0, generalCount = 1,
                notReadyDate = date, notReadyMs = t0 + 6 * hour, notReadyCount = 1,
                date = date, nowMs = t0 + 6 * hour + hour / 2,
            ),
        )
        assertTrue(
            notReady.backfillAllowed(
                generalDate = date, generalMs = t0, generalCount = 1,
                notReadyDate = date, notReadyMs = t0 + 6 * hour, notReadyCount = 1,
                date = date, nowMs = t0 + 7 * hour,
            ),
        )
        // After a NEWER general attempt its 6 h cooldown governs again (the not-ready budget
        // would still allow — the kind switched back, and only its cooldown blocks).
        assertFalse(
            notReady.backfillAllowed(
                generalDate = date, generalMs = t0 + 8 * hour, generalCount = 1,
                notReadyDate = date, notReadyMs = t0 + 6 * hour, notReadyCount = 1,
                date = date, nowMs = t0 + 9 * hour,
            ),
        )
        assertTrue(
            notReady.backfillAllowed(
                generalDate = date, generalMs = t0 + 8 * hour, generalCount = 1,
                notReadyDate = date, notReadyMs = t0 + 6 * hour, notReadyCount = 1,
                date = date, nowMs = t0 + 14 * hour,
            ),
        )
    }
}
