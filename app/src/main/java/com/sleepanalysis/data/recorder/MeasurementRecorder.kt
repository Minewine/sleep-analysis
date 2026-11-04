package com.sleepanalysis.data.recorder

import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.domain.policy.MeasurementTier
import com.sleepanalysis.domain.policy.MeasurementType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONLY component allowed to write Measurement rows (docs/ARCHITECTURE.md §6.1).
 * Provenance fields are stamped here, not by callers, and out-of-scope measurement types
 * are rejected outright so Tier-3 values can never reach the database.
 */
@Singleton
class MeasurementRecorder @Inject constructor(
    private val dao: MeasurementDao,
) {
    /** Writes a phone-derived algorithmic estimate with full provenance. */
    suspend fun recordPhoneEstimate(
        sessionId: Long,
        type: MeasurementType,
        value: Double,
        confidence: Double? = null,
        modelVersion: String? = null,
        periodStart: Long? = null,
        periodEnd: Long? = null,
        notes: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        check(type.tier != MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS) {
            "Refusing to write OUT_OF_SCOPE_FOR_CLAIMS measurement type ${type.name} " +
                "(docs/MEASUREMENT_RELIABILITY.md §2)"
        }
        dao.insert(
            MeasurementEntity(
                sessionId = sessionId,
                timestamp = timestamp,
                sourceType = SourceTypes.PHONE,
                method = Methods.ALGORITHM_ESTIMATED,
                isClinical = false,
                measurementType = type.name,
                tier = type.tier.name,
                valueNum = value,
                unit = type.unit.name,
                confidence = confidence,
                modelVersion = modelVersion,
                periodStart = periodStart,
                periodEnd = periodEnd,
                notes = notes,
            )
        )
    }

    /** Writes a Garmin device-reported value with vendor provenance (docs/GARMIN_INTEGRATION.md). */
    suspend fun recordGarminEstimate(
        type: MeasurementType,
        value: Double,
        periodStart: Long,
        periodEnd: Long,
        notes: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        checkGarminType(type)
        dao.insert(garminRow(type, value, periodStart, periodEnd, notes, timestamp))
    }

    /**
     * Writes an imported batch of Garmin device-reported values for one day window, REPLACING
     * the day's prior GARMIN rows first so a re-import never duplicates metrics
     * (docs/GARMIN_INTEGRATION.md §6). Same provenance as [recordGarminEstimate]. An empty
     * batch is a no-op: a failed re-import must not wipe previously imported values.
     */
    suspend fun replaceGarminEstimates(
        periodStart: Long,
        periodEnd: Long,
        metrics: List<Pair<MeasurementType, Double>>,
        notes: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        if (metrics.isEmpty()) return
        metrics.forEach { (type, _) -> checkGarminType(type) }
        dao.replaceGarminForDay(
            periodStart,
            metrics.map { (type, value) -> garminRow(type, value, periodStart, periodEnd, notes, timestamp) },
        )
    }

    private fun checkGarminType(type: MeasurementType) {
        check(type.tier != MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS) {
            "Refusing to write OUT_OF_SCOPE_FOR_CLAIMS measurement type ${type.name} " +
                "(docs/MEASUREMENT_RELIABILITY.md §2)"
        }
    }

    private fun garminRow(
        type: MeasurementType,
        value: Double,
        periodStart: Long,
        periodEnd: Long,
        notes: String?,
        timestamp: Long,
    ) = MeasurementEntity(
        sessionId = null, // imported outside any session; joined to reports by day window
        timestamp = timestamp,
        sourceType = SourceTypes.GARMIN,
        method = Methods.DEVICE_REPORTED,
        isClinical = false,
        measurementType = type.name,
        tier = type.tier.name,
        valueNum = value,
        unit = type.unit.name,
        confidence = null, // vendor does not expose confidence
        modelVersion = null, // vendor algorithm, not ours
        periodStart = periodStart,
        periodEnd = periodEnd,
        notes = notes,
    )

    companion object {
        /** Provenance vocabularies (docs/DATA_MODEL.md §3.2). */
        object SourceTypes {
            const val CLINICAL = "CLINICAL"
            const val PHONE = "PHONE"
            const val WEARABLE = "WEARABLE"
            const val USER = "USER"
            const val GARMIN = "GARMIN"
        }

        object Methods {
            const val CLINICAL_MEASURED = "CLINICAL_MEASURED"
            const val ALGORITHM_ESTIMATED = "ALGORITHM_ESTIMATED"
            const val DEVICE_REPORTED = "DEVICE_REPORTED"
            const val USER_REPORTED = "USER_REPORTED"
        }
    }
}
