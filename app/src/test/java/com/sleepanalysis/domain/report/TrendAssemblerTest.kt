package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.db.entity.QuietGapEntity
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.db.entity.UserLabel
import com.sleepanalysis.domain.policy.MeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Trend rows: stored measurements for the report-reproducible numbers, events/clips for the
 * structure numbers (bout, mean peak, coverage), and separate detector/score version gates.
 */
class TrendAssemblerTest {

    private val dayMs = 24 * 3_600_000L
    private var nextId = 1L

    private fun session(
        startedAt: Long,
        status: String = SessionStatus.COMPLETED,
        captureStats: String? = """{"schema_version":2,"windows_processed":40000}""",
    ) = SleepSessionEntity(
        id = nextId++, startedAt = startedAt, endedAt = startedAt + 8 * 3_600_000L,
        status = status, timeZone = "Europe/Zurich", 
        captureStats = captureStats,
    )

    private fun garmin(sessionId: Long, type: String, value: Double) = MeasurementEntity(
        sessionId = sessionId, timestamp = 0, sourceType = "GARMIN", method = "DEVICE_REPORTED",
        isClinical = false, measurementType = type, tier = "ESTABLISHED",
        valueNum = value, unit = "SECONDS",
    )

    private fun measurement(sessionId: Long, type: MeasurementType, value: Double, modelVersion: String? = null) =
        MeasurementEntity(
            sessionId = sessionId, timestamp = 0, sourceType = "PHONE", method = "ALGORITHM_ESTIMATED",
            isClinical = false, measurementType = type.name, tier = "ESTABLISHED",
            valueNum = value, unit = type.unit.name, modelVersion = modelVersion,
        )

    private fun snore(sessionId: Long, startOffset: Long, duration: Long, peak: Double, clipId: Long? = null, version: String = "energy-heuristic-v1-spectral") =
        AcousticEventEntity(
            id = nextId++, sessionId = sessionId, type = EventType.SNORE,
            startMs = startedAtFor(sessionId) + startOffset, endMs = startedAtFor(sessionId) + startOffset + duration,
            offsetMs = startOffset, durationMs = duration,
            peakDbfs = peak, meanDbfs = peak - 5, intensityTier = "LIGHT",
            confidence = 0.9, modelVersion = version, clipId = clipId,
        )

    private val startedAts = mutableMapOf<Long, Long>()
    private fun startedAtFor(sessionId: Long): Long = startedAts.getValue(sessionId)

    private fun clip(id: Long, sessionId: Long, eventId: Long) = AudioClipEntity(
        id = id, sessionId = sessionId, eventId = eventId,
        filePath = "session_$sessionId/evt_$id.wav", durationMs = 10_000, sizeBytes = 320_044,
    )

    @Test
    fun `trend row combines stored measurements and computed structure`() {
        val s = session(1_000L)
        startedAts[s.id] = 1_000L
        val clipped = snore(s.id, 60_000, 40_000, -30.0, clipId = 1)
        val e2 = snore(s.id, 101_000, 60_000, -40.0) // 1 s gap → same bout
        val e3 = snore(s.id, 400_000, 313_000, -25.3)
        val rows = TrendAssembler.assemble(
            listOf(s),
            mapOf(
                s.id to listOf(
                    measurement(s.id, MeasurementType.SNORE_SCORE, 53.0, "snore-score-v2"),
                    measurement(s.id, MeasurementType.SNORE_TIME, 6343.0),
                    measurement(s.id, MeasurementType.SNORE_SHARE_OF_NIGHT, 29.23),
                ),
            ),
            mapOf(s.id to listOf(clipped, e2, e3)),
            mapOf(s.id to listOf(clip(1, s.id, clipped.id))),
            emptyMap(),
            mapOf(
                s.id to listOf(
                    garmin(s.id, "VENDOR_SLEEP_SCORE", 71.0),
                    garmin(s.id, "VENDOR_DEEP_SLEEP", 5820.0),
                ),
            ),
        )
        val t = rows.single()
        assertEquals(105L, t.snoreMinutes) // 6343 s → 105 min
        assertEquals(29.23, t.snoreSharePercent!!, 0.0001)
        assertEquals(313_000L, t.longestBoutMs) // the lone long event IS its own bout
        assertEquals((-30.0 + -40.0 + -25.3) / 3, t.meanPeakDbfs!!, 0.0001)
        assertEquals(53, t.snoreScore)
        assertEquals("snore-score-v2", t.scoreFormulaVersion)
        assertEquals("energy-heuristic-v1-spectral", t.detectorVersion)
        assertEquals(71, t.garminScore)
        assertEquals(97L, t.garminDeepMinutes)
        // Clip coverage: only the first 40 s of the 100 s first bout union has audio.
        // Union snoring: 40+1 gap? events: [60-100], [101-161], [400-713] → union 100+313=413 s.
        assertEquals(40.0 * 100 / 413, t.clipCoveragePercent!!, 0.0001)
        assertFalse(t.detectorChangedFromPrevious)
        assertFalse(t.scoreFormulaChangedFromPrevious)
    }

