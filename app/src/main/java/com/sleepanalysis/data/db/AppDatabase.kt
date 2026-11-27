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
import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.dao.ModelRegistryDao
import com.sleepanalysis.data.db.dao.QuietGapDao
import com.sleepanalysis.data.db.dao.RawPayloadDao
import com.sleepanalysis.data.db.dao.ReportDao
import com.sleepanalysis.data.db.dao.SleepSessionDao
import com.sleepanalysis.data.db.dao.SleepStageDao
import com.sleepanalysis.data.db.dao.UserFactorDao
import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.db.entity.ModelRegistryEntity
import com.sleepanalysis.data.db.entity.QuietGapEntity
import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.ReportSnapshotEntity
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
        RawPayloadEntity::class,
        UserFactorEntity::class,
        SleepStageEntity::class,
        QuietGapEntity::class,
        ReportSnapshotEntity::class,
        ModelRegistryEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun acousticEventDao(): AcousticEventDao
    abstract fun audioClipDao(): AudioClipDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun rawPayloadDao(): RawPayloadDao
    abstract fun userFactorDao(): UserFactorDao
    abstract fun sleepStageDao(): SleepStageDao
    abstract fun quietGapDao(): QuietGapDao
    abstract fun reportDao(): ReportDao
    abstract fun modelRegistryDao(): ModelRegistryDao

    /**
     * Removes every row (docs/PRIVACY_AND_REGULATORY.md §4). One transaction, so a rollback
     * undoes the whole purge.
     */
    suspend fun purgeEverythingIncludingClinical() = withContext(Dispatchers.IO) {
        withTransaction {
            measurementDao().deleteAll()
            audioClipDao().deleteAll()
            acousticEventDao().deleteAll()
            sleepSessionDao().deleteAll()
            rawPayloadDao().deleteAll()
            userFactorDao().deleteAll()
            sleepStageDao().deleteAll()
            quietGapDao().deleteAll()
            reportDao().deleteAll()
            modelRegistryDao().deleteAll()
        }
    }

    companion object {
        const val NAME = "sleepanalysis.db"

        fun build(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
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

        /**
         * v12 adds the report snapshot table (docs/DATA_MODEL.md §3.7) and the temporal
         * model-version registry (§3.8): finalize now stores each night's headline JSON plus
         * the detector/score versions that produced it, giving the personal-baseline gates a
         * registry-backed comparability record. Both start empty — snapshots are written at
         * each new finalize, never back-filled.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `report` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, `generatedAt` INTEGER NOT NULL, " +
                        "`appVersion` TEXT NOT NULL, `detectorVersion` TEXT, `scoreVersion` TEXT, " +
                        "`payload` TEXT NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sleep_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_report_sessionId` ON `report` (`sessionId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `model_registry` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `version` TEXT NOT NULL, " +
                        "`validFromUtc` INTEGER NOT NULL, `validToUtc` INTEGER)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_model_registry_kind_validFromUtc` " +
                        "ON `model_registry` (`kind`, `validFromUtc`)"
                )
            }
        }

        /**
         * v13 adds the N-of-1 experiment table (docs/DATA_MODEL.md §3.12): one playbook action
         * tried for a planned number of nights against the baseline band captured at start.
         * Starts empty — rows are written when the user starts an experiment, never back-filled.
         * Not session-bound (experiments span nights and outlive them): no FK, no CASCADE.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_experiment` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`hypothesisRuleId` TEXT, `playbookActionId` TEXT, " +
                        "`metric` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                        "`plannedNights` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`baselineStatsJson` TEXT NOT NULL, `resultJson` TEXT, " +
                        "`confirmPhaseJson` TEXT, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sleep_experiment_status` " +
                        "ON `sleep_experiment` (`status`)"
                )
            }
        }

        /**
         * v14 stamps each stage row's provenance (docs/DATA_MODEL.md §3.10): the Health Connect
         * import writes WEARABLE timelines alongside the GARMIN ones, so deletes/replaces are
         * source-scoped. Every pre-v14 row is Garmin-derived (estimate) — DEFAULT 'GARMIN'.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sleep_stage ADD COLUMN source TEXT NOT NULL DEFAULT 'GARMIN'")
            }
        }

        /**
         * v15 retires the N-of-1 experiment vertical (consolidation T6): the experiments,
         * playbook and escalation features are removed, so their table goes with them. A pure
         * DROP — any stored experiment rows are lost (owner decision D1). The report snapshot
         * and model_registry tables are untouched.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `sleep_experiment`")
            }
        }

        /**
         * v16 is the consolidation T7 cut (owner-approved loss of stored PSG study rows):
         * (1) the clinical_baseline table goes — its screen and repository are gone and the
         * morning report no longer renders a clinical section — and with it the two
         * immutability triggers (INV-03 retires); (2) write-only columns go: sleep_session
         * {deviceModel, osVersion, appVersion, audioBytesTotal}, acoustic_event {labeledAt,
         * createdAt}, quiet_gap {createdAt}, user_factor {recordedAt}. Table rebuild (create
         * new → copy → drop → rename) because SQLite's ALTER DROP COLUMN would leave the
         * schema not matching Room's expectations for NOT NULL/defaults history. Row data
         * of surviving columns is preserved.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TRIGGER IF EXISTS trg_clinical_baseline_no_update")
                db.execSQL("DROP TRIGGER IF EXISTS trg_clinical_baseline_no_delete")
                db.execSQL("DROP TABLE IF EXISTS `clinical_baseline`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_session_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `status` TEXT NOT NULL, " +
                        "`timeZone` TEXT NOT NULL, `captureStats` TEXT, `morningNotes` TEXT, " +
                        "`resumedAfterInterruption` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `sleep_session_new` (" +
                        "`id`,`startedAt`,`endedAt`,`status`,`timeZone`,`captureStats`," +
                        "`morningNotes`,`resumedAfterInterruption`,`createdAt`) SELECT " +
                        "`id`,`startedAt`,`endedAt`,`status`,`timeZone`,`captureStats`," +
                        "`morningNotes`,`resumedAfterInterruption`,`createdAt` FROM `sleep_session`"
                )
                db.execSQL("DROP TABLE `sleep_session`")
                db.execSQL("ALTER TABLE `sleep_session_new` RENAME TO `sleep_session`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `acoustic_event_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, " +
                        "`offsetMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`peakDbfs` REAL NOT NULL, `meanDbfs` REAL NOT NULL, " +
                        "`intensityTier` TEXT NOT NULL, `confidence` REAL NOT NULL, " +
                        "`modelVersion` TEXT NOT NULL, `clipId` INTEGER, `userLabel` TEXT, " +
                        "`featuresJson` TEXT, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sleep_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `acoustic_event_new` (" +
                        "`id`,`sessionId`,`type`,`startMs`,`endMs`,`offsetMs`,`durationMs`," +
                        "`peakDbfs`,`meanDbfs`,`intensityTier`,`confidence`,`modelVersion`," +
                        "`clipId`,`userLabel`,`featuresJson`) SELECT " +
                        "`id`,`sessionId`,`type`,`startMs`,`endMs`,`offsetMs`,`durationMs`," +
                        "`peakDbfs`,`meanDbfs`,`intensityTier`,`confidence`,`modelVersion`," +
                        "`clipId`,`userLabel`,`featuresJson` FROM `acoustic_event`"
                )
                db.execSQL("DROP TABLE `acoustic_event`")
                db.execSQL("ALTER TABLE `acoustic_event_new` RENAME TO `acoustic_event`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_acoustic_event_sessionId_startMs` " +
                        "ON `acoustic_event` (`sessionId`, `startMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_acoustic_event_type` ON `acoustic_event` (`type`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `quiet_gap_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
                        "`endMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`kind` TEXT, `meanDbfs` REAL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sleep_session`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `quiet_gap_new` (" +
                        "`id`,`sessionId`,`startMs`,`endMs`,`durationMs`,`kind`,`meanDbfs`) SELECT " +
                        "`id`,`sessionId`,`startMs`,`endMs`,`durationMs`,`kind`,`meanDbfs` FROM `quiet_gap`"
                )
                db.execSQL("DROP TABLE `quiet_gap`")
                db.execSQL("ALTER TABLE `quiet_gap_new` RENAME TO `quiet_gap`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_quiet_gap_sessionId_startMs` " +
                        "ON `quiet_gap` (`sessionId`, `startMs`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_factor_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, `factorKey` TEXT NOT NULL, " +
                        "`valueNum` REAL, `valueText` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO `user_factor_new` (" +
                        "`id`,`date`,`factorKey`,`valueNum`,`valueText`) SELECT " +
                        "`id`,`date`,`factorKey`,`valueNum`,`valueText` FROM `user_factor`"
                )
                db.execSQL("DROP TABLE `user_factor`")
                db.execSQL("ALTER TABLE `user_factor_new` RENAME TO `user_factor`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_factor_date` ON `user_factor` (`date`)"
                )
            }
        }

        /** All schema migrations in order — shared with the instrumented migration test. */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
            MIGRATION_15_16,
        )
    }
}
