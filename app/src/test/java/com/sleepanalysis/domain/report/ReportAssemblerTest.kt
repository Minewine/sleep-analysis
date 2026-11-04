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
}
