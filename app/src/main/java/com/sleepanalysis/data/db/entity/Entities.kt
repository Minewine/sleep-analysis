package com.sleepanalysis.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Session lifecycle values stored in [SleepSessionEntity.status] (docs/DATA_MODEL.md §3.4). */
object SessionStatus {
    const val RECORDING = "RECORDING"
    const val COMPLETED = "COMPLETED"
    const val INTERRUPTED = "INTERRUPTED"
    const val RECOVERED = "RECOVERED"
}

/** Acoustic event types (docs/DATA_MODEL.md §3.5). Phase 2 adds experimental breathing types. */
object EventType {
    const val SNORE = "SNORE"
    const val COUGH = "COUGH"
    const val SPEECH = "SPEECH"
    const val LOUD_BREATHING = "LOUD_BREATHING"
    const val OTHER_NOISE = "OTHER_NOISE"
    const val UNKNOWN = "UNKNOWN"
}

object UserLabel {
    const val SNORE = "SNORE"
    const val COUGH = "COUGH"
    const val SPEECH = "SPEECH"
    const val MOVEMENT = "MOVEMENT"
    const val OTHER_NOT_SNORE = "OTHER_NOT_SNORE"
}

@Entity(tableName = "sleep_session")
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String = SessionStatus.RECORDING,
    val timeZone: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    /** JSON payload v1 — see docs/DATA_MODEL.md §5.1. */
    val captureStats: String? = null,
    val audioBytesTotal: Long = 0,
    /**
     * Short user note added after the night (schema v6) — e.g. "woke at 3am", "partner snoring".
     * User-entered context like daily factors: never a measurement, included in exports.
     */
    val morningNotes: String? = null,
    /**
     * True when START_STICKY resumed this session after the capture process died mid-night
     * (schema v7): overnight capture was not continuous, and the morning report surfaces this
     * prominently. The RECOVERED status covers the opposite outcome — a death with no resume.
     */
    val resumedAfterInterruption: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "acoustic_event",
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId", "startMs"), Index("type")],
)
data class AcousticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: String,
    val startMs: Long,
    val endMs: Long,
    /** Offset from session start — stable across time zones/DST (docs/DATA_MODEL.md §2). */
    val offsetMs: Long,
    val durationMs: Long,
    /** Relative intensity in dBFS — never a calibrated SPL value (docs/MEASUREMENT_RELIABILITY.md M2). */
    val peakDbfs: Double,
    val meanDbfs: Double,
    val intensityTier: String,
    val confidence: Double,
    val modelVersion: String,
    val clipId: Long? = null,
    /**
     * User-corrected class from clip playback (docs/DATA_MODEL.md §3.5): what the user says the
     * event really was — SNORE / COUGH / SPEECH / MOVEMENT / OTHER_NOT_SNORE. This is the
     * personal labeled dataset Phase 2 model improvement trains against; it never alters
     * Phase 1 scores.
     */
    val userLabel: String? = null,
    /** When the user applied the label — dataset curation needs to distinguish label age. */
    val labeledAt: Long? = null,
    /**
     * The detection model's decision inputs as JSON (schema v5) — for energy-heuristic-v0:
     * enter_windows, snr_peak_db, snr_mean_db, low_band_ratio_peak, low_band_ratio_mean,
     * noise_floor_dbfs ([com.sleepanalysis.monitoring.detection.EventFeatures]). Null for rows
     * recorded before this existed. Debug/inspection only — never rendered as a measurement.
     */
    val featuresJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "audio_clip",
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["eventId"], unique = true), Index("createdAt")],
)
data class AudioClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val eventId: Long? = null,
    /** Path relative to the clip root directory. */
    val filePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val sampleRate: Int = 16_000,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Universal provenance-stamped metric row (docs/DATA_MODEL.md §3.2). Written only through
 * MeasurementRecorder; clinical values live in [ClinicalBaselineEntity] in Phase 1.
 */
@Entity(
    tableName = "measurement",
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("measurementType", "periodStart"), Index("sessionId"), Index("sourceType", "method")],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Survives session deletion (SET NULL) so longitudinal history outlives raw data. */
    val sessionId: Long? = null,
    val timestamp: Long,
    val sourceType: String,
    val method: String,
    val isClinical: Boolean,
    val measurementType: String,
    val tier: String,
    val valueNum: Double? = null,
    val valueText: String? = null,
    val unit: String,
    val confidence: Double? = null,
    val modelVersion: String? = null,
    val periodStart: Long? = null,
    val periodEnd: Long? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One clinically measured study night (e.g. HUG PSG, January 2026). Immutable once saved:
 * no update/delete DAO methods exist and DB triggers abort UPDATE/DELETE
 * (docs/DATA_MODEL.md §4). Corrections are made by adding a superseding entry.
 */
@Entity(tableName = "clinical_baseline")
data class ClinicalBaselineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO yyyy-MM-dd. */
    val studyDate: String,
    val lab: String,
    val studyType: String,
    val nightLabel: String? = null,
    val totalSleepTimeMinutes: Int? = null,
    val sleepEfficiencyPercent: Double? = null,
    val arousalIndexPerHour: Double? = null,
    val n3Percent: Double? = null,
    val ahiPerHour: Double? = null,
    val notes: String? = null,
    val sourceDocument: String? = null,
    val enteredAt: Long = System.currentTimeMillis(),
)

/**
 * Raw JSON payloads fetched from external sources (Garmin Personal API, docs/GARMIN_INTEGRATION.md).
 * Kept verbatim alongside normalized Measurements so field mappings can be corrected or
 * re-derived later without losing the original data. One row per (source, endpoint, date).
 */
@Entity(
    tableName = "raw_payload",
    indices = [Index(value = ["source", "endpoint", "date"], unique = true)],
)
data class RawPayloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** e.g. "GARMIN" — mirrors MeasurementRecorder.SourceTypes. */
    val source: String,
    /** Endpoint path, e.g. "/hrv-service/hrv/2026-01-12". */
    val endpoint: String,
    /** ISO yyyy-MM-dd the payload covers. */
    val date: String,
    val payload: String,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/**
 * One self-logged daily lifestyle factor (docs/DATA_MODEL.md §3.6). Key set in
 * [com.sleepanalysis.domain.factors.DailyFactor]. Only affirmative/set factors are stored —
 * absence means "not logged that day". These are user-entered context, NOT measurements:
 * no tier, no confidence, never rendered as clinical or algorithmic values.
 */
@Entity(tableName = "user_factor", indices = [Index("date")])
data class UserFactorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO yyyy-MM-dd (local calendar day the factor applies to). */
    val date: String,
    val factorKey: String,
    val valueNum: Double? = null,
    val valueText: String? = null,
    val recordedAt: Long = System.currentTimeMillis(),
)
