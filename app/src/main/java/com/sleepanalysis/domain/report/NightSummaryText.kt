package com.sleepanalysis.domain.report

import com.sleepanalysis.domain.scoring.SnoreScore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * The shareable plain-text night summary (report screen share action). Pure and
 * timezone-explicit: times render in the SESSION's zone (not the device's), so a summary
 * shared from a trip still reads correctly. Deliberately conservative: comparisons appear
 * only when the version gates allow them (same detector for time/share, same score formula
 * for the score), mirroring the on-screen TL;DR.
 */
object NightSummaryText {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

    fun build(report: MorningReport): String {
        val zone = ZoneId.of(report.timeZone)
        fun time(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
        fun date(ms: Long): String = DATE.format(Instant.ofEpochMilli(ms).atZone(zone))
        fun hms(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            val h = s / 3600
            val m = (s % 3600) / 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        val captureStart = report.captureWindowStartMs ?: report.startedAt
        val capturedSeconds = (report.endedAt - captureStart).coerceAtLeast(0) / 1000
        val lines = mutableListOf<String>()
        lines += "Night of ${date(report.startedAt)} (${report.timeZone.substringAfterLast('/')})"
        lines += "Snored ${hms(report.snoreSeconds)} — ${String.format(java.util.Locale.US, "%.0f", report.snoreSharePercent)}% " +
            "of the ${hms(capturedSeconds)} captured."

        report.boutStats?.let {
            lines += "Worst stretch: ${hms(it.longestBoutMs / 1000)} " +
                "(${time(it.longestBoutStartMs)}–${time(it.longestBoutEndMs)})."
        }
        report.longestSnore?.let {
            lines += "Longest burst: ${hms(it.durationMs / 1000)} from ${time(it.startMs)}."
        }
        if (report.snoreEventCount > 0) {
            lines += "Score ${report.snoreScore}/100 (share " +
                "${String.format(java.util.Locale.US, "%.0f", report.scoreBreakdown.sharePoints)} + intensity " +
                "${String.format(java.util.Locale.US, "%.0f", report.scoreBreakdown.intensityPoints)}; ${SnoreScore.FORMULA_VERSION})."
        }
        report.topClips.firstOrNull()?.let { clip ->
            val startMs = report.startedAt + clip.offsetMs
            lines += "Loudest clip: ${time(startMs)} at ${String.format(java.util.Locale.US, "%.1f", clip.peakDbfs)} dBFS."
        }
        if (report.quietGaps.isNotEmpty()) {
            val longest = report.quietGaps.maxBy { it.durationMs }
            lines += "Quiet stretches ≥10 min: ${report.quietGaps.size} (longest " +
                "${hms(longest.durationMs / 1000)})."
        }

        report.previousNight?.let { prev ->
            if (prev.sameDetectorVersion && prev.snoreSeconds != null) {
                val delta = report.snoreSeconds - prev.snoreSeconds
                val sign = if (delta > 0) "+" else if (delta < 0) "−" else ""
                // Score comparison gated on the formula version: stored scores from a
                // different formula are not comparable (docs/NIGHT_REPORT.md §Score).
                val scorePart = if (prev.sameScoreFormula && prev.snoreScore != null) {
                    "score ${report.snoreScore} vs ${prev.snoreScore}"
                } else {
                    "score ${report.snoreScore}"
                }
                lines += "vs previous night: ${hms(prev.snoreSeconds)} snoring " +
                    "($sign${hms(abs(delta))}), $scorePart."
            } else {
                lines += "Previous night not compared (different detector/score version)."
            }
        }
        return lines.joinToString("\n")
    }
}
