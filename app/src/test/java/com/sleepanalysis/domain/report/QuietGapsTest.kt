package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quiet-stretch tracking (≥10 min) inside the capture window. */
class QuietGapsTest {

    private val t0 = 1_730_000_000_000L
    private val windowEnd = t0 + 6 * 3_600_000L

    private fun event(startMs: Long, durationMs: Long, type: String = EventType.SNORE) =
        AcousticEventEntity(
            id = startMs / 1000, sessionId = 1, type = type,
            startMs = startMs, endMs = startMs + durationMs,
            offsetMs = startMs - t0, durationMs = durationMs,
            peakDbfs = -30.0, meanDbfs = -35.0, intensityTier = "LIGHT",
            confidence = 0.9, modelVersion = "energy-heuristic-v1-spectral",
        )

    @Test
    fun `internal gaps of 11 and 12 minutes are found`() {
        // Events around two real quiet stretches (session 3: 01:53→02:04, 03:59→04:11).
        // The window is pinned to the events so leading/trailing quiet stays out of the way.
        val firstStart = t0 + 4_800_000
        val secondStart = firstStart + 8_000 + 11 * 60_000 + 5_000 // 11m05s gap
        val thirdStart = secondStart + 8_000 + 12 * 60_000 // 12m gap
        val gaps = quietGaps(
            listOf(
                event(firstStart, 8_000),
                event(secondStart, 8_000),
                event(thirdStart, 8_000),
            ),
            windowStartMs = firstStart,
            windowEndMs = thirdStart + 8_000 + 1_000,
        )
        assertEquals(2, gaps.size)
        assertEquals(11 * 60_000 + 5_000, gaps[0].durationMs)
        assertEquals(12 * 60_000, gaps[1].durationMs)
    }

    @Test
    fun `sub-threshold gaps are ignored`() {
        val gaps = quietGaps(
            listOf(event(t0, 1_000), event(t0 + 9 * 60_000, 1_000), event(t0 + 18 * 60_000, 1_000)),
            windowStartMs = t0,
            windowEndMs = windowEnd,
        )
        // Two 9-min gaps: both under 10 min → nothing. The long tail after the last event
        // (hours) IS a quiet stretch.
        assertTrue(gaps.all { it.startMs >= t0 + 18 * 60_000 + 1_000 })
    }

    @Test
    fun `leading and trailing quiet count as gaps`() {
        val gaps = quietGaps(
            listOf(event(t0 + 15 * 60_000, 1_000)), // one event, 15 min after capture start
            windowStartMs = t0,
            windowEndMs = windowEnd,
        )
        assertEquals(2, gaps.size)
        assertEquals(15 * 60_000, gaps[0].durationMs) // leading
        assertEquals(windowEnd - (t0 + 15 * 60_000 + 1_000), gaps[1].durationMs) // trailing
    }

    @Test
    fun `a start delay must not appear as a quiet gap`() {
        // Window begins at capture start (capture_started_at), NOT at the tap.
        val captureStart = t0 + 60 * 60_000L
        val gaps = quietGaps(
            listOf(event(captureStart + 30 * 60_000, 1_000)),
            windowStartMs = captureStart,
            windowEndMs = windowEnd,
        )
        assertTrue(gaps.none { it.startMs < captureStart })
    }

    @Test
    fun `events outside the window are ignored`() {
        val before = event(t0 - 3_600_000, 3_600_000) // ends exactly at window start
        val gaps = quietGaps(listOf(before), windowStartMs = t0, windowEndMs = windowEnd)
        // The pre-window event contributes nothing; the whole window is one trailing gap.
        assertEquals(1, gaps.size)
        assertEquals(t0, gaps[0].startMs)
        assertEquals(windowEnd - t0, gaps[0].durationMs)
    }

    @Test
    fun `degenerate windows yield nothing`() {
        assertTrue(quietGaps(emptyList(), windowStartMs = t0, windowEndMs = t0).isEmpty())
        assertTrue(quietGaps(emptyList(), windowStartMs = t0, windowEndMs = t0 - 1).isEmpty())
    }

    // ---- classification via the capture's level buckets (schema v3 stats) ----

    private fun bucket(startMs: Long, endMs: Long, meanDbfs: Double) =
        AmbientLevelBucket(startMs, endMs, meanDbfs)

    @Test
    fun `fully covered gap is QUIET with the overlap-weighted mean level`() {
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        val buckets = listOf(
            bucket(t0, t0 + 5 * 60_000, -90.0),
            bucket(t0 + 5 * 60_000, t0 + 10 * 60_000, -80.0),
            bucket(t0 + 10 * 60_000, t0 + 15 * 60_000, -85.0),
        )
        val classified = classifyQuietGaps(listOf(gap), buckets).single()
        assertEquals(QuietGapKind.QUIET, classified.kind)
        // 2 buckets cover fully, the third half → weighted mean of (-90×5 -80×5 -85×2)/12.
        assertEquals((-90 * 5 + -80 * 5 + -85 * 2) / 12.0, classified.meanDbfs!!, 0.0001)
    }

    @Test
    fun `gap with no bucket evidence is UNCAPTURED (process death)`() {
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        val classified = classifyQuietGaps(
            listOf(gap),
            listOf(bucket(t0 + 30 * 60_000, t0 + 35 * 60_000, -80.0)),
        ).single()
        assertEquals(QuietGapKind.UNCAPTURED, classified.kind)
        assertNull(classified.meanDbfs)
    }

    @Test
    fun `partial evidence stays unclassified rather than guessing`() {
        // Buckets cover 25% of the gap (process death mid-way): neither quiet nor uncaptured.
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        val classified = classifyQuietGaps(
            listOf(gap),
            listOf(bucket(t0, t0 + 3 * 60_000, -85.0)),
        ).single()
        assertNull(classified.kind)
        assertNull(classified.meanDbfs)
    }