    @Test
    fun `detector and score changes are flagged independently`() {
        val s1 = session(0)
        val s2 = session(dayMs)
        val s3 = session(2 * dayMs)
        startedAts[s1.id] = 0; startedAts[s2.id] = dayMs; startedAts[s3.id] = 2 * dayMs
        val rows = TrendAssembler.assemble(
            listOf(s1, s2, s3),
            mapOf(
                s1.id to listOf(measurement(s1.id, MeasurementType.SNORE_SCORE, 66.0, "energy-heuristic-v0")),
                s2.id to listOf(measurement(s2.id, MeasurementType.SNORE_SCORE, 67.0, "snore-score-v2")),
                s3.id to listOf(measurement(s3.id, MeasurementType.SNORE_SCORE, 67.0, "snore-score-v3")),
            ),
            mapOf(
                s1.id to listOf(snore(s1.id, 0, 1_000, -30.0, version = "energy-heuristic-v0")),
                s2.id to listOf(snore(s2.id, 0, 1_000, -30.0, version = "energy-heuristic-v1-spectral")),
                s3.id to listOf(snore(s3.id, 0, 1_000, -30.0, version = "energy-heuristic-v1-spectral")),
            ),
            emptyMap(),
        )
        assertTrue(rows[1].detectorChangedFromPrevious) // v0 → v1-spectral
        assertTrue(rows[1].scoreFormulaChangedFromPrevious) // detector string → snore-score-v2
        assertFalse(rows[2].detectorChangedFromPrevious)
        assertTrue(rows[2].scoreFormulaChangedFromPrevious) // v2 → v3
        assertTrue(TrendAssembler.anyVersionChange(rows))
    }

    @Test
    fun `unknown provenance never renders as a change`() {
        val s1 = session(0)
        val s2 = session(dayMs)
        startedAts[s1.id] = 0; startedAts[s2.id] = dayMs
        val rows = TrendAssembler.assemble(
            listOf(s1, s2),
            mapOf(
                // s1 has no score row at all; s2 has events but no measurements.
                s2.id to listOf(measurement(s2.id, MeasurementType.SNORE_SCORE, 67.0, "snore-score-v2")),
            ),
            mapOf(
                s1.id to listOf(snore(s1.id, 0, 1_000, -30.0)),
                s2.id to listOf(snore(s2.id, 0, 1_000, -30.0)),
            ),
            emptyMap(),
        )
        assertNull(rows[0].scoreFormulaVersion)
        assertFalse(rows[1].scoreFormulaChangedFromPrevious)
        assertFalse(rows[1].detectorChangedFromPrevious)
    }

    @Test
    fun `zero-capture sessions and stats-less nights are excluded from trends`() {
        // P2-7: a trend night must have processed at least one analysis window. The stats-less
        // stub ({"schema_version":1}) is exactly what a stop during the start delay writes —
        // it measured nothing and must not become a "0% snore, score 0" row. Pre-observability
        // nights (no stats at all) no longer count either: unverifiable capture is not a
        // verifiable night.
        val junk = session(0, captureStats = """{"schema_version":1}""")
        val empty = session(dayMs, captureStats = """{"schema_version":2,"windows_processed":0}""")
        val real = session(2 * dayMs)
        startedAts[junk.id] = 0; startedAts[empty.id] = dayMs; startedAts[real.id] = 2 * dayMs
        val rows = TrendAssembler.assemble(listOf(junk, empty, real), emptyMap(), emptyMap(), emptyMap())
        assertEquals(1, rows.size)
        assertEquals(2 * dayMs, rows.single().startedAt)
    }

