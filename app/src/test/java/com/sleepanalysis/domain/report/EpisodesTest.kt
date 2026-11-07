package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Episode grouping tests: breath-cycle fragments merge into reviewable episodes (night 3). */
class EpisodesTest {

    private var nextId = 1L

    private fun event(
        startMs: Long,
        endMs: Long,
        peakDbfs: Double,
        type: String = EventType.SNORE,
        clipId: Long? = null,
    ) = AcousticEventEntity(
        id = nextId++,
        sessionId = 1,
        type = type,
        startMs = startMs,
        endMs = endMs,
        offsetMs = startMs,
        durationMs = endMs - startMs,
        peakDbfs = peakDbfs,
        meanDbfs = peakDbfs - 5,
        intensityTier = "QUIET",
        confidence = 0.9,
        modelVersion = "energy-heuristic-v0",
        clipId = clipId,
    )

    private fun clip(id: Long, eventId: Long) = ClipRef(
        eventId = eventId,
        filePath = "session_1/evt_$id.wav",
        offsetMs = 0,
        durationMs = 8000,
        intensityTier = "QUIET",
        eventType = EventType.SNORE,
        peakDbfs = -30.0,
    )

    @Test
    fun `fragments within the gap merge into one episode`() {
        // 0s, 3s, 8s: gaps of 3 s and 5 s — one breath-cycle episode.
        val eps = groupEpisodes(
            listOf(event(0, 3_000, -35.0), event(8_000, 11_000, -30.0)),
            emptyMap(),
        )
        assertEquals(1, eps.size)
        assertEquals(0, eps[0].startMs)
        assertEquals(11_000, eps[0].endMs)
        assertEquals(2, eps[0].snoreCount)
        assertEquals(-30.0, eps[0].peakDbfs, 0.0001)
    }

    @Test
    fun `a gap beyond the tolerance starts a new episode`() {
        val eps = groupEpisodes(
            listOf(event(0, 3_000, -35.0), event(14_000, 17_000, -30.0)),
            emptyMap(),
        )
        assertEquals(2, eps.size)
    }

    @Test
    fun `gap of exactly the tolerance stays in the same episode`() {
        // Second event starts 10 s after the first ends — boundary is inclusive.
        val eps = groupEpisodes(
            listOf(event(0, 3_000, -35.0), event(13_000, 16_000, -30.0)),
            emptyMap(),
        )
        assertEquals(1, eps.size)
    }

    @Test
    fun `members are ordered loudest peak first and the peak leads`() {
        val eps = groupEpisodes(
            listOf(event(0, 3_000, -30.0), event(5_000, 8_000, -16.6), event(9_000, 12_000, -25.0)),
            emptyMap(),
        )
        assertEquals(1, eps.size)
        assertEquals(-16.6, eps[0].events[0].peakDbfs, 0.0001)
        assertEquals(-30.0, eps[0].events[2].peakDbfs, 0.0001)
    }

    @Test
    fun `clip comes from the loudest member that has one`() {
        val quietWithClip = event(0, 3_000, -32.0, clipId = 9)
        val loudest = event(5_000, 8_000, -16.6)
        val clips = mapOf(quietWithClip.id to clip(9, quietWithClip.id))
        val eps = groupEpisodes(listOf(quietWithClip, loudest), clips)
        assertEquals(quietWithClip.id, eps.single().clip!!.eventId)
    }

    @Test
    fun `non-snore members count in the episode but not as snoring`() {
        val eps = groupEpisodes(
            listOf(event(0, 3_000, -30.0), event(5_000, 8_000, -28.0, type = EventType.OTHER_NOISE)),
            emptyMap(),
        )
        assertEquals(1, eps[0].snoreCount)
    }

    @Test
    fun `episodes come back chronological regardless of input order`() {
        val eps = groupEpisodes(
            listOf(event(60_000, 63_000, -30.0), event(0, 3_000, -20.0)),
            emptyMap(),
        )
        assertEquals(listOf(0L, 60_000L), eps.map { it.startMs })
    }

    @Test
    fun `loudestEpisodes ranks by peak and caps the count`() {
        val events = listOf(
            event(0, 3_000, -40.0),
            event(60_000, 63_000, -16.6),
            event(120_000, 123_000, -30.0),
        )
        val top = loudestEpisodes(groupEpisodes(events, emptyMap()), n = 2)
        assertEquals(listOf(-16.6, -30.0), top.map { it.peakDbfs })
    }

    @Test
    fun `empty input yields no episodes`() {
        assertEquals(0, groupEpisodes(emptyList(), emptyMap()).size)
        assertEquals(0, loudestEpisodes(emptyList()).size)
        assertNull(groupEpisodes(emptyList(), emptyMap()).firstOrNull())
    }
}
