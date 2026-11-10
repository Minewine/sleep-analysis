package com.sleepanalysis.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report snapshot payload (schema v12, docs/DATA_MODEL.md §3.7/§5.2): build ↔ parse round-trip. */
class ReportSnapshotTest {

    @Test
    fun `payload round-trips through the tolerant parser`() {
        val headline = ReportSnapshot.Headline(
            validityGrade = "B",
            snoreScore = 34,
            snoreSharePercent = 2.8,
            snoreSeconds = 812,
            longestBoutMs = 1_292_000,
            garminSleepStartMs = 1_793_657_000_000,
            garminSleepEndMs = 1_793_681_420_000,
            garminSleepScore = 71,
            garminOverlapWithCaptureMs = 4_277_000,
        )
        val json = ReportSnapshot.payload(headline)
        assertTrue(json.contains("\"schema_version\":1"))
        assertEquals(headline, ReportSnapshot.parse(json))
    }

    @Test
    fun `absent metrics stay absent - nulls never render as zeros`() {
        val json = ReportSnapshot.payload(ReportSnapshot.Headline(validityGrade = "D"))
        assertTrue(!json.contains("snore_score"))
        val parsed = ReportSnapshot.parse(json)
        assertEquals("D", parsed?.validityGrade)
        assertNull(parsed?.snoreScore)
        assertNull(parsed?.snoreSharePercent)
        assertNull(parsed?.garminSleepStartMs)
    }

    @Test
    fun `malformed or empty payloads parse to null`() {
        assertNull(ReportSnapshot.parse(null))
        assertNull(ReportSnapshot.parse(""))
        assertNull(ReportSnapshot.parse("not json"))
        assertNull(ReportSnapshot.parse("{\"unrelated\":1}"))
    }

    @Test
    fun `a future payload version is refused, never degraded`() {
        assertNull(
            ReportSnapshot.parse(
                "{\"schema_version\":99,\"snore_seconds\":512,\"validity_grade\":\"A\"}",
            ),
        )
    }

    @Test
    fun `present-but-non-numeric values are treated as missing, never as zero`() {
        val parsed = ReportSnapshot.parse(
            "{\"schema_version\":1,\"snore_seconds\":\"abc\",\"snore_score\":\"42\"," +
                "\"snore_share_percent\":\"NaN\"}",
        )
        assertNull(parsed?.snoreSeconds)
        assertNull(parsed?.snoreSharePercent)
        // A numeric STRING coerces (tolerant read), a non-numeric one does not fabricate a value.
        assertEquals(42, parsed?.snoreScore)
    }
}
