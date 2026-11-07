package com.sleepanalysis.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sleepanalysis.data.db.dao.AcousticEventDao
import com.sleepanalysis.data.db.dao.AudioClipDao
import com.sleepanalysis.data.db.dao.ClinicalBaselineDao
import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.dao.QuietGapDao
import com.sleepanalysis.data.db.dao.RawPayloadDao
import com.sleepanalysis.data.db.dao.SleepSessionDao
import com.sleepanalysis.data.db.dao.SleepStageDao
import com.sleepanalysis.data.db.dao.UserFactorDao
import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.ClinicalBaselineEntity
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.db.entity.QuietGapEntity
import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.db.entity.SleepStageEntity
import com.sleepanalysis.data.db.entity.UserFactorEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Database(
    entities = [
        SleepSessionEntity::class,
        AcousticEventEntity::class,
        AudioClipEntity::class,
        MeasurementEntity::class,
        ClinicalBaselineEntity::class,
        RawPayloadEntity::class,
        UserFactorEntity::class,
        SleepStageEntity::class,
        QuietGapEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun acousticEventDao(): AcousticEventDao
    abstract fun audioClipDao(): AudioClipDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun clinicalBaselineDao(): ClinicalBaselineDao
    abstract fun rawPayloadDao(): RawPayloadDao
    abstract fun userFactorDao(): UserFactorDao
    abstract fun sleepStageDao(): SleepStageDao
    abstract fun quietGapDao(): QuietGapDao

    /**
     * Removes every row including the clinical baseline. The immutability triggers must be
     * dropped for the duration of the purge and recreated afterwards. This is the ONLY path
     * allowed to delete clinical rows (docs/DATA_MODEL.md §4, docs/PRIVACY_AND_REGULATORY.md §4).
     */
    suspend fun purgeEverythingIncludingClinical() = withContext(Dispatchers.IO) {
        val raw = openHelper.writableDatabase
        withTransaction {
            raw.execSQL("DROP TRIGGER IF EXISTS $TRIGGER_UPDATE")
            raw.execSQL("DROP TRIGGER IF EXISTS $TRIGGER_DELETE")
            try {
                measurementDao().deleteAll()
                audioClipDao().deleteAll()
                acousticEventDao().deleteAll()
                sleepSessionDao().deleteAll()
                clinicalBaselineDao().deleteAll()
                rawPayloadDao().deleteAll()
                userFactorDao().deleteAll()
                sleepStageDao().deleteAll()
                quietGapDao().deleteAll()
            } finally {
                createImmutabilityTriggers(raw)
            }
        }
    }

    companion object {
        const val NAME = "sleepanalysis.db"
        const val TRIGGER_UPDATE = "trg_clinical_baseline_no_update"
        const val TRIGGER_DELETE = "trg_clinical_baseline_no_delete"

        fun createImmutabilityTriggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS $TRIGGER_UPDATE BEFORE UPDATE ON clinical_baseline " +
                    "BEGIN SELECT RAISE(ABORT, 'clinical baseline is immutable'); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS $TRIGGER_DELETE BEFORE DELETE ON clinical_baseline " +
                    "BEGIN SELECT RAISE(ABORT, 'clinical baseline is immutable'); END"
            )
        }

        fun build(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) = createImmutabilityTriggers(db)
                        override fun onOpen(db: SupportSQLiteDatabase) = createImmutabilityTriggers(db)
                    }
                )
                .build()

        /** v2 adds raw_payload for vendor JSON storage (docs/GARMIN_INTEGRATION.md). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `raw_payload` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`source` TEXT NOT NULL, `endpoint` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_payload_source_endpoint_date` " +
                        "ON `raw_payload` (`source`, `endpoint`, `date`)"
                )
            }
        }

        /** v3 adds user_factor for self-logged daily lifestyle factors (docs/DATA_MODEL.md §3.6). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_factor` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, `factorKey` TEXT NOT NULL, " +
                        "`valueNum` REAL, `valueText` TEXT, `recordedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_factor_date` ON `user_factor` (`date`)"
                )
            }
        }

        /** v4 adds the labeling timestamp to acoustic_event (docs/DATA_MODEL.md §3.5). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `acoustic_event` ADD COLUMN `labeledAt` INTEGER")
            }
        }

        /** v5 adds per-event detector features JSON (debug visibility of v0 decisions). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `acoustic_event` ADD COLUMN `featuresJson` TEXT")
            }
        }

        /** v6 adds the user's morning notes to the session (docs/DATA_MODEL.md §3.4). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sleep_session` ADD COLUMN `morningNotes` TEXT")
            }
        }

        /** v7 marks sessions that START_STICKY resumed after a mid-night process death. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sleep_session` ADD COLUMN `resumedAfterInterruption` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v8 is declaration-only: the column has carried `DEFAULT 0` in the actual database
         * since v7 — the entity now declares `@ColumnInfo(defaultValue = "0")` so the exported
         * schema matches reality (and seeded/pre-v7 rows default to false). No SQL changes.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: nothing to change on disk (see KDoc).
            }
        }

        /** v10 stores the night's classified quiet gaps (docs/DATA_MODEL.md §3.11). */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `quiet_gap` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
                        "`endMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`kind` TEXT, `meanDbfs` REAL, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sleep_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_quiet_gap_sessionId_startMs` " +
                        "ON `quiet_gap` (`sessionId`, `startMs`)"
                )
            }
        }

        /** v9 adds the vendor sleep-stage timeline (docs/DATA_MODEL.md §3.10). */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_stage` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
                        "`endMs` INTEGER NOT NULL, `stage` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sleep_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sleep_stage_sessionId_startMs` " +
                        "ON `sleep_stage` (`sessionId`, `startMs`)"
                )
            }
        }

        /**
         * v11 stores per-clip applied gain + file peak (docs/DATA_MODEL.md §3.5): clips are now
         * written from RAW ring samples with a float mic gain and a −1 dBFS block peak-limit,
         * so the row can say what was actually stored. `appliedGain` defaults to 2.5 — the
         * fixed in-place int16 mic gain every pre-v11 clip was written with (truthful history);
         * `filePeakDbfs` stays NULL there (those clips' peaks were never recorded).
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `audio_clip` ADD COLUMN `appliedGain` REAL NOT NULL DEFAULT 2.5")
                db.execSQL("ALTER TABLE `audio_clip` ADD COLUMN `filePeakDbfs` REAL")
            }
        }

        /** All schema migrations in order — shared with the instrumented migration test. */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            MIGRATION_10_11,
        )
    }
}
