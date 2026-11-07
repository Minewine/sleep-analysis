package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.domain.report.CaptureStats
import com.sleepanalysis.domain.report.SkippedHopSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NightStatsMergeTest {

    private fun stats(
        windows: Long = 100,
        skipped: Long = 4,
        clips: Int = 2,
        bytes: Long = 512,
        thermal: Int = 1,
        levelGateSkipped: Int = 0,
        spans: List<SkippedHopSpan> = emptyList(),
    ) = AudioEngine.NightStats(
        windowsProcessed = windows,
        windowsSkipped = skipped,
        ambientDbfsMedian = -80.0,
        ambientDbfsP95 = -60.0,
        clipCount = clips,
        clipBytesTotal = bytes,
        thermalThrottleEvents = thermal,
        levelGateSkipped = levelGateSkipped,
        skippedHopSpans = spans,
    )

    @Test
    fun `null carry returns the same stats unchanged`() {
        val s = stats()
        assertSame(s, s.withCarry(null))
    }

    @Test
    fun `carry sums windows skipped and thermal events`() {
        val merged = stats(windows = 5407, skipped = 0, thermal = 0)
            .withCarry(StatsCarry(windowsProcessed = 55_800, windowsSkipped = 12, thermalThrottleEvents = 2))
        assertEquals(61_207, merged.windowsProcessed)
        assertEquals(12, merged.windowsSkipped)
        assertEquals(2, merged.thermalThrottleEvents)
    }

    @Test
    fun `carry does not touch clip stats or ambient levels`() {
        val merged = stats(clips = 44, bytes = 11_265_936)
            .withCarry(StatsCarry(windowsProcessed = 10_000, windowsSkipped = 0, thermalThrottleEvents = 0))
        assertEquals(44, merged.clipCount)
        assertEquals(11_265_936, merged.clipBytesTotal)
        assertEquals(-80.0, merged.ambientDbfsMedian!!, 1e-9)
        assertEquals(-60.0, merged.ambientDbfsP95!!, 1e-9)
    }

    @Test
    fun `StatsCarry from parsed stats sums counters and defaults nulls`() {
        val json = """{"schema_version":1,"windows_processed":54000,"windows_skipped":3,""" +
            """"thermal_throttle_events":2}"""
        val carry = StatsCarry.from(CaptureStats.parse(json))
        assertEquals(54_000L, carry!!.windowsProcessed)
        assertEquals(3L, carry.windowsSkipped)
        assertEquals(2, carry.thermalThrottleEvents)
        assertEquals(0, carry.levelGateSkipped)
    }

    @Test
    fun `StatsCarry carries level_gate_skipped across restarts`() {
        val json = """{"schema_version":1,"windows_processed":54000,"level_gate_skipped":231}"""
        val merged = stats(levelGateSkipped = 9).withCarry(StatsCarry.from(CaptureStats.parse(json)))
        assertEquals(240, merged.levelGateSkipped)
    }

    @Test
    fun `withCarry concatenates and sorts skipped-hop spans`() {
        val carried = listOf(SkippedHopSpan(200, 4_000, 3), SkippedHopSpan(100, 900, 1))
        val current = listOf(SkippedHopSpan(6_000, 7_000, 1))
        val merged = stats(spans = current)
            .withCarry(StatsCarry(10, 0, 0, skippedHopSpans = carried))
        assertEquals(
            listOf(SkippedHopSpan(100, 900, 1), SkippedHopSpan(200, 4_000, 3), SkippedHopSpan(6_000, 7_000, 1)),
            merged.skippedHopSpans,
        )
    }

    @Test
    fun `StatsCarry carries skipped-hop spans from parsed stats`() {
        val json = """{"schema_version":2,"windows_processed":54000,"thermal_throttle_events":1,""" +
            """"thermal_skipped_hops":[[1000,4000,3],[9000,10000,1]]}"""
        val carry = StatsCarry.from(CaptureStats.parse(json))!!
        assertEquals(2, carry.skippedHopSpans.size)
        assertEquals(3, carry.skippedHopSpans[0].hops)
    }

    @Test
    fun `StatsCarry is null without persisted windows`() {
        assertNull(StatsCarry.from(null))
        assertNull(StatsCarry.from(CaptureStats.parse("""{"schema_version":1}""")))
    }

    @Test
    fun `captureStatsJson round-trips through CaptureStats parse`() {
        val json = captureStatsJson(
            stats(windows = 61_207, skipped = 12, thermal = 2, levelGateSkipped = 231),
            clipCountOverride = 94,
        )
        val parsed = CaptureStats.parse(json)!!
        assertEquals(61_207L, parsed.windowsProcessed)
        assertEquals(12L, parsed.windowsSkipped)
        assertEquals(94, parsed.clipCount)
        assertEquals(2, parsed.thermalThrottleEvents)
        assertEquals(231, parsed.levelGateSkipped)
    }

    @Test
    fun `captureStatsJson round-trips skipped-hop spans`() {
        val spans = listOf(SkippedHopSpan(1_000, 4_000, 3), SkippedHopSpan(9_000, 10_000, 1))
        val json = captureStatsJson(stats(spans = spans))
        assertEquals(spans, CaptureStats.parse(json)!!.skippedHopSpans)
    }

    @Test
    fun `captureStatsJson without spans omits the skipped-hop key`() {
        val json = captureStatsJson(stats())
        assertTrue(!json.contains("thermal_skipped_hops"))
        assertEquals(0, CaptureStats.parse(json)!!.skippedHopSpans.size)
    }

    @Test
    fun `captureStatsJson null stats is the empty schema object`() {
        assertEquals("""{"schema_version":1}""", captureStatsJson(null))
        val parsed = CaptureStats.parse(captureStatsJson(null))!!
        assertNull(parsed.windowsProcessed)
    }
    @Test
    fun `capture stats v3 round-trips ambient level buckets`() {
        val stats = AudioEngine.NightStats(
            windowsProcessed = 100, windowsSkipped = 2,
            ambientDbfsMedian = -83.0, ambientDbfsP95 = -40.0,
            clipCount = 3, clipBytesTotal = 96_000, thermalThrottleEvents = 0,
            levelGateSkipped = 1,
            skippedHopSpans = emptyList(),
            ambientBuckets = listOf(
                com.sleepanalysis.domain.report.AmbientLevelBucket(1_000L, 301_000L, -84.5),
                com.sleepanalysis.domain.report.AmbientLevelBucket(301_000L, 601_000L, -79.0),
            ),
        )
        val json = captureStatsJson(stats, clipCountOverride = 3)
        assertTrue(json.contains("\"schema_version\":3"))
        assertTrue(json.contains("ambient_level_buckets"))
        val parsed = com.sleepanalysis.domain.report.CaptureStats.parse(json)!!
        assertEquals(2, parsed.ambientBuckets.size)
        assertEquals(1_000L, parsed.ambientBuckets[0].startMs)
        assertEquals(-84.5, parsed.ambientBuckets[0].meanDbfs, 0.0001)
        // v2-era JSON (no buckets) parses with an empty timeline.
        val legacy = com.sleepanalysis.domain.report.CaptureStats.parse(
            "{\"schema_version\":2,\"windows_processed\":10,\"windows_skipped\":0," +
                "\"clip_count\":1,\"thermal_throttle_events\":0,\"level_gate_skipped\":0}",
        )!!
        assertTrue(legacy.ambientBuckets.isEmpty())
    }
}
