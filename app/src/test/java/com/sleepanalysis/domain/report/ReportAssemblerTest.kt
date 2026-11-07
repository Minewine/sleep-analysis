package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.ClinicalBaselineEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.domain.report.FactorEntry
import com.sleepanalysis.domain.report.GarminMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAssemblerTest {

    private val t0 = 1_730_000_000_000L
    private val eightHours = 8 * 3_600_000L

    private fun session(status: String = SessionStatus.COMPLETED, endedAt: Long? = t0 + eightHours) =
        SleepSessionEntity(
            id = 1,
            startedAt = t0,
            endedAt = endedAt,
            status = status,
            timeZone = "Europe/Zurich",
            deviceModel = "Test",
            osVersion = "30",
            appVersion = "0.1.0",
        )

    private fun snoreEvent(id: Long, offsetMs: Long, durationMs: Long, tier: String) =
        AcousticEventEntity(
            id = id,
            sessionId = 1,
            type = EventType.SNORE,
            startMs = t0 + offsetMs,
            endMs = t0 + offsetMs + durationMs,
            offsetMs = offsetMs,
            durationMs = durationMs,
            peakDbfs = -15.0,
            meanDbfs = -20.0,
            intensityTier = tier,
            confidence = 0.8,
            modelVersion = "energy-heuristic-v0",
        )

    @Test
    fun `assembles headline numbers from events`() {
        val events = listOf(
            snoreEvent(1, offsetMs = 3_600_000, durationMs = 60_000, tier = "LOUD"),
            snoreEvent(2, offsetMs = 7_200_000, durationMs = 30_000, tier = "LIGHT"),
        )
        val report = ReportAssembler.assemble(session(), events, emptyList(), emptyList())
        assertNotNull(report)
        report!!
        assertEquals(8 * 3600L, report.timeInBedSeconds)
        assertEquals(90L, report.snoreSeconds)
        assertEquals(2, report.snoreEventCount)
        assertEquals(90.0 / 28800.0 * 100.0, report.snoreSharePercent, 0.0001)
        assertEquals(1, report.intensityBreakdown["LOUD"])
        assertEquals(1, report.intensityBreakdown["LIGHT"])
        assertTrue(report.snoreScore in 1..100)
        assertEquals("energy-heuristic-v0", report.modelVersion)
    }

    @Test
    fun `hourly buckets land in the right hour`() {
        val events = listOf(
            snoreEvent(1, offsetMs = 3_600_000, durationMs = 60_000, tier = "LOUD"),
            snoreEvent(2, offsetMs = 7_200_000, durationMs = 30_000, tier = "LIGHT"),
        )
        val report = ReportAssembler.assemble(session(), events, emptyList(), emptyList())!!
        assertEquals(8, report.hourly.size)
        assertEquals(60L, report.hourly[1].snoreSeconds)
        assertEquals(30L, report.hourly[2].snoreSeconds)
        assertEquals(0L, report.hourly[0].snoreSeconds)
    }

    @Test
    fun `non-snoring events count but do not add snore time`() {
        val other = snoreEvent(3, offsetMs = 1_000_000, durationMs = 10_000, tier = "LIGHT")
            .copy(type = EventType.OTHER_NOISE)
        val report = ReportAssembler.assemble(session(), listOf(other), emptyList(), emptyList())!!
        assertEquals(0L, report.snoreSeconds)
        assertEquals(1, report.eventCountsByType[EventType.OTHER_NOISE])
        assertEquals(0, report.snoreScore)
    }

    @Test
    fun `clip refs join via event clipId`() {
        val events = listOf(
            snoreEvent(1, offsetMs = 100_000, durationMs = 20_000, tier = "LOUD").copy(clipId = 9),
        )
        val clips = listOf(
            AudioClipEntity(
                id = 9, sessionId = 1, eventId = 1,
                filePath = "session_1/evt_100000.wav", durationMs = 8000, sizeBytes = 262_144,
            ),
        )
        val report = ReportAssembler.assemble(session(), events, clips, emptyList())!!
        assertEquals(1, report.clips.size)
        assertEquals("session_1/evt_100000.wav", report.clips[1L]!!.filePath)
    }

    @Test
    fun `clip joins work for legacy rows where audio_clip eventId is null`() {
        // Rows written before the eventId back-fill existed: only event.clipId is set.
        val events = listOf(
            snoreEvent(1, offsetMs = 100_000, durationMs = 20_000, tier = "LOUD").copy(clipId = 9),
        )
        val clips = listOf(
            AudioClipEntity(
                id = 9, sessionId = 1, eventId = null,
                filePath = "session_1/evt_100000.wav", durationMs = 8000, sizeBytes = 262_144,
            ),
        )
        val report = ReportAssembler.assemble(session(), events, clips, emptyList())!!
        assertEquals(1, report.clips.size)
    }

    @Test
    fun `recovered session adds integrity note`() {
        val report = ReportAssembler.assemble(session(status = SessionStatus.RECOVERED), emptyList(), emptyList(), emptyList())!!
        assertTrue(report.integrityNotes.any { it.contains("recovered") })
    }

    @Test
    fun `resumed session leads the integrity notes`() {
        // Prominence contract: the resume note is FIRST, ahead of the status note.
        val report = ReportAssembler.assemble(
            session(status = SessionStatus.RECOVERED).copy(resumedAfterInterruption = true),
            emptyList(), emptyList(), emptyList(),
        )!!
        assertTrue(report.integrityNotes.first().contains("Resumed after an interruption"))
        assertEquals(2, report.integrityNotes.size)

        val fresh = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList())!!
        assertTrue(fresh.integrityNotes.isEmpty())
    }

    @Test
    fun `in-progress session cannot produce a report`() {
        assertNull(ReportAssembler.assemble(session(endedAt = null), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `garmin metrics ride along as device-reported context`() {
        val garmin = listOf(
            GarminMetric("VENDOR_HRV_RMSSD", 45.0, "MS", t0),
            GarminMetric("VENDOR_SLEEP_DURATION", 26460.0, "SECONDS", t0),
        )
        val report = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), garmin)!!
        assertEquals(2, report.garmin.size)
        assertEquals("VENDOR_HRV_RMSSD", report.garmin[0].measurementType)
        // Garmin metrics never feed the acoustic snore score.
        assertEquals(0, report.snoreScore)
    }

    @Test
    fun `self-logged factors ride along as user context and never touch scores`() {
        val factors = listOf(
            FactorEntry("ALCOHOL", 2.0, null),
            FactorEntry("STRESS", 4.0, null),
            FactorEntry("NOTES", null, "late meal at 22:30"),
        )
        val report = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), factors = factors)!!
        assertEquals(3, report.dailyFactors.size)
        assertEquals(0, report.snoreScore) // user context never alters acoustic results
    }

    @Test
    fun `health warnings derive from capture stats`() {
        val healthy = CaptureStats(
            schemaVersion = 1, windowsProcessed = 57_000, windowsSkipped = 0,
            ambientDbfsMedian = -50.0, ambientDbfsP95 = -40.0,
            clipCount = 10, thermalThrottleEvents = 0,
        )
        val ok = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = healthy)!!
        assertTrue(ok.healthWarnings.isEmpty())

        val gappy = healthy.copy(windowsProcessed = 5_000)
        val warnings = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = gappy)!!
            .healthWarnings
        assertTrue(warnings.any { it.contains("Capture gaps suspected") })

        // Delayed start: capture_started_at narrows the expectation to the real capture
        // window — 2 h of delay on an 8 h night with full coverage must NOT warn.
        val delayed = healthy.copy(captureStartMs = t0 + 2 * 3_600_000L)
        val noDelayWarning = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = delayed)!!
            .healthWarnings
        assertTrue("delayed start must not trip the gap warning: $noDelayWarning", noDelayWarning.none { it.contains("Capture gaps suspected") })

        // ...but the same capture window with real gaps still does.
        val delayedGappy = delayed.copy(windowsProcessed = 20_000) // 6 h window expects 43_200
        assertTrue(
            ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = delayedGappy)!!
                .healthWarnings.any { it.contains("Capture gaps suspected") },
        )

        val throttled = healthy.copy(thermalThrottleEvents = 2)
        assertTrue(
            ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = throttled)!!
                .healthWarnings.any { it.contains("Thermal throttling") }
        )

        // Skipped-hop spans quantify the reduced detection coverage during throttling.
        val hopGaps = throttled.copy(
            skippedHopSpans = listOf(SkippedHopSpan(1000, 4000, 3), SkippedHopSpan(9000, 10000, 1)),
        )
        val hopWarnings = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = hopGaps)!!
            .healthWarnings
        assertTrue(hopWarnings.any { it.contains("4 analysis hops") && it.contains("coverage") })

        val dead = healthy.copy(windowsProcessed = 0)
        assertTrue(
            ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = dead)!!
                .healthWarnings.any { it.contains("capture likely failed") }
        )

        val noStats = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = null)!!
        assertTrue(noStats.healthWarnings.any { it.contains("unavailable") })

        // Recovered sessions finalize with unknown stats (null) — unknown is NOT "failed".
        val unknown = healthy.copy(windowsProcessed = null)
        assertTrue(
            ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = unknown)!!
                .healthWarnings.isEmpty()
        )
    }

    @Test
    fun `morning notes ride along from the session row`() {
        val s = session().copy(morningNotes = "woke at 3am, partner snoring")
        val report = ReportAssembler.assemble(s, emptyList(), emptyList(), emptyList())!!
        assertEquals("woke at 3am, partner snoring", report.morningNotes)
    }

    @Test
    fun `loudest episodes lead with the night's peak and carry the clip`() {
        val loudest = snoreEvent(1, offsetMs = 100_000, durationMs = 11_000, tier = "LOUD")
            .copy(peakDbfs = -16.6, clipId = 9)
        // Overlaps the first event's window → same episode.
        val quieterLater = snoreEvent(2, offsetMs = 105_000, durationMs = 4_000, tier = "QUIET")
            .copy(peakDbfs = -30.0)
        val farAway = snoreEvent(3, offsetMs = 400_000, durationMs = 3_000, tier = "LIGHT")
            .copy(peakDbfs = -25.0) // minutes later: separate episode
        val clips = listOf(
            AudioClipEntity(
                id = 9, sessionId = 1, eventId = 1,
                filePath = "session_1/evt_100000.wav", durationMs = 8000, sizeBytes = 262_144,
            ),
        )
        val report = ReportAssembler.assemble(session(), listOf(loudest, quieterLater, farAway), clips, emptyList())!!
        // Two episodes (first two events are within 10 s): loudest first, clip attached.
        assertEquals(2, report.loudestEpisodes.size)
        assertEquals(-16.6, report.loudestEpisodes[0].peakDbfs, 0.0001)
        assertEquals(2, report.loudestEpisodes[0].snoreCount)
        assertEquals(1L, report.loudestEpisodes[0].clip!!.eventId)
        assertEquals(-25.0, report.loudestEpisodes[1].peakDbfs, 0.0001)
        assertEquals(null, report.loudestEpisodes[1].clip)
    }

    @Test
    fun `clinical baseline rides along as context`() {
        val baseline = listOf(
            ClinicalBaselineEntity(
                id = 1, studyDate = "2026-01-12", lab = "HUG", studyType = "PSG",
                ahiPerHour = 17.6, totalSleepTimeMinutes = 334,
            ),
        )
        val report = ReportAssembler.assemble(session(), emptyList(), emptyList(), baseline)!!
        assertEquals(1, report.baseline.size)
        assertEquals(17.6, report.baseline[0].ahiPerHour!!, 0.0001)
    }

    // ---- display filtering: low-confidence OTHER_NOISE (docs/NIGHT_REPORT.md) ----

    private fun noiseEvent(id: Long, confidence: Double) =
        snoreEvent(id, offsetMs = 1_000_000, durationMs = 5_000, tier = "QUIET")
            .copy(type = EventType.OTHER_NOISE, confidence = confidence)

    @Test
    fun `low-confidence noise is display-filtered with an integrity note`() {
        val junk = noiseEvent(10, confidence = 0.3)
        val keep = noiseEvent(11, confidence = 0.7)
        val report = ReportAssembler.assemble(session(), listOf(junk, keep), emptyList(), emptyList())!!
        assertEquals(1, report.eventCountsByType[EventType.OTHER_NOISE])
        assertEquals(1, report.lowConfidenceNoiseExcluded)
        assertTrue(report.integrityNotes.any { it.contains("1 low-confidence noise") })
        assertTrue(report.events.none { it.id == 10L })
    }

    @Test
    fun `threshold 0 disables the noise display filter`() {
        val junk = noiseEvent(12, confidence = 0.1)
        val report = ReportAssembler.assemble(
            session(), listOf(junk), emptyList(), emptyList(), noiseMinConfidence = 0.0,
        )!!
        assertEquals(1, report.eventCountsByType[EventType.OTHER_NOISE])
        assertEquals(0, report.lowConfidenceNoiseExcluded)
        assertTrue(report.integrityNotes.none { it.contains("low-confidence") })
    }

    @Test
    fun `stored quiet gaps override the report-time computation`() {
        val stored = listOf(
            QuietGap(t0 + 30 * 60_000, t0 + 42 * 60_000, 12 * 60_000, QuietGapKind.UNCAPTURED, null),
        )
        val report = ReportAssembler.assemble(
            session(), emptyList(), emptyList(), emptyList(), quietGapsOverride = stored,
        )!!
        assertEquals(1, report.quietGaps.size)
        assertEquals(QuietGapKind.UNCAPTURED, report.quietGaps.single().kind)
    }

    @Test
    fun `low-confidence SNORE events are never filtered`() {
        val quietSnore = snoreEvent(20, offsetMs = 2_000_000, durationMs = 4_000, tier = "QUIET")
            .copy(confidence = 0.2)
        val report = ReportAssembler.assemble(session(), listOf(quietSnore), emptyList(), emptyList())!!
        assertEquals(1, report.snoreEventCount)
        assertEquals(0, report.lowConfidenceNoiseExcluded)
    }

    @Test
    fun `bouts and quiet gaps ride along on the report`() {
        // Burst + 3 s gap + burst, then a 7 s gap, then one more burst, then a >10 min
        // silence and a final burst: the three bursts chain at ≤10 s into ONE bout, but the
        // 7 s gap splits them at ≤5 s — making an accidental swap of the two gap constants
        // observable in both directions.
        val first = snoreEvent(30, offsetMs = 60_000, durationMs = 8_000, tier = "LIGHT")
        val second = snoreEvent(31, offsetMs = 71_000, durationMs = 8_000, tier = "LIGHT")
        val third = snoreEvent(32, offsetMs = 86_000, durationMs = 8_000, tier = "LIGHT")
        val afterGap = snoreEvent(33, offsetMs = 60_000 + 12 * 60_000, durationMs = 4_000, tier = "QUIET")
        val report = ReportAssembler.assemble(session(), listOf(first, second, third, afterGap), emptyList(), emptyList())!!
        val stats = report.boutStats!!
        assertEquals(4, stats.burstCount)
        assertEquals(2, stats.boutCount)
        assertEquals(34_000L, stats.longestBoutMs) // 60s → 94s span at the ≤10 s gap
        // The tighter ≤5 s gap: the 7 s pause splits the chain — 3 bouts, longest 19 s.
        assertEquals(3, report.boutStats5s!!.boutCount)
        assertEquals(19_000L, report.boutStats5s!!.longestBoutMs)
        // Two gaps ≥10 min: the 11m41s internal silence and the long trailing quiet after
        // the last event (the 8 h window extends far beyond it).
        assertEquals(2, report.quietGaps.size)
        assertEquals(686_000L, report.quietGaps[0].durationMs) // 94 s → 780 s = 11m26s
    }

    @Test
    fun `quiet gaps use the capture window when a start delay exists`() {
        // 1 h delay: the window between tap and capture start must NOT be a quiet gap.
        val delayed = CaptureStats(
            schemaVersion = 2, windowsProcessed = 57_000, windowsSkipped = 0,
            ambientDbfsMedian = -50.0, ambientDbfsP95 = -40.0,
            clipCount = 10, thermalThrottleEvents = 0,
            captureStartMs = t0 + 3_600_000,
        )
        val late = snoreEvent(40, offsetMs = 3_600_000 + 30_000, durationMs = 4_000, tier = "LIGHT")
        val report = ReportAssembler.assemble(session(), listOf(late), emptyList(), emptyList(), capture = delayed)!!
        assertEquals(t0 + 3_600_000, report.captureWindowStartMs)
        assertTrue(report.quietGaps.none { it.startMs < t0 + 3_600_000 })
    }

    @Test
    fun `share denominator flags the captured-time source`() {
        val withStats = CaptureStats(
            schemaVersion = 2, windowsProcessed = 57_000, windowsSkipped = 0,
            ambientDbfsMedian = -50.0, ambientDbfsP95 = -40.0,
            clipCount = 10, thermalThrottleEvents = 0,
        )
        assertTrue(ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = withStats)!!.shareUsesCapturedTime)
        assertFalse(ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), capture = null)!!.shareUsesCapturedTime)
    }

    @Test
    fun `garmin sleep summary rides along with the window overlap`() {
        val sleep = GarminSleepSummary(
            date = "2026-11-06", sleepStartMs = t0 + 7_200_000, sleepEndMs = t0 + 8 * 3_600_000,
            sleepScore = 71.0, overlapWithCaptureMs = 3_600_000,
            captureWindowStartMs = t0, captureWindowEndMs = t0 + 8 * 3_600_000,
        )
        val report = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), garminSleep = sleep)!!
        assertEquals(71.0, report.garminSleep!!.sleepScore!!, 0.0001)
        assertEquals(3_600_000L, report.garminSleep!!.overlapWithCaptureMs)
    }

    @Test
    fun `garmin overlap math handles partial contained and disjoint windows`() {
        val capStart = t0
        val capEnd = t0 + 6 * 3_600_000L
        // Partial overlap (the real Nov 5→6 shape): Garmin starts mid-capture, ends after it.
        assertEquals(
            4 * 3_600_000L,
            ReportAssembler.garminOverlapMs(capStart, capEnd, t0 + 2 * 3_600_000L, t0 + 9 * 3_600_000L),
        )
        // Garmin window fully inside the capture.
        assertEquals(
            2 * 3_600_000L,
            ReportAssembler.garminOverlapMs(capStart, capEnd, t0 + 3_600_000L, t0 + 3 * 3_600_000L),
        )
        // Disjoint: exactly 0 — the UI's "wrong night's data" signal.
        assertEquals(
            0L,
            ReportAssembler.garminOverlapMs(capStart, capEnd, capEnd + 3_600_000L, capEnd + 4 * 3_600_000L),
        )
    }

    // ---- morning-summary additions: longest event, top clips, coverage, Garmin status ----

    private fun clip(id: Long, eventId: Long, durationMs: Long = 10_000) = AudioClipEntity(
        id = id, sessionId = 1, eventId = eventId,
        filePath = "session_1/evt_$id.wav", durationMs = durationMs, sizeBytes = 320_044,
    )

    @Test
    fun `longest single spell and bout ride the report for the TLDR`() {
        val long = snoreEvent(60, offsetMs = 100_000, durationMs = 300_000, tier = "LIGHT")
        val short = snoreEvent(61, offsetMs = 400_500_000, durationMs = 8_000, tier = "QUIET")
        val report = ReportAssembler.assemble(session(), listOf(long, short), emptyList(), emptyList())!!
        assertEquals(300_000L, report.longestSnore!!.durationMs)
        assertEquals(t0 + 100_000, report.longestSnore!!.startMs)
        // 300 s then 8 s — a 292 s gap splits them: two bouts, longest is the first.
        assertEquals(2, report.boutStats!!.boutCount)
        assertEquals(300_000L, report.boutStats!!.longestBoutMs)
    }

    @Test
    fun `top clips rank by peak then duration and are capped`() {
        val events = listOf(
            snoreEvent(70, offsetMs = 60_000, durationMs = 5_000, tier = "LIGHT").copy(clipId = 1, peakDbfs = -20.0),
            snoreEvent(71, offsetMs = 120_000, durationMs = 9_000, tier = "LIGHT").copy(clipId = 2, peakDbfs = -20.0),
            snoreEvent(72, offsetMs = 180_000, durationMs = 5_000, tier = "LOUD").copy(clipId = 3, peakDbfs = -14.0),
            snoreEvent(73, offsetMs = 240_000, durationMs = 4_000, tier = "QUIET").copy(clipId = 4, peakDbfs = -40.0),
            snoreEvent(74, offsetMs = 300_000, durationMs = 5_000, tier = "QUIET").copy(clipId = 5, peakDbfs = -41.0),
        )
        val clips = listOf(
            clip(1, 70), clip(2, 71, durationMs = 12_000), clip(3, 72), clip(4, 73), clip(5, 74),
        )
        val report = ReportAssembler.assemble(session(), events, clips, emptyList())!!
        // Highest peak first (event 72 at -14), then the -20 pair by clip duration
        // (12 s before 10 s), then the quiet ones by peak.
        assertEquals(listOf(72L, 71L, 70L, 73L, 74L), report.topClips.map { it.eventId })
        assertEquals(-14.0, report.topClips[0].peakDbfs, 0.0001)
    }

    @Test
    fun `top clips respect the low-confidence display filter`() {
        val junk = noiseEvent(90, confidence = 0.3).copy(clipId = 9) // hidden, but has a clip
        val clips = listOf(clip(9, 90))
        val report = ReportAssembler.assemble(session(), listOf(junk), clips, emptyList())!!
        assertEquals(1, report.lowConfidenceNoiseExcluded)
        assertTrue(report.topClips.isEmpty()) // hidden event's clip must not surface
        assertTrue(report.clips.isEmpty())
    }

    @Test
    fun `clip coverage reports spells and union snoring time`() {
        val a = snoreEvent(80, offsetMs = 60_000, durationMs = 30_000, tier = "LIGHT").copy(clipId = 1)
        val b = snoreEvent(81, offsetMs = 70_000, durationMs = 30_000, tier = "LIGHT") // overlaps a, no clip
        val clips = listOf(clip(1, 80))
        val report = ReportAssembler.assemble(session(), listOf(a, b), clips, emptyList())!!
        val c = report.clipCoverage!!
        assertEquals(1, c.snoreEventsWithClip)
        assertEquals(2, c.snoreEventCount)
        assertEquals(30L, c.coveredSeconds) // only a's window has audio
        assertEquals(40L, c.totalSeconds) // union 60s→100s
    }

    @Test
    fun `garmin status reflects payload presence`() {
        val metricsOnly = ReportAssembler.assemble(
            session(), emptyList(), emptyList(), emptyList(),
            garmin = listOf(GarminMetric("VENDOR_HRV_RMSSD", 45.0, "MS", t0)),
        )!!
        assertEquals(GarminLinkStatus.METRICS_ONLY, metricsOnly.garminStatus)

        val missing = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList())!!
        assertEquals(GarminLinkStatus.MISSING, missing.garminStatus)

        val linked = ReportAssembler.assemble(
            session(), emptyList(), emptyList(), emptyList(),
            garmin = listOf(GarminMetric("VENDOR_HRV_RMSSD", 45.0, "MS", t0)),
            garminSleep = GarminSleepSummary("2026-11-06", t0, t0 + 1, 71.0, 1, t0, t0 + 1),
        )!!
        assertEquals(GarminLinkStatus.LINKED, linked.garminStatus)
    }

    @Test
    fun `longest bout stage is the majority vendor stage of its span`() {
        val long = snoreEvent(95, offsetMs = 3_600_000, durationMs = 100_000, tier = "LIGHT")
        // Stage timeline: REM covers 30 s of the bout, LIGHT covers the remaining 70 s.
        val stages = listOf(
            StageSpan(t0 + 3_600_000, t0 + 3_630_000, "REM"),
            StageSpan(t0 + 3_630_000, t0 + 3_700_000, "LIGHT"),
            StageSpan(t0 + 3_700_000, t0 + 3_800_000, "DEEP"), // after the bout — must not count
        )
        val report = ReportAssembler.assemble(
            session(), listOf(long), emptyList(), emptyList(), garminStages = stages,
        )!!
        val bs = report.longestBoutStage!!
        assertEquals("LIGHT", bs.stage)
        assertEquals(0.70, bs.coveredFraction, 0.0001)
    }

    @Test
    fun `stage attribution is null without a timeline or without overlap`() {
        val ev = snoreEvent(96, offsetMs = 3_600_000, durationMs = 60_000, tier = "LIGHT")
        val noStages = ReportAssembler.assemble(session(), listOf(ev), emptyList(), emptyList())!!
        assertNull(noStages.longestBoutStage)

        val disjoint = ReportAssembler.assemble(
            session(), listOf(ev), emptyList(), emptyList(),
            garminStages = listOf(StageSpan(t0, t0 + 1_000, "DEEP")),
        )!!
        assertNull(disjoint.longestBoutStage)
    }

    @Test
    fun `snore minutes per stage split a straddling burst between stages`() {
        // One 180 s burst: 60 s inside REM, 120 s inside LIGHT.
        val burst = snoreEvent(70, offsetMs = 3_600_000, durationMs = 180_000, tier = "LIGHT")
        val stages = listOf(
            StageSpan(t0 + 3_600_000, t0 + 3_660_000, "REM"),
            StageSpan(t0 + 3_660_000, t0 + 3_780_000, "LIGHT"),
        )
        val byStage = ReportAssembler.snoreMinutesPerStage(listOf(burst), stages)
        assertEquals(2, byStage.size)
        assertEquals(2L, byStage[0].snoreMinutes) // LIGHT: 2 m, loudest first
        assertEquals("LIGHT", byStage[0].stage)
        assertEquals(1L, byStage[1].snoreMinutes)
        assertEquals("REM", byStage[1].stage)
        assertEquals(1L, byStage[1].stageMinutes) // minutes, not ms
    }

    @Test
    fun `snore minutes per stage omit silent stages and sort by volume`() {
        val burst1 = snoreEvent(71, offsetMs = 60_000, durationMs = 120_000, tier = "LIGHT")
        val stages = listOf(
            StageSpan(t0 + 60_000, t0 + 180_000, "LIGHT"),
            StageSpan(t0 + 300_000, t0 + 400_000, "DEEP"), // no snoring inside
        )
        val byStage = ReportAssembler.snoreMinutesPerStage(listOf(burst1), stages)
        assertEquals(1, byStage.size) // DEEP omitted — no snoring inside it
        assertEquals("LIGHT", byStage[0].stage)
        assertEquals(2L, byStage[0].snoreMinutes)
        assertEquals(2L, byStage[0].stageMinutes)
    }

    @Test
    fun `previous night comparison rides along ungated`() {
        val prev = PreviousNightSummary(
            sessionId = 99, startedAt = t0 - 86_400_000,
            snoreSeconds = 9336, sharePercent = 30.46, snoreScore = 53,
            scoreFormulaVersion = "snore-score-v2", detectorVersion = "energy-heuristic-v1-spectral",
            sameDetectorVersion = true, sameScoreFormula = true,
        )
        val report = ReportAssembler.assemble(session(), emptyList(), emptyList(), emptyList(), previousNight = prev)!!
        assertEquals(prev, report.previousNight)
    }

    @Test
    fun `quiet gaps ignore the display filter (a gap means nothing was detected)`() {
        // Without the hidden noise event, 60s→1200s would be one 19-min quiet stretch. The
        // hidden low-confidence event sits mid-span: the display must NOT call that quiet.
        val junk = noiseEvent(50, confidence = 0.3).copy(
            startMs = t0 + 630_000,
            endMs = t0 + 634_000,
            offsetMs = 630_000,
        )
        val before = snoreEvent(51, offsetMs = 60_000, durationMs = 4_000, tier = "QUIET")
        val after = snoreEvent(52, offsetMs = 1_200_000, durationMs = 4_000, tier = "QUIET")
        val capture = CaptureStats(
            schemaVersion = 2, windowsProcessed = 57_000, windowsSkipped = 0,
            ambientDbfsMedian = -50.0, ambientDbfsP95 = -40.0,
            clipCount = 10, thermalThrottleEvents = 0,
        )
        val report = ReportAssembler.assemble(
            session(), listOf(before, junk, after), emptyList(), emptyList(), capture = capture,
        )!!
        assertEquals(1, report.lowConfidenceNoiseExcluded) // hidden from display...
        // ...but the detected event still breaks the span: the would-be 19-min quiet stretch
        // (60 s → 1200 s) is split into two 9m26s halves, both under the 10-min threshold, so
        // no reported gap covers the hidden event's span.
        assertTrue(report.quietGaps.none { it.startMs <= t0 + 630_000 && it.endMs >= t0 + 634_000 })
        assertTrue(report.quietGaps.none { it.endMs <= t0 + 1_200_000 })
    }
}
