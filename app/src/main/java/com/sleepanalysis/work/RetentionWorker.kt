package com.sleepanalysis.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sleepanalysis.data.db.AppDatabase
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.data.logs.SessionLogger
import com.sleepanalysis.data.prefs.UserPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Daily retention job (docs/DATA_MODEL.md §6): clips expire before sessions, sessions before
 * measurements; measurements and the clinical baseline persist until the user deletes them.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val clipStore: ClipStore,
    private val logger: SessionLogger,
    private val prefs: UserPreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val retention = prefs.retention.first()

        // 1. Clips past their retention window → delete rows + files.
        val expiredClips = db.audioClipDao().olderThan(now - retention.clipsRetentionDays * DAY_MS)
        if (expiredClips.isNotEmpty()) {
            expiredClips.forEach { clipStore.absolutePath(it.filePath).delete() }
            db.audioClipDao().deleteByIds(expiredClips.map { it.id })
        }

        // 2. Sessions past their retention window (events cascade; measurements survive).
        db.sleepSessionDao().deleteOlderThan(now - retention.sessionRetentionDays * DAY_MS)

        // 3. Session logs follow session retention.
        logger.purgeOlderThan(now - retention.sessionRetentionDays * DAY_MS)

        // 4. Orphan sweep: clip files with no DB row. Guarded (OrphanSweepPolicy): while a
        // session is recording there is a window between writeClip() and the audio_clip insert,
        // so sweep only when no session is open and only for files older than an hour.
        val openSessions = db.sleepSessionDao().countOpen()
        val referenced = db.audioClipDao().all().map { it.filePath }.toSet()
        clipStore.deleteOrphans(referenced, nowMs = now) { fileAgeMs ->
            OrphanSweepPolicy.shouldSweep(openSessions, fileAgeMs)
        }

        Result.success()
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val UNIQUE_NAME = "retention"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetentionWorker>(24, TimeUnit.HOURS).build(),
            )
        }
    }
}

/**
 * Pure guard for the orphan sweep (step 4): a recording session always has a window between
 * clipStore.writeClip() and the audio_clip insert, so sweeping during an active night can
 * delete in-flight clips — and even with no open session, a file written seconds ago may
 * still be waiting for its row. Sweep only when nothing is open, and only files untouched
 * for at least an hour.
 */
object OrphanSweepPolicy {
    const val MIN_FILE_AGE_MS = 60L * 60 * 1000

    fun shouldSweep(openSessionCount: Int, fileAgeMs: Long): Boolean =
        openSessionCount == 0 && fileAgeMs >= MIN_FILE_AGE_MS
}
