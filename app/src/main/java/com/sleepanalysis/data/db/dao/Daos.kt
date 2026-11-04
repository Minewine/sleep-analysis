package com.sleepanalysis.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.ClinicalBaselineEntity
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.SleepSessionEntity
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

@Dao
interface AcousticEventDao {
    @Insert
    suspend fun insert(event: AcousticEventEntity): Long

    @Query("SELECT * FROM acoustic_event WHERE sessionId = :sessionId ORDER BY startMs")
    suspend fun bySession(sessionId: Long): List<AcousticEventEntity>

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
