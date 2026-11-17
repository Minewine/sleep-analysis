package com.sleepanalysis.data.healthconnect

import androidx.health.connect.client.records.SleepSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stage-type mapping of the Health Connect import. The androidx STAGE_TYPE_* values are public
 * compile-time constants of connect-client 1.1.0 — Kotlin inlines them, so plain JVM tests need
 * no instrumentation. Deliberately NOT the Garmin numeric map (GarminJsonParsers', unverified).
 */
class HealthConnectStageMappingTest {

    @Test
    fun `the four canonical span types keep their labels`() {
        assertEquals("AWAKE", HealthConnectImportManager.mapStageType(SleepSessionRecord.STAGE_TYPE_AWAKE))
        assertEquals("LIGHT", HealthConnectImportManager.mapStageType(SleepSessionRecord.STAGE_TYPE_LIGHT))
        assertEquals("DEEP", HealthConnectImportManager.mapStageType(SleepSessionRecord.STAGE_TYPE_DEEP))
        assertEquals("REM", HealthConnectImportManager.mapStageType(SleepSessionRecord.STAGE_TYPE_REM))
    }

    @Test
    fun `awake in bed folds into awake`() {
        assertEquals("AWAKE", HealthConnectImportManager.mapStageType(SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED))
    }

    @Test
    fun `sleeping out of bed and unknown are skipped`() {
        assertNull(HealthConnectImportManager.mapStageType(SleepSessionRecord.STAGE_TYPE_SLEEPING))
        assertNull(HealthConnectImportManager.mapStageType(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED))
        assertNull(HealthConnectImportManager.mapStageType(SleepSessionRecord.STAGE_TYPE_UNKNOWN))
    }
}