    @Test
    fun `zero processed windows is not a trend night even with skipped windows`() {
        val s = session(1_000L, captureStats = """{"schema_version":6,"windows_processed":0,"windows_skipped":40}""")
        startedAts[s.id] = 1_000L
        assertFalse(TrendAssembler.isTrendNight(s))
    }

    @Test
    fun `one processed window still counts as a trend night`() {
        val s = session(1_000L, captureStats = """{"schema_version":5,"windows_processed":1,"windows_skipped":0}""")
        startedAts[s.id] = 1_000L
        assertTrue(TrendAssembler.isTrendNight(s))
    }

    @Test
    fun `trend rows follow user relabels for bout mean peak coverage and detector version`() {
        // P2-9: the trend's structure numbers must use the same label-aware snore set as the
        // stored share/score rows (SnoreMath.eventsCountingAsSnore). A relabeled cough never
        // counted toward snore minutes; it must not count toward the bout, mean peak, clip
        // coverage or detector version either — one row, one truth.
        val s = session(1_000L)
        startedAts[s.id] = 1_000L
        val bridging = snore(s.id, 145_000, 10_000, -12.0, version = "v-relabeled")
            .copy(userLabel = UserLabel.COUGH) // loudest event, really a cough
        val boutStart = snore(s.id, 100_000, 40_000, -30.0)
        val boutEnd = snore(s.id, 160_000, 40_000, -31.0)
        val clipped = snore(s.id, 400_000, 30_000, -25.0, clipId = 1)
        val rows = TrendAssembler.assemble(
            listOf(s),
            emptyMap(),
            mapOf(s.id to listOf(bridging, boutStart, boutEnd, clipped)),
            mapOf(s.id to listOf(clip(1, s.id, clipped.id))),
        )
        val row = rows.single()
        // Without the bridging cough the two 40 s bursts stay separate bouts (5 s gaps ≤ 10 s
        // would chain them into one 100 s bout if the cough still counted).
        assertEquals(40_000L, row.longestBoutMs)
        // Mean peak over the counted set only (−30, −31, −25) — the −12 cough is out.
        assertEquals((-30.0 + -31.0 + -25.0) / 3, row.meanPeakDbfs!!, 0.0001)
        // Clip coverage: 30 s covered / 110 s of counted snoring, not /120 s.
        assertEquals(30_000 * 100.0 / 110_000, row.clipCoveragePercent!!, 0.0001)
        // Version provenance comes from the counted set, not a relabeled event.
        assertEquals("energy-heuristic-v1-spectral", row.detectorVersion)
    }

    @Test
    fun `stored quiet-gap totals aggregate by kind and stay null for legacy nights`() {
        val withGaps = session(0)
        val legacy = session(dayMs)
        val zeroGap = session(2 * dayMs)
        startedAts[withGaps.id] = 0; startedAts[legacy.id] = dayMs; startedAts[zeroGap.id] = 2 * dayMs
        val rows = TrendAssembler.assemble(
            listOf(withGaps, legacy, zeroGap),
            emptyMap(), emptyMap(), emptyMap(),
            mapOf(
                withGaps.id to listOf(
                    gapEntity(withGaps.id, 12 * 60_000, "QUIET"),
                    gapEntity(withGaps.id, 15 * 60_000, "QUIET"),
                    gapEntity(withGaps.id, 10 * 60_000, "UNCAPTURED"),
                    gapEntity(withGaps.id, 8 * 60_000, null), // unclassified: counted, no minutes
                ),
                // Empty entry = v10 night with genuinely zero gaps: nulls, like legacy.
                legacy.id to emptyList(),
            ),
        )
        val t = rows[0]
        assertEquals(4, t.quietGapCount) // all stored gaps incl. the unclassified one
        assertEquals(27L, t.quietMinutes) // 12 + 15 quiet minutes only (unclassified: no minutes)
        assertEquals(10L, t.uncapturedMinutes)
        // Legacy and gap-free nights: nulls → the UI renders no quiet segment, not "quiet 0s".
        assertNull(rows[1].quietGapCount)
        assertNull(rows[1].quietMinutes)
        assertNull(rows[2].quietGapCount)
        assertNull(rows[2].quietMinutes)
    }

