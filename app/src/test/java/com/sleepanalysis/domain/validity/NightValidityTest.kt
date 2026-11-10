package com.sleepanalysis.domain.validity

import com.sleepanalysis.data.files.ClipIntegrity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NightValidity v1 ("night-validity-v1"): every check's trigger/no-trigger boundary, the full
 * grade matrix, and the notes JSON format the NIGHT_VALIDITY row stores verbatim.
 */
class NightValidityTest {

    // ---- Helpers --------------------------------------------------------------

    /** A healthy default capture summary; tests override one field at a time. */
    private fun capture(
        windowsProcessed: Long? = 57_600L, // 8 h × 2 windows/s
        windowSeconds: Long? = 8 * 3600L,
        thermalThrottleEvents: Int? = 0,
        skippedHops: Int = 0,
    ) = NightValidity.CaptureSummary(
        windowsProcessed = windowsProcessed,
        windowSeconds = windowSeconds,
        thermalThrottleEvents = thermalThrottleEvents,
        skippedHops = skippedHops,
    )

    /** A clean night: nothing triggers, grade A. */
    private fun clean(
        transform: (NightValidity.Input) -> NightValidity.Input = { it },
    ): NightValidity.Result = NightValidity.compute(
        transform(
            NightValidity.Input(
                capture = capture(),
                clips = NightValidity.ClipQualitySummary(clipCount = 12, clippedCount = 0, unreadableCount = 0, missingCount = 0),
                uncapturedGapMinutes = 0.0,
                sessionStatus = "COMPLETED",
                resumedAfterInterruption = false,
                snoreShareOfCapture = 0.15,
                ambient = NightValidity.AmbientNoiseSummary(p95Z = 0.4),
            ),
        ),
    )

    private fun reasonIds(result: NightValidity.Result) = result.reasons.map { it.id }

    private fun warnings(result: NightValidity.Result) = result.reasons.count { it.severity == NightValidity.Severity.WARNING }

    // ---- capture_failed -------------------------------------------------------

