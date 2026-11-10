package com.sleepanalysis.domain.validity

import com.sleepanalysis.data.garmin.GarminJsonParsers
import com.sleepanalysis.domain.policy.MeasurementType

/**
 * Pure validators for imported Garmin data (docs/GARMIN_INTEGRATION.md §6,
 * docs/NIGHT_REPORT.md §Night validity): freshness, plausibility, internal consistency. They
 * run on the PARSED metrics/stage
 * spans before the rows are written — implausible values are dropped from the write (the raw
 * JSON payloads stay verbatim) and every finding becomes a stable-id [Warning] that both the
 * Garmin card and the night's validity grade surface. Nothing here calls the network or the DB.
 *
 * Threshold provenance (mirrors [NightValidity]'s KDoc): plausibility ranges are CITED —
 * heart rate 30–220 bpm and rMSSD 2–300 ms bound wrist PPG physiology, SpO₂ 70–100% with
 * < 90% flagged distrust because wrist PPG error is ±2–3 pp (Jung 2022, Rajakariar 2024) and
 * SpO₂ is NEVER desaturation counting (M12/M17); the reported-vs-window sleep-time tolerance
 * ±45 min is CITED (vendor validation bias ~+40 min — Schyvens 2025, Chinoy 2021). The 24 h
 * freshness window and the ±10% stage-sum tolerance are app conventions. Zero overlap between
 * the sleep window and the captured night is the pre-existing "wrong night's data" signal,
 * now carried as a warning too.
 */
object GarminValidators {

    // ---- Stable warning ids (the NIGHT_VALIDITY reasons reuse them verbatim) ----
    const val STALE = "garmin_stale"
    const val NO_OVERLAP = "garmin_no_overlap"
    const val STAGE_MISMATCH = "garmin_stage_mismatch"
    const val TST_MISMATCH = "garmin_tst_mismatch"
    const val SPAN_OVERLAP = "garmin_span_overlap"
    const val IMPLAUSIBLE = "garmin_implausible"
    const val SPO2_DISTRUST = "garmin_spo2_distrust"

    /** Plausibility bounds (cited — see object KDoc). */
    const val HR_MIN_BPM = 30.0
    const val HR_MAX_BPM = 220.0
    const val RMSSD_MIN_MS = 2.0
    const val RMSSD_MAX_MS = 300.0
    const val SPO2_MIN_PERCENT = 70.0
    const val SPO2_MAX_PERCENT = 100.0
    const val SPO2_DISTRUST_BELOW = 90.0

    /** Stage-seconds sum vs reported duration tolerance (convention). */
    const val STAGE_SUM_TOLERANCE = 0.10

    /** Reported TST vs the sleep-window length tolerance in minutes (cited). */
    const val TST_TOLERANCE_MIN = 45.0

    /** Night end → fetchedAt beyond this means the import happened days late (convention). */
    const val FRESHNESS_LIMIT_MS = 24 * 3_600_000L

    data class Warning(val id: String, val detail: String)

    /**
     * The validate outcome: [warnings] in deterministic order, [keptMetrics] with implausible
     * values dropped, [validSpans] with negative/overlapping stage spans rejected.
     */
    data class GarminValidity(
        val warnings: List<Warning>,
        val keptMetrics: List<GarminJsonParsers.ParsedMetric>,
        val validSpans: List<GarminJsonParsers.SleepLevelSpan>,
    )

