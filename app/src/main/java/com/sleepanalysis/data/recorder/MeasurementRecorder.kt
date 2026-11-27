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
        requireWritable(type)
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
        requireWritable(type)
        dao.insert(garminRow(type, value, periodStart, periodEnd, notes, timestamp, sessionId = null))
    }

    /**
     * Writes an imported batch of Garmin device-reported values for one day window, REPLACING
     * the prior GARMIN rows of the batch's measurement types first so a re-import never
     * duplicates metrics (docs/GARMIN_INTEGRATION.md §6). The replace is endpoint-scoped: a
     * partial re-import (only some endpoints returned 2xx this run) leaves the other types'
     * previously imported rows intact. Same provenance as [recordGarminEstimate]. An empty
     * batch is a no-op: a failed re-import must not wipe previously imported values.
     */
    suspend fun replaceGarminEstimates(
        periodStart: Long,
        periodEnd: Long,
        metrics: List<Pair<MeasurementType, Double>>,
        notes: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        sessionId: Long? = null,
    ) {
        if (metrics.isEmpty()) return
        metrics.forEach { (type, _) -> requireWritable(type) }
        dao.replaceGarminForDay(
            periodStart,
            metrics.map { (type, value) ->
                garminRow(type, value, periodStart, periodEnd, notes, timestamp, sessionId)
            },
        )
    }

    /**
     * One phone-derived estimate destined for the label-recompute replace path — provenance is
     * stamped in [replacePhoneEstimates], not by callers.
     */
    data class PhoneEstimate(
        val type: MeasurementType,
        val value: Double,
        val modelVersion: String? = null,
        val notes: String? = null,
    )

    /**
     * Rewrites a session's snore-aggregate PHONE rows after a user label changed the night's
     * snore event set (SessionRepository.labelEvent): delete-then-insert scoped to
     * [SNORE_AGGREGATE_TYPES] — INCLUDING types with no incoming row, so a recompute that
     * stops producing a row (intensity rows when no snoring remains) deletes it instead of
     * leaving a stale value: finalize would not have written it either. Rows outside the set
     * (TIME_IN_BED, NOISE_*, ACOUSTIC_EVENT_COUNT — session bounds, ambient stats, all-event
     * counts) are never touched. Every rewritten row is flagged `label_corrected=true` in its
     * notes (docs/DATA_MODEL.md §3.2) and carries the caller's model version per
     * docs/MEASUREMENT_RELIABILITY.md §7.1. Must run inside the caller's session transaction.
     */
    suspend fun replacePhoneEstimates(sessionId: Long, estimates: List<PhoneEstimate>) {
        estimates.forEach { requireWritable(it.type) }
        dao.replacePhoneForSession(
            sessionId,
            SNORE_AGGREGATE_TYPES.map { it.name },
            estimates.map { it.phoneRow(sessionId) },
        )
    }

    private fun PhoneEstimate.phoneRow(sessionId: Long) = MeasurementEntity(
        sessionId = sessionId,
        timestamp = System.currentTimeMillis(),
        sourceType = SourceTypes.PHONE,
        method = Methods.ALGORITHM_ESTIMATED,
        isClinical = false,
        measurementType = type.name,
        tier = type.tier.name,
        valueNum = value,
        unit = type.unit.name,
        confidence = null,
        modelVersion = modelVersion,
        periodStart = null,
        periodEnd = null,
        notes = notes?.let { "$it; $LABEL_CORRECTED_NOTES" } ?: LABEL_CORRECTED_NOTES,
    )

    private fun requireWritable(type: MeasurementType) {
        check(type.tier != MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS) {
            "Refusing to write OUT_OF_SCOPE_FOR_CLAIMS measurement type ${type.name} " +
                "(docs/MEASUREMENT_RELIABILITY.md §2)"
        }
    }

    /**
     * One NIGHT_VALIDITY row destined for [replaceNightValidity] — provenance is stamped there,
     * not by callers. The grade letter is the value; the notes carry NightValidity's JSON.
     */
    data class ValidityEstimate(
        val grade: String,
        val notes: String,
        val modelVersion: String = com.sleepanalysis.domain.validity.NightValidity.VERSION,
    )

    /**
     * Rewrites a session's NIGHT_VALIDITY PHONE row (delete-then-insert scoped to the type,
     * the [replacePhoneEstimates] pattern): finalize writes it first; a label recompute or a
     * Garmin re-import of a linked session recomputes and REPLACES instead of duplicating.
     * The grade letter lives in valueText ([MeasurementUnit.GRADE] has no numeric value —
     * valueNum stays null). Must run inside the caller's session transaction.
     */
    suspend fun replaceNightValidity(sessionId: Long, estimate: ValidityEstimate) {
        requireWritable(MeasurementType.NIGHT_VALIDITY)
        dao.replacePhoneForSession(
            sessionId,
            listOf(MeasurementType.NIGHT_VALIDITY.name),
            listOf(estimate.row(sessionId)),
        )
    }

    private fun ValidityEstimate.row(sessionId: Long) = MeasurementEntity(
        sessionId = sessionId,
        timestamp = System.currentTimeMillis(),
        sourceType = SourceTypes.PHONE,
        method = Methods.ALGORITHM_ESTIMATED,
        isClinical = false,
        measurementType = MeasurementType.NIGHT_VALIDITY.name,
        tier = MeasurementType.NIGHT_VALIDITY.tier.name,
        valueNum = null,
        valueText = grade,
        unit = MeasurementType.NIGHT_VALIDITY.unit.name,
        confidence = null,
        modelVersion = modelVersion,
        periodStart = null,
        periodEnd = null,
        notes = notes,
    )

    private fun garminRow(
        type: MeasurementType,
        value: Double,
        periodStart: Long,
        periodEnd: Long,
        notes: String?,
        timestamp: Long,
        sessionId: Long?,
    ) = MeasurementEntity(
        // Linked to the best time-overlapping session when the import can identify one
        // (docs/GARMIN_INTEGRATION.md §6); null leaves the date-window join as the fallback.
        sessionId = sessionId,
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
            const val ALGORITHM_ESTIMATED = "ALGORITHM_ESTIMATED"
            const val DEVICE_REPORTED = "DEVICE_REPORTED"
        }

        /** Notes flag marking a measurement rewritten by a user-label recompute (§7.1). */
        const val LABEL_CORRECTED_NOTES = "label_corrected=true"

        /**
         * The snore aggregates a label recompute rewrites (SessionRepository.labelEvent) —
         * everything else a PHONE write carries derives from session bounds (TIME_IN_BED),
         * ambient stats (NOISE_*) or all-event counts (ACOUSTIC_EVENT_COUNT), none changed by
         * a user label.
         */
        val SNORE_AGGREGATE_TYPES = listOf(
            MeasurementType.SNORE_EVENT_COUNT,
            MeasurementType.SNORE_TIME,
            MeasurementType.SNORE_SHARE_OF_NIGHT,
            MeasurementType.SNORE_SCORE,
            MeasurementType.SNORE_INTENSITY_PEAK,
            MeasurementType.SNORE_INTENSITY_MEAN,
        )
    }
}
