package com.sleepanalysis.data.healthconnect

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.room.withTransaction
import com.sleepanalysis.data.db.AppDatabase
import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.SleepStageEntity
import com.sleepanalysis.data.garmin.GarminImportManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Orchestrates a manual "Import from Health Connect" for one date: read the wearable stage
 * timeline over on-device IPC (docs/PRIVACY_AND_REGULATORY.md §3 — no network, no audio) and
 * link it to the time-overlapping acoustic session, mirroring GarminImportManager's
 * window/link/replace discipline.
 *
 * Failure semantics: never blocks core recording/reporting; every import ends in [state] as a
 * terminal [HealthConnectImportState]. Blocked imports are distinct [HealthConnectImportState.
 * NotReady] cases per [NotReadyReason] — provider unavailable, read permission not granted, or
 * no acoustic session overlapping the day window — so the UI renders each as its own actionable
 * state instead of a failure.
 *
 * Rows are stored with source WEARABLE ([STAGE_SOURCE]) via the per-source replace (schema
 * v14), so a session's GARMIN timeline is never touched. One raw_payload summary row
 * (source HEALTHCONNECT) records what was read, stored and skipped — skipped stage spans are
 * accounted per type, never silently lost.
 */
@Singleton
class HealthConnectImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {

    sealed interface HealthConnectImportState {
        data object Idle : HealthConnectImportState
        data class Importing(val date: LocalDate) : HealthConnectImportState
        data class Success(
            val date: LocalDate,
            val stagesStored: Int,
            /** Minutes of stage spans the vendor carries but we do not store (see [mapStageType]). */
            val skippedStageMinutes: Long,
            val linkedSessionId: Long?,
        ) : HealthConnectImportState

        data class NotReady(val date: LocalDate, val reason: NotReadyReason) : HealthConnectImportState
        data class Error(val date: LocalDate?, val message: String) : HealthConnectImportState
    }

    /** Why an import was blocked before anything could be stored (each maps to its own UI state). */
    enum class NotReadyReason {
        /** No Health Connect provider on this device (or an unusably old one). */
        HC_UNAVAILABLE,
        /** Provider present, but the sleep read permission is not granted yet. */
        PERMISSION_MISSING,
        /** No acoustic session overlaps the day window — nothing to link the timeline to. */
        NO_ACOUSTIC_SESSION,
    }

    /** Pure outcome of mapping a day's HC timelines: the rows to store + what was skipped. */
    data class MappedStages(
        val rows: List<SleepStageEntity>,
        val skippedMinutes: Long,
        val skippedMinutesByType: Map<String, Long>,
    )

    /** Pure outcome of mapping ONE HC stage span: the row to store, or the skipped accounting. */
    data class SpanMapping(
        val row: SleepStageEntity?,
        val skippedMinutes: Long,
        /** Summary key when the span was skipped (null = stored as [row]). */
        val skippedTypeKey: String?,
    )

    private val _state = MutableStateFlow<HealthConnectImportState>(HealthConnectImportState.Idle)
    val state: StateFlow<HealthConnectImportState> = _state.asStateFlow()

    /** Serializes imports on this singleton — see [importForDate]. */
    private val importMutex = Mutex()

    /**
     * True when a usable Health Connect provider is on the device — covers the Android 14+
     * built-in provider and the installed-APK case alike.
     */
    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** True when the session carries no WEARABLE stage rows yet — the report backfill's gate. */
    suspend fun needsImport(sessionId: Long): Boolean =
        db.sleepStageDao().countForSession(sessionId, STAGE_SOURCE) == 0

    /**
     * Imports [date] into the local day window of [zone] — the zone is deliberately REQUIRED,
     * the same bracket the Garmin import reads (see GarminImportManager.importForDate).
     *
     * Serialized like the Garmin import: the settings button and report paths gate on
     * check-then-act reads of [state], so a second import must wait here, not overwrite
     * [state] mid-flight. Returns the terminal state (also published on [state]) so the
     * caller can render it without re-reading the flow.
     */
    suspend fun importForDate(date: LocalDate, zone: ZoneId): HealthConnectImportState =
        importMutex.withLock {
            importForDateLocked(date, zone)
        }

