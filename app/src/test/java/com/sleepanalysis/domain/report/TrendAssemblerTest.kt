package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.MeasurementEntity
import com.sleepanalysis.data.db.entity.QuietGapEntity
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.domain.policy.MeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        status = status, timeZone = "Europe/Zurich", deviceModel = "Test",
        osVersion = "30", appVersion = "0.1.0", captureStats = captureStats,
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
    fun `zero-capture sessions are excluded but old nights without stats count as real`() {
        val junk = session(0, captureStats = """{"schema_version":1}""") // windows_processed absent → counts
        val empty = session(dayMs, captureStats = """{"schema_version":2,"windows_processed":0}""") // excluded
        val real = session(2 * dayMs)
        startedAts[junk.id] = 0; startedAts[empty.id] = dayMs; startedAts[real.id] = 2 * dayMs
        val rows = TrendAssembler.assemble(listOf(junk, empty, real), emptyMap(), emptyMap(), emptyMap())
        assertEquals(2, rows.size)
        assertEquals(0L, rows[0].startedAt)
        assertEquals(2 * dayMs, rows[1].startedAt)
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
        assertEquals(4 * dayMs, rows.first().startedAt) // last 7 of the 10 completed
        assertEquals(11 * dayMs, rows.last().startedAt) // RECOVERED night included
    }
}
