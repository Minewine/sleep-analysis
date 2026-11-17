package com.sleepanalysis.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.ClinicalBaselineEntity
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.db.entity.ModelRegistryEntity
import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.ReportSnapshotEntity
import com.sleepanalysis.data.db.entity.SleepExperimentEntity
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.db.entity.QuietGapEntity
import com.sleepanalysis.data.db.entity.SleepStageEntity
import com.sleepanalysis.data.db.entity.UserFactorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepSessionDao {
    @Insert
    suspend fun insert(session: SleepSessionEntity): Long

    @Query(
        "UPDATE sleep_session SET endedAt = :endedAt, status = :status, " +
            "captureStats = :captureStats, audioBytesTotal = :audioBytes WHERE id = :id"
    )
    suspend fun finalize(id: Long, endedAt: Long, status: String, captureStats: String?, audioBytes: Long)

    @Query("SELECT * FROM sleep_session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun findOpen(): SleepSessionEntity?

    /** Open-session count — > 0 means a night is (or was) recording (orphan-sweep guard). */
    @Query("SELECT COUNT(*) FROM sleep_session WHERE endedAt IS NULL")
    suspend fun countOpen(): Int

    @Query("SELECT * FROM sleep_session WHERE id = :id")
    suspend fun byId(id: Long): SleepSessionEntity?

    @Query("SELECT * FROM sleep_session ORDER BY startedAt DESC LIMIT 1")
    suspend fun latest(): SleepSessionEntity?

    @Query("SELECT * FROM sleep_session ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SleepSessionEntity>>

    @Query("SELECT startedAt FROM sleep_session ORDER BY startedAt DESC")
    suspend fun allStartTimes(): List<Long>

    @Query("SELECT * FROM sleep_session")
    suspend fun all(): List<SleepSessionEntity>

    @Query("DELETE FROM sleep_session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sleep_session SET morningNotes = :notes WHERE id = :id")
    suspend fun setMorningNotes(id: Long, notes: String?)

    /** Marks an open session as resumed after a mid-night process death (schema v7). */
    @Query("UPDATE sleep_session SET resumedAfterInterruption = 1 WHERE id = :id AND endedAt IS NULL")
    suspend fun markResumedAfterInterruption(id: Long)

    /** Heartbeat-time capture progress, so a resumed session can carry counters across restarts. */
    @Query("UPDATE sleep_session SET captureStats = :json WHERE id = :id AND endedAt IS NULL")
    suspend fun updateCaptureStats(id: Long, json: String)

    @Query("DELETE FROM sleep_session WHERE startedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM sleep_session")
    suspend fun deleteAll()
}

/** One clip-backed event with its clip's relative path — the labeling work set. */
data class ClipBackedEventRow(
    @androidx.room.Embedded val event: com.sleepanalysis.data.db.entity.AcousticEventEntity,
    val clipPath: String,
)

@Dao
interface AcousticEventDao {
    @Insert
    suspend fun insert(event: AcousticEventEntity): Long

    @Query(
        "SELECT e.*, c.filePath AS clipPath FROM acoustic_event e " +
            "JOIN audio_clip c ON c.id = e.clipId " +
            "WHERE e.sessionId = :sessionId ORDER BY e.startMs"
    )
    suspend fun clipBackedEvents(sessionId: Long): List<ClipBackedEventRow>

    @Query("SELECT * FROM acoustic_event WHERE sessionId = :sessionId ORDER BY startMs")
    suspend fun bySession(sessionId: Long): List<AcousticEventEntity>

    /** The labeled event's session — the label recompute rewrites that session's measurements. */
    @Query("SELECT * FROM acoustic_event WHERE id = :eventId")
    suspend fun byId(eventId: Long): AcousticEventEntity?

    /** Detector version of the session's first snore event — cross-night comparison guard. */
    @Query(
        "SELECT modelVersion FROM acoustic_event WHERE sessionId = :sessionId " +
            "AND type = 'SNORE' ORDER BY startMs LIMIT 1"
    )
    suspend fun firstSnoreModelVersion(sessionId: Long): String?

    @Query("SELECT COUNT(*) FROM acoustic_event WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("UPDATE acoustic_event SET userLabel = :label, labeledAt = :labeledAt WHERE id = :eventId")
    suspend fun setUserLabel(eventId: Long, label: String?, labeledAt: Long?)

    /**
     * Retention: clears the clip link of events whose clip rows are being deleted, in the
     * same transaction as the row deletion (RetentionWorker) — belt-and-braces alongside the
     * FK's ON DELETE SET_NULL, keeping the clip↔event invariant explicit (DATA_MODEL.md §3.5).
     */
    @Query("UPDATE acoustic_event SET clipId = NULL WHERE clipId IN (:clipIds)")
    suspend fun clearClipIds(clipIds: List<Long>)

    @Query("DELETE FROM acoustic_event")
    suspend fun deleteAll()
}

@Dao
interface AudioClipDao {
    @Insert
    suspend fun insert(clip: AudioClipEntity): Long

    @Query("SELECT * FROM audio_clip WHERE sessionId = :sessionId")
    suspend fun bySession(sessionId: Long): List<AudioClipEntity>

    @Query("SELECT * FROM audio_clip WHERE createdAt < :cutoff")
    suspend fun olderThan(cutoff: Long): List<AudioClipEntity>

    @Query("SELECT * FROM audio_clip")
    suspend fun all(): List<AudioClipEntity>

    @Query("SELECT COUNT(*) FROM audio_clip WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM audio_clip WHERE sessionId = :sessionId")
    suspend fun sumBytesForSession(sessionId: Long): Long

    /** Back-fills the event link after the event row exists (insert order: clip → event). */
    @Query("UPDATE audio_clip SET eventId = :eventId WHERE id = :clipId")
    suspend fun setEventId(clipId: Long, eventId: Long)

    @Query("DELETE FROM audio_clip WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM audio_clip")
    suspend fun deleteAll()
}

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(measurement: MeasurementEntity): Long

    @Insert
    suspend fun insertAll(measurements: List<MeasurementEntity>)

    @Query("SELECT * FROM measurement WHERE sessionId = :sessionId")
    suspend fun bySession(sessionId: Long): List<MeasurementEntity>

    @Query("DELETE FROM measurement")
    suspend fun deleteAll()

    /** Garmin/vendor metrics whose reporting day overlaps [from, to) (docs/GARMIN_INTEGRATION.md). */
    @Query(
        "SELECT * FROM measurement WHERE sourceType = 'GARMIN' " +
            "AND periodStart >= :from AND periodStart < :to ORDER BY measurementType"
    )
    suspend fun garminBetween(from: Long, to: Long): List<MeasurementEntity>

    /**
     * Deletes the GARMIN rows of one day window for the given measurement types ONLY — a
     * partial re-import (an endpoint that failed this run) must not wipe the other types'
     * previously imported rows (docs/GARMIN_INTEGRATION.md §6).
     */
    @Query(
        "DELETE FROM measurement WHERE sourceType = 'GARMIN' AND periodStart = :periodStart " +
            "AND measurementType IN (:types)"
    )
    suspend fun deleteGarminForTypes(periodStart: Long, types: List<String>)

    /**
     * Atomic delete-then-insert so a re-import can never duplicate the day's GARMIN rows. The
     * delete is scoped to the incoming rows' measurement types: a partial re-import leaves the
     * other types' rows intact. An empty batch inserts nothing and deletes nothing.
     */
    @Transaction
    suspend fun replaceGarminForDay(periodStart: Long, rows: List<MeasurementEntity>) {
        if (rows.isEmpty()) return
        deleteGarminForTypes(periodStart, rows.map { it.measurementType }.distinct())
        insertAll(rows)
    }

    /**
     * Deletes the PHONE rows of one session for the given measurement types ONLY — the label
     * recompute rewrites the snore aggregates (MeasurementRecorder.replacePhoneEstimates) and
     * must never touch the session's other rows (docs/DATA_MODEL.md §3.2).
     */
    @Query(
        "DELETE FROM measurement WHERE sourceType = 'PHONE' AND sessionId = :sessionId " +
            "AND measurementType IN (:types)"
    )
    suspend fun deletePhoneForSession(sessionId: Long, types: List<String>)

    /**
     * Atomic delete-then-insert so a label recompute can never duplicate a session's PHONE
     * rows (mirrors [replaceGarminForDay]). The delete is scoped to [types] — the FULL set of
     * types being rewritten, not just the incoming rows': a recompute that stops producing a
     * row (intensity rows when no snoring remains) must delete it, never leave a stale value.
     * Unlike [replaceGarminForDay], empty [rows] with a non-empty [types] delete-only.
     */
    @Transaction
    suspend fun replacePhoneForSession(sessionId: Long, types: List<String>, rows: List<MeasurementEntity>) {
        deletePhoneForSession(sessionId, types)
        insertAll(rows)
    }
}

/** Clinical baseline is insert + read only — immutability is enforced (docs/DATA_MODEL.md §4). */
@Dao
interface ClinicalBaselineDao {
    @Insert
    suspend fun insert(baseline: ClinicalBaselineEntity): Long

    @Query("SELECT * FROM clinical_baseline ORDER BY studyDate DESC, id DESC")
    fun observeAll(): Flow<List<ClinicalBaselineEntity>>

    @Query("SELECT * FROM clinical_baseline ORDER BY studyDate")
    suspend fun all(): List<ClinicalBaselineEntity>

    @Query("DELETE FROM clinical_baseline")
    suspend fun deleteAll()
}

/** Raw vendor payloads (docs/GARMIN_INTEGRATION.md). Upsert per (source, endpoint, date). */
@Dao
interface RawPayloadDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(payload: RawPayloadEntity): Long

    @Query("SELECT * FROM raw_payload WHERE source = :source AND date = :date ORDER BY endpoint")
    suspend fun forDate(source: String, date: String): List<RawPayloadEntity>

    @Query("DELETE FROM raw_payload WHERE fetchedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM raw_payload")
    suspend fun deleteAll()
}

/** Vendor sleep-stage timeline of one session (schema v9; per-source since v14). Device-reported context only. */
@Dao
interface SleepStageDao {
    @Insert
    suspend fun insertAll(stages: List<SleepStageEntity>)

    @Query("SELECT * FROM sleep_stage WHERE sessionId = :sessionId ORDER BY startMs")
    suspend fun bySession(sessionId: Long): List<SleepStageEntity>

    /** Deletes ONE source's rows only — the GARMIN and WEARABLE timelines coexist (schema v14). */
    @Query("DELETE FROM sleep_stage WHERE sessionId = :sessionId AND source = :source")
    suspend fun deleteForSession(sessionId: Long, source: String)

    /** Device purge — deliberately unscoped, wipes every source (docs/DATA_MODEL.md §6). */
    @Query("DELETE FROM sleep_stage")
    suspend fun deleteAll()

    /** True (=0) when the session carries no timeline from [source] — the needs-import gate. */
    @Query("SELECT COUNT(*) FROM sleep_stage WHERE sessionId = :sessionId AND source = :source")
    suspend fun countForSession(sessionId: Long, source: String): Int

    /** Atomic replace so a re-import can never duplicate a session's stage timeline. */
    @Transaction
    suspend fun replaceForSession(sessionId: Long, source: String, stages: List<SleepStageEntity>) {
        deleteForSession(sessionId, source)
        insertAll(stages)
    }
}

/** Stored event-free spans ≥10 min of one session (schema v10, docs/DATA_MODEL.md §3.11). */
@Dao
interface QuietGapDao {
    @Insert
    suspend fun insertAll(gaps: List<QuietGapEntity>)

    @Query("SELECT * FROM quiet_gap WHERE sessionId = :sessionId ORDER BY startMs")
    suspend fun bySession(sessionId: Long): List<QuietGapEntity>

    @Query("DELETE FROM quiet_gap WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM quiet_gap")
    suspend fun deleteAll()

    /** Atomic replace: re-finalizing a session must never duplicate its gaps. */
    @Transaction
    suspend fun replaceForSession(sessionId: Long, gaps: List<QuietGapEntity>) {
        deleteForSession(sessionId)
        insertAll(gaps)
    }
}

/** Self-logged daily lifestyle factors. Only set factors are stored (absence = not logged). */
@Dao
interface UserFactorDao {
    @Insert
    suspend fun insertAll(factors: List<UserFactorEntity>)

    @Query("SELECT * FROM user_factor WHERE `date` = :date ORDER BY factorKey")
    suspend fun forDate(date: String): List<UserFactorEntity>

    @Query("DELETE FROM user_factor WHERE `date` = :date")
    suspend fun deleteForDate(date: String)

    @Query("DELETE FROM user_factor")
    suspend fun deleteAll()

    /** Atomic replace: a crash between delete and insert must never lose the day's log. */
    @Transaction
    suspend fun replaceForDate(date: String, factors: List<UserFactorEntity>) {
        deleteForDate(date)
        insertAll(factors)
    }
}

/**
 * Finalize-time report snapshots (schema v12, docs/DATA_MODEL.md §3.7). Upsert per session
 * (unique index) — a re-write replaces, never duplicates; rows follow session retention via
 * the FK CASCADE and delete-all purges the rest.
 */
@Dao
interface ReportDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(report: ReportSnapshotEntity): Long

    @Query("SELECT * FROM report WHERE sessionId = :sessionId")
    suspend fun bySession(sessionId: Long): ReportSnapshotEntity?

    @Query("SELECT * FROM report ORDER BY generatedAt DESC")
    suspend fun all(): List<ReportSnapshotEntity>

    @Query("DELETE FROM report")
    suspend fun deleteAll()
}