    private suspend fun importForDateLocked(date: LocalDate, zone: ZoneId): HealthConnectImportState {
        _state.value = HealthConnectImportState.Importing(date)
        try {
            if (!isAvailable()) {
                return notReady(date, NotReadyReason.HC_UNAVAILABLE)
            }
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) {
                return notReady(date, NotReadyReason.PERMISSION_MISSING)
            }
            // The read window is the date's day window widened INTO THE PREVIOUS EVENING:
            // a night ending on [date] usually STARTS on [date] - 1, and HC's TimeRangeFilter
            // matches records by their START time — the plain day window returned 0 records
            // for a session that fell asleep before local midnight (found on-device
            // 2026-11-18: wake-date import missed the night entirely; the previous date's
            // window held the record). Linking is unaffected: each record links by its own
            // span, so a widened read can only catch more of the right nights, never
            // mis-assign (bestSessionOverlapping picks the best-overlapping session).
            val dayWindow = GarminImportManager.dayWindowMs(date, zone)
            val window =
                (dayWindow.first - EVENING_READ_LEAD_MS) to dayWindow.second
            val response = client.readRecords(
                ReadRecordsRequest(
                    SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(window.first),
                        Instant.ofEpochMilli(window.second),
                    ),
                    // Only Garmin Connect: Health Connect aggregates every sleep app on the
                    // device, and an unfiltered read would merge a second app's sessions
                    // into the WEARABLE timeline as overlapping spans (the docs describe
                    // this import as "written by Garmin Connect").
                    dataOriginFilter = garminConnectDataOrigins(),
                )
            )
            Log.d(
                TAG,
                "import $date: window=${Instant.ofEpochMilli(window.first)}..${Instant.ofEpochMilli(window.second)} " +
                    "records=${response.records.size}",
            )
            // Per-record linking: each Health Connect record carries its own sleep window,
            // and THAT span — not the import date's day window — links to the best-
            // overlapping acoustic session. The day window is only the read filter:
            // linking against it mis-assigns retrospective imports, where the window's
            // largest overlap is the PREVIOUS night's morning tail, not the record's own
            // night (found on-device: importing 2026-11-14 linked the Nov 13→14 session
            // while the record described Nov 14→15). Once the record span is known the
            // rule is the same as the Garmin import: stage spans are session-scoped
            // context, never free-floating rows.
            val sessions = db.sleepSessionDao().all()
            val linkedBySession = response.records.mapNotNull { record ->
                GarminImportManager.bestSessionOverlapping(
                    sessions,
                    record.startTime.toEpochMilli() to record.endTime.toEpochMilli(),
                )?.let { it.id to record }
            }
            if (linkedBySession.isEmpty()) {
                return notReady(date, NotReadyReason.NO_ACOUSTIC_SESSION)
            }
            val rowsBySession = linkedBySession.groupBy({ it.first }, { it.second })

            var stagesStored = 0
            var skippedMinutes = 0L
            val skippedByType = mutableMapOf<String, Long>()
            var primarySessionId: Long? = null
            var primaryRowCount = -1
            val recordsWithoutSession = response.records.size - linkedBySession.size
            db.withTransaction {
                for ((sessionId, records) in rowsBySession) {
                    // Rows + skip accounting from the record's own stages (the pure mapper
                    // is the auditable single source of truth for what made it into rows).
                    val mapped = buildStageRows(sessionId, records)
                    stagesStored += mapped.rows.size
                    skippedMinutes += mapped.skippedMinutes
                    for ((typeKey, minutes) in mapped.skippedMinutesByType) {
                        skippedByType.merge(typeKey, minutes, Long::plus)
                    }
                    if (mapped.rows.size > primaryRowCount) {
                        primaryRowCount = mapped.rows.size
                        primarySessionId = sessionId
                    }

                    // Replace-per-source: stale WEARABLE rows of a previous import go first,
                    // the session's GARMIN timeline is untouched, and an empty read is a
                    // delete, not a skip.
                    db.sleepStageDao().replaceForSession(sessionId, STAGE_SOURCE, mapped.rows)
                }

                // One summary row keeps the run auditable — the same raw-payload discipline
                // the Garmin import follows (upsert per source/endpoint/date, docs/DATA_MODEL.md §3.9).
                db.rawPayloadDao().upsert(
                    RawPayloadEntity(
                        source = RAW_PAYLOAD_SOURCE,
                        endpoint = RAW_PAYLOAD_ENDPOINT,
                        date = date.toString(),
                        payload = summaryJson(
                            recordsRead = response.records.size,
                            stagesStored = stagesStored,
                            skippedMinutes = skippedMinutes,
                            skippedMinutesByType = skippedByType,
                            recordsWithoutSession = recordsWithoutSession,
                            linkedSessionId = primarySessionId,
                            window = window,
                        ),
                    )
                )
            }

