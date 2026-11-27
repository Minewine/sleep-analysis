package com.sleepanalysis.domain.scoring

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.UserLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class SnoreMathTest {

    @Test
    fun `empty input is zero`() {
        assertEquals(0L, SnoreMath.unionDurationMs(emptyList()))
    }

    @Test
    fun `disjoint intervals sum`() {
        assertEquals(15_000L, SnoreMath.unionDurationMs(listOf(0L to 10_000L, 15_000L to 20_000L)))
    }

    @Test
    fun `overlapping intervals count the overlap once`() {
        // 0–30 s snore delivered as two overlapping windows: 30 s, not 50 s.
        assertEquals(30_000L, SnoreMath.unionDurationMs(listOf(0L to 20_000L, 10_000L to 30_000L)))
    }

    @Test
    fun `contained intervals add nothing`() {
        assertEquals(60_000L, SnoreMath.unionDurationMs(listOf(0L to 60_000L, 10_000L to 20_000L)))
    }

    @Test
    fun `unsorted input is handled`() {
        assertEquals(30_000L, SnoreMath.unionDurationMs(listOf(10_000L to 30_000L, 0L to 20_000L)))
    }

    @Test
    fun `chain of touching intervals merges into one span`() {
        // Touching (s == previous end) is an overlap, not a gap.
        assertEquals(30_000L, SnoreMath.unionDurationMs(listOf(0L to 10_000L, 10_000L to 20_000L, 20_000L to 30_000L)))
    }

    @Test
    fun `degenerate intervals contribute nothing`() {
        assertEquals(
            10_000L,
            SnoreMath.unionDurationMs(listOf(5_000L to 2_000L, 0L to 10_000L)),
        )
    }

    // --- eventsCountingAsSnore (label-aware snore set, docs/MEASUREMENT_RELIABILITY.md §7.1) ---

    private fun event(
        type: String = EventType.SNORE,
        label: String? = null,
        startMs: Long = 0,
    ) = AcousticEventEntity(
        sessionId = 1,
        type = type,
        startMs = startMs,
        endMs = startMs + 10_000,
        offsetMs = startMs,
        durationMs = 10_000,
        peakDbfs = -20.0,
        meanDbfs = -30.0,
        intensityTier = "LIGHT",
        confidence = 0.9,
        modelVersion = "test",
        userLabel = label,
    )

    @Test
    fun `unlabeled events keep the detector's classification`() {
        val events = listOf(event(EventType.SNORE), event(EventType.OTHER_NOISE))
        assertEquals(listOf(EventType.SNORE), SnoreMath.eventsCountingAsSnore(events).map { it.type })
    }

    @Test
    fun `a non-SNORE label excludes the event even when the detector said SNORE`() {
        val labels = listOf(UserLabel.COUGH, UserLabel.SPEECH, UserLabel.MOVEMENT, UserLabel.OTHER_NOT_SNORE)
        for (label in labels) {
            assertEquals(
                "label $label must exclude",
                0,
                SnoreMath.eventsCountingAsSnore(listOf(event(label = label))).size,
            )
        }
    }

    @Test
    fun `a SNORE label includes the event even when the detector said otherwise`() {
        val events = listOf(event(EventType.OTHER_NOISE, label = UserLabel.SNORE))
        assertEquals(1, SnoreMath.eventsCountingAsSnore(events).size)
    }

    @Test
    fun `mixed night keeps only the events that count as snoring`() {
        // The Nov-8 dogfood shape: two detector snares, the louder one user-labeled COUGH.
        val loud = event(startMs = 0, label = UserLabel.COUGH)
        val quiet = event(startMs = 20_000)
        val kept = SnoreMath.eventsCountingAsSnore(listOf(loud, quiet))
        assertEquals(listOf(20_000L), kept.map { it.startMs })
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(0, SnoreMath.eventsCountingAsSnore(emptyList()).size)
    }
}
