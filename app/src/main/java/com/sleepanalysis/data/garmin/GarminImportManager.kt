package com.sleepanalysis.data.garmin

import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates a manual "Import from Garmin" for one date (docs/GARMIN_INTEGRATION.md §6):
 * fetch → store raw JSON verbatim → map to provenance-stamped Measurements.
 *
 * Failure semantics: never blocks core recording/reporting; every failure lands in [state]
 * as [GarminImportState.Error] and each endpoint fails independently. Metrics are recorded
 * only from endpoints that returned any 2xx with parseable content, and a re-import replaces
 * the day's GARMIN rows of the endpoints that succeeded this run instead of duplicating them
 * (a partial import never deletes the other endpoints' previously imported rows).
 *
 * A not-ready night — Garmin hasn't processed it server-side yet (dailySleep AND hrv both 404,
 * typical for a morning import, ready hours later) — is a distinct [GarminImportState.NotReady]:
 * nothing is stored, and the auto-backfill re-tries under the looser not-ready budget
 * (UserPreferences.GarminNotReadyBounds) instead of the general 2/day cap.
 */
@Singleton
class GarminImportManager @Inject constructor(
    private val client: GarminClient,
    private val keyStore: GarminKeyStore,
    private val db: AppDatabase,
    private val rawDao: RawPayloadDao,
    private val measurementDao: MeasurementDao,
    private val recorder: MeasurementRecorder,
    private val validityCalculator: com.sleepanalysis.data.repo.ValidityCalculator,
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
            /** Validator findings for this import (stale/implausible/mismatch — see GarminValidators). */
            val warnings: List<com.sleepanalysis.domain.validity.GarminValidators.Warning> = emptyList(),
        ) : GarminImportState

        /**
         * Garmin hasn't processed this night server-side yet — dailySleep AND hrv both HTTP 404,
         * i.e. the night's data simply doesn't exist there. Distinct from [Error] so the UI says
         * "retry later" instead of failing, and from [Success] so a too-early morning import is
         * never a bogus 0-metric success. Nothing was stored; [endpointStatuses] keeps the raw
         * verdicts for the settings diagnostics, and the auto-backfill draws from the looser
         * not-ready budget (UserPreferences.GarminNotReadyBounds).
         */
        data class NotReady(
            val date: String,
            val endpointStatuses: List<Pair<String, Int>>,
        ) : GarminImportState

        data class Error(val date: String?, val message: String) : GarminImportState
    }

    private val _state = MutableStateFlow<GarminImportState>(GarminImportState.Idle)
    val state: StateFlow<GarminImportState> = _state.asStateFlow()

    /** Serializes imports on this singleton — see [importForDate]. */
    private val importMutex = Mutex()

    /** True when a key is stored — the auto-backfill path only attempts then. */
    fun canImport(): Boolean = keyStore.hasKey()

    /**
     * Imports [date] into the local day window of [zone]. The zone is deliberately REQUIRED —
     * defaulting to the device zone is what made metrics import into a window the
     * session-timezone join never read (see ReportViewModel.importGarminForThisNight).
     *
     * Serialized: every caller gates on check-then-act flows (backfill budget, refresh
     * button) that cannot be atomic across their DataStore reads — a second import entering
     * while one is in flight must wait here, not overwrite [state] mid-flight and let the
     * loser's outcome kind mark the wrong budget counter.
     */
    suspend fun importForDate(date: LocalDate, zone: ZoneId) = importMutex.withLock {
        importForDateLocked(date, zone)
    }

    private suspend fun importForDateLocked(date: LocalDate, zone: ZoneId) {
        val dateStr = date.toString()
        Log.i(TAG, "import $dateStr ($zone): start")
        _state.value = GarminImportState.Importing(dateStr)
        try {
            if (!keyStore.hasKey()) {
                Log.w(TAG, "import $dateStr: failed — no API key stored")
                _state.value = GarminImportState.Error(dateStr, "No Garmin API key stored — add one in Settings first.")
                return
            }
            val result = client.fetchDay(dateStr)

            // Garmin hasn't processed this night yet (morning import before Garmin's nightly
            // processing): store nothing — running the empty replace would even re-link the
            // session and wipe its previously imported stage timeline — and report the distinct
            // NotReady so the backfill re-tries under its own budget once Garmin catches up.
            if (isNotReadyNight(result)) {
                Log.i(TAG, "import $dateStr: not ready (dailySleep and hrv both 404) — nothing stored")
                _state.value = GarminImportState.NotReady(
                    date = dateStr,
                    endpointStatuses = result.endpoints.map { it.endpoint to it.httpStatus },
                )
                return
            }
            val nowMs = System.currentTimeMillis()

            var endpointsStored = 0
            var imported = 0
            var linkedSessionId: Long? = null
            var importWarnings: List<com.sleepanalysis.domain.validity.GarminValidators.Warning> = emptyList()
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
                val okJsons = result.endpoints
                    .filter { it.httpStatus in 200..299 && it.json != null }
                    .associate { it.endpoint to it.json!! }
                // Body Battery: prefer the dedicated report endpoint; daily summary is the fallback.
                val parsed = parseNightData(okJsons, dayStart)
                val seen = mutableSetOf<MeasurementType>()
                // Re-import replace: the prior GARMIN rows of the incoming types go first, so
                // tapping Import twice never duplicates a vendor metric, and a partial
                // re-import never deletes the other types' rows (docs/GARMIN_INTEGRATION.md §6).
                val deduped = parsed.metrics.filter { seen.add(it.type) }

                // 3. Link to the best time-overlapping acoustic session (not just the calendar
                //    date): prefer the payload's own sleep window, fall back to the day window.
                //    The window also scopes which sessions even qualify.
                val linkWindow = parsed.sleepWindow
                    ?.let { it.startMs to it.endMs }
                    ?: (dayStart to dayEnd)
                val sessions = db.sleepSessionDao().all()
                val overlapping = sessions.map { it to overlapMs(it.startedAt, it.endedAt ?: nowMs, linkWindow.first, linkWindow.second) }
                    .filter { it.second > 0 }
                val linked = bestSessionOverlapping(sessions, linkWindow, nowMs)
                linkedSessionId = linked?.id

                // Validators run on the parsed data BEFORE the write (docs/NIGHT_REPORT.md
                // §Night validity): implausible values and corrupted stage spans are dropped
                // from the rows — the raw payloads above keep every original byte — and the
                // findings ride on Success for the Garmin card and the night's validity grade.
                // The overlap anchor is the linked session's PERSISTED capture start (delay-
                // aware), the same anchor the report-time summary and the recompute paths use,
                // so one night cannot flip garmin_no_overlap between paths.
                val validity = com.sleepanalysis.domain.validity.GarminValidators.validate(
                    metrics = deduped,
                    stageSpans = parsed.stageSpans,
                    sleepWindow = parsed.sleepWindow,
                    nightEndMs = parsed.sleepWindow?.endMs ?: linked?.endedAt ?: dayEnd,
                    fetchedAtMs = nowMs,
                    captureStartMs = linked?.let {
                        com.sleepanalysis.domain.report.CaptureStats.parse(it.captureStats)?.captureStartMs
                            ?: it.startedAt
                    },
                    captureEndMs = linked?.endedAt ?: nowMs,
                )
                importWarnings = if (linked == null) {
                    // The overlap verdict the link computation already made, as a warning.
                    validity.warnings + com.sleepanalysis.domain.validity.GarminValidators.noOverlap()
                } else {
                    validity.warnings
                }

                recorder.replaceGarminEstimates(
                    periodStart = dayStart,
                    periodEnd = dayEnd,
                    metrics = validity.keptMetrics.map { it.type to it.value },
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
                        db.sleepStageDao().deleteForSession(stale.id, SOURCE)
                    }
                    db.sleepStageDao().replaceForSession(
                        session.id,
                        SOURCE,
                        validity.validSpans.map {
                            com.sleepanalysis.data.db.entity.SleepStageEntity(
                                sessionId = session.id,
                                startMs = it.startMs,
                                endMs = it.endMs,
                                stage = it.stage,
                            )
                        },
                    )
                    // The import usually lands after finalize — recompute + REPLACE the linked
                    // night's validity grade with the fresh Garmin validation (docs/
                    // NIGHT_REPORT.md §Night validity). Inside the transaction on purpose: if
                    // this ever throws, the whole import rolls back and the Error state stays
                    // truthful — a Success must never publish rows whose validity row failed.
                    validityCalculator.computeAndRecord(session, validity)
                }
                imported = validity.keptMetrics.size
            }

            Log.i(
                TAG,
                "import $dateStr: ok metrics=$imported endpoints=$endpointsStored " +
                    "linked=${linkedSessionId ?: "none"} warnings=${importWarnings.size}",
            )
            _state.value = GarminImportState.Success(
                date = dateStr,
                metricsImported = imported,
                endpointsStored = endpointsStored,
                endpointStatuses = result.endpoints.map { it.endpoint to it.httpStatus },
                linkedSessionId = linkedSessionId,
                warnings = importWarnings,
            )
        } catch (e: GarminClient.GarminException) {
            Log.w(TAG, "import $dateStr: failed — ${e.message}")
            _state.value = GarminImportState.Error(dateStr, e.message ?: "Garmin import failed.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Scope cancelled mid-import — propagate so structured concurrency holds;
            // swallowing this as an import error would break the caller's cancellation.
            // The singleton must not stay stuck at Importing, though: [state] is shared by
            // the backfill gate, the report's refresh/import buttons and Settings — a stale
            // Importing blocks all of them until app restart.
            Log.d(TAG, "import $dateStr: cancelled")
            _state.value = GarminImportState.Error(dateStr, "Garmin import cancelled.")
            throw e
        } catch (e: Exception) {
            client.invalidateToken()
            Log.w(TAG, "import $dateStr: failed — ${e.message ?: e.javaClass.simpleName} (token invalidated)")
            _state.value = GarminImportState.Error(dateStr, "Garmin import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Metrics already imported for a date (used by the report to decide what to show). */
    suspend fun garminMetricsBetween(from: Long, to: Long) = measurementDao.garminBetween(from, to)

    companion object {
        private const val TAG = "GarminImport"
        const val SOURCE = "GARMIN"
        const val EP_DAILY_SLEEP = "/sleep-service/sleep/dailySleep"
        /** Stage-timeline endpoint (path-style date); 404/empty tolerated — totals remain the fallback. */
        const val EP_SLEEP_TIMELINE = "/sleep-service/sleep"
        const val EP_HRV = "/hrv-service/hrv"
        const val EP_BODY_BATTERY = "/wellness-service/wellness/bodyBattery/reports/daily"
        const val EP_DAILY_SUMMARY = "/usersummary-service/v2/dailysummaries"
        const val EP_STRESS = "/usersummary-service/stats/stress/daily"

        /** Vendor data parsed for one night — the import and validity-recompute paths share it. */
        data class ParsedNight(
            val metrics: List<GarminJsonParsers.ParsedMetric>,
            val stageSpans: List<GarminJsonParsers.SleepLevelSpan>,
            val sleepWindow: GarminJsonParsers.GarminSleepWindow?,
        )

        /**
         * Parses the day's endpoint payloads (2xx-only entries) into metrics, the stage
         * timeline and the sleep window — the exact parsing the import writes from, so the
         * validators and the validity recompute see the same data the rows carry.
         * Stage timeline: the sleep-service detail endpoint when it serves the sleepLevels
         * array, else the dailySleep payload's own (rarely present) sleepLevels — totals are
         * unaffected either way (docs/GARMIN_INTEGRATION.md §6).
         */
        fun parseNightData(jsonByEndpoint: Map<String, String>, dayStartMs: Long): ParsedNight {
            val sleepJson = jsonByEndpoint[EP_DAILY_SLEEP]
            val timelineJson = jsonByEndpoint[EP_SLEEP_TIMELINE]
            val stageSpans = buildList {
                timelineJson?.let { addAll(GarminJsonParsers.parseSleepLevels(it)) }
                if (isEmpty()) sleepJson?.let { addAll(GarminJsonParsers.parseSleepLevels(it)) }
            }
            val metrics = buildList {
                sleepJson?.let { addAll(GarminJsonParsers.parseDailySleep(it, dayStartMs)) }
                jsonByEndpoint[EP_HRV]?.let { addAll(GarminJsonParsers.parseHrv(it, dayStartMs)) }
                jsonByEndpoint[EP_BODY_BATTERY]?.let { addAll(GarminJsonParsers.parseBodyBattery(it, dayStartMs)) }
                jsonByEndpoint[EP_DAILY_SUMMARY]?.let { addAll(GarminJsonParsers.parseDailySummary(it, dayStartMs)) }
                jsonByEndpoint[EP_STRESS]?.let { addAll(GarminJsonParsers.parseStress(it, dayStartMs)) }
            }
            return ParsedNight(
                metrics = metrics,
                stageSpans = stageSpans,
                sleepWindow = sleepJson?.let { GarminJsonParsers.parseSleepWindow(it) },
            )
        }

        /**
         * True when the night's data simply doesn't exist server-side yet: BOTH [EP_DAILY_SLEEP]
         * and [EP_HRV] came back HTTP 404 (Garmin hasn't processed the night). Partial 404s are
         * NOT not-ready — dailySleep 404 with hrv 200 is usable data (Success; the endpoint
         * statuses carry the detail), and a non-404 verdict (network failure, other codes) is
         * never a not-ready one. Pure given the fetched result — unit-tested.
         */
        fun isNotReadyNight(result: GarminClient.GarminDayResult): Boolean {
            fun statusOf(endpoint: String): Int? =
                result.endpoints.firstOrNull { it.endpoint == endpoint }?.httpStatus
            return statusOf(EP_DAILY_SLEEP) == 404 && statusOf(EP_HRV) == 404
        }

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
