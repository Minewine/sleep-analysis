package com.sleepanalysis.data.repo

import com.sleepanalysis.data.db.entity.RawPayloadEntity
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.domain.policy.MeasurementType
import com.sleepanalysis.domain.validity.GarminValidators
import com.sleepanalysis.domain.validity.NightValidity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure glue behind the validity inputs (ValidityCalculator delegates to these): the
 * prior-night ambient selection + z-math, and the Garmin revalidation from stored payloads.
 * Direct tests per review n1 — self-exclusion, the ≥5 gate with exactly 4 true priors, the
 * z boundary, stats-less legacy nights (parse-before-window-cut), and payload revalidation.
 */
class ValidityCalculatorTest {

    // 2026-01-12T21:00Z → 2026-01-13T05:30Z (UTC): wake date 2026-01-13.
    private val startMs = 1_768_251_600_000L
    private val endMs = 1_768_282_200_000L
    private val dayStartMs = 1_768_262_400_000L // 2026-01-13T00:00Z

    /** Priors with mean −30 dBFS and sd 1 — the canonical spread used across these tests. */
    private val spreadPriors = listOf(-29.0, -31.0, -29.0, -31.0, -30.0)

    private fun session(
        id: Long,
        startedAt: Long,
        endedAt: Long? = startedAt + 30_600_000L,
        p95: Double? = -30.0,
    ) = SleepSessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        status = "COMPLETED",
        timeZone = "UTC",
        
        
        
