package com.sleepanalysis.data.healthconnect

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
// Alias: the simple name collides with kotlin's @Metadata annotation in K2 resolution.
import androidx.health.connect.client.records.metadata.Metadata as HcMetadata
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.garmin.GarminImportManager
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of the Health Connect import: record-span linking, row building, summary JSON. */
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

    private fun session(id: Long, start: Long, end: Long) = SleepSessionEntity(
        id = id,
        startedAt = start,
        endedAt = end,
        status = SessionStatus.COMPLETED,
        timeZone = "Europe/Zurich",
        
        
        
    )

    @Test
    fun `the import reads only Garmin Connect records`() {
        // Health Connect aggregates every sleep app on the device; without the origin filter
        // a second writer's sessions would merge into the WEARABLE timeline as overlapping
        // spans. connect-client 1.1.0 cannot build a Metadata with a custom DataOrigin on the
        // JVM, so the filter set is pinned here and applied at the thin ReadRecordsRequest
        // call site (instrumented-only).
        assertEquals(
            setOf(DataOrigin("com.garmin.android.apps.connectmobile")),
            HealthConnectImportManager.garminConnectDataOrigins(),
        )
        assertEquals(
            "com.garmin.android.apps.connectmobile",
            HealthConnectImportManager.GARMIN_CONNECT_PACKAGE,
        )
    }

    @Test
    fun `a record links by its own span not the import date's day window`() {
        // On-device regression (2026-11-17): importing 2026-11-14 linked the Nov 13→14
        // session, because the DAY window overlaps that night's ~7h morning tail harder
        // than the record's own night — the record span must link instead. The wrong
        // timeline then rendered on the wrong night's report.
        val t0 = 0L // read as 2026-11-14 00:00 for readable offsets
        val prevNight = session(16, start = t0 - 1_700_000, end = t0 + 25_400_000) // 13th 22:32 → 14th 07:04
        val thisNight = session(17, start = t0 + 82_000_000, end = t0 + 112_500_000) // 14th 22:47 → 15th 07:32
        val recordSpan = t0 + 83_000_000 to t0 + 112_300_000 // HC record 23:04 → 07:31

        val byRecordSpan = GarminImportManager.bestSessionOverlapping(listOf(prevNight, thisNight), recordSpan)
        assertEquals(17L, byRecordSpan?.id)
        // Documents the bug the fix removes: the day window picks the previous night.
        val byDayWindow = GarminImportManager.bestSessionOverlapping(listOf(prevNight, thisNight), t0 to t0 + 86_400_000L)
        assertEquals(16L, byDayWindow?.id)
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
        val mapped = HealthConnectImportManager.buildStageRows(7, listOf(rec))
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
        val mapped = HealthConnectImportManager.buildStageRows(7, listOf(rec))
        assertEquals(1, mapped.rows.size)
        assertEquals(6L, mapped.skippedMinutes)
        assertEquals(mapOf("OUT_OF_BED" to 3L, "UNKNOWN" to 3L), mapped.skippedMinutesByType)
    }

    @Test
    fun `an empty timeline maps to no rows and no skipped minutes`() {
        val mapped = HealthConnectImportManager.buildStageRows(7, emptyList())
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
        val json = JSONObject(
            HealthConnectImportManager.summaryJson(
                recordsRead = 2,
                stagesStored = 1,
                skippedMinutes = 3,
                skippedMinutesByType = mapOf("UNKNOWN" to 3),
                recordsWithoutSession = 1,
                linkedSessionId = 7,
                window = 0L to 86_400_000L,
            ),
        )
        assertEquals(1, json.getInt("schema_version"))
        assertEquals(2, json.getInt("records_read"))
        assertEquals(1, json.getInt("stages_stored"))
        assertEquals(3L, json.getLong("skipped_minutes"))
        assertEquals(3L, json.getJSONObject("skipped_minutes_by_type").getLong("UNKNOWN"))
        assertEquals(1, json.getInt("records_without_session"))
        assertEquals(7L, json.getLong("linked_session_id"))
        assertEquals(0L, json.getLong("window_start_ms"))
        assertEquals(86_400_000L, json.getLong("window_end_ms"))
    }

    @Test
    fun `summary json omits linked session when nothing linked`() {
        // Defensive shape: the manager never writes a summary without a linked session,
        // but the JSON contract keeps linked_session_id absent rather than null-valued.
        val json = JSONObject(
            HealthConnectImportManager.summaryJson(
                recordsRead = 1,
                stagesStored = 0,
                skippedMinutes = 0,
                skippedMinutesByType = emptyMap(),
                recordsWithoutSession = 1,
                linkedSessionId = null,
                window = 0L to 86_400_000L,
            ),
        )
        assertFalse(json.has("linked_session_id"))
        assertEquals(1, json.getInt("records_without_session"))
    }
}
