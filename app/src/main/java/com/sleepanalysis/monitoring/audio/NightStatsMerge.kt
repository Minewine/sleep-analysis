package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.domain.report.CaptureStats
import com.sleepanalysis.domain.report.SkippedHopSpan

/**
 * Cross-restart statistics carry (night 1 dogfood): when the process dies mid-night and
 * START_STICKY resumes the same session, each engine restart resets the per-run counters and
 * session-level stats would silently shrink to the last segment. The heartbeat persists the
 * cumulative stats into the session row; on resume the saved values are carried forward here.
 *
 * Ambient median/p95 are NOT carried — the raw window levels of a dead engine are gone, so the
 * merged stats keep the current engine's ambient statistics. Clip counts/bytes are not carried
 * either: finalizeSession takes those from the audio_clip table, which is authoritative across
 * restarts. The level-gate skip counter IS carried (like the window counters) so night-level
 * diagnostics of the level gate survive restarts, and skipped-hop spans are concatenated and
 * sorted (epoch-anchored) so thermal coverage gaps survive restarts too.
 */
data class StatsCarry(
    val windowsProcessed: Long,
    val windowsSkipped: Long,
    val thermalThrottleEvents: Int,
    val levelGateSkipped: Int = 0,
    val skippedHopSpans: List<SkippedHopSpan> = emptyList(),
) {
    companion object {
        /** Counter carry from heartbeat-persisted stats of earlier engines; null when none saved. */
        fun from(capture: CaptureStats?): StatsCarry? {
            val windows = capture?.windowsProcessed ?: return null
            return StatsCarry(
                windowsProcessed = windows,
                windowsSkipped = capture.windowsSkipped ?: 0L,
                thermalThrottleEvents = capture.thermalThrottleEvents ?: 0,
                levelGateSkipped = capture.levelGateSkipped ?: 0,
                skippedHopSpans = capture.skippedHopSpans,
            )
        }
    }
}

/** Adds pre-restart counters from [carry] (null = fresh session, nothing to carry). */
fun AudioEngine.NightStats.withCarry(carry: StatsCarry?): AudioEngine.NightStats {
    if (carry == null) return this
    return copy(
        windowsProcessed = windowsProcessed + carry.windowsProcessed,
        windowsSkipped = windowsSkipped + carry.windowsSkipped,
        thermalThrottleEvents = thermalThrottleEvents + carry.thermalThrottleEvents,
        levelGateSkipped = levelGateSkipped + carry.levelGateSkipped,
        skippedHopSpans = (carry.skippedHopSpans + skippedHopSpans).sortedBy { it.startEpochMs },
    )
}

/**
 * Serializes capture stats for the session row (docs/DATA_MODEL.md §5.1). [clipCountOverride]
 * lets the finalizer store the DB-accurate clip count, which spans all engines of the session.
 * [savedAtMs] stamps heartbeat progress writes so a recovered session's true capture end is
 * bounded by the last verifiably-live moment, not by tap-to-stop. Pure — shared by heartbeat
 * progress writes and finalize. v2 adds thermal_skipped_hops (arrays of
 * [startEpochMs, endEpochMs, hops]); the parser tolerates their absence.
 */
fun captureStatsJson(
    stats: AudioEngine.NightStats?,
    clipCountOverride: Int? = null,
    savedAtMs: Long? = null,
): String {
    if (stats == null) return """{"schema_version":1}"""
    val clipCount = clipCountOverride ?: stats.clipCount
    val spansJson = if (stats.skippedHopSpans.isEmpty()) "" else
        ""","thermal_skipped_hops":[""" +
            stats.skippedHopSpans.joinToString(",") {
                "[${it.startEpochMs},${it.endEpochMs},${it.hops}]"
            } + "]"
    val savedAtJson = savedAtMs?.let { ""","saved_at_ms":$it""" } ?: ""
    return """{"schema_version":2,"windows_processed":${stats.windowsProcessed},""" +
        """"windows_skipped":${stats.windowsSkipped},""" +
        """"ambient_dbfs_median":${stats.ambientDbfsMedian ?: "null"},""" +
        """"ambient_dbfs_p95":${stats.ambientDbfsP95 ?: "null"},""" +
        """"clip_count":$clipCount,""" +
        """"thermal_throttle_events":${stats.thermalThrottleEvents},""" +
        """"level_gate_skipped":${stats.levelGateSkipped}""" +
        savedAtJson + spansJson + "}"
}