    /**
     * [metrics]/[stageSpans] are the freshly parsed vendor data; [sleepWindow] the vendor's own
     * sleep window; [nightEndMs]/[fetchedAtMs] anchor freshness; [captureStartMs]/[captureEndMs]
     * the acoustic capture window (when known) for the overlap check. Pure and deterministic.
     */
    fun validate(
        metrics: List<GarminJsonParsers.ParsedMetric>,
        stageSpans: List<GarminJsonParsers.SleepLevelSpan>,
        sleepWindow: GarminJsonParsers.GarminSleepWindow?,
        nightEndMs: Long?,
        fetchedAtMs: Long?,
        captureStartMs: Long? = null,
        captureEndMs: Long? = null,
    ): GarminValidity {
        // Findings are assembled in the canonical reason order (the same order the
        // NIGHT_VALIDITY row lists them in): stale → no-overlap → stage-mismatch →
        // tst-mismatch → span-overlap → implausible → spo2-distrust.
        val warnings = mutableListOf<Warning>()

        // Freshness: an import days after the night ended may be a late partial backfill.
        if (nightEndMs != null && fetchedAtMs != null && fetchedAtMs - nightEndMs > FRESHNESS_LIMIT_MS) {
            warnings += Warning(
                STALE,
                String.format(
                    java.util.Locale.US, "imported %.1f h after the night ended (limit 24 h)",
                    (fetchedAtMs - nightEndMs) / 3_600_000.0,
                ),
            )
        }

        // Zero overlap: the sleep window and the captured night are disjoint — likely the
        // wrong night's data. (The importer adds this via [noOverlap] when no session linked.)
        if (sleepWindow != null && captureStartMs != null && captureEndMs != null &&
            com.sleepanalysis.domain.report.ReportAssembler.garminOverlapMs(
                captureStartMs, captureEndMs, sleepWindow.startMs, sleepWindow.endMs,
            ) == 0L
        ) {
            warnings += noOverlap()
        }

        // Plausibility: out-of-range values are dropped (raw payloads keep the originals).
        val kept = mutableListOf<GarminJsonParsers.ParsedMetric>()
        val dropped = mutableListOf<String>()
        val distrusted = mutableListOf<String>()
        for (metric in metrics) {
            val (plausible, distrust) = when (metric.type) {
                MeasurementType.VENDOR_AVG_HEART_RATE_SLEEP, MeasurementType.VENDOR_RESTING_HEART_RATE ->
                    (metric.value in HR_MIN_BPM..HR_MAX_BPM) to false
                MeasurementType.VENDOR_HRV_RMSSD ->
                    (metric.value in RMSSD_MIN_MS..RMSSD_MAX_MS) to false
                MeasurementType.VENDOR_SPO2_AVG_SLEEP, MeasurementType.VENDOR_SPO2_LOWEST_SLEEP ->
                    (metric.value in SPO2_MIN_PERCENT..SPO2_MAX_PERCENT) to
                        (metric.value < SPO2_DISTRUST_BELOW)
                else -> true to false // vendor composites/scores are opaque by contract (M18)
            }
            when {
                !plausible -> dropped += "${metric.type.name} ${format(metric.value)} outside " + rangeText(metric.type)
                distrust -> {
                    kept += metric
                    distrusted += "${metric.type.name} ${format(metric.value)}% (<90, wrist PPG ±2–3 pp)"
                }
                else -> kept += metric
            }
        }

        // Stage spans: negative/overlapping spans are payload corruption — reject the later
        // span of an overlapping pair so the timeline keeps the earliest version of each span.
        val validSpans = mutableListOf<GarminJsonParsers.SleepLevelSpan>()
        var rejectedSpans = 0
        for (span in stageSpans.sortedBy { it.startMs }) {
            val negative = span.endMs <= span.startMs
            val overlaps = validSpans.lastOrNull()?.let { span.startMs < it.endMs } == true
            if (negative || overlaps) {
                rejectedSpans++
            } else {
                validSpans += span
            }
        }

        // Consistency: the stage spans must roughly add up to the reported duration.
        val reportedDuration = kept.firstOrNull { it.type == MeasurementType.VENDOR_SLEEP_DURATION }?.value
        if (reportedDuration != null && reportedDuration > 0 && validSpans.isNotEmpty()) {
            val spanSeconds = validSpans.sumOf { it.endMs - it.startMs } / 1000.0
            val deviation = kotlin.math.abs(spanSeconds - reportedDuration) / reportedDuration
            if (deviation > STAGE_SUM_TOLERANCE) {
                warnings += Warning(
                    STAGE_MISMATCH,
                    String.format(
                        java.util.Locale.US,
                        "stage spans sum to %.1f h vs reported %.1f h (±10%%)",
                        spanSeconds / 3600, reportedDuration / 3600,
                    ),
                )
            }
        }

        // Reported TST vs the vendor's own window: the known validation bias is ~+40 min,
        // so beyond ±45 min the duration row cannot be trusted as the window's summary.
        if (reportedDuration != null && sleepWindow != null) {
            val windowMin = (sleepWindow.endMs - sleepWindow.startMs) / 60_000.0
            val reportedMin = reportedDuration / 60.0
            if (kotlin.math.abs(reportedMin - windowMin) > TST_TOLERANCE_MIN) {
                warnings += Warning(
                    TST_MISMATCH,
                    String.format(
                        java.util.Locale.US,
                        "reported %.0f min vs sleep window %.0f min (±%.0f min)",
                        reportedMin, windowMin, TST_TOLERANCE_MIN,
                    ),
                )
            }
        }

        if (rejectedSpans > 0) {
            warnings += Warning(
                SPAN_OVERLAP,
                "$rejectedSpans stage span(s) rejected (overlapping or negative)",
            )
        }
        if (dropped.isNotEmpty()) {
            warnings += Warning(IMPLAUSIBLE, "values dropped: " + dropped.joinToString("; "))
        }
        if (distrusted.isNotEmpty()) {
            warnings += Warning(SPO2_DISTRUST, "wrist SpO₂ distrust flag: " + distrusted.joinToString("; "))
        }

        return GarminValidity(warnings, kept, validSpans)
    }

    /** Canonical zero-overlap warning (used when no acoustic session overlaps at all). */
    fun noOverlap(): Warning =
        Warning(NO_OVERLAP, "no acoustic session overlaps the Garmin sleep window")

    private fun rangeText(type: MeasurementType): String = when (type) {
        MeasurementType.VENDOR_AVG_HEART_RATE_SLEEP, MeasurementType.VENDOR_RESTING_HEART_RATE ->
            "$HR_MIN_BPM–$HR_MAX_BPM bpm"
        MeasurementType.VENDOR_HRV_RMSSD -> "$RMSSD_MIN_MS–$RMSSD_MAX_MS ms"
        MeasurementType.VENDOR_SPO2_AVG_SLEEP, MeasurementType.VENDOR_SPO2_LOWEST_SLEEP ->
            "$SPO2_MIN_PERCENT–$SPO2_MAX_PERCENT%"
        else -> "plausible range"
    }

    private fun format(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
