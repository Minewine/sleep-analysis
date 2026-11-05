package com.sleepanalysis.monitoring.service

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary of the thermal-throttle decision: MODERATE is the first throttled level. */
class ThermalPolicyTest {

    @Test
    fun `none and light do not throttle`() {
        assertFalse(ThermalPolicy.isThrottled(PowerManager.THERMAL_STATUS_NONE))
        assertFalse(ThermalPolicy.isThrottled(PowerManager.THERMAL_STATUS_LIGHT))
    }

    @Test
    fun `moderate and above throttle`() {
        assertTrue(ThermalPolicy.isThrottled(PowerManager.THERMAL_STATUS_MODERATE))
        assertTrue(ThermalPolicy.isThrottled(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(ThermalPolicy.isThrottled(PowerManager.THERMAL_STATUS_CRITICAL))
        assertTrue(ThermalPolicy.isThrottled(PowerManager.THERMAL_STATUS_EMERGENCY))
    }
}