            return HealthConnectImportState.Success(
                date = date,
                stagesStored = stagesStored,
                skippedStageMinutes = skippedMinutes,
                linkedSessionId = primarySessionId,
            ).also { _state.value = it }
        } catch (e: CancellationException) {
            // Scope cancelled mid-import — propagate so structured concurrency holds; the
            // singleton must not stay stuck at Importing, though: [state] is shared by the
            // settings card and the report, and a stale Importing blocks all of them until
            // app restart (same discipline as GarminImportManager).
            _state.value = HealthConnectImportState.Error(date, "Health Connect import cancelled.")
            throw e
        } catch (e: Exception) {
            return HealthConnectImportState.Error(
                date,
                "Health Connect import failed: ${e.message ?: e.javaClass.simpleName}",
            ).also { _state.value = it }
        }
    }

    /** Terminal exit: publish + return the NotReady state in one step. */
    private fun notReady(date: LocalDate, reason: NotReadyReason): HealthConnectImportState =
        HealthConnectImportState.NotReady(date, reason).also {
            Log.d(TAG, "import $date not ready: $reason")
            _state.value = it
        }

    companion object {
        private const val TAG = "HealthConnectImport"
        /** How far into the previous evening the HC read window reaches (see importForDateLocked). */
        private const val EVENING_READ_LEAD_MS = 6L * 60 * 60 * 1000
        /** sleep_stage.source value of the HC-imported timeline (schema v14 vocabulary). */
        const val STAGE_SOURCE = "WEARABLE"
        /**
         * The only data origin the sleep-session read accepts — the wearable timeline the
         * docs describe is "written by Garmin Connect". Pinned by
         * HealthConnectSessionLinkTest (connect-client 1.1.0 cannot build a Metadata with a
         * custom DataOrigin on the JVM, so the request-level wiring stays the thin,
         * instrumented-only call site).
         */
        const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"

        /** The [ReadRecordsRequest.dataOriginFilter] for the sleep-session read. */
        fun garminConnectDataOrigins(): Set<DataOrigin> = setOf(DataOrigin(GARMIN_CONNECT_PACKAGE))
        /** raw_payload.source value of the import summary row (mirrors Garmin's SOURCE). */
        const val RAW_PAYLOAD_SOURCE = "HEALTHCONNECT"
        /** raw_payload.endpoint value of the import summary row. */
        const val RAW_PAYLOAD_ENDPOINT = "health-connect/sleep"

        /**
         * Maps an HC stage type to the stored label: AWAKE_IN_BED folds into AWAKE, the four
         * canonical spans keep their names; SLEEPING (coarse, span-less), OUT_OF_BED and
         * UNKNOWN carry no usable span label and return null — skipped here, but their
         * minutes are accounted in [MappedStages.skippedMinutesByType], never silently lost.
         * Named androidx constants ONLY — never the unverified Garmin numeric map.
         */
        fun mapStageType(hcStageType: Int): String? = when (hcStageType) {
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "AWAKE"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
            SleepSessionRecord.STAGE_TYPE_REM -> "REM"
            else -> null
        }

        /** Stable summary key for a skipped HC type (docs/DATA_MODEL.md §3.10 vocabulary). */
        private fun skippedTypeKey(hcStageType: Int): String = when (hcStageType) {
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
            SleepSessionRecord.STAGE_TYPE_UNKNOWN -> "UNKNOWN"
            else -> "TYPE_$hcStageType"
        }

        /**
         * The given records' stages mapped into rows (source [STAGE_SOURCE]), ordered by
         * start time; skipped minutes aggregated per type. Pure — unit tested. Records
         * arrive already scoped by the read filter and linked per record span (see
         * importForDateLocked — the day window is NOT re-applied here: a record spilling
         * past midnight is stored whole). Span-level input hygiene lives in [mapStageSpan].
         */
        fun buildStageRows(
            sessionId: Long,
            records: List<SleepSessionRecord>,
        ): MappedStages {
            val rows = mutableListOf<SleepStageEntity>()
            var skippedMinutes = 0L
            val skippedByType = mutableMapOf<String, Long>()
            for (record in records) {
                for (stage in record.stages) {
                    val mapping = mapStageSpan(
                        sessionId,
                        stage.stage,
                        stage.startTime.toEpochMilli(),
                        stage.endTime.toEpochMilli(),
                    )
                    mapping.row?.let(rows::add)
                    skippedMinutes += mapping.skippedMinutes
                    if (mapping.skippedTypeKey != null) {
                        skippedByType.merge(mapping.skippedTypeKey, mapping.skippedMinutes, Long::plus)
                    }
                }
            }
            return MappedStages(
                rows = rows.sortedBy { it.startMs },
                skippedMinutes = skippedMinutes,
                skippedMinutesByType = skippedByType.toSortedMap(),
            )
        }

        /**
         * Maps ONE stage span (raw epoch millis — no HC type needed to construct) to its row
         * or its skipped accounting. A zero/negative-length span is a malformed provider
         * record — never a row, whatever its type says (mirrors GarminValidators.validSpans).
         * connect-client 1.1.0 already rejects such spans in Stage's constructor ("startTime
         * must be before endTime"), so this guard is defense-in-depth — kept pure on raw
         * millis so it stays unit-testable. The floored minutes are structurally 0 here (the
         * span cannot invent duration); the accounting keeps the skip path uniform.
         */
        fun mapStageSpan(sessionId: Long, hcStageType: Int, startMs: Long, endMs: Long): SpanMapping {
            val label = mapStageType(hcStageType)
            if (label == null || endMs <= startMs) {
                val minutes = (endMs - startMs).coerceAtLeast(0) / 60_000
                return SpanMapping(
                    row = null,
                    skippedMinutes = minutes,
                    skippedTypeKey = if (minutes > 0) label ?: skippedTypeKey(hcStageType) else null,
                )
            }
            return SpanMapping(
                row = SleepStageEntity(
                    sessionId = sessionId,
                    startMs = startMs,
                    endMs = endMs,
                    stage = label,
                    source = STAGE_SOURCE,
                ),
                skippedMinutes = 0,
                skippedTypeKey = null,
            )
        }

        /**
         * Compact summary JSON stored verbatim in raw_payload (docs/DATA_MODEL.md §3.9, §6):
         * readers handle older versions defensively, so [org.json.JSONObject] keys carry a
         * `schema_version`. Pure — unit tested.
         */
        fun summaryJson(
            recordsRead: Int,
            stagesStored: Int,
            skippedMinutes: Long,
            skippedMinutesByType: Map<String, Long>,
            recordsWithoutSession: Int,
            linkedSessionId: Long?,
            window: Pair<Long, Long>,
        ): String = JSONObject().apply {
            put("schema_version", 1)
            put("records_read", recordsRead)
            put("stages_stored", stagesStored)
            put("skipped_minutes", skippedMinutes)
            put("skipped_minutes_by_type", JSONObject(skippedMinutesByType))
            put("records_without_session", recordsWithoutSession)
            if (linkedSessionId != null) put("linked_session_id", linkedSessionId)
            put("window_start_ms", window.first)
            put("window_end_ms", window.second)
        }.toString()
    }
}
