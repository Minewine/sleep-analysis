package com.sleepanalysis.domain.validity

import com.sleepanalysis.data.files.ClipIntegrity
import com.sleepanalysis.domain.report.CaptureStats
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Night Validity — a data-quality grade A–D for one recorded night
 * (docs/NIGHT_REPORT.md §Night validity). Deterministic, provenance-honest, a pure function
 * of the night's capture evidence: it grades
 * HOW WELL the night was captured (windows, gaps, clipping, interruptions, Garmin linkage) and
 * never the physiology. [VERSION] is stored on the NIGHT_VALIDITY measurement row (same
 * schedule discipline as [com.sleepanalysis.domain.scoring.SnoreScore.FORMULA_VERSION] — bump
 * on any recalibration so trend comparability can key on it).
 *
 * Threshold provenance — every constant below is an APP CONVENTION unless marked cited:
 *  - capture failure window (30 s) and the 10%/40% window shortfalls mirror the report's
 *    existing capture-health heuristics (ReportAssembler.healthWarnings) — conventions.
 *  - unexplained-gap 15/60 min and clipped-clip 10%/30% rates — conventions.
 *  - ambient check is RELATIVE ONLY: phone dBFS is uncalibrated (M2), so the trigger is a
 *    z-score of tonight's ambient P95 against the rolling prior-night distribution (z > 2.5,
 *    needs ≥ 5 prior nights, else the check is omitted — never an absolute-dB comparison;
 *    copy-only references like the WHO night-noise guidance must never become a threshold).
 *  - Garmin plausibility ranges are CITED: wrist PPG heart rate 30–220 bpm, rMSSD 2–300 ms,
 *    SpO₂ 70–100% with < 90% flagged distrust (wrist PPG error ±2–3 pp — Jung 2022,
 *    Rajakariar 2024); SpO₂ is never desaturation counting (M12/M17).
 *  - reported-vs-window TST tolerance ±45 min is CITED (vendor validation bias ~+40 min —
 *    Schyvens 2025, Chinoy 2021).
 *  - low-snore INFO at < 1% of capture is CITED (phone-mic snoring PPV collapses for light
 *    snorers — Klaus 2021, IJERPH).
 *  - Garmin import lag > 24 h and stage-sum ±10% — conventions.
 *
 * The reasons ([Reason]) are ordered, machine-readable and stable: the UI maps [Reason.id] to
 * a localized string, the NIGHT_VALIDITY row's notes carry the full JSON ([notes]) verbatim.
 */
object NightValidity {

    /** Validity-rule identity stored on NIGHT_VALIDITY rows; bump on ANY recalibration. */
    const val VERSION = "night-validity-v1"

    /** Reason severities (stored verbatim in the notes JSON). */
    enum class Severity { INFO, WARNING, CRITICAL }

    object ReasonIds {
        const val STATS_MISSING = "stats_missing"
        const val CAPTURE_FAILED = "capture_failed"
        const val CAPTURE_GAPS = "capture_gaps"
        const val CAPTURE_GAPS_SEVERE = "capture_gaps_severe"
        const val THERMAL_THROTTLE = "thermal_throttle"
        const val UNEXPLAINED_GAP = "unexplained_gap"
        const val CLIPPED_CLIPS = "clipped_clips"
        const val AMBIENT_NOISE_HIGH = "ambient_noise_high"
        const val SESSION_INTERRUPTED = "session_interrupted"
        const val LOW_SNORE_SIGNAL = "low_snore_signal"
    }

    /** One machine-readable finding: stable [id], [severity], human-offloadable [detail]. */
    data class Reason(val id: String, val severity: Severity, val detail: String)

    /** The explainable outcome stored on the NIGHT_VALIDITY row. */
    data class Result(val grade: String, val reasons: List<Reason>)

    /** Capture-statistics summary (see [captureSummary] for the window rule). */
    data class CaptureSummary(
        val windowsProcessed: Long?,
        /** Capture-window seconds (capture_started_at-aware), time-in-bed fallback otherwise. */
        val windowSeconds: Long?,
        val thermalThrottleEvents: Int?,
        /** Analysis hops thermally skipped across the night (the reduced-coverage evidence). */
        val skippedHops: Int,
    )

