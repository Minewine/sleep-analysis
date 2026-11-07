package com.sleepanalysis.monitoring.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure formatting of the battery snapshot for session-log lines (see [format]). */
class BatteryStatusTest {

    @Test
    fun `null renders placeholders`() {
        assertEquals("batt=? charging=?", format(null))
    }

    @Test
    fun `values render exactly`() {
        assertEquals("batt=80% charging=false", format(BatteryStatus(80, false)))
        assertEquals("batt=100% charging=true", format(BatteryStatus(100, true)))
        assertEquals("batt=3% charging=true", format(BatteryStatus(3, true)))
    }
}
