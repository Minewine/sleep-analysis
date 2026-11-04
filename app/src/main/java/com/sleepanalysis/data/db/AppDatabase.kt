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
import com.sleepanalysis.data.db.dao.RawPayloadDao
import com.sleepanalysis.data.db.dao.SleepSessionDao
import com.sleepanalysis.data.db.dao.UserFactorDao
import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.ClinicalBaselineEntity
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.SleepSessionEntity
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
    ],
    version = 7,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
    }
}