    /**
     * Clip-integrity aggregate shared by finalize (validity row) and the report's clip scan —
     * one aggregation so the two surfaces can never disagree about the counts.
     */
    data class ClipQualitySummary(
        /** Clips scanned (denominator of the clipped rate). */
        val clipCount: Int,
        val clippedCount: Int,
        /** Files that exist but could not be decoded. */
        val unreadableCount: Int,
        /** Files missing from storage. */
        val missingCount: Int,
    )

    /** Tonight's ambient P95 as a z-score of the rolling prior-night distribution. */
    data class AmbientNoiseSummary(val p95Z: Double)

    data class Input(
        /** Null when the session has no capture stats at all (pre-observability or write failed). */
        val capture: CaptureSummary?,
        /** Null when no clip scan ran. */
        val clips: ClipQualitySummary? = null,
        /** Minutes of quiet-gap time classified UNCAPTURED (process death), null without gap data. */
        val uncapturedGapMinutes: Double? = null,
        /** Final session status (at finalize the row still says RECORDING — pass the target). */
        val sessionStatus: String? = null,
        val resumedAfterInterruption: Boolean = false,
        /** Union snoring seconds / captured analysis seconds (0..1); null when unknowable. */
        val snoreShareOfCapture: Double? = null,
        /** Null when fewer than [AMBIENT_MIN_PRIOR_NIGHTS] prior nights exist (check omitted). */
        val ambient: AmbientNoiseSummary? = null,
        /** Null when no Garmin import covers the night — no import is no input, not a warning. */
        val garmin: GarminValidators.GarminValidity? = null,
    )

    // ---- Thresholds (see the KDoc above for convention vs cited) ----

    /** Below this capture-window length a zero-window night is a test session, not a failure. */
    const val CAPTURE_FAILED_MIN_WINDOW_S = 30L
    const val SHORTFALL_WARN = 0.10
    const val SHORTFALL_CRITICAL = 0.40
    const val UNEXPLAINED_GAP_WARN_MIN = 15.0
    const val UNEXPLAINED_GAP_CRITICAL_MIN = 60.0
    const val CLIPPED_WARN = 0.10
    const val CLIPPED_CRITICAL = 0.30
    const val AMBIENT_Z_TRIGGER = 2.5
    const val AMBIENT_MIN_PRIOR_NIGHTS = 5
    const val LOW_SNORE_SHARE = 0.01

    /** The analysis hop grid (DetectorConfig.HOP_MS); duplicated to keep domain pure. */
    const val HOP_MS = 500L

