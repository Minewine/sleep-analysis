package com.sleepanalysis.data.garmin

import androidx.room.withTransaction
import com.sleepanalysis.data.db.AppDatabase
import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.dao.RawPayloadDao
import com.sleepanalysis.data.db.entity.RawPayloadEntity
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
        ) : GarminImportState

        data class Error(val date: String?, val message: String) : GarminImportState
    }

    private val _state = MutableStateFlow<GarminImportState>(GarminImportState.Idle)
    val state: StateFlow<GarminImportState> = _state.asStateFlow()

    suspend fun importForDate(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) {
        val dateStr = date.toString()
        _state.value = GarminImportState.Importing(dateStr)
        try {
            if (!keyStore.hasKey()) {
                _state.value = GarminImportState.Error(dateStr, "No Garmin API key stored — add one in Settings first.")
                return
            }
            val result = client.fetchDay(dateStr)

            var endpointsStored = 0
            var imported = 0
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

                // 2. Normalize into provenance-stamped measurements over the local day window.
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val metrics = buildList {
                    result.ok(EP_DAILY_SLEEP)?.let { addAll(GarminJsonParsers.parseDailySleep(it.json!!, dayStart)) }
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
                recorder.replaceGarminEstimates(
                    periodStart = dayStart,
                    periodEnd = dayEnd,
                    metrics = deduped.map { it.type to it.value },
                    notes = "Garmin Personal API, date $dateStr",
                )
                imported = deduped.size
            }

            _state.value = GarminImportState.Success(
                date = dateStr,
                metricsImported = imported,
                endpointsStored = endpointsStored,
                endpointStatuses = result.endpoints.map { it.endpoint to it.httpStatus },
            )
        } catch (e: GarminClient.GarminException) {
            _state.value = GarminImportState.Error(dateStr, e.message ?: "Garmin import failed.")
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
        const val EP_HRV = "/hrv-service/hrv"
        const val EP_BODY_BATTERY = "/wellness-service/wellness/bodyBattery/reports/daily"
        const val EP_DAILY_SUMMARY = "/usersummary-service/v2/dailysummaries"
        const val EP_STRESS = "/usersummary-service/stats/stress/daily"
    }
}
