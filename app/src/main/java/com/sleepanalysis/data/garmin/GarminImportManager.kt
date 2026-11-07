package com.sleepanalysis.data.garmin

import androidx.room.withTransaction
import com.sleepanalysis.data.db.AppDatabase
import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.dao.RawPayloadDao
import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.SleepStageEntity
import com.sleepanalysis.data.recorder.MeasurementRecorder
import com.sleepanalysis.data.secure.GarminKeyStore
import com.sleepanalysis.domain.policy.MeasurementType
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Orchestrates a manual "Import from Garmin" for one date (docs/GARMIN_INTEGRATION.md §6):
 * fetch → store raw JSON verbatim → map to provenance-stamped Measurements.
 *
 * Failure semantics: never blocks core recording/reporting; every failure lands in [state]
 * as [GarminImportState.Error] and each endpoint fails independently. Metrics are recorded
 * only from endpoints that returned any 2xx with parseable content, and a re-import replaces
 * the day's GARMIN rows of the endpoints that succeeded this run instead of duplicating them
 * (a partial import never deletes the other endpoints' previously imported rows).
 */
@Singleton
class GarminImportManager @Inject constructor(
    private val client: GarminClient,
    private val keyStore: GarminKeyStore,
    private val db: AppDatabase,
    private val rawDao: RawPayloadDao,
    private val measurementDao: MeasurementDao,
    private val recorder: MeasurementRecorder,
) {

    sealed interface GarminImportState {
        data object Idle : GarminImportState
        data class Importing(val date: String) : GarminImportState
        data class Success(
            val date: String,
            val metricsImported: Int,
            val endpointsStored: Int,
            val endpointStatuses: List<Pair<String, Int>>,
            /** Acoustic session the rows were linked to (null = date-window fallback). */
            val linkedSessionId: Long? = null,
        ) : GarminImportState

        data class Error(val date: String?, val message: String) : GarminImportState
    }

    private val _state = MutableStateFlow<GarminImportState>(GarminImportState.Idle)
    val state: StateFlow<GarminImportState> = _state.asStateFlow()

    /** True when a key is stored — the auto-backfill path only attempts then. */
    fun canImport(): Boolean = keyStore.hasKey()

    /**
     * Imports [date] into the local day window of [zone]. The zone is deliberately REQUIRED —
     * defaulting to the device zone is what made metrics import into a window the
     * session-timezone join never read (see ReportViewModel.importGarminForThisNight).
     */
    suspend fun importForDate(date: LocalDate, zone: ZoneId) {
        val dateStr = date.toString()
        _state.value = GarminImportState.Importing(dateStr)
        try {
            if (!keyStore.hasKey()) {
                _state.value = GarminImportState.Error(dateStr, "No Garmin API key stored — add one in Settings first.")
                return
            }
            val result = client.fetchDay(dateStr)
            val nowMs = System.currentTimeMillis()

            var endpointsStored = 0
            var imported = 0
            var linkedSessionId: Long? = null
            db.withTransaction {
                // 1. Raw JSON verbatim, one row per (source, endpoint, date).
                for (endpoint in result.endpoints) {
                    val json = endpoint.json
                    if (endpoint.httpStatus in 200..299 && json != null) {
                        rawDao.upsert(
                            RawPayloadEntity(
                                source = SOURCE,
                                endpoint = endpoint.endpoint,
                                date = dateStr,
                                payload = json,
                            )
                        )
                        endpointsStored++
                    }
                }

                // 2. Normalize into provenance-stamped measurements over the LOCAL day window
                //    of the session's zone (ReportViewModel passes session.timeZone) — never
                //    the device default zone, or DST/travel offsets shift every timestamp.
                val (dayStart, dayEnd) = dayWindowMs(date, zone)
                val sleepJson = result.ok(EP_DAILY_SLEEP)?.json
                // Stage timeline: the sleep-service detail endpoint when it serves the
                // sleepLevels array, else the dailySleep payload's own (rarely present)
                // sleepLevels — totals are unaffected either way (docs/GARMIN_INTEGRATION.md §6).
                val timelineJson = result.ok(EP_SLEEP_TIMELINE)?.json
                val stageSpans = buildList {
                    timelineJson?.let { addAll(GarminJsonParsers.parseSleepLevels(it)) }
                    if (isEmpty()) sleepJson?.let { addAll(GarminJsonParsers.parseSleepLevels(it)) }
                }
                val metrics = buildList {
                    sleepJson?.let { addAll(GarminJsonParsers.parseDailySleep(it, dayStart)) }
                    result.ok(EP_HRV)?.let { addAll(GarminJsonParsers.parseHrv(it.json!!, dayStart)) }
                    result.ok(EP_BODY_BATTERY)?.let { addAll(GarminJsonParsers.parseBodyBattery(it.json!!, dayStart)) }
                    result.ok(EP_DAILY_SUMMARY)?.let { addAll(GarminJsonParsers.parseDailySummary(it.json!!, dayStart)) }
                    result.ok(EP_STRESS)?.let { addAll(GarminJsonParsers.parseStress(it.json!!, dayStart)) }
                }
                // Body Battery: prefer the dedicated report endpoint; daily summary is the fallback.
                val seen = mutableSetOf<MeasurementType>()
                // Re-import replace: the prior GARMIN rows of the incoming types go first, so
                // tapping Import twice never duplicates a vendor metric, and a partial
                // re-import never deletes the other types' rows (docs/GARMIN_INTEGRATION.md §6).
                val deduped = metrics.filter { seen.add(it.type) }

                // 3. Link to the best time-overlapping acoustic session (not just the calendar
                //    date): prefer the payload's own sleep window, fall back to the day window.
                //    The window also scopes which sessions even qualify.
                val linkWindow = sleepJson?.let { GarminJsonParsers.parseSleepWindow(it) }
                    ?.let { it.startMs to it.endMs }
                    ?: (dayStart to dayEnd)
                val sessions = db.sleepSessionDao().all()
                val overlapping = sessions.map { it to overlapMs(it.startedAt, it.endedAt ?: nowMs, linkWindow.first, linkWindow.second) }
                    .filter { it.second > 0 }
                val linked = bestSessionOverlapping(sessions, linkWindow, nowMs)
                linkedSessionId = linked?.id

                recorder.replaceGarminEstimates(
                    periodStart = dayStart,
                    periodEnd = dayEnd,
                    metrics = deduped.map { it.type to it.value },
                    notes = "Garmin Personal API, date $dateStr",
                    sessionId = linked?.id,
                )

                // 4. Stage timeline (schema v9): stored only when a session was linked — spans
                //    are session-scoped context, never free-floating rows. Re-import replaces
                //    atomically, and a RE-LINK never leaves stale stages behind: every other
                //    overlapping candidate loses its timeline (it can only have one via this
                //    import path), and an empty levels result is a delete, not a skip.
                linked?.let { session ->
                    // A re-link never leaves stale stages behind: every other overlapping
                    // candidate loses its timeline (it can only have one via this import
                    // path), and an empty timeline is a delete, not a skip.
                    overlapping.map { it.first }.filter { it.id != session.id }.forEach { stale ->
                        db.sleepStageDao().deleteForSession(stale.id)
                    }
                    db.sleepStageDao().replaceForSession(
                        session.id,
                        stageSpans.map {
                            com.sleepanalysis.data.db.entity.SleepStageEntity(
                                sessionId = session.id,
                                startMs = it.startMs,
                                endMs = it.endMs,
                                stage = it.stage,
                            )
                        },
                    )
                }
                imported = deduped.size
            }

            _state.value = GarminImportState.Success(
                date = dateStr,
                metricsImported = imported,
                endpointsStored = endpointsStored,
                endpointStatuses = result.endpoints.map { it.endpoint to it.httpStatus },
                linkedSessionId = linkedSessionId,
            )
        } catch (e: GarminClient.GarminException) {
            _state.value = GarminImportState.Error(dateStr, e.message ?: "Garmin import failed.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Scope cancelled mid-import — propagate so structured concurrency holds;
            // swallowing this as an import error would break the caller's cancellation.
            throw e
        } catch (e: Exception) {
            client.invalidateToken()
            _state.value = GarminImportState.Error(dateStr, "Garmin import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Metrics already imported for a date (used by the report to decide what to show). */
    suspend fun garminMetricsBetween(from: Long, to: Long) = measurementDao.garminBetween(from, to)

    companion object {
        const val SOURCE = "GARMIN"
        const val EP_DAILY_SLEEP = "/sleep-service/sleep/dailySleep"
        /** Stage-timeline endpoint (path-style date); 404/empty tolerated — totals remain the fallback. */
        const val EP_SLEEP_TIMELINE = "/sleep-service/sleep"
        const val EP_HRV = "/hrv-service/hrv"
        const val EP_BODY_BATTERY = "/wellness-service/wellness/bodyBattery/reports/daily"
        const val EP_DAILY_SUMMARY = "/usersummary-service/v2/dailysummaries"
        const val EP_STRESS = "/usersummary-service/stats/stress/daily"

        /**
         * Epoch window of [date] in [zone] — the bracket every imported metric's timestamp
         * must fall in. Pure so the DST behavior is testable: a 23 h / 25 h day around a
         * clock change yields a 23 h / 25 h window, not a fixed 24 h.
         */
        fun dayWindowMs(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
            date.atStartOfDay(zone).toInstant().toEpochMilli() to
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        /**
         * The session whose window ([startedAt, endedAt or still-open]) overlaps [window] the
         * most — the time-based vendor↔session link (docs/GARMIN_INTEGRATION.md §6). Open
         * sessions qualify (their window grows to [nowMs]); ties resolve to the later-started
         * session; zero overlaps yield null. Pure given [nowMs] — unit tested.
         */
        fun bestSessionOverlapping(
            sessions: List<com.sleepanalysis.data.db.entity.SleepSessionEntity>,
            window: Pair<Long, Long>,
            nowMs: Long = System.currentTimeMillis(),
        ): com.sleepanalysis.data.db.entity.SleepSessionEntity? {
            val (ws, we) = window
            return sessions
                .map { it to overlapMs(it.startedAt, it.endedAt ?: nowMs, ws, we) }
                .filter { it.second > 0 }
                .maxWithOrNull(compareBy({ it.second }, { it.first.startedAt }))
                ?.first
        }

        private fun overlapMs(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
            (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)
    }
}
