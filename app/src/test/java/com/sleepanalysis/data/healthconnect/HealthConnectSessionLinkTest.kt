package com.sleepanalysis.data.healthconnect

import androidx.health.connect.client.records.SleepSessionRecord
// Alias: the simple name collides with kotlin's @Metadata annotation in K2 resolution.
import androidx.health.connect.client.records.metadata.Metadata as HcMetadata
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of the Health Connect import: window filtering, row building, summary JSON. */
class HealthConnectSessionLinkTest {

    private fun stage(type: Int, startMs: Long, durationMs: Long) = SleepSessionRecord.Stage(
        startTime = Instant.ofEpochMilli(startMs),
        endTime = Instant.ofEpochMilli(startMs + durationMs),
        stage = type,
    )

    private fun record(startMs: Long, endMs: Long, stages: List<SleepSessionRecord.Stage>) =
        SleepSessionRecord(
            startTime = Instant.ofEpochMilli(startMs),
            startZoneOffset = null,
            endTime = Instant.ofEpochMilli(endMs),
            endZoneOffset = null,
            // Metadata's ctor is internal in connect-client 1.1.0 — the companion factory is the public path.
            metadata = HcMetadata.unknownRecordingMethod(),
            stages = stages,
        )

    @Test
    fun `records outside the day window are dropped`() {
        val inWindow = record(1_000_000, 2_000_000, listOf(stage(SleepSessionRecord.STAGE_TYPE_DEEP, 1_000_000, 100_000)))
        val endsBefore = record(-2_000_000, -1_000_000, emptyList())
        val startsAfter = record(5_000_000, 6_000_000, emptyList())
        val mapped = HealthConnectImportManager.buildStageRows(
            sessionId = 7,
            records = listOf(startsAfter, endsBefore, inWindow),
            window = 0L to 3_000_000L,
        )
        assertEquals(1, mapped.rows.size)
        assertEquals(1_000_000, mapped.rows[0].startMs)
    }

    @Test
    fun `partially overlapping records are kept`() {
        // Starts before the window, ends inside it — the overlap is what qualifies.
        val spilling = record(-1_000_000, 1_000_000, listOf(stage(SleepSessionRecord.STAGE_TYPE_LIGHT, -500_000, 1_000_000)))
        val mapped = HealthConnectImportManager.buildStageRows(7, listOf(spilling), 0L to 3_000_000L)
        assertEquals(1, mapped.rows.size)
    }

    @Test
    fun `rows are ordered by start time and carry the wearable source`() {
        val rec = record(
            0,
            3_000_000,
            listOf(
                stage(SleepSessionRecord.STAGE_TYPE_LIGHT, 2_000_000, 500_000),
                stage(SleepSessionRecord.STAGE_TYPE_DEEP, 0, 1_000_000),
                stage(SleepSessionRecord.STAGE_TYPE_REM, 1_000_000, 1_000_000),
            ),
        )
        val mapped = HealthConnectImportManager.buildStageRows(7, listOf(rec), 0L to 3_000_000L)
        assertEquals(listOf(0L, 1_000_000L, 2_000_000L), mapped.rows.map { it.startMs })
        assertTrue(
            mapped.rows.all {
                it.source == HealthConnectImportManager.STAGE_SOURCE && it.sessionId == 7L
            },
        )
        assertEquals(listOf("DEEP", "REM", "LIGHT"), mapped.rows.map { it.stage })
    }

    @Test
    fun `skipped stage types are accounted per type never silently lost`() {
        val rec = record(
            0,
            4_000_000,
            listOf(
                stage(SleepSessionRecord.STAGE_TYPE_DEEP, 0, 600_000), // stored
                stage(SleepSessionRecord.STAGE_TYPE_UNKNOWN, 600_000, 120_000), // 2 min skipped
                stage(SleepSessionRecord.STAGE_TYPE_UNKNOWN, 720_000, 60_000), // 1 min skipped
                stage(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, 780_000, 180_000), // 3 min skipped
            ),
        )
        val mapped = HealthConnectImportManager.buildStageRows(7, listOf(rec), 0L to 5_000_000L)
        assertEquals(1, mapped.rows.size)
        assertEquals(6L, mapped.skippedMinutes)
        assertEquals(mapOf("OUT_OF_BED" to 3L, "UNKNOWN" to 3L), mapped.skippedMinutesByType)
    }

    @Test
    fun `an empty timeline maps to no rows and no skipped minutes`() {
        val mapped = HealthConnectImportManager.buildStageRows(7, emptyList(), 0L to 3_000_000L)
        assertTrue(mapped.rows.isEmpty())
        assertEquals(0L, mapped.skippedMinutes)
        assertTrue(mapped.skippedMinutesByType.isEmpty())
    }

    @Test
    fun `zero and negative length spans are never stored as rows`() {
        // connect-client 1.1.0 rejects such spans already in Stage's ctor ("startTime must be
        // before endTime"), so the guard is defense-in-depth — driven here through the pure
        // span mapper with raw epoch millis, no malformed Stage construction.
        val zero = HealthConnectImportManager.mapStageSpan(7, SleepSessionRecord.STAGE_TYPE_LIGHT, 2_000_000, 2_000_000)
        val negative = HealthConnectImportManager.mapStageSpan(7, SleepSessionRecord.STAGE_TYPE_REM, 3_000_000, 2_600_000)
        assertNull(zero.row)
        assertNull(negative.row)
        // Floored at zero: a malformed span cannot invent minutes to skip — the hygiene is
        // about keeping junk rows out, the summary stays truthful at 0.
        assertEquals(0L, zero.skippedMinutes)
        assertEquals(0L, negative.skippedMinutes)
        assertNull(zero.skippedTypeKey)
        assertNull(negative.skippedTypeKey)
        // The guard is span-length-only: a mappable type with a valid span still stores.
        val valid = HealthConnectImportManager.mapStageSpan(7, SleepSessionRecord.STAGE_TYPE_DEEP, 1_000_000, 1_500_000)
        assertEquals("DEEP", valid.row!!.stage)
        assertEquals(HealthConnectImportManager.STAGE_SOURCE, valid.row!!.source)
    }

    @Test
    fun `summary json carries counts skips session and window`() {
        val mapped = HealthConnectImportManager.MappedStages(
            rows = listOf(
                com.sleepanalysis.data.db.entity.SleepStageEntity(sessionId = 7, startMs = 0, endMs = 600_000, stage = "DEEP"),
            ),
            skippedMinutes = 3,
            skippedMinutesByType = mapOf("UNKNOWN" to 3),
        )
        val json = JSONObject(
            HealthConnectImportManager.summaryJson(
                recordsRead = 2, mapped = mapped, linkedSessionId = 7, window = 0L to 86_400_000L,
            ),
        )
        assertEquals(1, json.getInt("schema_version"))
        assertEquals(2, json.getInt("records_read"))
        assertEquals(1, json.getInt("stages_stored"))
        assertEquals(3L, json.getLong("skipped_minutes"))
        assertEquals(3L, json.getJSONObject("skipped_minutes_by_type").getLong("UNKNOWN"))
        assertEquals(7L, json.getLong("linked_session_id"))
        assertEquals(0L, json.getLong("window_start_ms"))
        assertEquals(86_400_000L, json.getLong("window_end_ms"))
    }
}
