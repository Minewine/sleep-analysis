package com.sleepanalysis.data.recorder

import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.domain.policy.MeasurementType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Garmin re-import contract (docs/GARMIN_INTEGRATION.md §6): a second import for the same
 * day replaces the day's GARMIN rows of the incoming types — it must never duplicate a vendor
 * metric, and a partial re-import (an endpoint that failed this run) must never delete the
 * other types' previously imported rows.
 */
class MeasurementRecorderTest {

    private val dayStart = 1_730_454_000_000L // 2026-01-12 local midnight-ish
    private val dayEnd = dayStart + 24 * 60 * 60 * 1000

    @Test
    fun `second import for the same day replaces prior garmin rows instead of duplicating`() = runTest {
        val dao = FakeMeasurementDao()
        val recorder = MeasurementRecorder(dao)
        val batch = listOf(
            MeasurementType.VENDOR_HRV_RMSSD to 45.0,
            MeasurementType.VENDOR_SLEEP_SCORE to 74.0,
        )

        recorder.replaceGarminEstimates(periodStart = dayStart, periodEnd = dayEnd, metrics = batch)
        assertEquals(2, dao.rows.size)

        // The user taps Import again (say, after fixing the key): values refresh, none duplicate.
        recorder.replaceGarminEstimates(
            periodStart = dayStart,
            periodEnd = dayEnd,
            metrics = listOf(
                MeasurementType.VENDOR_HRV_RMSSD to 52.0,
                MeasurementType.VENDOR_SLEEP_SCORE to 81.0,
            ),
        )
        assertEquals(2, dao.rows.size)
        val byType = dao.rows.associate { it.measurementType to it.valueNum }
        assertEquals(52.0, byType["VENDOR_HRV_RMSSD"]!!, 0.0001)
        assertEquals(81.0, byType["VENDOR_SLEEP_SCORE"]!!, 0.0001)
    }

    @Test
    fun `session linking stamps the batch rows when a session overlaps`() = runTest {
        val dao = FakeMeasurementDao()
        val recorder = MeasurementRecorder(dao)
        val metrics = listOf(MeasurementType.VENDOR_SLEEP_SCORE to 71.0)

        recorder.replaceGarminEstimates(
            periodStart = dayStart, periodEnd = dayEnd, metrics = metrics, sessionId = 3,
        )
        assertEquals(3L, dao.rows.single().sessionId)

        // No identifiable session → rows stay date-linked (null), the historical behavior.
        recorder.replaceGarminEstimates(periodStart = dayStart, periodEnd = dayEnd, metrics = metrics)
        assertNull(dao.rows.single().sessionId)
    }

    @Test
    fun `replace deletes the prior day rows before inserting the new ones`() = runTest {
        val dao = FakeMeasurementDao()
        val recorder = MeasurementRecorder(dao)
        val metrics = listOf(MeasurementType.VENDOR_HRV_RMSSD to 45.0)

        recorder.replaceGarminEstimates(periodStart = dayStart, periodEnd = dayEnd, metrics = metrics)
        recorder.replaceGarminEstimates(periodStart = dayStart, periodEnd = dayEnd, metrics = metrics)

        // Each import issues the delete first, then the inserts — twice in total.
        assertEquals(
            listOf("deleteGarminForTypes", "insertAll", "deleteGarminForTypes", "insertAll"),
            dao.ops,
        )
    }

    @Test
    fun `re-importing one day leaves other days untouched`() = runTest {
        val dao = FakeMeasurementDao()
        val recorder = MeasurementRecorder(dao)
        val otherStart = dayStart + 24 * 60 * 60 * 1000

        recorder.replaceGarminEstimates(
            periodStart = dayStart,
            periodEnd = dayEnd,
            metrics = listOf(MeasurementType.VENDOR_HRV_RMSSD to 45.0),
        )
        recorder.replaceGarminEstimates(
            periodStart = otherStart,
            periodEnd = otherStart + 24 * 60 * 60 * 1000,
            metrics = listOf(MeasurementType.VENDOR_HRV_RMSSD to 61.0),
        )
        recorder.replaceGarminEstimates(
            periodStart = dayStart,
            periodEnd = dayEnd,
            metrics = listOf(MeasurementType.VENDOR_HRV_RMSSD to 47.0),
        )

        assertEquals(2, dao.rows.size)
        val firstDay = dao.rows.single { it.periodStart == dayStart }
        assertEquals(47.0, firstDay.valueNum!!, 0.0001)
        assertEquals(61.0, dao.rows.single { it.periodStart == otherStart }.valueNum!!, 0.0001)
    }

    @Test
    fun `empty batch is a no-op and keeps previously imported rows`() = runTest {
        val dao = FakeMeasurementDao()
        val recorder = MeasurementRecorder(dao)
        recorder.replaceGarminEstimates(
            periodStart = dayStart,
            periodEnd = dayEnd,
            metrics = listOf(MeasurementType.VENDOR_HRV_RMSSD to 45.0),
        )

        // A re-import where every endpoint failed must not wipe the prior import.
        recorder.replaceGarminEstimates(periodStart = dayStart, periodEnd = dayEnd, metrics = emptyList())

        assertEquals(1, dao.rows.size)
        // The empty batch issued no DAO operations at all — no delete, no insert.
        assertEquals(listOf("deleteGarminForTypes", "insertAll"), dao.ops)
    }