    /**
     * [Result.grade]: any CRITICAL → D; else 0–1 WARNINGs → A, 2–3 → B, more → C. INFO reasons
     * never lower the grade. No capture stats at all → D with [ReasonIds.STATS_MISSING].
     */
    fun compute(input: Input): Result {
        val reasons = mutableListOf<Reason>()
        val capture = input.capture
        if (capture == null) {
            reasons += Reason(ReasonIds.STATS_MISSING, Severity.CRITICAL, "no capture statistics for this night")
        } else {
            val processed = capture.windowsProcessed
            val windowSeconds = capture.windowSeconds ?: 0L
            when {
                processed == null -> { /* stats unknown — never invent a capture failure */ }
                processed == 0L && windowSeconds > CAPTURE_FAILED_MIN_WINDOW_S ->
                    reasons += Reason(
                        ReasonIds.CAPTURE_FAILED, Severity.CRITICAL,
                        "no analysis windows processed in a ${windowSeconds}s capture window",
                    )
                else -> {
                    val expected = windowSeconds * 2 // 0.5 s hop
                    if (expected > 0 && processed != null && processed < expected) {
                        val shortfall = 1.0 - processed.toDouble() / expected
                        if (shortfall > SHORTFALL_CRITICAL) {
                            reasons += Reason(
                                ReasonIds.CAPTURE_GAPS_SEVERE, Severity.CRITICAL,
                                String.format(
                                    java.util.Locale.US, "processed %d of ~%d expected windows (%.0f%% short)",
                                    processed, expected, shortfall * 100,
                                ),
                            )
                        } else if (shortfall > SHORTFALL_WARN) {
                            reasons += Reason(
                                ReasonIds.CAPTURE_GAPS, Severity.WARNING,
                                String.format(
                                    java.util.Locale.US, "processed %d of ~%d expected windows (%.0f%% short)",
                                    processed, expected, shortfall * 100,
                                ),
                            )
                        }
                    }
                }
            }
            val throttleEvents = capture.thermalThrottleEvents ?: 0
            if (throttleEvents > 0 && capture.skippedHops > 0) {
                reasons += Reason(
                    ReasonIds.THERMAL_THROTTLE, Severity.WARNING,
                    "thermal throttling engaged $throttleEvents time(s), ${capture.skippedHops} analysis hops skipped",
                )
            }
        }
        input.uncapturedGapMinutes?.let { minutes ->
            when {
                minutes > UNEXPLAINED_GAP_CRITICAL_MIN ->
                    reasons += Reason(
                        ReasonIds.UNEXPLAINED_GAP, Severity.CRITICAL,
                        String.format(java.util.Locale.US, "%.0f min of the night have no capture evidence", minutes),
                    )
                minutes > UNEXPLAINED_GAP_WARN_MIN ->
                    reasons += Reason(
                        ReasonIds.UNEXPLAINED_GAP, Severity.WARNING,
                        String.format(java.util.Locale.US, "%.0f min of the night have no capture evidence", minutes),
                    )
            }
        }
        input.clips?.takeIf { it.clipCount > 0 }?.let { clips ->
            val rate = clips.clippedCount.toDouble() / clips.clipCount
            when {
                rate > CLIPPED_CRITICAL ->
                    reasons += Reason(
                        ReasonIds.CLIPPED_CLIPS, Severity.CRITICAL,
                        "${clips.clippedCount} of ${clips.clipCount} clips digitally clipped",
                    )
                rate > CLIPPED_WARN ->
                    reasons += Reason(
                        ReasonIds.CLIPPED_CLIPS, Severity.WARNING,
                        "${clips.clippedCount} of ${clips.clipCount} clips digitally clipped",
                    )
            }
        }
        input.ambient?.takeIf { it.p95Z > AMBIENT_Z_TRIGGER }?.let {
            reasons += Reason(
                ReasonIds.AMBIENT_NOISE_HIGH, Severity.WARNING,
                String.format(
                    java.util.Locale.US, "ambient P95 z=%+.1f vs prior nights (relative only, uncalibrated dBFS)",
                    it.p95Z,
                ),
            )
        }
        val status = input.sessionStatus
        if (status != null && (status != com.sleepanalysis.data.db.entity.SessionStatus.COMPLETED ||
                input.resumedAfterInterruption)
        ) {
            reasons += Reason(
                ReasonIds.SESSION_INTERRUPTED, Severity.WARNING,
                "session " + (if (input.resumedAfterInterruption) "resumed after an interruption" else "status ${status.lowercase()}"),
            )
        }
        // Garmin validator warnings pass through verbatim (their ids are already namespaced
        // garmin_*); no import = no input = no reasons here.
        input.garmin?.warnings?.forEach { w ->
            reasons += Reason(w.id, Severity.WARNING, w.detail)
        }
        input.snoreShareOfCapture?.takeIf { it < LOW_SNORE_SHARE }?.let { share ->
            reasons += Reason(
                ReasonIds.LOW_SNORE_SIGNAL, Severity.INFO,
                String.format(
                    java.util.Locale.US, "snoring covered %.1f%% of captured audio (below the 1%% reliability floor)",
                    share * 100,
                ),
            )
        }
        return Result(grade = gradeOf(reasons), reasons = reasons)
    }

    private fun gradeOf(reasons: List<Reason>): String {
        if (reasons.any { it.severity == Severity.CRITICAL }) return "D"
        return when (reasons.count { it.severity == Severity.WARNING }) {
            0, 1 -> "A"
            2, 3 -> "B"
            else -> "C"
        }
    }