    @Test
    fun `no buckets at all leaves gaps unclassified (pre-v3 nights)`() {
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        assertEquals(gap, classifyQuietGaps(listOf(gap), emptyList()).single())
    }

    // ---- digital silence: a silenced/dead mic delivers zeros, not a quiet bedroom (P2-4) ----

    @Test
    fun `digital-silence buckets classify the gap UNCAPTURED not QUIET`() {
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        val buckets = listOf(
            bucket(t0, t0 + 5 * 60_000, -100.0),
            bucket(t0 + 5 * 60_000, t0 + 10 * 60_000, -100.0),
            bucket(t0 + 10 * 60_000, t0 + 15 * 60_000, -100.0),
        )
        val classified = classifyQuietGaps(listOf(gap), buckets).single()
        assertEquals(QuietGapKind.UNCAPTURED, classified.kind)
        // The zeros ARE level evidence — of silence, not of a quiet bedroom.
        assertEquals(-100.0, classified.meanDbfs!!, 0.0001)
    }

    @Test
    fun `near-zero variance marks a bucket digital silence even above the level cutoff`() {
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        val buckets = listOf(
            AmbientLevelBucket(t0, t0 + 5 * 60_000, -80.0, coveredMs = 5 * 60_000, dbfsVariance = 0.0),
            AmbientLevelBucket(t0 + 5 * 60_000, t0 + 10 * 60_000, -80.0, coveredMs = 5 * 60_000, dbfsVariance = 0.0),
            AmbientLevelBucket(t0 + 10 * 60_000, t0 + 15 * 60_000, -80.0, coveredMs = 5 * 60_000, dbfsVariance = 0.0),
        )
        assertEquals(QuietGapKind.UNCAPTURED, classifyQuietGaps(listOf(gap), buckets).single().kind)
    }

    @Test
    fun `a client-silenced span forces UNCAPTURED even when buckets look healthy`() {
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        val buckets = listOf(
            bucket(t0, t0 + 5 * 60_000, -85.0),
            bucket(t0 + 5 * 60_000, t0 + 10 * 60_000, -84.0),
            bucket(t0 + 10 * 60_000, t0 + 15 * 60_000, -86.0),
        )
        val classified = classifyQuietGaps(
            listOf(gap),
            buckets,
            clientSilencedSpans = listOf(ClientSilencedSpan(t0, t0 + 12 * 60_000)),
        ).single()
        assertEquals(QuietGapKind.UNCAPTURED, classified.kind)
        assertNull(classified.meanDbfs)
    }

    @Test
    fun `partly silenced gap stays unclassified rather than being called quiet`() {
        // First 2 min silenced (Android delivered zeros), the rest verifiably quiet: neither
        // "quiet" (part was never heard) nor a clean "not captured" (most was).
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        val buckets = listOf(
            AmbientLevelBucket(t0 + 2 * 60_000, t0 + 7 * 60_000, -85.0, coveredMs = 5 * 60_000),
            AmbientLevelBucket(t0 + 7 * 60_000, t0 + 12 * 60_000, -85.0, coveredMs = 5 * 60_000),
        )
        val classified = classifyQuietGaps(
            listOf(gap),
            buckets,
            clientSilencedSpans = listOf(ClientSilencedSpan(t0, t0 + 2 * 60_000)),
        ).single()
        assertNull(classified.kind)
    }

    @Test
    fun `healthy buckets still classify QUIET when nothing was silenced`() {
        // Digital-silence logic must not eat a real quiet bedroom: −85 dBFS with real variance.
        val gap = QuietGap(t0, t0 + 12 * 60_000, 12 * 60_000)
        val buckets = listOf(
            AmbientLevelBucket(t0, t0 + 5 * 60_000, -85.0, coveredMs = 5 * 60_000, dbfsVariance = 2.0),
            AmbientLevelBucket(t0 + 5 * 60_000, t0 + 10 * 60_000, -84.0, coveredMs = 5 * 60_000, dbfsVariance = 1.5),
            AmbientLevelBucket(t0 + 10 * 60_000, t0 + 15 * 60_000, -86.0, coveredMs = 5 * 60_000, dbfsVariance = 3.0),
        )
        val classified = classifyQuietGaps(listOf(gap), buckets).single()
        assertEquals(QuietGapKind.QUIET, classified.kind)
    }

    // ---- TL;DR totals (P2-5): only kind == QUIET is a quiet stretch ----

    @Test
    fun `totals split quiet from not-captured and ignore unclassified gaps`() {
        val totals = QuietStretchTotals.of(
            listOf(
                QuietGap(t0, t0 + 11 * 60_000, 11 * 60_000, kind = QuietGapKind.QUIET),
                QuietGap(t0, t0 + 27 * 60_000, 27 * 60_000, kind = QuietGapKind.UNCAPTURED),
                QuietGap(t0, t0 + 15 * 60_000, 15 * 60_000, kind = null),
            ),
        )
        assertEquals(1, totals.quietCount)
        assertEquals(11 * 60_000L, totals.quietTotalMs)
        assertEquals(11 * 60_000L, totals.quietLongestMs)
        assertEquals(1, totals.uncapturedCount)
        assertEquals(27 * 60_000L, totals.uncapturedTotalMs)
        assertEquals(27 * 60_000L, totals.uncapturedLongestMs)
    }

    @Test
    fun `empty gaps give empty totals`() {
        val totals = QuietStretchTotals.of(emptyList())
        assertEquals(0, totals.quietCount)
        assertEquals(0, totals.uncapturedCount)
        assertNull(totals.quietLongestMs)
        assertNull(totals.uncapturedLongestMs)
    }
}
