package com.sleepanalysis.domain.policy

import com.sleepanalysis.data.db.dao.MeasurementDao
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.recorder.MeasurementRecorder
import com.sleepanalysis.domain.scoring.SnoreScore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement-discipline tests (docs/MEASUREMENT_RELIABILITY.md §7): the code tier table
 * must match the documented classification, and the recorder must refuse Tier-3 types.
 */
class ClaimPolicyTest {

    @Test
    fun `phase 1 measurement types are all established`() {
        val phase1 = listOf(
            MeasurementType.TIME_IN_BED,
            MeasurementType.SNORE_EVENT_COUNT,
            MeasurementType.SNORE_TIME,
            MeasurementType.SNORE_SHARE_OF_NIGHT,
            MeasurementType.SNORE_INTENSITY_PEAK,
            MeasurementType.SNORE_INTENSITY_MEAN,
            MeasurementType.SNORE_SCORE,
            MeasurementType.NOISE_MEDIAN,
            MeasurementType.NOISE_P95,
            MeasurementType.ACOUSTIC_EVENT_COUNT,
        )
        phase1.forEach { assertEquals("${it.name}", MeasurementTier.ESTABLISHED, it.tier) }
    }

    @Test
    fun `breathing indicators stay experimental`() {
        listOf(
            MeasurementType.BREATHING_IRREGULARITY_INDICATOR,
            MeasurementType.RESPIRATORY_PATTERN_INDICATOR,
        ).forEach { assertEquals(MeasurementTier.EXPERIMENTAL, it.tier) }
    }

    @Test
    fun `tier-3 types are exactly the documented M14-M17 set and are never displayable`() {
        val tier3 = listOf(
            MeasurementType.AHI_ESTIMATE,
            MeasurementType.RDI_ESTIMATE,
            MeasurementType.SLEEP_STAGE_ESTIMATE,
            MeasurementType.AROUSAL_INDEX_ESTIMATE,
            MeasurementType.SPO2_DESATURATION_ESTIMATE,
        )
        tier3.forEach {
            assertEquals(MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS, it.tier)
            assertFalse(ClaimPolicy.displayAllowed(it))
            assertFalse(ClaimPolicy.displayAllowed(it, experimentalToggleEnabled = true))
        }
        // Nothing else may be out of scope beyond the documented set.
        assertEquals(
            5,
            MeasurementType.entries.count { it.tier == MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS },
        )
    }

    @Test
    fun `experimental values display only behind the toggle`() {
        val type = MeasurementType.BREATHING_IRREGULARITY_INDICATOR
        assertFalse(ClaimPolicy.displayAllowed(type, experimentalToggleEnabled = false))
        assertTrue(ClaimPolicy.displayAllowed(type, experimentalToggleEnabled = true))
    }

    @Test
    fun `recorder stamps full provenance on phone estimates`() = runTest {
        val dao = FakeDao()
        val recorder = MeasurementRecorder(dao)
        recorder.recordPhoneEstimate(
            sessionId = 7,
            type = MeasurementType.SNORE_SCORE,
            value = 42.0,
            modelVersion = "energy-heuristic-v0",
        )
        val row = dao.rows.single()
        assertEquals("PHONE", row.sourceType)
        assertEquals("ALGORITHM_ESTIMATED", row.method)
        assertFalse(row.isClinical)
        assertEquals("ESTABLISHED", row.tier)
        assertEquals("SNORE_SCORE", row.measurementType)
        assertEquals("INDEX_0_100", row.unit)
        assertEquals("energy-heuristic-v0", row.modelVersion)
        assertEquals(7L, row.sessionId)
    }

    @Test
    fun `recorder refuses tier-3 measurement types outright`() = runTest {
        val dao = FakeDao()
        val recorder = MeasurementRecorder(dao)
        MeasurementType.entries
            .filter { it.tier == MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS }
            .forEach { type ->
                assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.test.runTest {
                        recorder.recordPhoneEstimate(sessionId = 1, type = type, value = 17.6)
                    }
                }
            }
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `garmin vendor metrics are established device-reported pass-throughs`() {
        val vendorTypes = listOf(
            MeasurementType.VENDOR_SLEEP_DURATION,
            MeasurementType.VENDOR_SLEEP_SCORE,
            MeasurementType.VENDOR_HRV_RMSSD,
            MeasurementType.VENDOR_BODY_BATTERY,
            MeasurementType.VENDOR_RESTING_HEART_RATE,
        )
        vendorTypes.forEach {
            assertEquals("${it.name}", MeasurementTier.ESTABLISHED, it.tier)
            assertTrue(ClaimPolicy.displayAllowed(it))
        }
    }

    @Test
    fun `recorder stamps garmin provenance as device-reported, not clinical`() = runTest {
        val dao = FakeDao()
        val recorder = MeasurementRecorder(dao)
        recorder.recordGarminEstimate(
            type = MeasurementType.VENDOR_HRV_RMSSD,
            value = 45.0,
            periodStart = 1_000L,
            periodEnd = 2_000L,
            notes = "Garmin Personal API, date 2026-01-12",
        )
        val row = dao.rows.single()
        assertEquals("GARMIN", row.sourceType)
        assertEquals("DEVICE_REPORTED", row.method)
        assertFalse(row.isClinical)
        assertEquals("ESTABLISHED", row.tier)
        assertEquals("MS", row.unit)
        assertEquals(1_000L, row.periodStart)
    }

    @Test
    fun `snore intensity keeps the relative-only DBFS unit`() {
        assertEquals(
            com.sleepanalysis.domain.policy.MeasurementUnit.DBFS,
            MeasurementType.SNORE_INTENSITY_PEAK.unit,
        )
        assertEquals(
            SnoreScore.IntensityTiers.EPIC,
            com.sleepanalysis.monitoring.detection.DetectorConfig.intensityTier(-5.0),
        )
    }

    private class FakeDao : MeasurementDao {
        val rows = mutableListOf<MeasurementEntity>()
        override suspend fun insert(measurement: MeasurementEntity): Long {
            rows.add(measurement)
            return rows.size.toLong()
        }

        override suspend fun insertAll(measurements: List<MeasurementEntity>) {
            rows.addAll(measurements)
        }

        override suspend fun bySession(sessionId: Long): List<MeasurementEntity> =
            rows.filter { it.sessionId == sessionId }

        override suspend fun garminBetween(from: Long, to: Long): List<MeasurementEntity> =
            rows.filter { it.sourceType == "GARMIN" && it.periodStart != null && it.periodStart >= from && it.periodStart < to }

        override suspend fun deleteGarminForDay(periodStart: Long) {
            rows.removeAll { it.sourceType == "GARMIN" && it.periodStart == periodStart }
        }

        override suspend fun deleteAll() = rows.clear()
    }
}
