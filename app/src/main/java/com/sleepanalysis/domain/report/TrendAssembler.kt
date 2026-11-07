package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.db.entity.QuietGapEntity
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.domain.policy.MeasurementType
import com.sleepanalysis.domain.scoring.SnoreMath

/**
 * One night's row of the last-N-nights trend. Stored measurements supply the numbers the
 * night's own report showed (score, snore time, share); the per-night structure numbers that
 * are not persisted (longest bout, mean peak, clip coverage) are COMPUTED from that night's
 * events/clips with exactly the report's math (same bout gap, same union rule) — so a trend
 * row and its Morning Summary always agree.
 *
 * Version provenance is carried twice on purpose: [detectorVersion] (events) and
 * [scoreFormulaVersion] (SNORE_SCORE row). The two change on independent schedules, so the
 * flags are separate — [detectorChangedFromPrevious] warns about time/share comparisons,
 * [scoreFormulaChangedFromPrevious] about score comparisons.
 */
data class NightTrend(
    val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val timeZone: String,
    val status: String,
    /** Union snoring minutes (stored SNORE_TIME / 60). */
    val snoreMinutes: Long?,
    /** Stored SNORE_SHARE_OF_NIGHT. */
    val snoreSharePercent: Double?,
    /** Longest bout (gaps ≤ [BOUT_GAP_MS]) computed from the night's events. */
    val longestBoutMs: Long?,
    /** Mean of the night's per-event peak dBFS. */
    val meanPeakDbfs: Double?,
    val snoreScore: Int?,
    val scoreFormulaVersion: String?,
    val detectorVersion: String?,
    val detectorChangedFromPrevious: Boolean,
    val scoreFormulaChangedFromPrevious: Boolean,
    /** % of union snoring time that has a retained clip. */
    val clipCoveragePercent: Double?,
    /** Garmin's own sleep score (device-reported row for the wake date). */
    val garminScore: Int?,
    /** Garmin stage totals: deep / REM minutes (device-reported). */
    val garminDeepMinutes: Long?,
    val garminRemMinutes: Long?,
    /** Stored quiet-gap count for the night (schema v10); null for legacy nights. */
    val quietGapCount: Int?,
    /** Total minutes of QUIET-classified gaps — "quieter night" evidence, not just less snoring. */
    val quietMinutes: Long?,
    /** Total minutes of UNCAPTURED-classified gaps (process death) — kept apart from quiet. */
    val uncapturedMinutes: Long?,
)

object TrendAssembler {

    /**
     * A night eligible for trending: finalized, COMPLETED or RECOVERED, and — per the trend's
     * "actual capture" contract — not a zero-window session. Sessions without capture stats at
     * all predate observability and count as real nights.
     */
    fun isTrendNight(session: SleepSessionEntity): Boolean {
        if (session.endedAt == null) return false
        if (session.status != SessionStatus.COMPLETED && session.status != SessionStatus.RECOVERED) return false
        val stats = CaptureStats.parse(session.captureStats)
        return stats?.windowsProcessed != 0L
    }