    /**
     * The machine-readable notes stored verbatim on the NIGHT_VALIDITY row — one format for the
     * finalize write and the recompute rewrites (label change, Garmin re-import) so neither
     * drifts. Hand-built (key order is part of the format; org.json objects do not order).
     */
    fun notes(result: Result): String = buildString {
        append("{\"version\":\"").append(VERSION).append("\",\"grade\":\"").append(result.grade)
        append("\",\"reasons\":[")
        result.reasons.forEachIndexed { i, r ->
            if (i > 0) append(',')
            append("{\"id\":").append(quoted(r.id))
            append(",\"severity\":").append(quoted(r.severity.name))
            append(",\"detail\":").append(quoted(r.detail))
            append('}')
        }
        append("]}")
    }

    /** Tolerant read-back of a stored notes JSON (report, trends); null when unusable. */
    fun parseNotes(json: String?): Result? = runCatching {
        if (json.isNullOrBlank()) return@runCatching null
        val o = JSONObject(json)
        if (o.optString("version") != VERSION) return@runCatching null
        val grade = o.optString("grade")
        if (grade !in GRADES) return@runCatching null
        val reasons = o.optJSONArray("reasons")?.toReasons() ?: emptyList()
        Result(grade, reasons)
    }.getOrNull()

    private fun JSONArray.toReasons(): List<Reason> =
        (0 until length()).mapNotNull { i ->
            val r = optJSONObject(i) ?: return@mapNotNull null
            val severity = r.optString("severity").let { s ->
                Severity.entries.firstOrNull { it.name == s }
            } ?: return@mapNotNull null
            Reason(r.optString("id"), severity, r.optString("detail"))
        }

    private fun quoted(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(c)
        }
        append('"')
    }

    /**
     * The capture summary for one finalized session — the same window rule as the report's
     * capture-health heuristics: capture_started_at-aware when persisted (delay-aware),
     * tap-to-stop time in bed otherwise. Null stats yield null (the stats_missing path).
     */
    fun captureSummary(stats: CaptureStats?, endedAtMs: Long, timeInBedSeconds: Long): CaptureSummary? {
        stats ?: return null
        val windowSeconds = stats.captureStartMs
            ?.takeIf { endedAtMs > it }
            ?.let { (endedAtMs - it) / 1000 }
            ?: timeInBedSeconds
        return CaptureSummary(
            windowsProcessed = stats.windowsProcessed,
            windowSeconds = windowSeconds,
            thermalThrottleEvents = stats.thermalThrottleEvents,
            skippedHops = stats.skippedHopSpans.sumOf { it.hops },
        )
    }

    /** Aggregates raw clip checks — the single shared integrity aggregation. */
    fun clipQualitySummary(checks: List<ClipIntegrity.ClipCheck>): ClipQualitySummary = ClipQualitySummary(
        clipCount = checks.size,
        clippedCount = checks.count { it.clipped },
        unreadableCount = checks.count { it.exists && !it.readable },
        missingCount = checks.count { !it.exists },
    )

    /**
     * Tonight's ambient P95 as a z-score of the rolling prior-night distribution — the pure
     * core of the relative-only ambient check. Needs ≥ [AMBIENT_MIN_PRIOR_NIGHTS] priors and
     * a non-zero spread; otherwise the check is omitted, never a guess (relative only, never
     * an absolute-dB comparison — see the object KDoc).
     */
    fun ambientNoise(tonightP95: Double, priorP95s: List<Double>): AmbientNoiseSummary? {
        if (priorP95s.size < AMBIENT_MIN_PRIOR_NIGHTS) return null
        val mean = priorP95s.average()
        val sd = sqrt(priorP95s.sumOf { (it - mean) * (it - mean) } / (priorP95s.size - 1))
        if (sd <= 0.0) return null
        return AmbientNoiseSummary((tonightP95 - mean) / sd)
    }

    private val GRADES = setOf("A", "B", "C", "D")
}
