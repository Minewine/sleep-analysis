package com.sleepanalysis.ui.report

import com.sleepanalysis.data.garmin.GarminImportManager
import com.sleepanalysis.data.prefs.UserPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silent morning backfill's retry accounting (ReportViewModel.maybeBackfillGarmin): the
 * attempt must mark the counter its outcome drew from — a not-ready morning (Garmin hasn't
 * processed the night yet) draws the looser not-ready budget and never the general 2/day cap —
 * and the next open of the report re-gates on that counter
 * (UserPreferences.GarminNotReadyBounds.backfillAllowed).
 */
class ReportViewModelGarminBackfillTest {

    private val date = "2026-11-06"
    private val hour = 3_600_000L
    private val t0 = 100 * hour

    @Test
    fun `only a not-ready outcome draws the not-ready budget`() {
        assertTrue(drawsNotReadyBudget(GarminImportManager.GarminImportState.NotReady(date, emptyList())))
        // Any other terminal outcome — including a 0-metric Success — is a general attempt.
        assertFalse(drawsNotReadyBudget(GarminImportManager.GarminImportState.Success(date, 0, 0, emptyList())))
        assertFalse(drawsNotReadyBudget(GarminImportManager.GarminImportState.Error(date, "network unreachable")))
        // Non-terminal states never reach the mark (guarded before the import starts).
        assertFalse(drawsNotReadyBudget(GarminImportManager.GarminImportState.Importing(date)))
        assertFalse(drawsNotReadyBudget(GarminImportManager.GarminImportState.Idle))
    }

    @Test
    fun `a not-ready morning marks its own counter and retries after its 1 h cooldown`() {
        // drawsNotReadyBudget(NotReady) → markGarminNotReadyRetry(date): not-ready count 1,
        // the general counter untouched. Half an hour later the backfill is still gated; after
        // the hour it retries on the not-ready budget alone.
        assertTrue(drawsNotReadyBudget(GarminImportManager.GarminImportState.NotReady(date, emptyList())))
        assertFalse(
            UserPreferences.GarminNotReadyBounds.backfillAllowed(
                generalDate = null, generalMs = 0, generalCount = 0,
                notReadyDate = date, notReadyMs = t0, notReadyCount = 1,
                date = date, nowMs = t0 + hour / 2,
            ),
        )
        assertTrue(
            UserPreferences.GarminNotReadyBounds.backfillAllowed(
                generalDate = null, generalMs = 0, generalCount = 0,
                notReadyDate = date, notReadyMs = t0, notReadyCount = 1,
                date = date, nowMs = t0 + hour,
            ),
        )
    }

    @Test
    fun `a real error keeps the general budget and its 6 h cooldown`() {
        // drawsNotReadyBudget(Error) → markGarminAutoRetry(date): one general attempt used.
        // After 1 h the backfill stays gated even though the not-ready budget is completely
        // unused — the counters are independent.
        assertFalse(drawsNotReadyBudget(GarminImportManager.GarminImportState.Error(date, "boom")))
        assertFalse(
            UserPreferences.GarminNotReadyBounds.backfillAllowed(
                generalDate = date, generalMs = t0, generalCount = 1,
                notReadyDate = null, notReadyMs = 0, notReadyCount = 0,
                date = date, nowMs = t0 + hour,
            ),
        )
        assertTrue(
            UserPreferences.GarminNotReadyBounds.backfillAllowed(
                generalDate = date, generalMs = t0, generalCount = 1,
                notReadyDate = null, notReadyMs = 0, notReadyCount = 0,
                date = date, nowMs = t0 + 6 * hour,
            ),
        )
    }
}
