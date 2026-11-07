package com.sleepanalysis.monitoring.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Device battery snapshot for the session log, restoring the earlier builds' `batt=80%
 * charging=false` field on the 15-minute heartbeat and the recording_stop lines. The only
 * Android-dependent part is [read] (sticky-broadcast lookup); [format] is pure and JVM-tested.
 *
 * Consumed by MonitorService: the heartbeat and recording_stop log details append
 * `format(read(context))` (restoring the earlier builds' battery field).
 */
data class BatteryStatus(val percent: Int, val charging: Boolean)

/**
 * Renders `"batt=80% charging=true"`; null (battery state unavailable) renders
 * `"batt=? charging=?"` so the log line keeps its shape on devices that never deliver
 * ACTION_BATTERY_CHANGED.
 */
fun format(b: BatteryStatus?): String =
    b?.let { "batt=${it.percent}% charging=${it.charging}" } ?: "batt=? charging=?"

/**
 * Current battery state from the sticky ACTION_BATTERY_CHANGED broadcast — no receiver is
 * registered, so the read is one line with no lifecycle. Returns null when the broadcast is
 * absent (never delivered since boot) or the level/scale pair is not positive (some OEMs
 * broadcast placeholder values early in boot), and treats charging as "plugged in":
 * BATTERY_STATUS_CHARGING or BATTERY_STATUS_FULL.
 */
fun read(context: Context): BatteryStatus? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level <= 0 || scale <= 0) return null
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryStatus(
        percent = ((level * 100) / scale).coerceAtMost(100), // some OEMs report level > scale
        charging = charging,
    )
}