    @Test
    fun `partial re-import replaces only the incoming types and keeps the other types' rows`() = runTest {
        val dao = FakeMeasurementDao()
        val recorder = MeasurementRecorder(dao)
        recorder.replaceGarminEstimates(
            periodStart = dayStart,
            periodEnd = dayEnd,
            metrics = listOf(
                MeasurementType.VENDOR_HRV_RMSSD to 45.0,
                MeasurementType.VENDOR_SLEEP_SCORE to 74.0,
            ),
        )

        // Only the HRV endpoint returned 2xx on this run: the day's previously imported sleep
        // rows must survive untouched, or a flaky endpoint wipes good data.
        recorder.replaceGarminEstimates(
            periodStart = dayStart,
            periodEnd = dayEnd,
            metrics = listOf(MeasurementType.VENDOR_HRV_RMSSD to 52.0),
        )

        assertEquals(2, dao.rows.size)
        assertEquals(52.0, dao.rows.single { it.measurementType == "VENDOR_HRV_RMSSD" }.valueNum!!, 0.0001)
        assertEquals(74.0, dao.rows.single { it.measurementType == "VENDOR_SLEEP_SCORE" }.valueNum!!, 0.0001)
    }

    @Test
    fun `batch rows carry the same provenance as the single-metric writer`() = runTest {
        val dao = FakeMeasurementDao()
        val recorder = MeasurementRecorder(dao)

        recorder.replaceGarminEstimates(
            periodStart = dayStart,
            periodEnd = dayEnd,
            metrics = listOf(MeasurementType.VENDOR_HRV_RMSSD to 45.0),
            notes = "Garmin Personal API, date 2026-01-12",
        )
        val batchRow = dao.rows.single()

        dao.rows.clear()
        dao.ops.clear()
        recorder.recordGarminEstimate(
            type = MeasurementType.VENDOR_HRV_RMSSD,
            value = 45.0,
            periodStart = dayStart,
            periodEnd = dayEnd,
            notes = "Garmin Personal API, date 2026-01-12",
        )
        val singleRow = dao.rows.single()

        assertEquals("GARMIN", batchRow.sourceType)
        assertEquals("DEVICE_REPORTED", batchRow.method)
        assertFalse(batchRow.isClinical)
        assertEquals("ESTABLISHED", batchRow.tier)
        assertEquals("MS", batchRow.unit)
        assertEquals(45.0, batchRow.valueNum!!, 0.0001)
        assertEquals(dayStart, batchRow.periodStart)
        assertEquals(dayEnd, batchRow.periodEnd)
        assertEquals("Garmin Personal API, date 2026-01-12", batchRow.notes)
        assertNull(batchRow.sessionId)
        assertNull(batchRow.modelVersion)
        // Identical provenance on every field the single writer stamps.
        assertEquals(singleRow.copy(id = 0, timestamp = 0, createdAt = 0), batchRow.copy(id = 0, timestamp = 0, createdAt = 0))
    }

    @Test
    fun `batch refuses out-of-scope measurement types outright`() = runTest {
        val dao = FakeMeasurementDao()
        val recorder = MeasurementRecorder(dao)
        assertThrows(IllegalStateException::class.java) {
            runTest {
                recorder.replaceGarminEstimates(
                    periodStart = dayStart,
                    periodEnd = dayEnd,
                    metrics = listOf(MeasurementType.AHI_ESTIMATE to 3.0),
                )
            }
        }
        assertTrue(dao.rows.isEmpty())
    }

    private class FakeMeasurementDao : MeasurementDao {
        val rows = mutableListOf<MeasurementEntity>()
        val ops = mutableListOf<String>()

        override suspend fun insert(measurement: MeasurementEntity): Long {
            ops += "insert"
            rows.add(measurement)
            return rows.size.toLong()
        }

        override suspend fun insertAll(measurements: List<MeasurementEntity>) {
            ops += "insertAll"
            rows.addAll(measurements)
        }

        override suspend fun bySession(sessionId: Long): List<MeasurementEntity> =
            rows.filter { it.sessionId == sessionId }

        override suspend fun garminBetween(from: Long, to: Long): List<MeasurementEntity> =
            rows.filter { it.sourceType == "GARMIN" && it.periodStart != null && it.periodStart >= from && it.periodStart < to }

        override suspend fun deleteGarminForTypes(periodStart: Long, types: List<String>) {
            ops += "deleteGarminForTypes"
            rows.removeAll {
                it.sourceType == "GARMIN" && it.periodStart == periodStart && it.measurementType in types
            }
        }

        override suspend fun deleteAll() {
            ops += "deleteAll"
            rows.clear()
        }

        // replaceGarminForDay is deliberately NOT overridden: the DAO's default body runs,
        // exercising the real delete-then-insert path through this fake.
    }
}
