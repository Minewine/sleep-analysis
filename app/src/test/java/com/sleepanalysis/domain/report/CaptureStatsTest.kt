package com.sleepanalysis.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureStatsTest {

    @Test
    fun `parses full v1 payload with windows_skipped`() {
        val stats = CaptureStats.parse(
            """{"schema_version":1,"windows_processed":67000,"windows_skipped":120,
               "ambient_dbfs_median":-49.0,"ambient_dbfs_p95":-39.1,
               "clip_count":12,"thermal_throttle_events":1}"""
        )!!
        assertEquals(67_000L, stats.windowsProcessed)
        assertEquals(120L, stats.windowsSkipped)
        assertEquals(-49.0, stats.ambientDbfsMedian!!, 0.0001)
        assertEquals(12, stats.clipCount)
        assertEquals(1, stats.thermalThrottleEvents)
    }

    @Test
    fun `parses legacy payload without skipped field`() {
        val stats = CaptureStats.parse("""{"schema_version":1,"windows_processed":100,"clip_count":2}""")!!
        assertEquals(100L, stats.windowsProcessed)
        assertNull(stats.windowsSkipped)
        assertNull(stats.ambientDbfsMedian)
    }

    @Test
    fun `null JSON fields survive as nulls`() {
        val stats = CaptureStats.parse(
            """{"schema_version":1,"ambient_dbfs_median":null,"windows_processed":5}"""
        )!!
        assertNull(stats.ambientDbfsMedian)
        assertEquals(5L, stats.windowsProcessed)
    }

    @Test
    fun `malformed or blank input yields null`() {
        assertNull(CaptureStats.parse("not json"))
        assertNull(CaptureStats.parse(""))
        assertNull(CaptureStats.parse(null))
    }

    @Test
    fun `parses level_gate_skipped and tolerates legacy payloads without it`() {
        val withField = CaptureStats.parse(
            """{"schema_version":1,"windows_processed":5407,"clip_count":44,
               "level_gate_skipped":231}"""
        )!!
        assertEquals(231, withField.levelGateSkipped)
        val legacy = CaptureStats.parse("""{"schema_version":1,"windows_processed":100,"clip_count":2}""")!!
        assertNull(legacy.levelGateSkipped)
    }

    @Test
    fun `parses thermal_skipped_hops spans and tolerates payloads without them`() {
        val withSpans = CaptureStats.parse(
            """{"schema_version":2,"windows_processed":5407,
               "thermal_skipped_hops":[[1793564621686,1793564624686,3],[1793564629686,1793564630686,1]]}"""
        )!!
        assertEquals(2, withSpans.skippedHopSpans.size)
        assertEquals(1_793_564_621_686L, withSpans.skippedHopSpans[0].startEpochMs)
        assertEquals(3, withSpans.skippedHopSpans[0].hops)
        assertEquals(1, withSpans.skippedHopSpans[1].hops)

        val legacy = CaptureStats.parse("""{"schema_version":1,"windows_processed":100}""")!!
        assertEquals(0, legacy.skippedHopSpans.size)
    }

    @Test
    fun `malformed skipped-hop entries are dropped, not fatal`() {
        val stats = CaptureStats.parse(
            """{"schema_version":2,"windows_processed":10,
               "thermal_skipped_hops":[[1000,2000],["x","y","z"],[3000,4000,2],[]]}"""
        )!!
        assertEquals(listOf(SkippedHopSpan(3000, 4000, 2)), stats.skippedHopSpans)
    }
}
