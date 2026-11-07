package com.sleepanalysis.domain.report

import com.sleepanalysis.domain.scoring.SnoreScore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shareable plain-text summary: content, version gates, session-timezone rendering. */
class NightSummaryTextTest {

    // Session 3's real start: 2026-11-05 23:37:03 Europe/Zurich (22:37 UTC).
    private val t0 = 1_793_918_223_405L

    private fun report(
        snoreSeconds: Long = 6343,
        share: Double = 29.23,
        previous: PreviousNightSummary? = null,
    ) = MorningReport(
        sessionId = 3,
        startedAt = t0,
        endedAt = t0 + 6 * 3_600_000 + 104_000,
        status = "COMPLETED",
        timeZone = "Europe/Zurich",
        timeInBedSeconds = 25_303,
        snoreSeconds = snoreSeconds,
        snoreSharePercent = share,
        snoreEventCount = 220,
        snoreScore = 53,
        scoreBreakdown = SnoreScore.Result(score = 53, sharePoints = 43.8, intensityPoints = 9.7, meanTierWeight = 0.243),
        intensityBreakdown = mapOf("QUIET" to 170, "LIGHT" to 48, "LOUD" to 2),
        eventCountsByType = mapOf("SNORE" to 220, "OTHER_NOISE" to 55),
        hourly = emptyList(),
        peakDbfs = -14.5,
        meanSnoreDbfs = -44.4,
        events = emptyList(),
        clips = emptyMap(),
        integrityNotes = emptyList(),
        baseline = emptyList(),
        modelVersion = "energy-heuristic-v1-spectral",
        boutStats = BoutStats(
            burstCount = 220, boutCount = 118, longestBurstMs = 313_000, medianBurstMs = 8_500,
            longestBoutMs = 1_292_000, longestBoutStartMs = t0 + 12_482_000,
            longestBoutEndMs = t0 + 13_774_000, longestBoutBursts = 17,
        ),
        longestSnore = LongSnoreEvent(t0 + 7_591_000, 313_000, -25.3),
        topClips = listOf(
            ClipRef(27, "session_3/evt_1.wav", 4_207_000, 10_000, "LOUD", "SNORE", -14.5),
        ),
        previousNight = previous,
    )

    @Test
    fun `summary contains the headline numbers in the session timezone`() {
        val text = NightSummaryText.build(report())
        // 23:37 local Nov 5 — the session zone, not UTC (22:37 same instant).
        assertTrue(text, text.contains("Night of Thu 5 Nov 2026 (Zurich)"))
        // Loudest clip at +70 min = 00:47 local Nov 6.
        assertTrue(text, text.contains("Loudest clip: 00:47 at -14.5 dBFS"))
        assertTrue(text, text.contains("Snored 1h 45m — 29% of the 6h 1m captured"))
        assertTrue(text, text.contains("Worst stretch: 21m (03:05–03:26)"))
        assertTrue(text, text.contains("Longest burst: 5m from 01:43"))
        assertTrue(text, text.contains("Score 53/100 (share 44 + intensity 10; snore-score-v2)"))
        assertFalse(text, text.contains("vs previous night")) // no previous night supplied
    }

    @Test
    fun `comparison appears only when the detector version matches`() {
        val same = PreviousNightSummary(
            sessionId = 1, startedAt = t0 - 86_400_000, snoreSeconds = 9336, sharePercent = 30.46,
            snoreScore = 53, scoreFormulaVersion = "snore-score-v2",
            detectorVersion = "energy-heuristic-v1-spectral",
            sameDetectorVersion = true, sameScoreFormula = true,
        )
        val text = NightSummaryText.build(report(previous = same))
        // 9336 - 6343 = 2993 s = 49m53s → "−49m" in the hms truncation.
        assertTrue(text, text.contains("vs previous night: 2h 35m snoring (−49m), score 53 vs 53"))

        val different = same.copy(
            detectorVersion = "energy-heuristic-v0",
            sameDetectorVersion = false,
            sameScoreFormula = false,
        )
        val hidden = NightSummaryText.build(report(previous = different))
        assertTrue(hidden, hidden.contains("Previous night not compared"))
        assertFalse(hidden, hidden.contains("vs previous night:"))
    }

    @Test
    fun `zero delta renders without a sign`() {
        val same = PreviousNightSummary(
            sessionId = 1, startedAt = t0 - 86_400_000, snoreSeconds = 6343, sharePercent = 29.23,
            snoreScore = 53, scoreFormulaVersion = "snore-score-v2",
            detectorVersion = "energy-heuristic-v1-spectral",
            sameDetectorVersion = true, sameScoreFormula = true,
        )
        val text = NightSummaryText.build(report(previous = same))
        assertTrue(text, text.contains("vs previous night: 1h 45m snoring (0m), score 53 vs 53"))
    }

    @Test
    fun `quiet gaps appear with the longest one`() {
        val withGaps = report().copy(
            quietGaps = listOf(
                QuietGap(t0 + 10 * 60_000, t0 + 21 * 60_000, 11 * 60_000),
                QuietGap(t0 + 60 * 60_000, t0 + 87 * 60_000, 27 * 60_000),
            ),
        )
        val text = NightSummaryText.build(withGaps)
        assertTrue(text, text.contains("Quiet stretches ≥10 min: 2 (longest 27m)"))
    }
}