    /**
     * Builds trend rows from eligible sessions (oldest first, at most [maxNights]).
     * [measurementsBySession]/[eventsBySession]/[clipsBySession] carry each night's rows.
     */
    fun assemble(
        sessions: List<SleepSessionEntity>,
        measurementsBySession: Map<Long, List<MeasurementEntity>>,
        eventsBySession: Map<Long, List<AcousticEventEntity>>,
        clipsBySession: Map<Long, List<AudioClipEntity>>,
        quietGapsBySession: Map<Long, List<QuietGapEntity>> = emptyMap(),
        garminBySession: Map<Long, List<MeasurementEntity>> = emptyMap(),
        maxNights: Int = 7,
    ): List<NightTrend> {
        val nights = sessions
            .filter(::isTrendNight)
            .sortedBy { it.startedAt }
            .takeLast(maxNights)

        var previousDetector: String? = null
        var previousFormula: String? = null
        return nights.map { session ->
            val measurements = measurementsBySession[session.id].orEmpty()
            fun value(type: MeasurementType): Double? =
                measurements.firstOrNull { it.measurementType == type.name }?.valueNum

            // Garmin rows for the night's WAKE-DATE window — works for linked and legacy
            // date-only rows alike (the query keys on sourceType+periodStart, not sessionId).
            val garminRows = garminBySession[session.id].orEmpty()
            fun garminValue(type: String): Double? =
                garminRows.firstOrNull { it.measurementType == type }?.valueNum

            val events = eventsBySession[session.id].orEmpty()
            val snoreEvents = events.filter { it.type == EventType.SNORE }
            val boutStats = boutStats(groupBouts(snoreEvents))
            val clipBacked = snoreEvents.filter { it.clipId != null }
            val totalSnoreSeconds = SnoreMath.unionDurationMs(
                snoreEvents.map { it.startMs to it.endMs },
            ) / 1000
            val coveredSeconds = SnoreMath.unionDurationMs(
                clipBacked.map { it.startMs to it.endMs },
            ) / 1000

            val detectorVersion = snoreEvents.firstOrNull()?.modelVersion
            val formulaVersion = measurements
                .firstOrNull { it.measurementType == MeasurementType.SNORE_SCORE.name }?.modelVersion
            // A change is only claimed when BOTH sides are known — unknown provenance must
            // not render as a change.
            val detectorChanged = previousDetector != null && detectorVersion != null &&
                detectorVersion != previousDetector
            val formulaChanged = previousFormula != null && formulaVersion != null &&
                formulaVersion != previousFormula
            previousDetector = detectorVersion
            previousFormula = formulaVersion

            NightTrend(
                sessionId = session.id,
                startedAt = session.startedAt,
                endedAt = session.endedAt!!,
                timeZone = session.timeZone,
                status = session.status,
                snoreMinutes = value(MeasurementType.SNORE_TIME)?.let { it.toLong() / 60 },
                snoreSharePercent = value(MeasurementType.SNORE_SHARE_OF_NIGHT),
                longestBoutMs = boutStats?.longestBoutMs,
                meanPeakDbfs = snoreEvents.map { it.peakDbfs }.takeIf { it.isNotEmpty() }?.average(),
                snoreScore = value(MeasurementType.SNORE_SCORE)?.toInt(),
                scoreFormulaVersion = formulaVersion,
                detectorVersion = detectorVersion,
                detectorChangedFromPrevious = detectorChanged,
                scoreFormulaChangedFromPrevious = formulaChanged,
                clipCoveragePercent = if (totalSnoreSeconds > 0) {
                    coveredSeconds * 100.0 / totalSnoreSeconds
                } else {
                    null
                },
                garminScore = garminValue("VENDOR_SLEEP_SCORE")?.toInt(),
                garminDeepMinutes = garminValue("VENDOR_DEEP_SLEEP")?.toLong()?.div(60),
                garminRemMinutes = garminValue("VENDOR_REM_SLEEP")?.toLong()?.div(60),
                quietGapCount = quietGapsBySession[session.id]?.takeIf { it.isNotEmpty() }?.size,
                quietMinutes = quietGapsBySession[session.id]
                    ?.takeIf { it.isNotEmpty() }
                    ?.filter { it.kind == QuietGapKind.QUIET }
                    ?.sumOf { it.durationMs }?.div(60_000),
                uncapturedMinutes = quietGapsBySession[session.id]
                    ?.takeIf { it.isNotEmpty() }
                    ?.filter { it.kind == QuietGapKind.UNCAPTURED }
                    ?.sumOf { it.durationMs }?.div(60_000),
            )
        }
    }

    /** True when any night in [trends] changed detector or score version vs its predecessor. */
    fun anyVersionChange(trends: List<NightTrend>): Boolean =
        trends.any { it.detectorChangedFromPrevious || it.scoreFormulaChangedFromPrevious }
}