    @Test
    fun `zero windows with a real capture window is critical`() {
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(windowsProcessed = 0, windowSeconds = 3600)),
        )
        assertEquals("D", r.grade)
        assertTrue(ReasonIdsOf.contains(r.reasons, "capture_failed", NightValidity.Severity.CRITICAL))
    }

    @Test
    fun `zero windows below the failure window falls to the shortfall check`() {
        // Mirrors the report heuristic: below 30 s the night is not "failed", but 0 of ~40
        // expected windows is still a 100% shortfall — the severe path.
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(windowsProcessed = 0, windowSeconds = 20)),
        )
        assertFalse(reasonIds(r).contains("capture_failed"))
        assertTrue(ReasonIdsOf.contains(r.reasons, "capture_gaps_severe", NightValidity.Severity.CRITICAL))
        assertEquals("D", r.grade)
    }

    @Test
    fun `unknown window count never claims a capture failure`() {
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(windowsProcessed = null, windowSeconds = 3600)),
        )
        assertFalse(reasonIds(r).contains("capture_failed"))
        assertFalse(reasonIds(r).contains("capture_gaps"))
    }

    // ---- capture gaps (shortfall) ---------------------------------------------

    @Test
    fun `shortfall at exactly 10 percent does not trigger`() {
        // 90% of 7200 expected windows processed → shortfall exactly 0.10 (boundary: strict >).
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(windowsProcessed = 6480, windowSeconds = 3600)),
        )
        assertFalse(reasonIds(r).contains("capture_gaps"))
        assertEquals("A", r.grade)
    }

    @Test
    fun `shortfall just over 10 percent warns`() {
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(windowsProcessed = 6400, windowSeconds = 3600)),
        )
        assertTrue(ReasonIdsOf.contains(r.reasons, "capture_gaps", NightValidity.Severity.WARNING))
        assertEquals("A", r.grade) // single warning stays A
    }

    @Test
    fun `shortfall just over 40 percent is critical`() {
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(windowsProcessed = 4300, windowSeconds = 3600)),
        )
        assertTrue(ReasonIdsOf.contains(r.reasons, "capture_gaps_severe", NightValidity.Severity.CRITICAL))
        assertFalse(reasonIds(r).contains("capture_gaps"))
        assertEquals("D", r.grade)
    }

    @Test
    fun `shortfall at exactly 40 percent stays a warning`() {
        // 4320 of 7200 expected windows → shortfall exactly 0.4 (fp-exact here): the strict >
        // convention means the severe threshold is NOT crossed.
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(windowsProcessed = 4320, windowSeconds = 3600)),
        )
        assertFalse(reasonIds(r).contains("capture_gaps_severe"))
        assertTrue(ReasonIdsOf.contains(r.reasons, "capture_gaps", NightValidity.Severity.WARNING))
    }

    @Test
    fun `processed above expected never goes negative`() {
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(windowsProcessed = 10_000, windowSeconds = 3600)),
        )
        assertTrue(reasonIds(r).isEmpty())
    }

    // ---- thermal throttle ------------------------------------------------------

    @Test
    fun `throttling with skipped hops warns`() {
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(thermalThrottleEvents = 2, skippedHops = 7200)),
        )
        assertTrue(ReasonIdsOf.contains(r.reasons, "thermal_throttle", NightValidity.Severity.WARNING))
    }

    @Test
    fun `throttling without skipped hops does not warn`() {
        val r = NightValidity.compute(
            NightValidity.Input(capture = capture(thermalThrottleEvents = 3, skippedHops = 0)),
        )
        assertFalse(reasonIds(r).contains("thermal_throttle"))
    }

    // ---- unexplained gaps ------------------------------------------------------

    @Test
    fun `uncaptured gaps escalate from silent to warning to critical`() {
        assertEquals(emptyList<String>(), reasonIds(clean { it.copy(uncapturedGapMinutes = 15.0) }))
        assertTrue(
            ReasonIdsOf.contains(
                clean { it.copy(uncapturedGapMinutes = 15.1) }.reasons,
                "unexplained_gap", NightValidity.Severity.WARNING,
            ),
        )
        assertTrue(
            ReasonIdsOf.contains(
                clean { it.copy(uncapturedGapMinutes = 60.0) }.reasons,
                "unexplained_gap", NightValidity.Severity.WARNING,
            ),
        )
        val critical = clean { it.copy(uncapturedGapMinutes = 60.5) }
        assertTrue(ReasonIdsOf.contains(critical.reasons, "unexplained_gap", NightValidity.Severity.CRITICAL))
        assertEquals("D", critical.grade)
    }

    // ---- clipped clips ----------------------------------------------------------

    @Test
    fun `clipped rate boundaries warn and go critical`() {
        // exactly 10% of 10 clips: no trigger (strict >).
        val at10 = clean {
            it.copy(clips = NightValidity.ClipQualitySummary(10, 1, 0, 0))
        }
        assertEquals(emptyList<String>(), reasonIds(at10))
        // 2/10 clipped → warning, grade still A (single warning).
        val over10 = clean { it.copy(clips = NightValidity.ClipQualitySummary(10, 2, 0, 0)) }
        assertTrue(ReasonIdsOf.contains(over10.reasons, "clipped_clips", NightValidity.Severity.WARNING))
        // exactly 30% clipped (fp-exact): strict > keeps it a warning, not critical.
        val at30 = clean { it.copy(clips = NightValidity.ClipQualitySummary(10, 3, 0, 0)) }
        assertTrue(ReasonIdsOf.contains(at30.reasons, "clipped_clips", NightValidity.Severity.WARNING))
        assertFalse(at30.reasons.any { it.id == "clipped_clips" && it.severity == NightValidity.Severity.CRITICAL })
        // 4/10 clipped → critical → D.
        val over30 = clean { it.copy(clips = NightValidity.ClipQualitySummary(10, 4, 0, 0)) }
        assertTrue(ReasonIdsOf.contains(over30.reasons, "clipped_clips", NightValidity.Severity.CRITICAL))
        assertEquals("D", over30.grade)
        // no clips at all → never a rate.
        val noClips = clean { it.copy(clips = NightValidity.ClipQualitySummary(0, 0, 0, 0)) }
        assertFalse(reasonIds(noClips).contains("clipped_clips"))
    }

    // ---- ambient (relative only) ----------------------------------------------

    @Test
    fun `ambient z above 2_5 warns and the check is omitted without input`() {
        val loud = clean { it.copy(ambient = NightValidity.AmbientNoiseSummary(p95Z = 2.6)) }
        assertTrue(ReasonIdsOf.contains(loud.reasons, "ambient_noise_high", NightValidity.Severity.WARNING))
        // Exactly at the threshold: no trigger (strict >).
        assertEquals(emptyList<String>(), reasonIds(clean { it.copy(ambient = NightValidity.AmbientNoiseSummary(p95Z = 2.5)) }))
        // No prior-night distribution (fewer than 5 nights) → omitted, never a guess.
        assertEquals(emptyList<String>(), reasonIds(clean { it.copy(ambient = null) }))
    }

    // ---- session status ---------------------------------------------------------

    @Test
    fun `non-completed or resumed sessions warn`() {
        val recovered = clean { it.copy(sessionStatus = "RECOVERED") }
        assertTrue(ReasonIdsOf.contains(recovered.reasons, "session_interrupted", NightValidity.Severity.WARNING))
        val resumed = clean { it.copy(resumedAfterInterruption = true) }
        assertTrue(ReasonIdsOf.contains(resumed.reasons, "session_interrupted", NightValidity.Severity.WARNING))
        assertEquals(emptyList<String>(), reasonIds(clean { it.copy(sessionStatus = "COMPLETED") }))
    }

    // ---- Garmin passthrough ------------------------------------------------------

    @Test
    fun `garmin warnings pass through with their ids as warning reasons`() {
        val garmin = GarminValidators.GarminValidity(
            warnings = listOf(
                GarminValidators.Warning("garmin_stale", "imported 30.0 h after the night ended (limit 24 h)"),
                GarminValidators.Warning("garmin_implausible", "values dropped: VENDOR_HRV_RMSSD 0.5 ms outside 2.0–300.0 ms"),
            ),
            keptMetrics = emptyList(),
            validSpans = emptyList(),
        )
        val r = clean { it.copy(garmin = garmin) }
        assertTrue(ReasonIdsOf.contains(r.reasons, "garmin_stale", NightValidity.Severity.WARNING))
        assertTrue(ReasonIdsOf.contains(r.reasons, "garmin_implausible", NightValidity.Severity.WARNING))
        assertEquals("B", r.grade) // two warnings → B
    }

    @Test
    fun `no garmin import is no input`() {
        assertEquals(emptyList<String>(), reasonIds(clean { it.copy(garmin = null) }))
    }

    // ---- low snore signal (INFO) -------------------------------------------------

    @Test
    fun `low snore share is an info that never lowers the grade`() {
        val r = clean { it.copy(snoreShareOfCapture = 0.009) }
        assertTrue(ReasonIdsOf.contains(r.reasons, "low_snore_signal", NightValidity.Severity.INFO))
        assertEquals("A", r.grade)
        assertEquals(emptyList<String>(), reasonIds(clean { it.copy(snoreShareOfCapture = 0.01) }))
        // Unknown share → omitted.
        assertEquals(emptyList<String>(), reasonIds(clean { it.copy(snoreShareOfCapture = null) }))
    }

    // ---- grade matrix -------------------------------------------------------------

    @Test
    fun `grade matrix - zero or one warning is A`() {
        assertEquals("A", clean().grade)
        assertEquals("A", clean { it.copy(uncapturedGapMinutes = 20.0) }.grade)
    }

    @Test
    fun `grade matrix - two or three warnings is B`() {
        val two = clean {
            it.copy(uncapturedGapMinutes = 20.0, sessionStatus = "RECOVERED")
        }
        assertEquals(2, warnings(two))
        assertEquals("B", two.grade)
        val three = clean {
            it.copy(
                uncapturedGapMinutes = 20.0,
                sessionStatus = "RECOVERED",
                clips = NightValidity.ClipQualitySummary(10, 2, 0, 0),
            )
        }
        assertEquals(3, warnings(three))
        assertEquals("B", three.grade)
    }

    @Test
    fun `grade matrix - four warnings is C`() {
        val four = clean {
            it.copy(
                uncapturedGapMinutes = 20.0,
                sessionStatus = "RECOVERED",
                clips = NightValidity.ClipQualitySummary(10, 2, 0, 0),
                ambient = NightValidity.AmbientNoiseSummary(p95Z = 3.0),
            )
        }
        assertEquals(4, warnings(four))
        assertEquals("C", four.grade)
    }

    @Test
    fun `grade matrix - any critical is D regardless of warnings`() {
        val r = clean { it.copy(uncapturedGapMinutes = 61.0) }
        assertEquals("D", r.grade)
    }

    @Test
    fun `no capture stats at all grades D with stats_missing`() {
        val r = NightValidity.compute(
            NightValidity.Input(
                capture = null,
                sessionStatus = "COMPLETED",
                snoreShareOfCapture = 0.2,
            ),
        )
        assertTrue(ReasonIdsOf.contains(r.reasons, "stats_missing", NightValidity.Severity.CRITICAL))
        assertEquals("D", r.grade)
    }

    // ---- captureSummary (the window rule) ------------------------------------------

    @Test
    fun `capture summary uses the capture-start window and falls back to time in bed`() {
        val endedAt = 1_800_000_000_000L
        val stats = com.sleepanalysis.domain.report.CaptureStats(
            schemaVersion = 3,
            windowsProcessed = 100,
            windowsSkipped = 4,
            ambientDbfsMedian = -40.0,
            ambientDbfsP95 = -30.0,
            clipCount = 0,
            thermalThrottleEvents = 1,
            skippedHopSpans = listOf(
                com.sleepanalysis.domain.report.SkippedHopSpan(0, 1000, 3),
                com.sleepanalysis.domain.report.SkippedHopSpan(2000, 3000, 2),
            ),
            captureStartMs = endedAt - 3600_000,
        )
        val s = NightValidity.captureSummary(stats, endedAt, timeInBedSeconds = 7200)
        assertEquals(3600L, s?.windowSeconds) // delay-aware window, not time in bed
        assertEquals(100L, s?.windowsProcessed)
        assertEquals(1, s?.thermalThrottleEvents)
        assertEquals(5, s?.skippedHops)
        // Pre-v8 stats (no capture_started_at) fall back to time in bed.
        val legacy = stats.copy(captureStartMs = null)
        assertEquals(7200L, NightValidity.captureSummary(legacy, endedAt, 7200)?.windowSeconds)
        // No stats at all → null input (the stats_missing path).
        assertNull(NightValidity.captureSummary(null, endedAt, 7200))
    }

    // ---- clip quality aggregation (shared with the report scan) ----------------------

    @Test
    fun `clip quality summary counts clipped unreadable and missing separately`() {
        val checks = listOf(
            ClipIntegrity.ClipCheck("a.wav", exists = true, clipped = true),
            ClipIntegrity.ClipCheck("b.wav", exists = true, clipped = false),
            ClipIntegrity.ClipCheck("c.wav", exists = true, readable = false),
            ClipIntegrity.ClipCheck("d.wav", exists = false),
        )
        val s = NightValidity.clipQualitySummary(checks)
        assertEquals(4, s.clipCount)
        assertEquals(1, s.clippedCount)
        assertEquals(1, s.unreadableCount)
        assertEquals(1, s.missingCount)
    }

    // ---- notes JSON -------------------------------------------------------------------

    @Test
    fun `notes json is stable and round-trips through parseNotes`() {
        val r = clean {
            it.copy(uncapturedGapMinutes = 20.0, sessionStatus = "RECOVERED")
        }
        val json = NightValidity.notes(r)
        assertEquals(
            "{\"version\":\"night-validity-v1\",\"grade\":\"B\"," +
                "\"reasons\":[{\"id\":\"unexplained_gap\",\"severity\":\"WARNING\",\"detail\":\"" +
                "20 min of the night have no capture evidence\"}," +
                "{\"id\":\"session_interrupted\",\"severity\":\"WARNING\",\"detail\":\"session status recovered\"}]}",
            json,
        )
        val parsed = NightValidity.parseNotes(json)
        assertEquals(r, parsed)
    }

    @Test
    fun `notes json escapes quotes and backslashes`() {
        val r = NightValidity.Result(
            grade = "A",
            reasons = listOf(
                NightValidity.Reason("x", NightValidity.Severity.INFO, "said \"hi\" \\ ok"),
            ),
        )
        val parsed = NightValidity.parseNotes(NightValidity.notes(r))
        assertEquals(r, parsed)
    }

    @Test
    fun `parseNotes rejects wrong versions and unknown grades`() {
        assertNull(NightValidity.parseNotes(null))
        assertNull(NightValidity.parseNotes(""))
        assertNull(NightValidity.parseNotes("{\"version\":\"night-validity-v0\",\"grade\":\"A\",\"reasons\":[]}"))
        assertNull(NightValidity.parseNotes("{\"version\":\"night-validity-v1\",\"grade\":\"F\",\"reasons\":[]}"))
        assertNull(NightValidity.parseNotes("not json"))
    }

    /** Tiny readability shim for the reason assertions above. */
    private object ReasonIdsOf {
        fun contains(reasons: List<NightValidity.Reason>, id: String, severity: NightValidity.Severity): Boolean =
            reasons.any { it.id == id && it.severity == severity }
    }
}