    private fun gapEntity(sessionId: Long, durationMs: Long, kind: String?) = QuietGapEntity(
        sessionId = sessionId, startMs = 0, endMs = durationMs, durationMs = durationMs,
        kind = kind, meanDbfs = if (kind == "QUIET") -84.0 else null,
    )

    @Test
    fun `only finalized nights trend, oldest first, capped at 7`() {
        val sessions = (0L until 10).map { session(it * dayMs) } +
            session(11 * dayMs, SessionStatus.RECOVERED) +
            session(12 * dayMs, SessionStatus.RECORDING).copy(endedAt = null) +
            session(13 * dayMs, SessionStatus.INTERRUPTED)
        startedAts.putAll(sessions.associate { it.id to it.startedAt })
        val rows = TrendAssembler.assemble(sessions, emptyMap(), emptyMap(), emptyMap())
        assertEquals(7, rows.size)
        assertEquals(5 * dayMs, rows.first().startedAt) // last 7 of the 10 completed
        // RECOVERED night included; the INTERRUPTED night (with windows) trends too (P2-6)
        // — a recorded night, not a missed one; the open RECORDING session stays out.
        assertEquals(11 * dayMs, rows[5].startedAt)
        assertEquals(13 * dayMs, rows.last().startedAt)
    }

    // ---- synthetic missed-night rows (SessionRepository.buildTrends threads the skip set) ----

    private val zone = ZoneId.of("Europe/Zurich")
    private val today = LocalDate.of(2026, 11, 19)

    /** A session whose night WAKES on [wakeDate] (started 22:14 the evening before). */
    private fun wakingOn(wakeDate: LocalDate) = session(
        wakeDate.minusDays(1).atTime(22, 14).atZone(zone).toInstant().toEpochMilli(),
    )

