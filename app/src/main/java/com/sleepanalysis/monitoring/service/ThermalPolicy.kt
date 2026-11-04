package com.sleepanalysis.monitoring.service

import android.os.PowerManager

/**
 * Pure thermal-throttling decision: at MODERATE+ the engine analyzes every 2nd hop
 * (0.5 s → 1.0 s) while the ring buffer keeps advancing, so clip capture is unaffected
 * (README known limitations). The system does not re-broadcast the status when a listener
 * registers, so the current status must also be applied to a freshly created engine
 * (see [MonitorService]).
 */
object ThermalPolicy {
    fun isThrottled(thermalStatus: Int): Boolean =
        thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
}
