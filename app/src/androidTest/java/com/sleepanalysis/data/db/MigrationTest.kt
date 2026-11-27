package com.sleepanalysis.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end migration check (docs/DATA_MODEL.md §7): seeds real rows into a **v1** database,
 * runs every migration 1→latest with Room's schema validation against the exported schemas
 * (`app/schemas`, wired as androidTest assets), then reopens through the same
 * `AppDatabase.build()` the app uses. Rows of surviving columns must survive; the T7 cuts
 * (clinical_baseline table + immutability triggers, write-only column bundle) must be real.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @After
    fun cleanUp() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate1ToLatest_preservesRows_dropsClinicalBaselineAndWriteOnlyColumns() {
        // 1. Seed at v1 (11-column sleep_session — morningNotes/resumedAfterInterruption only
        //    arrive in v6/v7; measurement v1 has full provenance; clinical_baseline still exists).
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO sleep_session " +
                    "(startedAt, endedAt, status, timeZone, deviceModel, osVersion, appVersion, " +
                    "captureStats, audioBytesTotal, createdAt) VALUES (" +
                    "1793653161000, 1793681421000, 'COMPLETED', 'Europe/Zurich', 'TestDevice', '15', " +
                    "'0.1.0-phase1', '{\"schema_version\":1}', 123456, 1793681500000)"
            )
            db.execSQL(
                "INSERT INTO measurement " +
                    "(sessionId, timestamp, sourceType, method, isClinical, measurementType, tier, " +
                    "unit, valueNum, createdAt) VALUES (" +
                    "1, 1793653200000, 'PHONE', 'ALGORITHM_ESTIMATED', 0, 'SNORE_TIME', " +
                    "'ESTABLISHED', 'SECONDS', 812.0, 1793681500000)"
            )
        }

        // 2. Migrate 1 → latest, validating every step against the exported schemas.
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *AppDatabase.MIGRATIONS).close()

        // 3. Reopen the SAME file through the app's own builder — the builder the app runs.
        val db = AppDatabase.build(context, TEST_DB)
        try {
            val sql = db.openHelper.writableDatabase

            // Rows survive: session (with the v8 default applied to the v1-seeded row)
            // and measurement.
            sql.query("SELECT status, timeZone, resumedAfterInterruption FROM sleep_session").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("COMPLETED", c.getString(0))
                assertEquals("Europe/Zurich", c.getString(1))
                assertEquals(0, c.getInt(2)) // defaultValue "0" on the v1 row
                assertTrue(c.isLast) // exactly one row
            }
            sql.query("SELECT valueNum FROM measurement WHERE measurementType = 'SNORE_TIME'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(812.0, c.getDouble(0), 0.0001)
            }
            // T7 cuts: clinical_baseline and its immutability triggers are gone.
            sql.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN " +
                    "('clinical_baseline', 'trg_clinical_baseline_no_update', " +
                    "'trg_clinical_baseline_no_delete')"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }

            // v9 sleep_stage round-trip through the app's own builder (table exists, writable,
            // and the FK CASCADE that removes stages with their session is in force).
            sql.execSQL(
                "INSERT INTO sleep_stage (sessionId, startMs, endMs, stage, createdAt) VALUES " +
                    "(1, 1793657000000, 1793657120000, 'DEEP', 1793681500000)"
            )
            sql.query("SELECT stage FROM sleep_stage WHERE sessionId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("DEEP", c.getString(0))
            }
            sql.query("SELECT COUNT(*) FROM sleep_stage").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            // v10 quiet_gap round-trip + CASCADE with its session.
            sql.execSQL(
                "INSERT INTO quiet_gap (sessionId, startMs, endMs, durationMs, kind, meanDbfs, createdAt) VALUES " +
                    "(1, 1793658000000, 1793658720000, 720000, 'QUIET', -83.5, 1793681500000)"
            )
            sql.query("SELECT kind, meanDbfs FROM quiet_gap WHERE sessionId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("QUIET", c.getString(0))
                assertEquals(-83.5, c.getDouble(1), 0.0001)
            }
            // v11 audio_clip round-trip: the new gain/peak columns persist, a legacy-style
            // insert (no new columns) backfills the fixed 2.5 int16 mic gain (truthful
            // history) with a NULL peak, and the FK CASCADE removes clips with their session.
            sql.execSQL(
                "INSERT INTO audio_clip (sessionId, filePath, durationMs, sizeBytes, sampleRate, createdAt, appliedGain, filePeakDbfs) VALUES " +
                    "(1, 'session_1/evt_snore_1793657000000_1.wav', 8000, 256044, 16000, 1793681500000, 1.4, -1.0)"
            )
            sql.execSQL(
                "INSERT INTO audio_clip (sessionId, filePath, durationMs, sizeBytes, sampleRate, createdAt) VALUES " +
                    "(1, 'session_1/evt_snore_1793657000000_2.wav', 8000, 256044, 16000, 1793681500000)"
            )
            sql.query(
                "SELECT appliedGain, filePeakDbfs FROM audio_clip " +
                    "WHERE filePath = 'session_1/evt_snore_1793657000000_1.wav'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1.4, c.getDouble(0), 0.0001)
                assertEquals(-1.0, c.getDouble(1), 0.0001)
            }
            sql.query(
                "SELECT appliedGain, filePeakDbfs FROM audio_clip " +
                    "WHERE filePath = 'session_1/evt_snore_1793657000000_2.wav'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(2.5, c.getDouble(0), 0.0001) // v11 DEFAULT backfill
                assertTrue(c.isNull(1))
            }
            // v12 round-trip: the report snapshot and the temporal model registry are writable,
            // the snapshot's unique per-session index enforces one row per session, and both
            // follow the FK CASCADE (snapshot) / stay registry-side rows.
            sql.execSQL(
                "INSERT INTO report (sessionId, generatedAt, appVersion, detectorVersion, scoreVersion, payload) VALUES " +
                    "(1, 1793681500000, '0.1.0-phase1', 'energy-heuristic-v2-burst', 'snore-score-v3', " +
                    "'{\"schema_version\":1,\"validity_grade\":\"B\",\"snore_seconds\":812}')"
            )
            sql.query(
                "SELECT detectorVersion, scoreVersion, payload FROM report WHERE sessionId = 1"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("energy-heuristic-v2-burst", c.getString(0))
                assertEquals("snore-score-v3", c.getString(1))
                assertTrue(c.getString(2).contains("\"validity_grade\":\"B\""))
            }
            sql.execSQL(
                "INSERT INTO model_registry (kind, version, validFromUtc, validToUtc) VALUES " +
                    "('DETECTOR', 'energy-heuristic-v1-spectral', 1792000000000, 1793000000000)"
            )
            sql.execSQL(
                "INSERT INTO model_registry (kind, version, validFromUtc, validToUtc) VALUES " +
                    "('DETECTOR', 'energy-heuristic-v2-burst', 1793000000000, NULL)"
            )
            sql.query(
                "SELECT COUNT(*) FROM model_registry WHERE kind = 'DETECTOR' AND validToUtc IS NULL"
            ).use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0)) // exactly one open row per kind
            }
            sql.execSQL("DELETE FROM sleep_session WHERE id = 1")
            sql.query("SELECT COUNT(*) FROM sleep_stage").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0)) // CASCADE removed the stage with its session
            }
            sql.query("SELECT COUNT(*) FROM quiet_gap").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0)) // …and the quiet gap
            }
            sql.query("SELECT COUNT(*) FROM audio_clip").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0)) // …and the clips
            }
            sql.query("SELECT COUNT(*) FROM report").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0)) // …and the report snapshot (v12 CASCADE)
            }
            sql.query("SELECT COUNT(*) FROM model_registry").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0)) // registry rows are version history — NOT session-bound
            }
            // v15 dropped sleep_experiment (consolidation T6): the table must be gone after
            // the full 1→latest chain, while every surviving table above still round-trips.
            sql.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'sleep_experiment'"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate13To14_addsStageSource_defaultingLegacyRowsToGarmin() {
        // 1. Seed at v13: a session and a sleep_stage row in the v13 column list — no source
        //    column yet (it arrives with v14).
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO sleep_session " +
                    "(startedAt, endedAt, status, timeZone, deviceModel, osVersion, appVersion, " +
                    "captureStats, audioBytesTotal, createdAt) VALUES (" +
                    "1793653161000, 1793681421000, 'COMPLETED', 'Europe/Zurich', 'TestDevice', '15', " +
                    "'0.1.0-phase1', '{\"schema_version\":1}', 123456, 1793681500000)"
            )
            db.execSQL(
                "INSERT INTO sleep_stage (sessionId, startMs, endMs, stage, createdAt) VALUES " +
                    "(1, 1793657000000, 1793657120000, 'DEEP', 1793681500000)"
            )
        }

        // 2. Migrate 13 → 14, validating the ALTER against the exported schemas.
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *AppDatabase.MIGRATIONS).close()

        // 3. Reopen the SAME file through the app's own builder (see migrate1ToLatest).
        val db = AppDatabase.build(context, TEST_DB)
        try {
            val sql = db.openHelper.writableDatabase

            // The pre-v14 row reads back with the migration's DEFAULT 'GARMIN' (all pre-v14
            // rows were Garmin-derived — truthful history, mirroring the v11 gain backfill).
            sql.query("SELECT stage, source FROM sleep_stage WHERE sessionId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("DEEP", c.getString(0))
                assertEquals("GARMIN", c.getString(1))
            }
            // A fresh v13-style insert (no source) defaults likewise.
            sql.execSQL(
                "INSERT INTO sleep_stage (sessionId, startMs, endMs, stage, createdAt) VALUES " +
                    "(1, 1793657120000, 1793657240000, 'LIGHT', 1793681500000)"
            )
            sql.query("SELECT source FROM sleep_stage WHERE stage = 'LIGHT'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("GARMIN", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate14To15_dropsSleepExperiment() {
        // 1. Seed at v14: a session plus a sleep_experiment row (the vertical's storage —
        //    docs/DATA_MODEL.md §3.12). Stored experiment rows are lost (owner decision D1).
        helper.createDatabase(TEST_DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO sleep_session " +
                    "(startedAt, endedAt, status, timeZone, deviceModel, osVersion, appVersion, " +
                    "captureStats, audioBytesTotal, createdAt) VALUES (" +
                    "1793653161000, 1793681421000, 'COMPLETED', 'Europe/Zurich', 'TestDevice', '15', " +
                    "'0.1.0-phase1', '{\"schema_version\":1}', 123456, 1793681500000)"
            )
            db.execSQL(
                "INSERT INTO sleep_experiment (hypothesisRuleId, playbookActionId, metric, startedAt, " +
                    "plannedNights, status, baselineStatsJson, resultJson, confirmPhaseJson, createdAt) VALUES " +
                    "(NULL, 'fixed_wake', 'snore_share', 1793000000000, 7, 'ACTIVE', " +
                    "'{\"metric\":\"snore_share\",\"median\":0.1,\"mad\":0.02,\"low\":0.04,\"high\":0.16}', " +
                    "NULL, NULL, 1793000000000)"
            )
        }

        // 2. Migrate 14 → 15, validating the DROP against the exported schemas.
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *AppDatabase.MIGRATIONS).close()

        // 3. Reopen the SAME file through the app's own builder (see migrate1ToLatest).
        val db = AppDatabase.build(context, TEST_DB)
        try {
            val sql = db.openHelper.writableDatabase

            // The experiment table is gone; everything else survives intact.
            sql.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'sleep_experiment'"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            sql.query("SELECT COUNT(*) FROM sleep_session").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            sql.query("SELECT COUNT(*) FROM report").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0)) // snapshot table untouched by v15 (still empty here)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate15To16_dropsBaselineAndWriteOnlyColumns_preservingData() {
        // 1. Seed at v15: a session, an event, a gap, factor rows and a baseline row, all in
        //    the v15 column lists (write-only columns still present). The baseline row and the
        //    write-only column values are lost in 15→16 (owner-approved, consolidation T7).
        helper.createDatabase(TEST_DB, 15).use { db ->
            db.execSQL(
                "CREATE TRIGGER trg_clinical_baseline_no_update BEFORE UPDATE ON clinical_baseline " +
                    "BEGIN SELECT RAISE(ABORT, 'clinical baseline is immutable'); END"
            )
            db.execSQL(
                "INSERT INTO sleep_session " +
                    "(startedAt, endedAt, status, timeZone, deviceModel, osVersion, appVersion, " +
                    "captureStats, audioBytesTotal, morningNotes, resumedAfterInterruption, createdAt) VALUES (" +
                    "1793653161000, 1793681421000, 'COMPLETED', 'Europe/Zurich', 'TestDevice', '15', " +
                    "'0.1.0-phase1', '{\"schema_version\":1}', 123456, 'note', 0, 1793681500000)"
            )
            db.execSQL(
                "INSERT INTO acoustic_event " +
                    "(sessionId, type, startMs, endMs, offsetMs, durationMs, peakDbfs, meanDbfs, " +
                    "intensityTier, confidence, modelVersion, userLabel, labeledAt, createdAt) VALUES (" +
                    "1, 'SNORE', 1793657000000, 1793657120000, 38389000, 120000, -15.0, -20.0, " +
                    "'LOUD', 0.9, 'energy-heuristic-v3-peakgate', 'SNORE', 1793680000000, 1793657120000)"
            )
            db.execSQL(
                "INSERT INTO quiet_gap " +
                    "(sessionId, startMs, endMs, durationMs, kind, meanDbfs, createdAt) VALUES (" +
                    "1, 1793658000000, 1793658720000, 720000, 'QUIET', -83.5, 1793681500000)"
            )
            db.execSQL(
                "INSERT INTO user_factor (date, factorKey, valueNum, valueText, recordedAt) VALUES " +
                    "('2026-08-01', 'ALCOHOL', 2.0, NULL, 1793600000000)"
            )
            db.execSQL(
                "INSERT INTO clinical_baseline (studyDate, lab, studyType, enteredAt) " +
                    "VALUES ('2026-01-12', 'HUG', 'PSG', 1700000000000)"
            )
        }

        // 2. Migrate 15 → 16, validating the rebuilds against the exported schemas.
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *AppDatabase.MIGRATIONS).close()

        // 3. Reopen the SAME file through the app's own builder (see migrate1ToLatest).
        val db = AppDatabase.build(context, TEST_DB)
        try {
            val sql = db.openHelper.writableDatabase

            // clinical_baseline and its trigger are gone.
            sql.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE name IN " +
                    "('clinical_baseline', 'trg_clinical_baseline_no_update')"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            // Surviving data round-trips; the write-only columns are gone.
            sql.query(
                "SELECT status, morningNotes, resumedAfterInterruption, createdAt FROM sleep_session"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("COMPLETED", c.getString(0))
                assertEquals("note", c.getString(1))
                assertEquals(0, c.getInt(2))
                assertEquals(1793681500000, c.getLong(3))
            }
            sql.query(
                "SELECT type, userLabel, peakDbfs FROM acoustic_event WHERE sessionId = 1"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("SNORE", c.getString(0))
                assertEquals("SNORE", c.getString(1))
                assertEquals(-15.0, c.getDouble(2), 0.0001)
            }
            sql.query("SELECT kind, meanDbfs FROM quiet_gap WHERE sessionId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("QUIET", c.getString(0))
                assertEquals(-83.5, c.getDouble(1), 0.0001)
            }
            sql.query("SELECT date, factorKey, valueNum FROM user_factor").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("2026-08-01", c.getString(0))
                assertEquals("ALCOHOL", c.getString(1))
                assertEquals(2.0, c.getDouble(2), 0.0001)
            }
            // Fresh writes still work on the rebuilt tables.
            sql.execSQL(
                "INSERT INTO user_factor (date, factorKey, valueText) VALUES " +
                    "('2026-08-02', 'CAFFEINE', 'late coffee')"
            )
            sql.query("SELECT COUNT(*) FROM user_factor").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val LATEST_VERSION = 16
    }
}
