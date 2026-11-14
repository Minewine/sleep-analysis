package com.sleepanalysis.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Defaults of the preference flags that have no bounds object ([PreferenceBounds]) — the
 * read-time mapping is the seam the DataStore flows use, so the absent-key default is pinned
 * here (the DataStore itself needs an Android context and lives behind instrumentation).
 */
class UserPreferencesDefaultsTest {

    @Test
    fun `capture wakelock experiment flag defaults to held (off)`() {
        // Default = wakelock HELD, today's behavior; the flag only opts OUT for the A/B night.
        assertEquals(false, UserPreferences.DEFAULT_DEBUG_SKIP_CAPTURE_WAKELOCK)
        assertEquals(false, UserPreferences.debugSkipCaptureWakelockOrDefault(null))
    }

    @Test
    fun `capture wakelock experiment flag passes stored values through`() {
        assertEquals(true, UserPreferences.debugSkipCaptureWakelockOrDefault(true))
        assertEquals(false, UserPreferences.debugSkipCaptureWakelockOrDefault(false))
    }
}
