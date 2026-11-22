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
        spectralSkipped: Long = 0,
        spans: List<SkippedHopSpan> = emptyList(),
        clientSilenced: List<com.sleepanalysis.domain.report.ClientSilencedSpan> = emptyList(),
    ) = AudioEngine.NightStats(
        windowsProcessed = windows,
        windowsSkipped = skipped,
        ambientDbfsMedian = -80.0,
        ambientDbfsP95 = -60.0,
        clipCount = clips,
        clipBytesTotal = bytes,
        thermalThrottleEvents = thermal,
        levelGateSkipped = levelGateSkipped,
        spectralSkipped = spectralSkipped,
        skippedHopSpans = spans,
        clientSilencedSpans = clientSilenced,
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
    fun `capture stats writes schema v6 and round-trips spectral_skipped`() {
        val json = captureStatsJson(stats(spectralSkipped = 12_345), clipCountOverride = 3)
        assertTrue(json.contains("\"schema_version\":6"))
        assertTrue(json.contains("\"spectral_skipped\":12345"))
        assertEquals(12_345L, CaptureStats.parse(json)!!.spectralSkipped)
        // v3-era JSON (no spectral_skipped) parses with a null — readers tolerate its absence.
        val legacy = CaptureStats.parse(
            """{"schema_version":3,"windows_processed":10,"level_gate_skipped":2}""",
        )!!
        assertNull(legacy.spectralSkipped)
    }

    @Test
    fun `StatsCarry carries spectral_skipped across restarts`() {
        val json = """{"schema_version":4,"windows_processed":54000,"spectral_skipped":30000}"""
        val merged = stats(spectralSkipped = 7).withCarry(StatsCarry.from(CaptureStats.parse(json)))
        assertEquals(30_007L, merged.spectralSkipped)
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
        assertTrue(json.contains("\"schema_version\":6"))
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

    @Test
    fun `capture stats v6 round-trips client-silenced spans and bucket coverage`() {
        val stats = AudioEngine.NightStats(
            windowsProcessed = 100, windowsSkipped = 2,
            ambientDbfsMedian = -83.0, ambientDbfsP95 = -40.0,
            clipCount = 3, clipBytesTotal = 96_000, thermalThrottleEvents = 0,
            ambientBuckets = listOf(
                com.sleepanalysis.domain.report.AmbientLevelBucket(
                    0L, 300_000L, -100.0, coveredMs = 299_500L, dbfsVariance = 0.0,
                ),
            ),
            clientSilencedSpans = listOf(
                com.sleepanalysis.domain.report.ClientSilencedSpan(1_000, 61_000),
            ),
        )
        val json = captureStatsJson(stats, clipCountOverride = 3)
        assertTrue(json.contains("\"schema_version\":6"))
        assertTrue(json.contains("client_silenced_spans"))
        val parsed = com.sleepanalysis.domain.report.CaptureStats.parse(json)!!
        assertEquals(
            listOf(com.sleepanalysis.domain.report.ClientSilencedSpan(1_000, 61_000)),
            parsed.clientSilencedSpans,
        )
        assertEquals(299_500L, parsed.ambientBuckets[0].coveredMs)
        assertEquals(0.0, parsed.ambientBuckets[0].dbfsVariance!!, 0.0)
    }

    @Test
    fun `legacy v5 bucket arrays parse with full-bucket coverage and no variance`() {
        val parsed = com.sleepanalysis.domain.report.CaptureStats.parse(
            """{"schema_version":5,"windows_processed":10,"ambient_level_buckets":[[0,300000,-84.5]]}""",
        )!!
        assertEquals(300_000L, parsed.ambientBuckets[0].coveredMs) // legacy full-bucket claim
        assertNull(parsed.ambientBuckets[0].dbfsVariance)
        assertTrue(parsed.clientSilencedSpans.isEmpty())
    }

    @Test
    fun `withCarry concatenates and sorts client-silenced spans`() {
        val merged = stats(
            clientSilenced = listOf(com.sleepanalysis.domain.report.ClientSilencedSpan(6_000, 7_000)),
        ).withCarry(
            StatsCarry(
                windowsProcessed = 10, windowsSkipped = 0, thermalThrottleEvents = 0,
                clientSilencedSpans = listOf(com.sleepanalysis.domain.report.ClientSilencedSpan(100, 200)),
            ),
        )
        assertEquals(
            listOf(
                com.sleepanalysis.domain.report.ClientSilencedSpan(100, 200),
                com.sleepanalysis.domain.report.ClientSilencedSpan(6_000, 7_000),
            ),
            merged.clientSilencedSpans,
        )
    }

    @Test
    fun `StatsCarry carries client-silenced spans from parsed stats`() {
        val json = """{"schema_version":6,"windows_processed":54000,"client_silenced_spans":[[1000,61000]]}"""
        val carry = StatsCarry.from(CaptureStats.parse(json))!!
        assertEquals(
            listOf(com.sleepanalysis.domain.report.ClientSilencedSpan(1_000, 61_000)),
            carry.clientSilencedSpans,
        )
    }
}
