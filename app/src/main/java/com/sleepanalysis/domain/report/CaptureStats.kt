package com.sleepanalysis.domain.report

import org.json.JSONArray
import org.json.JSONObject

/**
 * One continuous period of thermally skipped analysis hops (docs/DATA_MODEL.md §5.1). Within a
 * span, every 2nd hop of the 0.5 s grid was unanalyzed (the skip rule while throttled), so
 * analysis can correct per-hour detection coverage for the reduced sampling rate. Epoch-based
 * so spans from a resumed engine merge trivially. [hops] is the skipped-hop count for sanity.
 */
data class SkippedHopSpan(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val hops: Int,
)

/**
 * Parsed capture statistics for one session (the `capture_stats` JSON written at session
 * finalize, docs/DATA_MODEL.md §5.1). Tolerant: missing or malformed fields become null so
 * older sessions and interrupted writes still render.
 */
data class CaptureStats(
    val schemaVersion: Int,
    val windowsProcessed: Long?,
    val windowsSkipped: Long?,
    val ambientDbfsMedian: Double?,
    val ambientDbfsP95: Double?,
    val clipCount: Int?,
    val thermalThrottleEvents: Int?,
    val levelGateSkipped: Int? = null,
    val skippedHopSpans: List<SkippedHopSpan> = emptyList(),
    /** When this stats object was written — heartbeat progress carries a live timestamp. */
    val savedAtMs: Long? = null,
    /** When audio capture actually began (after the start delay); null for pre-v8 rows. */
    val captureStartMs: Long? = null,
) {
    /**
     * Mic-live capture time in ms: every 0.5 s analysis hop the engine processed or
     * thermally skipped, summed across all engines of the session (the heartbeat carry makes
     * the counters cumulative). Excludes the start delay, process-death gaps and
     * pre/post-capture wall time — the denominator snore share and score are meant to use
     * (docs/DATA_MODEL.md §5.1). Null when the session has no capture stats; 0 when stats
     * exist but nothing was processed.
     */
    fun capturedAnalysisMs(hopMs: Long = HOP_MS): Long? =
        windowsProcessed?.let { processed -> (processed + (windowsSkipped ?: 0L)) * hopMs }

    companion object {
        /** The analysis hop grid (DetectorConfig.HOP_MS); duplicated here to keep domain pure. */
        const val HOP_MS = 500L

        fun parse(json: String?): CaptureStats? = runCatching {
            if (json.isNullOrBlank()) return@runCatching null
            val o = JSONObject(json)
            CaptureStats(
                schemaVersion = o.optInt("schema_version", 1),
                windowsProcessed = o.optLongOrNull("windows_processed"),
                windowsSkipped = o.optLongOrNull("windows_skipped"),
                ambientDbfsMedian = o.optDoubleOrNull("ambient_dbfs_median"),
                ambientDbfsP95 = o.optDoubleOrNull("ambient_dbfs_p95"),
                clipCount = o.optIntOrNull("clip_count"),
                thermalThrottleEvents = o.optIntOrNull("thermal_throttle_events"),
                levelGateSkipped = o.optIntOrNull("level_gate_skipped"),
                skippedHopSpans = o.optJSONArrayOrNull("thermal_skipped_hops")?.toSpans() ?: emptyList(),
                savedAtMs = o.optLongOrNull("saved_at_ms"),
                captureStartMs = o.optLongOrNull("capture_started_at"),
            )
        }.getOrNull()

        private fun JSONArray.toSpans(): List<SkippedHopSpan> =
            (0 until length()).mapNotNull { i ->
                val a = optJSONArray(i) ?: return@mapNotNull null
                if (a.length() < 3) return@mapNotNull null
                val span = SkippedHopSpan(startEpochMs = a.optLong(0), endEpochMs = a.optLong(1), hops = a.optInt(2))
                // Non-numeric entries opt* to 0: degenerate spans are payload garbage, drop them.
                if (span.hops <= 0 || span.endEpochMs < span.startEpochMs) null else span
            }

        private fun JSONObject.optJSONArrayOrNull(key: String): JSONArray? =
            if (has(key) && !isNull(key)) optJSONArray(key) else null

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key) else null

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null
    }
}
