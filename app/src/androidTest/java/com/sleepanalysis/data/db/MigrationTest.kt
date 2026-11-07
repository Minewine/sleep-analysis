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
 * `AppDatabase.build()` the app uses. Rows must survive; the clinical-immutability triggers
 * must exist (they are (re)created on every open) and must actually enforce.
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
    fun migrate1ToLatest_preservesRows_andRecreatesImmutabilityTriggers() {
        // 1. Seed at v1 (11-column sleep_session — morningNotes/resumedAfterInterruption only
        //    arrive in v6/v7; measurement v1 has full provenance; clinical_baseline is nullable
        //    except the required study fields).
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
            db.execSQL(
                "INSERT INTO clinical_baseline (studyDate, lab, studyType, enteredAt) " +
                    "VALUES ('2026-01-12', 'HUG', 'PSG', 1700000000000)"
            )
        }

        // 2. Migrate 1 → latest, validating every step against the exported schemas.
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *AppDatabase.MIGRATIONS).close()

        // 3. Reopen the SAME file through the app's own builder (AppDatabase.build registers
        //    the onOpen callback that (re)creates the immutability triggers — a raw
        //    Room.databaseBuilder here would test a database the app never runs).
        val db = AppDatabase.build(context, TEST_DB)
        try {
            val sql = db.openHelper.writableDatabase

            // Rows survive: session (with the v8 default applied to the v1-seeded row),
            // measurement, and the clinical baseline.
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
            sql.query("SELECT COUNT(*) FROM clinical_baseline").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }

            // Triggers exist (recreated on open) and enforce immutability.
            sql.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { c ->
                val found = buildList { while (c.moveToNext()) add(c.getString(0)) }
                assertTrue(
                    "expected both immutability triggers, found: $found",
                    found.containsAll(listOf(AppDatabase.TRIGGER_UPDATE, AppDatabase.TRIGGER_DELETE)),
                )
            }
            try {
                sql.execSQL("UPDATE clinical_baseline SET lab = 'FORGED' WHERE id = 1")
                fail("clinical_baseline UPDATE must be rejected by ${AppDatabase.TRIGGER_UPDATE}")
            } catch (_: android.database.SQLException) {
                // expected — the trigger raised ABORT
            }
            try {
                sql.execSQL("DELETE FROM clinical_baseline WHERE id = 1")
                fail("clinical_baseline DELETE must be rejected by ${AppDatabase.TRIGGER_DELETE}")
            } catch (_: android.database.SQLException) {
                // expected — the trigger raised ABORT
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
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val LATEST_VERSION = 11
    }
}