        captureStats = if (p95 == null) {
            """{"schema_version":1}""" // stats-less legacy night (pre-v3)
        } else {
            """{"schema_version":3,"ambient_dbfs_p95":$p95}"""
        },
    )

    private fun night(i: Int, self: SleepSessionEntity, p95: Double) =
        session(id = i.toLong(), startedAt = self.startedAt - i * 86_400_000L, p95 = p95)

    // ---- ambient prior-night selection + z-math ---------------------------------

    @Test
    fun `graded night is excluded from its own prior distribution`() {
        val self = session(id = 10, startedAt = startMs, p95 = -34.0)
        val fourPriors = listOf(
            night(1, self, spreadPriors[0]),
            night(2, self, spreadPriors[1]),
            night(3, self, spreadPriors[2]),
            night(4, self, spreadPriors[3]),
        )
        // 4 true priors → the ≥5 gate fails.
        assertNull(ambientNoiseSummaryFor(self, fourPriors))
        // The graded night sitting in the same list must NOT become the fifth prior: with
        // self counted the gate would pass (z ≈ −1.56 against the 5-value distribution),
        // with exclusion it stays null.
        assertNull(ambientNoiseSummaryFor(self, fourPriors + self))
        // A genuine fifth prior passes the gate and yields the exact z.
        assertEquals(
            -4.0,
            ambientNoiseSummaryFor(self, fourPriors + night(5, self, spreadPriors[4]))!!.p95Z,
            1e-9,
        )
    }

    @Test
    fun `later nights never leak into a recomputed older night's priors`() {
        val self = session(id = 10, startedAt = startMs, p95 = -34.0)
        val later = listOf(
            session(11, startedAt = startMs + 86_400_000L, p95 = -79.0),
            session(12, startedAt = startMs + 2 * 86_400_000L, p95 = -81.0),
            session(13, startedAt = startMs + 3 * 86_400_000L, p95 = -79.0),
            session(14, startedAt = startMs + 4 * 86_400_000L, p95 = -81.0),
            session(15, startedAt = startMs + 5 * 86_400_000L, p95 = -80.0),
        )
        // All five "priors" are AFTER the graded night (sd 1 spread, so a leak would produce
        // a huge non-null z): the selection must see zero priors.
        assertNull(ambientNoiseSummaryFor(self, later))
    }

    @Test
    fun `gate needs five true priors - exactly four is omitted`() {
        val self = session(id = 10, startedAt = startMs, p95 = -34.0)
        val four = (1..4).map { night(it, self, spreadPriors[it - 1]) }
        assertNull(ambientNoiseSummaryFor(self, four))
        val five = (1..5).map { night(it, self, spreadPriors[it - 1]) }
        val summary = ambientNoiseSummaryFor(self, five)
        // mean −30, sd 1, tonight −34 → z = −4.0.
        assertEquals(-4.0, summary!!.p95Z, 1e-9)
    }

    @Test
    fun `stats-less legacy nights do not shrink the usable prior window`() {
        val self = session(id = 99, startedAt = startMs + 30 * 86_400_000L, p95 = -34.0)
        // 12 recent stats-less legacy nights + the 5 spread priors (17 candidates total).
        // Parse-before-take must find all 5 valid priors; the take-before-parse bug kept only
        // 2 of them inside the 14-night window and the ≥5 gate failed.
        val sessions = (1..12)
            .map { session(it.toLong(), self.startedAt - it * 86_400_000L, p95 = null) } +
            (1..5).map { night(it, self, spreadPriors[it - 1]) }
        val summary = ambientNoiseSummaryFor(self, sessions)
        assertNotNull(summary)
        assertEquals(-4.0, summary!!.p95Z, 1e-9)
    }

    @Test
    fun `window cut keeps the most recent valid priors`() {
        val self = session(id = 99, startedAt = startMs, p95 = -34.0)
        // 14 identical priors + an outlier 15 nights back: the rolling window keeps the 14
        // most recent and excludes the outlier — all kept priors identical → sd 0 → omitted.
        // (Had the outlier leaked into the window, sd > 0 and the result would be non-null.)
        val recent = (1..14).map { night(it, self, -30.0) }
        val outlier = night(15, self, -80.0)
        assertNull(ambientNoiseSummaryFor(self, recent + outlier))
    }

    // ---- Garmin revalidation from stored payloads --------------------------------

    private fun payload(endpoint: String, json: String, fetchedAt: Long) = RawPayloadEntity(
        source = "GARMIN",
        endpoint = endpoint,
        date = "2026-01-13",
        payload = json,
        fetchedAt = fetchedAt,
    )

    private fun dailySleepJson(hr: Double) =
        """{"sleepStartTimestampGMT":${dayStartMs - 1_800_000},""" +
            """"sleepEndTimestampGMT":$endMs,"sleepTimeSeconds":28800,"avgHeartRate":$hr}"""

    @Test
    fun `revalidation parses stored payloads and runs the validators`() {
        val session = session(id = 10, startedAt = startMs, endedAt = endMs, p95 = -30.0)
        // Window 6.5 h, reported 8 h; HR 260 bpm is implausible and dropped.
        val validity = garminValidityFromPayloads(
            session,
            listOf(
                payload(
                    "/sleep-service/sleep/dailySleep",
                    dailySleepJson(hr = 260.0),
                    fetchedAt = endMs + 3_600_000L, // fresh
                ),
            ),
        )
        assertNotNull(validity)
        // The implausible HR is dropped from the kept metrics; duration survives.
        assertEquals(
            listOf(MeasurementType.VENDOR_SLEEP_DURATION),
            validity!!.keptMetrics.map { it.type },
        )
        assertTrue(validity.warnings.any { it.id == GarminValidators.IMPLAUSIBLE })
        // Reported 480 min vs window 390 min → 90 min off → TST mismatch.
        assertTrue(validity.warnings.any { it.id == GarminValidators.TST_MISMATCH })
    }

    @Test
    fun `revalidation is null without payloads or for a still-recording session`() {
        val session = session(id = 10, startedAt = startMs, endedAt = endMs)
        assertNull(garminValidityFromPayloads(session, emptyList()))
        assertNull(garminValidityFromPayloads(session.copy(endedAt = null), listOf(payload("/x", "{}", 0))))
    }

    @Test
    fun `revalidation freshness anchors on the payload's own fetchedAt`() {
        val session = session(id = 10, startedAt = startMs, endedAt = endMs)
        val late = garminValidityFromPayloads(
            session,
            listOf(payload("/sleep-service/sleep/dailySleep", dailySleepJson(hr = 52.0), fetchedAt = endMs + 25 * 3_600_000L)),
        )
        assertTrue(late!!.warnings.any { it.id == GarminValidators.STALE })
        val fresh = garminValidityFromPayloads(
            session,
            listOf(payload("/sleep-service/sleep/dailySleep", dailySleepJson(hr = 52.0), fetchedAt = endMs + 2 * 3_600_000L)),
        )
        assertTrue(fresh!!.warnings.none { it.id == GarminValidators.STALE })
    }

    // ---- the pure z-core (NightValidity.ambientNoise) -------------------------------

    @Test
    fun `ambientNoise - z math, zero-spread omission and the 5-prior gate`() {
        // mean −30, sd 1.
        assertEquals(2.5, NightValidity.ambientNoise(-27.5, spreadPriors)!!.p95Z, 1e-9)
        assertEquals(-2.5, NightValidity.ambientNoise(-32.5, spreadPriors)!!.p95Z, 1e-9)
        // Zero spread → omit; fewer than 5 priors → omit.
        assertNull(NightValidity.ambientNoise(-30.0, listOf(-30.0, -30.0, -30.0, -30.0, -30.0)))
        assertNull(NightValidity.ambientNoise(-30.0, spreadPriors.take(4)))
    }
}
