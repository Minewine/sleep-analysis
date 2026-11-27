package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.AppDatabase
import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.files.ClipIntegrity
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.data.garmin.GarminImportManager
import com.sleepanalysis.data.recorder.MeasurementRecorder
import com.sleepanalysis.domain.report.CaptureStats
import com.sleepanalysis.domain.report.NightDates
import com.sleepanalysis.domain.report.QuietGapKind
import com.sleepanalysis.domain.scoring.SnoreMath
import com.sleepanalysis.domain.validity.GarminValidators
import com.sleepanalysis.domain.validity.NightValidity
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composition glue for the night's validity grade (docs/NIGHT_REPORT.md §Night validity):
 * gathers the stored evidence a finalized night has (capture stats, clip files, quiet gaps,
 * Garmin raw payloads, prior nights' ambient levels) into [NightValidity.Input], computes the
 * grade and records the NIGHT_VALIDITY row through [MeasurementRecorder.replaceNightValidity].
 * All grading logic itself lives in the pure domain objects; the selection rules that shape
 * the inputs live in the pure functions below ([ambientNoiseSummaryFor],
 * [garminValidityFromPayloads]) so they are directly unit-testable.
 *
 * Write paths: finalize (SessionRepository), a label recompute (labelEvent) and a Garmin
 * re-import of a linked session (GarminImportManager) — each replaces the row instead of
 * duplicating it.
 */
@Singleton
class ValidityCalculator @Inject constructor(
    private val db: AppDatabase,
    private val clipStore: ClipStore,
    private val recorder: MeasurementRecorder,
) {

    /**
     * Scans the session's clip files (the [ClipIntegrity] read-only decode) and aggregates
     * them with the shared [NightValidity.clipQualitySummary]. Bounded by the night's clip
     * budget; called from IO contexts only.
     */
    suspend fun clipQualityForSession(sessionId: Long): NightValidity.ClipQualitySummary =
        NightValidity.clipQualitySummary(
            db.audioClipDao().bySession(sessionId).map {
                ClipIntegrity.check(clipStore.absolutePath(it.filePath), it.filePath)
            },
        )

    /**
     * Tonight's ambient P95 against the rolling prior-night distribution — delegates the
     * selection + z-math to the pure [ambientNoiseSummaryFor].
     */
    suspend fun ambientNoiseSummary(session: SleepSessionEntity): NightValidity.AmbientNoiseSummary? =
        ambientNoiseSummaryFor(session, db.sleepSessionDao().all())

    /**
     * Re-validates the night's stored Garmin raw payloads (label-recompute path: the import
     * is long gone by labeling time, the payloads — and their original fetchedAt — are not).
     * Delegates to the pure [garminValidityFromPayloads]; null when nothing was ever imported
     * for the wake date.
     */
    suspend fun garminValidityFor(session: SleepSessionEntity): GarminValidators.GarminValidity? =
        garminValidityFromPayloads(
            session,
            db.rawPayloadDao().forDate(
                GarminImportManager.SOURCE,
                NightDates.garminWakeDate(session.startedAt, session.endedAt, session.timeZone).toString(),
            ),
        )

    /**
     * Builds the validity input from the session's STORED state (recompute paths: the engine's
     * live NightStats are gone — the same stored-stats discipline as the label-corrected snore
     * recompute). [garmin] passes through when the caller has a fresh validation result.
     */
    suspend fun inputFor(
        session: SleepSessionEntity,
        garmin: GarminValidators.GarminValidity? = null,
    ): NightValidity.Input {
        val endedAt = session.endedAt ?: return NightValidity.Input(capture = null)
        val stats = CaptureStats.parse(session.captureStats)
        val timeInBedSeconds = ((endedAt - session.startedAt).coerceAtLeast(0)) / 1000
        val capturedAnalysisSeconds = stats?.capturedAnalysisMs()?.div(1000)?.takeIf { it > 0 }
        val capturedSeconds = capturedAnalysisSeconds ?: timeInBedSeconds
        val snoreEvents = SnoreMath.eventsCountingAsSnore(db.acousticEventDao().bySession(session.id))
        val snoreSeconds = SnoreMath.unionDurationMs(snoreEvents.map { it.startMs to it.endMs }) / 1000
        val uncapturedMinutes = db.quietGapDao().bySession(session.id)
            .filter { it.kind == QuietGapKind.UNCAPTURED }
            .sumOf { it.durationMs } / 60_000.0
        return NightValidity.Input(
            capture = NightValidity.captureSummary(stats, endedAt, timeInBedSeconds),
            clips = clipQualityForSession(session.id),
            uncapturedGapMinutes = uncapturedMinutes,
            sessionStatus = session.status,
            resumedAfterInterruption = session.resumedAfterInterruption,
            // The share-of-capture input requires the captured-analysis denominator: on the
            // time-in-bed fallback it is null (the share would not be comparable).
            snoreShareOfCapture = capturedAnalysisSeconds?.takeIf { capturedSeconds > 0 }
                ?.let { snoreSeconds.toDouble() / capturedSeconds },
            ambient = ambientNoiseSummary(session),
            garmin = garmin,
        )
    }

    /**
     * Computes the grade from stored state and REPLACES the session's NIGHT_VALIDITY row.
     * [garmin] non-null when a fresh validation exists (Garmin re-import path); the raw
     * payload revalidation covers the label path. No-op for a still-recording session.
     */
    suspend fun computeAndRecord(
        session: SleepSessionEntity,
        garmin: GarminValidators.GarminValidity? = null,
    ) {
        if (session.endedAt == null) return
        val result = NightValidity.compute(inputFor(session, garmin))
        recorder.replaceNightValidity(
            session.id,
            MeasurementRecorder.ValidityEstimate(
                grade = result.grade,
                notes = NightValidity.notes(result),
            ),
        )
    }

    companion object {
        /** Rolling ambient-prior window size (app convention: two dogfood weeks). */
        const val AMBIENT_PRIOR_NIGHTS_MAX = 14
    }
}

/**
 * The rolling prior-night ambient distribution for [self] (docs/NIGHT_REPORT.md §Night
 * validity). Selection rules, all pinned by ValidityCalculatorTest:
 *  - the graded night is EXCLUDED by id (recompute paths run when the row already exists —
 *    including tonight itself, or tonight would count as its own prior and let the ≥5 gate
 *    pass with only 4 true priors);
 *  - strictly earlier STARTS only ([self.startedAt]) — later nights must never leak into a
 *    recomputed older night's "prior" distribution;
 *  - finalized nights only (endedAt set);
 *  - the ambient P95 is PARSED BEFORE the [ValidityCalculator.AMBIENT_PRIOR_NIGHTS_MAX]
 *    window cut, so a run of stats-less legacy nights cannot shrink the usable priors below
 *    the ≥5 gate when older valid nights exist.
 * Pure — the z-math itself lives in [NightValidity.ambientNoise] (≥5 priors, sd > 0, else
 * omit; relative only, never an absolute-dB comparison).
 */
internal fun ambientNoiseSummaryFor(
    self: SleepSessionEntity,
    sessions: List<SleepSessionEntity>,
): NightValidity.AmbientNoiseSummary? {
    val tonightP95 = CaptureStats.parse(self.captureStats)?.ambientDbfsP95 ?: return null
    // Parse BEFORE the window cut (m2): stats-less legacy nights must never shrink the
    // usable priors below the ≥5 gate while older valid nights exist.
    val priors = sessions.asSequence()
        .filter { it.id != self.id && it.startedAt < self.startedAt }
        .filter { it.endedAt != null }
        .mapNotNull { session ->
            CaptureStats.parse(session.captureStats)?.ambientDbfsP95
                ?.let { session.startedAt to it }
        }
        .sortedByDescending { it.first }
        .take(ValidityCalculator.AMBIENT_PRIOR_NIGHTS_MAX)
        .map { it.second }
        .toList()
    return NightValidity.ambientNoise(tonightP95, priors)
}

/**
 * Revalidates [session]'s night from its STORED Garmin raw payloads (the wake-date rows,
 * docs/DATA_MODEL.md §5.3) — the same parsing and validators the import path runs
 * ([GarminImportManager.parseNightData] + [GarminValidators.validate]), so a label-time
 * recompute cannot disagree with what an import would find. Pure; null without payloads or
 * for a still-recording session.
 */
internal fun garminValidityFromPayloads(
    session: SleepSessionEntity,
    payloads: List<RawPayloadEntity>,
): GarminValidators.GarminValidity? {
    val endedAt = session.endedAt ?: return null
    if (payloads.isEmpty()) return null
    val zone = ZoneId.of(session.timeZone)
    val day = NightDates.garminWakeDate(session.startedAt, endedAt, session.timeZone)
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val parsed = GarminImportManager.parseNightData(
        jsonByEndpoint = payloads.associate { it.endpoint to it.payload },
        dayStartMs = dayStart,
    )
    val capture = CaptureStats.parse(session.captureStats)
    return GarminValidators.validate(
        metrics = parsed.metrics,
        stageSpans = parsed.stageSpans,
        sleepWindow = parsed.sleepWindow,
        nightEndMs = parsed.sleepWindow?.endMs ?: endedAt,
        fetchedAtMs = payloads.maxOf { it.fetchedAt },
        captureStartMs = capture?.captureStartMs ?: session.startedAt,
        captureEndMs = endedAt,
    )
}
