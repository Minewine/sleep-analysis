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
import com.sleepanalysis.data.prefs.UserPreferences
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
import kotlinx.coroutines.flow.first
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
    private val userPreferences: UserPreferences,
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
     *
     * [appliedGain]/[filePeakDbfs] persist the clip write's gain + peak on the audio_clip row
     * (schema v11); the engine supplies both exactly when [clipRelativePath] is set.
     */
    suspend fun insertEvent(
        sessionId: Long,
        sessionStartEpochMs: Long,
        captureTimeBaseEpochMs: Long,
        draft: EventDetector.EventDraft,
        clipRelativePath: String?,
        clipDurationMs: Long,
        clipSizeBytes: Long,
        appliedGain: Float? = null,
        filePeakDbfs: Double? = null,
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
                        // Defensive fallback: the engine always passes appliedGain with a clip;
                        // 2.5 is the fixed mic gain those clips were written with.
                        appliedGain = appliedGain ?: 2.5f,
                        filePeakDbfs = filePeakDbfs,
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
    ): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val session = sessionDao.byId(sessionId) ?: return@withTransaction false
            if (session.endedAt != null) return@withTransaction false
            val events = eventDao.bySession(sessionId)
            val now = System.currentTimeMillis()
            val capture = com.sleepanalysis.domain.report.CaptureStats.parse(session.captureStats)
            // True end bound for recovered sessions: the last captured event, or the last
            // heartbeat-saved progress write — whichever is later — plus a small grace.
            // Bounding by it keeps a mid-night process death from inflating the night with
            // the dead gap instead of "now" (tap-to-stop time) (docs/DATA_MODEL.md §5.1).
            val lastAliveMs = maxOf(
                events.maxOfOrNull { it.endMs } ?: Long.MIN_VALUE,
                capture?.savedAtMs ?: Long.MIN_VALUE,
            ).takeIf { it != Long.MIN_VALUE }
            val endedAt = if (status == SessionStatus.RECOVERED && lastAliveMs != null) {
                minOf(now, lastAliveMs + RECOVERY_END_GRACE_MS)
            } else {
                now
            }

            // Clip counts/bytes come from the table, not the engine counters: the table spans all
            // engines of the session (resumed recordings) and clips written before a process death.
            val dbClipCount = clipDao.countForSession(sessionId)
            val dbClipBytes = clipDao.sumBytesForSession(sessionId)

            val timeInBedSeconds = ((endedAt - session.startedAt).coerceAtLeast(0)) / 1000

            // Denominator: mic-live captured time (processed + thermally-skipped 0.5 s hops,
            // cumulative across engine restarts) — NOT tap-to-end time in bed, which counts
            // the start delay, dead gaps and pre-capture wall time as "night". Falls back to
            // time-in-bed only when the session has no capture stats at all.
            val capturedSeconds = (
                nightStats?.let {
                    (it.windowsProcessed + it.windowsSkipped) *
                        com.sleepanalysis.domain.report.CaptureStats.HOP_MS / 1000
                }
                    ?: capture?.capturedAnalysisMs()?.div(1000)
                )?.takeIf { it > 0 } ?: timeInBedSeconds

            val snoreEvents = events.filter { it.type == EventType.SNORE }
            // Union, not sum: overlapping merged-window events must count their audio once
            // (docs/DATA_MODEL.md §5.1; com.sleepanalysis.domain.scoring.SnoreMath).
            val snoreSeconds = com.sleepanalysis.domain.scoring.SnoreMath
                .unionDurationMs(snoreEvents.map { it.startMs to it.endMs }) / 1000
            val sharePercent = if (capturedSeconds > 0) snoreSeconds * 100.0 / capturedSeconds else 0.0
            // Score v2 weights intensity by per-tier snoring TIME (the per-tier unions
            // partition snoreSeconds), not event counts — docs/NIGHT_REPORT.md §Score.
            val tierSeconds: Map<String, Long> = snoreEvents.groupBy { it.intensityTier }
                .mapValues { (_, tierEvents) ->
                    com.sleepanalysis.domain.scoring.SnoreMath
                        .unionDurationMs(tierEvents.map { it.startMs to it.endMs })
                }
            val score = SnoreScore.compute(snoreSeconds, capturedSeconds, tierSeconds)

            // A recovered session with no events has no verifiable capture: recording time in
            // bed, share or score would present tap-to-stop wall time as sleep data.
            val recoveredWithNoEvents = status == SessionStatus.RECOVERED && events.isEmpty()
            if (!recoveredWithNoEvents) {
                recorder.recordPhoneEstimate(sessionId, MeasurementType.TIME_IN_BED, timeInBedSeconds.toDouble())
            }
            recorder.recordPhoneEstimate(sessionId, MeasurementType.SNORE_EVENT_COUNT, snoreEvents.size.toDouble())
            recorder.recordPhoneEstimate(sessionId, MeasurementType.SNORE_TIME, snoreSeconds.toDouble())
            if (!recoveredWithNoEvents && capturedSeconds > 0) {
                recorder.recordPhoneEstimate(sessionId, MeasurementType.SNORE_SHARE_OF_NIGHT, sharePercent)
            }
            if (!recoveredWithNoEvents) {
                // The score row carries the SCORE FORMULA version, never the detector's model
                // version: the two change independently and trend comparability keys on this
                // field (docs/NIGHT_REPORT.md §Score). The notes hold the reproducible breakdown.
                recorder.recordPhoneEstimate(
                    sessionId, MeasurementType.SNORE_SCORE, score.score.toDouble(),
                    modelVersion = SnoreScore.FORMULA_VERSION,
                    notes = String.format(
                        java.util.Locale.US,
                        "%s: share %.1f/60 + intensity %.1f/40 (tier weight %.3f)",
                        SnoreScore.FORMULA_VERSION,
                        score.sharePoints, score.intensityPoints, score.meanTierWeight,
                    ),
                )
            }
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

            // Store the night's classified quiet gaps (schema v10): event-free spans ≥10 min
            // inside the capture window, classified by the level buckets — QUIET (windows
            // processed, ambient-bed level) vs UNCAPTURED (no evidence: process death).
            // Replace-per-session, so a re-finalize never duplicates them. Evidence source:
            // the merged night stats (all engines' buckets, carried across restarts), falling
            // back to the heartbeat-persisted capture stats.
            val levelBuckets = nightStats?.ambientBuckets ?: capture?.ambientBuckets.orEmpty()
            val gapWindowStart = capture?.captureStartMs ?: session.startedAt
            val classifiedGaps = com.sleepanalysis.domain.report.classifyQuietGaps(
                com.sleepanalysis.domain.report.quietGaps(events, gapWindowStart, endedAt),
                levelBuckets,
            )
            db.quietGapDao().replaceForSession(
                sessionId,
                classifiedGaps.map {
                    com.sleepanalysis.data.db.entity.QuietGapEntity(
                        sessionId = sessionId,
                        startMs = it.startMs,
                        endMs = it.endMs,
                        durationMs = it.durationMs,
                        kind = it.kind,
                        meanDbfs = it.meanDbfs,
                    )
                },
            )

            // captureStartMs is re-persisted here: the heartbeat write recorded it, and
            // dropping it would turn the start-delay hour into a phantom quiet stretch.
            val statsJson = nightStats?.let {
                captureStatsJson(
                    it,
                    clipCountOverride = dbClipCount,
                    captureStartMs = capture?.captureStartMs,
                )
            }
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
     * Each write is stamped with `saved_at_ms` so a RECOVERED finalize can bound the night
     * by the last moment capture was verifiably alive.
     */
    suspend fun saveCaptureProgress(
        sessionId: Long,
        stats: AudioEngine.NightStats,
        captureStartAtMs: Long? = null,
    ) = withContext(Dispatchers.IO) {
        sessionDao.updateCaptureStats(
            sessionId,
            captureStatsJson(
                stats,
                savedAtMs = System.currentTimeMillis(),
                captureStartMs = captureStartAtMs,
            ),
        )
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
        val garminSleep = garminSleepSummaryForSession(session, capture)
        val previousNight = previousNightSummaryFor(session)
        val garminStages = db.sleepStageDao().bySession(sessionId).map {
            com.sleepanalysis.domain.report.StageSpan(it.startMs, it.endMs, it.stage)
        }
        val noiseMinConfidence = userPreferences.noiseDisplayMinConfidenceNow()
        // Stored gaps (schema v10) win; legacy nights fall back to report-time computation
        // (classified when their capture stats carry level buckets).
        val storedGaps = db.quietGapDao().bySession(sessionId).map {
            com.sleepanalysis.domain.report.QuietGap(it.startMs, it.endMs, it.durationMs, it.kind, it.meanDbfs)
        }  // (already domain-typed rows)
        ReportAssembler.assemble(
            session, events, clips, baseline, garmin, factors, capture, garminSleep, previousNight,
            garminStages, noiseMinConfidence,
            quietGapsOverride = storedGaps.ifEmpty { null },
        )
    }

    /**
     * The previous completed night, from its STORED measurements — the comparison shows what
     * that night's own report said, never recomputed numbers (docs/NIGHT_REPORT.md §TLDR).
     * Comparison gates: detector version (events) gates time/share deltas, the score row's
     * formula version gates score deltas — a version change hides the comparison instead of
     * comparing across it.
     */
    private suspend fun previousNightSummaryFor(
        session: SleepSessionEntity,
    ): com.sleepanalysis.domain.report.PreviousNightSummary? {
        val previous = com.sleepanalysis.domain.report.PreviousNight
            .pick(sessionDao.observeAll().first(), session)
            ?: return null
        val measurements = measurementDao.bySession(previous.id)
        fun value(type: MeasurementType): Double? =
            measurements.firstOrNull { it.measurementType == type.name }?.valueNum
        val scoreRow = measurements.firstOrNull { it.measurementType == MeasurementType.SNORE_SCORE.name }
        val currentDetector = eventDao.firstSnoreModelVersion(session.id)
        val previousDetector = eventDao.firstSnoreModelVersion(previous.id)
        // The report recomputes the current night's score with the CURRENT formula, so the
        // stored previous score is comparable exactly when it was produced by that formula.
        val scoreFormula = scoreRow?.modelVersion
        return com.sleepanalysis.domain.report.PreviousNightSummary(
            sessionId = previous.id,
            startedAt = previous.startedAt,
            snoreSeconds = value(MeasurementType.SNORE_TIME)?.toLong(),
            sharePercent = value(MeasurementType.SNORE_SHARE_OF_NIGHT),
            snoreScore = value(MeasurementType.SNORE_SCORE)?.toInt(),
            scoreFormulaVersion = scoreFormula,
            detectorVersion = previousDetector,
            sameDetectorVersion = currentDetector != null && previousDetector != null &&
                currentDetector == previousDetector,
            sameScoreFormula = scoreFormula == SnoreScore.FORMULA_VERSION,
        )
    }

    /**
     * Garmin's own sleep window for this night, read from the verbatim dailySleep raw payload
     * (docs/NIGHT_REPORT.md §Garmin). Report-time linking only: the wake-date join
     * ([NightDates.garminWakeDate]) picks the payload, the overlap against the capture window
     * makes the vendor/acoustic comparison honest. Null when the payload was aged out by
     * retention (raw payloads follow session retention) or never imported — the day-window
     * metrics above still render.
     */
    private suspend fun garminSleepSummaryForSession(
        session: SleepSessionEntity,
        capture: com.sleepanalysis.domain.report.CaptureStats?,
    ): com.sleepanalysis.domain.report.GarminSleepSummary? {
        val endedAt = session.endedAt ?: return null
        val zone = java.time.ZoneId.of(session.timeZone)
        val day = com.sleepanalysis.domain.report.NightDates
            .garminWakeDate(session.startedAt, endedAt, session.timeZone)
        val payload = db.rawPayloadDao().forDate(com.sleepanalysis.data.garmin.GarminImportManager.SOURCE, day.toString())
            .firstOrNull { it.endpoint == com.sleepanalysis.data.garmin.GarminImportManager.EP_DAILY_SLEEP }
            ?: return null
        val window = com.sleepanalysis.data.garmin.GarminJsonParsers
            .parseSleepWindow(payload.payload) ?: return null
        val captureStart = capture?.captureStartMs ?: session.startedAt
        val overlapMs = com.sleepanalysis.domain.report.ReportAssembler.garminOverlapMs(
            captureStartMs = captureStart,
            captureEndMs = endedAt,
            sleepStartMs = window.startMs,
            sleepEndMs = window.endMs,
        )
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val sleepScore = com.sleepanalysis.data.garmin.GarminJsonParsers
            .parseDailySleep(payload.payload, dayStart)
            .firstOrNull { it.type == com.sleepanalysis.domain.policy.MeasurementType.VENDOR_SLEEP_SCORE }
            ?.value
        return com.sleepanalysis.domain.report.GarminSleepSummary(
            date = day.toString(),
            sleepStartMs = window.startMs,
            sleepEndMs = window.endMs,
            sleepScore = sleepScore,
            overlapWithCaptureMs = overlapMs,
            captureWindowStartMs = captureStart,
            captureWindowEndMs = endedAt,
        )
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

    /**
     * The last [maxNights] trend-eligible nights as trend rows (docs/NIGHT_REPORT.md §Trends).
     * Stored measurements supply the report-reproducible numbers; the per-night structure
     * numbers (longest bout, mean peak, clip coverage) come from the night's events/clips
     * with the report's own math. Candidates are narrowed here so the per-night reads stay
     * bounded (≤ 7 nights × 3 small queries).
     */
    suspend fun buildTrends(maxNights: Int = 7): List<com.sleepanalysis.domain.report.NightTrend> =
        withContext(Dispatchers.IO) {
            val candidates = sessionDao.observeAll().first()
                .filter(com.sleepanalysis.domain.report.TrendAssembler::isTrendNight)
                .sortedBy { it.startedAt }
                .takeLast(maxNights)
            val measurements = candidates.associate { it.id to measurementDao.bySession(it.id) }
            val events = candidates.associate { it.id to eventDao.bySession(it.id) }
            val clips = candidates.associate { it.id to clipDao.bySession(it.id) }
            // Omit empty results: 0 stored rows means "legacy or genuinely gap-free" —
            // either way the trend renders no quiet segment, never a misleading "quiet 0s".
            val quietGaps = candidates.mapNotNull { s ->
                db.quietGapDao().bySession(s.id).takeIf { it.isNotEmpty() }?.let { s.id to it }
            }.toMap()
            // Garmin rows joined by WAKE-DATE window — works for linked and legacy date-only
            // rows alike (the query keys on sourceType+periodStart, not sessionId).
            val garmin = candidates.associate { s ->
                val zone = java.time.ZoneId.of(s.timeZone)
                val day = com.sleepanalysis.domain.report.NightDates
                    .garminWakeDate(s.startedAt, s.endedAt, s.timeZone)
                val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                s.id to measurementDao.garminBetween(from, to)
            }
            com.sleepanalysis.domain.report.TrendAssembler.assemble(
                candidates, measurements, events, clips, quietGaps, garmin, maxNights,
            )
        }

    private fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    companion object {
        /** Detector identity — stored on every event/estimate for traceability. */
        const val MODEL_VERSION = "energy-heuristic-v2-burst"

        /** Grace added after the last verifiably-alive moment when bounding a recovered night. */
        const val RECOVERY_END_GRACE_MS = 60_000L
    }
}