    private fun midnightMs(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `synthetic missed rows fill the uncovered wake dates of the 7-night span`() {
        val rows = TrendAssembler.assemble(listOf(wakingOn(today.minusDays(2))), emptyMap(), emptyMap(), emptyMap())
        val merged = TrendAssembler.injectMissedNights(rows, emptyList(), emptySet(), today, zone)
        // Span = today..today-6: six uncovered dates → six synthetic rows around the real one.
        assertEquals(7, merged.size)
        val missed = merged.filter { it.missed }
        assertEquals(6, missed.size)
        missed.forEach {
            assertEquals(TrendAssembler.MISSED_SESSION_ID, it.sessionId)
            assertNull(it.snoreScore)
            assertNull(it.snoreMinutes)
            assertTrue(it.detectorChangedFromPrevious.not())
        }
        // The synthetic for a wake date anchors on that date's local midnight; rows stay
        // chronological (the real row's 22:14 start slots between the midnights).
        val expectedMidnights = listOf(today, today.minusDays(1), today.minusDays(3), today.minusDays(4), today.minusDays(5), today.minusDays(6))
            .map { midnightMs(it) }
        assertEquals(expectedMidnights.sorted(), missed.map { it.startedAt })
        assertFalse(rows[0].missed) // the real row is untouched
    }

    @Test
    fun `skip set suppresses the synthetic row for its night`() {
        val rows = TrendAssembler.assemble(listOf(wakingOn(today.minusDays(2))), emptyMap(), emptyMap(), emptyMap())
        // P2-6: the skip key is the night's intended WAKE date — skipping tonight suppresses
        // the synthetic row for the night that wakes today.
        val merged = TrendAssembler.injectMissedNights(
            rows, emptyList(), setOf(today.toEpochDay()), today, zone,
        )
        assertEquals(5, merged.count { it.missed })
        assertEquals(0, merged.count { it.missed && it.startedAt == midnightMs(today) })
    }

    @Test
    fun `a post-midnight schedule's skip wakes on the occurrence day and suppresses correctly`() {
        // P2-6's driver: with a 00:30 start, a deliberate skip's key IS the wake date —
        // the same day. The old occurrence-day rule rendered that skip as "Missed".
        val rows = TrendAssembler.assemble(listOf(wakingOn(today.minusDays(2))), emptyMap(), emptyMap(), emptyMap())
        val merged = TrendAssembler.injectMissedNights(
            rows, emptyList(), setOf(today.toEpochDay()), today, zone,
        )
        assertEquals(0, merged.count { it.missed && it.startedAt == midnightMs(today) })
    }

    @Test
    fun `an INTERRUPTED night with windows is a real trend row, never a synthetic missed one`() {
        // P2-6: a night recorded for hours that ended in a capture error was recorded —
        // it keeps its real row (status INTERRUPTED) and silences the missed synthetic.
        val interrupted = session(
            today.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli(),
            status = SessionStatus.INTERRUPTED,
        )
        startedAts[interrupted.id] = interrupted.startedAt
        assertTrue(TrendAssembler.isTrendNight(interrupted))
        val merged = TrendAssembler.injectMissedNights(
            TrendAssembler.assemble(listOf(interrupted), emptyMap(), emptyMap(), emptyMap()),
            emptyList(), emptySet(), today, zone,
        )
        assertEquals(1, merged.count { !it.missed && it.status == SessionStatus.INTERRUPTED })
        assertEquals(0, merged.count { it.missed && it.startedAt == midnightMs(today) })
    }

    @Test
    fun `an INTERRUPTED night without windows is not a trend night`() {
        val empty = session(
            today.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli(),
            status = SessionStatus.INTERRUPTED,
            captureStats = """{"schema_version":1}""",
        )
        startedAts[empty.id] = empty.startedAt
        assertFalse(TrendAssembler.isTrendNight(empty))
    }

    @Test
    fun `a real row wins over the synthetic for its wake date`() {
        val rows = TrendAssembler.assemble(listOf(wakingOn(today)), emptyMap(), emptyMap(), emptyMap())
        val merged = TrendAssembler.injectMissedNights(rows, emptyList(), emptySet(), today, zone)
        // The real row covers today's wake date: synthetics fill only today-1..today-6.
        assertEquals(6, merged.count { it.missed })
        assertEquals(1, merged.count { !it.missed })
        assertEquals(0, merged.count { it.missed && it.startedAt == midnightMs(today) })
    }

    @Test
    fun `an open session suppresses its in-progress night`() {
        val rows = TrendAssembler.assemble(listOf(wakingOn(today.minusDays(2))), emptyMap(), emptyMap(), emptyMap())
        // Started 23:00 yesterday, still recording: provisional wake (start + 12 h) is today.
        val open = SleepSessionEntity(
            id = 99, startedAt = today.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli(),
            endedAt = null, status = SessionStatus.RECORDING, timeZone = zone.id,
            
        )
        val merged = TrendAssembler.injectMissedNights(rows, listOf(open), emptySet(), today, zone)
        assertEquals(0, merged.count { it.missed && it.startedAt == midnightMs(today) })
        assertEquals(5, merged.count { it.missed }) // the other six dates minus the real row's date
    }

    @Test
    fun `no finalized night at all injects nothing`() {
        val merged = TrendAssembler.injectMissedNights(emptyList(), emptyList(), emptySet(), today, zone)
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `seven real rows covering the whole span inject nothing`() {
        val rows = (0L until 7).map { TrendAssembler.assemble(listOf(wakingOn(today.minusDays(it))), emptyMap(), emptyMap(), emptyMap()) }
            .reduce { acc, list -> acc + list }
        val merged = TrendAssembler.injectMissedNights(rows, emptyList(), emptySet(), today, zone)
        assertEquals(7, merged.size)
        assertEquals(0, merged.count { it.missed })
    }

    @Test
    fun `the cap bounds the candidate span, not the merged list`() {
        // Seven consecutive missed nights behind a single real row that woke today-8: the
        // candidate span is always exactly maxNights dates (today..today-6), so 7 synthetics
        // — and the merged list intentionally exceeds 7. Nights older than the span keep
        // their real rows; nothing is synthesized for today-7 or today-8.
        val rows = TrendAssembler.assemble(listOf(wakingOn(today.minusDays(8))), emptyMap(), emptyMap(), emptyMap())
        val merged = TrendAssembler.injectMissedNights(rows, emptyList(), emptySet(), today, zone)
        assertEquals(8, merged.size)
        assertEquals(7, merged.count { it.missed })
        val missedStarts = merged.filter { it.missed }.map { it.startedAt }.toSet()
        assertFalse(missedStarts.contains(midnightMs(today.minusDays(7))))
        assertFalse(missedStarts.contains(midnightMs(today.minusDays(8))))
        assertEquals(1, merged.count { !it.missed && it.startedAt == rows.single().startedAt })
    }

    @Test
    fun `real rows older than the span leave their dates uncovered for synthesis`() {
        // Seven real nights ending at today-4: the span (today..today-6) still synthesizes
        // the four uncovered recent dates — old rows do not fence off the recent past.
        val rows = (10L downTo 4L)
            .map { TrendAssembler.assemble(listOf(wakingOn(today.minusDays(it))), emptyMap(), emptyMap(), emptyMap()) }
            .reduce { acc, list -> acc + list }
        val merged = TrendAssembler.injectMissedNights(rows, emptyList(), emptySet(), today, zone)
        assertEquals(11, merged.size) // 7 real + today..today-3 synthesized
        assertEquals(4, merged.count { it.missed })
        assertEquals(7, merged.count { !it.missed })
    }

    @Test
    fun `a row recorded in another zone keeps its own wake-date anchor`() {
        // Traveler probe: the session is stored with UTC (device tz at recording time) and
        // ends 23:30 UTC Nov 18 = 00:30 Zurich Nov 19 — it woke TODAY in today's zone, yet
        // coverage keys on the row's OWN zone, so its wake date reads today-1 and today's
        // synthetic is still injected (the same real night can appear twice in Zurich's
        // frame). Pinned to make the anchoring rule explicit, not to bless it.
        val utc = ZoneId.of("UTC")
        val utcNight = SleepSessionEntity(
            id = nextId++,
            startedAt = today.minusDays(1).atTime(16, 0).atZone(utc).toInstant().toEpochMilli(),
            endedAt = today.minusDays(1).atTime(23, 30).atZone(utc).toInstant().toEpochMilli(),
            status = SessionStatus.COMPLETED, timeZone = utc.id, 
            
            captureStats = """{"schema_version":2,"windows_processed":40000}""",
        )
        val rows = TrendAssembler.assemble(listOf(utcNight), emptyMap(), emptyMap(), emptyMap())
        val merged = TrendAssembler.injectMissedNights(rows, emptyList(), emptySet(), today, zone)
        // Sanity: the ended instant IS 00:30 Zurich on today's date.
        assertEquals(
            today.toEpochDay(),
            Instant.ofEpochMilli(utcNight.endedAt!!).atZone(zone).toLocalDate().toEpochDay(),
        )
        assertEquals(1, merged.count { !it.missed })
        assertEquals(6, merged.count { it.missed })
        assertEquals(1, merged.count { it.missed && it.startedAt == midnightMs(today) })
    }

    @Test
    fun `the synthetic anchor is the transition day's true local midnight`() {
        // DST probe for the midnight anchoring: the 23 h day is Mar 29→Mar 30 2026 (clocks
        // spring 02:00→03:00 ON Mar 29), so Mar 30's local midnight is only 23 h after Mar
        // 29's — a 24 h-step back from today (Mar 31) would land the Mar 30 synthetic an
        // hour off. The atStartOfDay anchor stays exact.
        val dstToday = LocalDate.of(2026, 3, 31)
        val rows = TrendAssembler.assemble(listOf(wakingOn(LocalDate.of(2026, 3, 23))), emptyMap(), emptyMap(), emptyMap())
        val merged = TrendAssembler.injectMissedNights(rows, emptyList(), emptySet(), dstToday, zone)
        assertEquals(7, merged.count { it.missed })
        val mar29 = midnightMs(LocalDate.of(2026, 3, 29))
        assertEquals(23 * 3_600_000L, midnightMs(LocalDate.of(2026, 3, 30)) - mar29)
        assertEquals(1, merged.count { it.missed && it.startedAt == mar29 })
        assertEquals(1, merged.count { it.missed && it.startedAt == midnightMs(LocalDate.of(2026, 3, 30)) })
    }
}