/**
 * N-of-1 experiment rows (schema v13, docs/DATA_MODEL.md §3.12). Exactly ONE ACTIVE row is a
 * tracker-logic invariant (not SQL); the A-B-A confirm phase re-activates the DONE row itself.
 */
@Dao
interface SleepExperimentDao {
    @Insert
    suspend fun insert(experiment: SleepExperimentEntity): Long

    /** The single ACTIVE row, if any (exactly one is enforced before insert — ExperimentTracker). */
    @Query("SELECT * FROM sleep_experiment WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): SleepExperimentEntity?

    @Query("SELECT * FROM sleep_experiment ORDER BY startedAt DESC")
    suspend fun history(): List<SleepExperimentEntity>

    @Query("SELECT * FROM sleep_experiment WHERE id = :id")
    suspend fun byId(id: Long): SleepExperimentEntity?

    /** Phase verdict lands at the night threshold: ACTIVE → DONE with the verdict JSON. */
    @Query("UPDATE sleep_experiment SET status = 'DONE', resultJson = :resultJson WHERE id = :id AND status = 'ACTIVE'")
    suspend fun finish(id: Long, resultJson: String)

    /** A-B-A confirm phase re-activates the DONE row (reuse — the single-ACTIVE invariant holds). */
    @Query("UPDATE sleep_experiment SET status = 'ACTIVE', confirmPhaseJson = :confirmPhaseJson WHERE id = :id AND status = 'DONE'")
    suspend fun beginConfirmPhase(id: Long, confirmPhaseJson: String)

    /**
     * Confirm completed: back to DONE with the (possibly upgraded) verdict + the final confirm
     * JSON. Guarded to ACTIVE — an ABANDONED (or already-DONE) row can never be resurrected
     * by an in-flight finalize.
     */
    @Query(
        "UPDATE sleep_experiment SET status = 'DONE', resultJson = :resultJson, " +
            "confirmPhaseJson = :confirmPhaseJson WHERE id = :id AND status = 'ACTIVE'"
    )
    suspend fun finishConfirm(id: Long, resultJson: String, confirmPhaseJson: String)

    /**
     * User keeps the verdict (verdict card stops rendering; result otherwise untouched).
     * Allowed on DONE and — for a Keep that races the confirm finalize's recomposition window
     * — on an ACTIVE confirm row too (the finalize rebuild preserves the kept flag); never on
     * an ABANDONED row.
     */
    @Query("UPDATE sleep_experiment SET resultJson = :resultJson WHERE id = :id AND status != 'ABANDONED'")
    suspend fun updateResult(id: Long, resultJson: String)

    /** User abandons the experiment (ACTIVE or DONE — the row keeps any verdict it earned). */
    @Query("UPDATE sleep_experiment SET status = 'ABANDONED' WHERE id = :id AND status != 'ABANDONED'")
    suspend fun abandon(id: Long)

    @Query("DELETE FROM sleep_experiment")
    suspend fun deleteAll()
}

/**
 * Temporal model-version registry (schema v12, docs/DATA_MODEL.md §3.8): which DETECTOR /
 * SCORE versions produced which nights. Reads for the eligibility gates: the current row
 * per kind, the full history, and the version(s) valid at a given instant (range query).
 */
@Dao
interface ModelRegistryDao {
    @Insert
    suspend fun insert(row: ModelRegistryEntity): Long

