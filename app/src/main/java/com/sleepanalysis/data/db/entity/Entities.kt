package com.sleepanalysis.data.db.entity

import androidx.room.ColumnInfo
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
    /** JSON payload v1 — see docs/DATA_MODEL.md §5.1. */
    val captureStats: String? = null,
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
    @ColumnInfo(defaultValue = "0")
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
     * personal labeled dataset Phase 2 model improvement trains against. Since snore-score-v3
     * it also re-weighs the night's snore aggregates (docs/MEASUREMENT_RELIABILITY.md §7.1):
     * non-SNORE excludes, SNORE includes, no label keeps the detector's class. The stored type
     * is never changed.
     */
    val userLabel: String? = null,
    /**
     * The detection model's decision inputs as JSON (schema v5) — for energy-heuristic-v3-peakgate:
     * enter_windows, snr_peak_db, snr_mean_db, low_band_ratio_peak, low_band_ratio_mean,
     * noise_floor_dbfs ([com.sleepanalysis.monitoring.detection.EventFeatures]). Null for rows
     * recorded before this existed. Debug/inspection only — never rendered as a measurement.
     */
    val featuresJson: String? = null,
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
    /**
     * Total gain actually applied to the stored samples (schema v11): mic gain × block
     * peak-limit scale (ClipStore.gainAndLimit) — less than the mic gain on loud clips that
     * were limited. Old rows predate raw+limited clip writes and were written with the fixed
     * 2.5 in-place int16 mic gain: that default is truthful history, not a placeholder.
     */
    @ColumnInfo(defaultValue = "2.5")
    val appliedGain: Float = 2.5f,
    /** Peak of the stored samples in dBFS (schema v11, ≈ −1.0 on limited clips); null pre-v11. */
    val filePeakDbfs: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Universal provenance-stamped metric row (docs/DATA_MODEL.md §3.2). Written only through
 * MeasurementRecorder.
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
 * One vendor sleep-stage span of a linked session (schema v9, docs/DATA_MODEL.md §3.10).
 * Device-reported context only — never compared to PSG stages (docs/MEASUREMENT_RELIABILITY.md
 * M13). Populated when a Garmin payload carries a sleepLevels timeline; the owning session's
 * deletion removes it (FK CASCADE). Stage is the vendor's own label, uppercased
 * (DEEP/LIGHT/REM/AWAKE, or an unknown vendor label verbatim).
 *
 * [source] is provenance (schema v14): GARMIN = stage spans derived from the Personal API
 * night payload (estimate); WEARABLE = Health Connect stage timeline. Both timelines can
 * coexist on one session — deletes and replaces are per-source.
 */
@Entity(
    tableName = "sleep_stage",
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId", "startMs")],
)
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long,
    val stage: String,
    val createdAt: Long = System.currentTimeMillis(),
    // defaultValue REQUIRED so Room's migration validation matches the ALTER-produced
    // DEFAULT 'GARMIN' (schema v14) — pre-v14 rows were all Garmin-derived.
    @ColumnInfo(defaultValue = "GARMIN") val source: String = "GARMIN",
)

/**
 * One event-free span ≥10 min inside a session's capture window (schema v10,
 * docs/DATA_MODEL.md §3.11), computed at finalize from the night's events and the capture's
 * level buckets: QUIET spans sat at the ambient bed (real quiet sleep), UNCAPTURED spans have
 * no level evidence (process death). Written once per session (replace); the session's
 * deletion removes them (FK CASCADE).
 */
@Entity(
    tableName = "quiet_gap",
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId", "startMs")],
)
data class QuietGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    /** QUIET or UNCAPTURED (domain/report.QuietGapKind); null = no bucket evidence existed. */
    val kind: String? = null,
    /** Mean window dBFS of the covering level buckets (null without evidence). */
    val meanDbfs: Double? = null,
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
)

/**
 * One finalize-time snapshot of the night's headline metrics (schema v12,
 * docs/DATA_MODEL.md §3.7): a compact, ClaimPolicy-safe JSON payload plus the model
 * provenance (detector + score versions) so later analysis can gate comparisons on what
 * produced the numbers. One row per session (unique index, regeneration upserts); the
 * session's deletion removes it (FK CASCADE) — reports are regenerable while source data
 * exists, never a second source of truth (docs/DATA_MODEL.md §6).
 */
@Entity(
    tableName = "report",
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"], unique = true)],
)
data class ReportSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val generatedAt: Long,
    val appVersion: String,
    /** First snore event's model version (null: a night with no snoring cannot name one). */
    val detectorVersion: String? = null,
    /** SNORE_SCORE formula version that produced the snapshot's score ([SnoreScore.FORMULA_VERSION]). */
    val scoreVersion: String? = null,
    /** Compact headline JSON (docs/DATA_MODEL.md §5.2, domain/report.ReportSnapshot). */
    val payload: String,
)

/** Registry kinds stored in [ModelRegistryEntity.kind] (docs/DATA_MODEL.md §3.8). */
object ModelRegistryKind {
    const val DETECTOR = "DETECTOR"
    const val SCORE = "SCORE"
}

/**
 * One model/formula version's validity range (schema v12, docs/DATA_MODEL.md §3.8): the
 * temporal record of which DETECTOR and SCORE versions produced which nights — the gate
 * personal baselines and trends need to avoid comparing across a version change.
 * [validToUtc] null = still current. Exactly one open row per kind is kept (the writer
 * closes the previous open row when a new version appears).
 */
@Entity(tableName = "model_registry", indices = [Index("kind", "validFromUtc")])
data class ModelRegistryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [ModelRegistryKind.DETECTOR] or [ModelRegistryKind.SCORE]. */
    val kind: String,
    val version: String,
    val validFromUtc: Long,
    /** Null = the version is still current (no later row of the same kind was registered). */
    val validToUtc: Long? = null,
)
