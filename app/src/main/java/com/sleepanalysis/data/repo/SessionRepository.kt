package com.sleepanalysis.data.repo

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import com.sleepanalysis.data.db.AppDatabase
import com.sleepanalysis.data.db.dao.AcousticEventDao
import com.sleepanalysis.data.db.dao.AudioClipDao
import com.sleepanalysis.data.db.dao.ClinicalBaselineDao
import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.dao.SleepSessionDao
import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.data.logs.SessionLogger
import com.sleepanalysis.data.recorder.MeasurementRecorder
import com.sleepanalysis.domain.policy.MeasurementType
import com.sleepanalysis.domain.report.MorningReport
import com.sleepanalysis.domain.report.ReportAssembler
import com.sleepanalysis.domain.scoring.SnoreScore
import com.sleepanalysis.monitoring.audio.AudioEngine
import com.sleepanalysis.monitoring.audio.captureStatsJson
import com.sleepanalysis.monitoring.detection.EventDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Session lifecycle + report assembly (docs/ARCHITECTURE.md §4). Measurement writes for a
 * finalized night go exclusively through [MeasurementRecorder] with full provenance.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val db: AppDatabase,
    private val sessionDao: SleepSessionDao,
    private val eventDao: AcousticEventDao,
    private val clipDao: AudioClipDao,
    private val baselineDao: ClinicalBaselineDao,
    private val measurementDao: MeasurementDao,
    private val recorder: MeasurementRecorder,
    private val clipStore: ClipStore,
    private val logger: SessionLogger,
    @ApplicationContext private val context: Context,
) {

    suspend fun startSession(): Long = withContext(Dispatchers.IO) {
        sessionDao.insert(
            SleepSessionEntity(
                startedAt = System.currentTimeMillis(),
                status = SessionStatus.RECORDING,
                timeZone = java.time.ZoneId.systemDefault().id,
                deviceModel = Build.MODEL ?: "unknown",
                osVersion = Build.VERSION.RELEASE ?: "unknown",
                appVersion = versionName(),
            )
        )
    }

    suspend fun session(sessionId: Long): SleepSessionEntity? = sessionDao.byId(sessionId)

    suspend fun findOpenSession(): SleepSessionEntity? = sessionDao.findOpen()

    suspend fun latestSession(): SleepSessionEntity? = sessionDao.latest()

    fun observeSessions(): Flow<List<SleepSessionEntity>> = sessionDao.observeAll()

    /**
     * Called by the service on the capture thread for every finalized event.
     *
     * [captureTimeBaseEpochMs] is the epoch of the engine's first captured sample: event
     * absolute times are base + draft offset. Using the session start here instead would
     * backdate every event of an engine resumed after process death (night 1 dogfood bug).
     * [offsetMs] stays session-relative — the report timeline and hourly buckets place events
     * by it, so it must span the whole session, not the current engine segment.
     */
    suspend fun insertEvent(
        sessionId: Long,
        sessionStartEpochMs: Long,
        captureTimeBaseEpochMs: Long,
        draft: EventDetector.EventDraft,
        clipRelativePath: String?,
        clipDurationMs: Long,
        clipSizeBytes: Long,
        modelVersion: String = MODEL_VERSION,
    ) = withContext(Dispatchers.IO) {
        // One transaction: process death mid-way must never leave a clip row without its
        // event (or vice versa) — the clip↔event link invariant (DATA_MODEL.md §3.5).
        db.withTransaction {
            val clipId = clipRelativePath?.let {
                clipDao.insert(
                    AudioClipEntity(
                        sessionId = sessionId,
                        filePath = it,
                        durationMs = clipDurationMs,
                        sizeBytes = clipSizeBytes,
                    )
                )
            }
            val times = resolveEventTimes(
                sessionStartEpochMs = sessionStartEpochMs,
                captureTimeBaseEpochMs = captureTimeBaseEpochMs,
                draftStartOffsetMs = draft.startMs,
                draftEndOffsetMs = draft.endMs,
            )
            val eventId = eventDao.insert(
                AcousticEventEntity(
                    sessionId = sessionId,
                    type = draft.type,
                    startMs = times.startMs,
                    endMs = times.endMs,
                    offsetMs = times.offsetMs,
                    durationMs = draft.endMs - draft.startMs,
                    peakDbfs = draft.peakDbfs,
                    meanDbfs = draft.meanDbfs,
                    intensityTier = draft.intensityTier,
                    confidence = draft.confidence,
                    modelVersion = modelVersion,
                    clipId = clipId,
                    featuresJson = draft.features.toJson(),
                )
            )
            // Keep both directions of the clip↔event link populated (DATA_MODEL.md §3.5).
            if (clipId != null) clipDao.setEventId(clipId, eventId)
        }
    }

    /**
     * Closes the session and writes the night's Established-tier measurements through the
     * recorder. [nightStats] is null for recovered/interrupted sessions where the engine
     * died with the process — heartbeat-persisted progress (if any) is then kept in the row
     * instead of being overwritten with an empty stats object.
     *
     * Transactional with an in-transaction `endedAt` re-check so a finalize racing a resume
     * (or a double finalize) can never duplicate measurements or finalize a row twice; returns
     * false when the row was already finalized (callers must not report success).
     */
    suspend fun finalizeSession(
        sessionId: Long,
        status: String,
        nightStats: AudioEngine.NightStats?,
        modelVersion: String = MODEL_VERSION,
    ): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val session = sessionDao.byId(sessionId) ?: return@withTransaction false
            if (session.endedAt != null) return@withTransaction false
            val events = eventDao.bySession(sessionId)
            // For recovered sessions the true death time is unknowable — bound it by the last
            // captured event (+1 min grace) so time-in-bed isn't inflated by the dead gap.
            val endedAt = if (status == SessionStatus.RECOVERED && events.isNotEmpty()) {
                minOf(System.currentTimeMillis(), events.maxOf { it.endMs } + 60_000)
            } else {
                System.currentTimeMillis()
            }

            // Clip counts/bytes come from the table, not the engine counters: the table spans all
            // engines of the session (resumed recordings) and clips written before a process death.
            val dbClipCount = clipDao.countForSession(sessionId)
            val dbClipBytes = clipDao.sumBytesForSession(sessionId)

            val timeInBedSeconds = ((endedAt - session.startedAt).coerceAtLeast(0)) / 1000
            val snoreEvents = events.filter { it.type == EventType.SNORE }
            val snoreSeconds = snoreEvents.sumOf { it.durationMs } / 1000
            val sharePercent = if (timeInBedSeconds > 0) snoreSeconds * 100.0 / timeInBedSeconds else 0.0
            val tierCounts = snoreEvents.groupingBy { it.intensityTier }.eachCount()
            val score = SnoreScore.compute(snoreSeconds, timeInBedSeconds.toLong(), tierCounts)

            recorder.recordPhoneEstimate(sessionId, MeasurementType.TIME_IN_BED, timeInBedSeconds.toDouble())
            recorder.recordPhoneEstimate(sessionId, MeasurementType.SNORE_EVENT_COUNT, snoreEvents.size.toDouble())
            recorder.recordPhoneEstimate(sessionId, MeasurementType.SNORE_TIME, snoreSeconds.toDouble())
            if (timeInBedSeconds > 0) {
                recorder.recordPhoneEstimate(sessionId, MeasurementType.SNORE_SHARE_OF_NIGHT, sharePercent)
            }
            recorder.recordPhoneEstimate(
                sessionId, MeasurementType.SNORE_SCORE, score.toDouble(),
                modelVersion = modelVersion,
            )
            snoreEvents.maxOfOrNull { it.peakDbfs }?.let {
                recorder.recordPhoneEstimate(sessionId, MeasurementType.SNORE_INTENSITY_PEAK, it)
            }
            snoreEvents.map { it.meanDbfs }.takeIf { it.isNotEmpty() }?.average()?.let {
                recorder.recordPhoneEstimate(sessionId, MeasurementType.SNORE_INTENSITY_MEAN, it)
            }
            recorder.recordPhoneEstimate(
                sessionId, MeasurementType.ACOUSTIC_EVENT_COUNT, events.size.toDouble()
            )
            nightStats?.ambientDbfsMedian?.let {
                recorder.recordPhoneEstimate(sessionId, MeasurementType.NOISE_MEDIAN, it)
            }
            nightStats?.ambientDbfsP95?.let {
                recorder.recordPhoneEstimate(sessionId, MeasurementType.NOISE_P95, it)
            }

            val statsJson = nightStats?.let { captureStatsJson(it, clipCountOverride = dbClipCount) }
                // Engine stats are gone (process death) — keep whatever progress the heartbeat saved
                // rather than overwriting it with an empty stats object.
                ?: session.captureStats.takeUnless { it.isNullOrBlank() }
                ?: """{"schema_version":1}"""

            sessionDao.finalize(
                id = sessionId,
                endedAt = endedAt,
                status = status,
                captureStats = statsJson,
                audioBytes = dbClipBytes,
            )
            true
        }
    }

    /**
     * Heartbeat-time persistence of cumulative capture stats. If the process dies and
     * START_STICKY resumes the same session, beginRecording carries these counters forward
     * instead of the finalizer silently shrinking the night's stats to the last segment.
     */
    suspend fun saveCaptureProgress(sessionId: Long, stats: AudioEngine.NightStats) =
        withContext(Dispatchers.IO) {
            sessionDao.updateCaptureStats(sessionId, captureStatsJson(stats))
        }

    /** Clips already saved for a session — the per-night clip cap counts the whole session. */
    suspend fun clipCountForSession(sessionId: Long): Int = withContext(Dispatchers.IO) {
        clipDao.countForSession(sessionId)
    }

    /** User-corrected class for an event (docs/DATA_MODEL.md §3.5) — dataset input, never alters scores. */
    suspend fun labelEvent(eventId: Long, label: String?) = withContext(Dispatchers.IO) {
        eventDao.setUserLabel(eventId, label, label?.let { System.currentTimeMillis() })
    }

    /** Morning notes: short user context about the night; blank text clears the note. */
    suspend fun setMorningNotes(sessionId: Long, notes: String?) = withContext(Dispatchers.IO) {
        sessionDao.setMorningNotes(sessionId, notes?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * Marks that START_STICKY resumed this open session after the capture process died
     * mid-night — the morning report surfaces it prominently. The RECOVERED status only
     * covers a death with no resume.
     */
    suspend fun markResumedAfterInterruption(sessionId: Long) = withContext(Dispatchers.IO) {
        sessionDao.markResumedAfterInterruption(sessionId)
    }

    /**
     * Finalizes EVERY open session as RECOVERED (nightStats unknown — the engine died with the
     * process). Used when the user explicitly confirms an interrupted night, and as a guard
     * before a new recording starts so two sessions can never be open at once. Counts only
     * sessions actually finalized (a finalize that lost a race reports no success).
     */
    suspend fun finalizeOpenAsRecovered(): Int = withContext(Dispatchers.IO) {
        var count = 0
        while (count < 5) { // safety bound; there should only ever be one
            val open = sessionDao.findOpen() ?: break
            if (!finalizeSession(open.id, SessionStatus.RECOVERED, nightStats = null)) break
            count++
        }
        count
    }

    /** How much of an interrupted night was actually saved, for the Home banner. */
    suspend fun savedEventCountForSession(sessionId: Long): Int = withContext(Dispatchers.IO) {
        eventDao.countForSession(sessionId)
    }

    suspend fun deleteNight(sessionId: Long) = withContext(Dispatchers.IO) {
        clipStore.deleteSessionClips(sessionId) // files first, then rows cascade
        sessionDao.delete(sessionId)
        logger.deleteSessionLog(sessionId) // the per-session log never outlives the night
    }

    /** Removes everything, including the clinical baseline (docs/PRIVACY_AND_REGULATORY.md §4). */
    suspend fun deleteAllData() = withContext(Dispatchers.IO) {
        db.purgeEverythingIncludingClinical()
        clipStore.deleteAll()
        logger.deleteAll() // session logs never outlive delete-all either
    }

    suspend fun buildReport(sessionId: Long): MorningReport? = withContext(Dispatchers.IO) {
        val session = sessionDao.byId(sessionId) ?: return@withContext null
        val events = eventDao.bySession(sessionId)
        val clips = clipDao.bySession(sessionId)
        val baseline = baselineDao.all()
        val garmin = garminMetricsForSessionDay(session)
        val factors = factorsForSessionDay(session)
        val capture = com.sleepanalysis.domain.report.CaptureStats.parse(session.captureStats)
        ReportAssembler.assemble(session, events, clips, baseline, garmin, factors, capture)
    }

    /** Self-logged factors for the calendar day the session started in (user context, not measurements). */
    private suspend fun factorsForSessionDay(session: SleepSessionEntity): List<com.sleepanalysis.domain.report.FactorEntry> {
        val day = com.sleepanalysis.domain.report.NightDates.factorDay(session.startedAt, session.timeZone)
        return db.userFactorDao().forDate(day.toString()).map {
            com.sleepanalysis.domain.report.FactorEntry(
                factorKey = it.factorKey,
                valueNum = it.valueNum,
                valueText = it.valueText,
            )
        }
    }

    /**
     * Garmin device-reported metrics for the night's WAKE calendar day. Garmin keys a night by
     * its morning (calendarDate = wake date), not by the evening it started — a 21:52→07:10
     * session is Garmin's "tomorrow" (docs/DATA_MODEL.md §5.3, [NightDates]).
     */
    private suspend fun garminMetricsForSessionDay(
        session: SleepSessionEntity,
    ): List<com.sleepanalysis.domain.report.GarminMetric> {
        val zone = java.time.ZoneId.of(session.timeZone)
        val day = com.sleepanalysis.domain.report.NightDates
            .garminWakeDate(session.startedAt, session.endedAt, session.timeZone)
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return measurementDao.garminBetween(from, to).map {
            com.sleepanalysis.domain.report.GarminMetric(
                measurementType = it.measurementType,
                value = it.valueNum ?: return@map null,
                unit = it.unit,
                timestampMs = it.timestamp,
            )
        }.filterNotNull()
    }

    suspend fun sessionsOlderThan(cutoff: Long): List<Long> =
        sessionDao.allStartTimes().filter { it < cutoff } // small table; filter in memory

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    companion object {
        /** Detector identity — stored on every event/estimate for traceability. */
        const val MODEL_VERSION = "energy-heuristic-v1-spectral"
    }
}