    /** Closes every still-open row of [kind] except the just-inserted [exceptId] (validTo = now). */
    @Query(
        "UPDATE model_registry SET validToUtc = :validToUtc " +
            "WHERE kind = :kind AND validToUtc IS NULL AND id != :exceptId"
    )
    suspend fun closeOpenRows(kind: String, validToUtc: Long, exceptId: Long)

    /** The current version of [kind] — null when no finalize has registered one yet. */
    @Query(
        "SELECT * FROM model_registry WHERE kind = :kind AND validToUtc IS NULL " +
            "ORDER BY validFromUtc DESC LIMIT 1"
    )
    suspend fun currentForKind(kind: String): ModelRegistryEntity?

    /** Full validity history of [kind], oldest first. */
    @Query("SELECT * FROM model_registry WHERE kind = :kind ORDER BY validFromUtc")
    suspend fun historyForKind(kind: String): List<ModelRegistryEntity>

    /** Versions valid at [atMs] — rows whose range covers the instant (null validTo = open end). */
    @Query(
        "SELECT * FROM model_registry WHERE kind = :kind AND validFromUtc <= :atMs " +
            "AND (validToUtc IS NULL OR validToUtc > :atMs) ORDER BY validFromUtc"
    )
    suspend fun versionsAt(kind: String, atMs: Long): List<ModelRegistryEntity>

    @Query("DELETE FROM model_registry")
    suspend fun deleteAll()
}
