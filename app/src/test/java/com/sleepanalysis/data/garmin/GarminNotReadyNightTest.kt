package com.sleepanalysis.data.garmin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The not-ready night verdict (docs/GARMIN_INTEGRATION.md §6): Garmin returns 404 on the sleep
 * endpoints while the night isn't processed server-side yet — a whole-night 404 is a distinct
 * NotReady outcome, never a bogus 0-metric Success or a generic error. Partial 404s stay
 * Success (the endpoint statuses carry the detail).
 */
class GarminNotReadyNightTest {

    private fun day(vararg statuses: Pair<String, Int>) = GarminClient.GarminDayResult(
        statuses.map { (endpoint, status) -> GarminClient.EndpointResult(endpoint, status, null) },
    )

    @Test
    fun `dailySleep and hrv both 404 is not ready`() {
        assertTrue(
            GarminImportManager.isNotReadyNight(
                day(
                    GarminImportManager.EP_DAILY_SLEEP to 404,
                    GarminImportManager.EP_HRV to 404,
                ),
            ),
        )
    }

    @Test
    fun `a whole-night 404 is not ready`() {
        assertTrue(
            GarminImportManager.isNotReadyNight(
                day(
                    GarminImportManager.EP_DAILY_SLEEP to 404,
                    GarminImportManager.EP_SLEEP_TIMELINE to 404,
                    GarminImportManager.EP_HRV to 404,
                    GarminImportManager.EP_BODY_BATTERY to 404,
                    GarminImportManager.EP_DAILY_SUMMARY to 404,
                    GarminImportManager.EP_STRESS to 404,
                ),
            ),
        )
    }

    @Test
    fun `dailySleep 404 with hrv 200 is usable data, not not-ready`() {
        assertFalse(
            GarminImportManager.isNotReadyNight(
                day(
                    GarminImportManager.EP_DAILY_SLEEP to 404,
                    GarminImportManager.EP_HRV to 200,
                ),
            ),
        )
    }

    @Test
    fun `hrv 404 with dailySleep 200 is not not-ready`() {
        assertFalse(
            GarminImportManager.isNotReadyNight(
                day(
                    GarminImportManager.EP_DAILY_SLEEP to 200,
                    GarminImportManager.EP_HRV to 404,
                ),
            ),
        )
    }

    @Test
    fun `an absent hrv verdict is not not-ready`() {
        assertFalse(
            GarminImportManager.isNotReadyNight(day(GarminImportManager.EP_DAILY_SLEEP to 404)),
        )
    }

    @Test
    fun `a network failure is not a not-ready verdict`() {
        // -1 = STATUS_NO_HTTP: no HTTP verdict this run — never Garmin's "not processed" 404.
        assertFalse(
            GarminImportManager.isNotReadyNight(
                day(
                    GarminImportManager.EP_DAILY_SLEEP to -1,
                    GarminImportManager.EP_HRV to -1,
                ),
            ),
        )
    }
}
