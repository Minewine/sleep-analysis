package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Previous-night pick: eligibility, strict ordering, deterministic tie-break. */
class PreviousNightTest {

    private var nextId = 1L

    private fun session(startedAt: Long, status: String = SessionStatus.COMPLETED, endedAt: Long? = startedAt + 8 * 3_600_000) =
        SleepSessionEntity(
            id = nextId++, startedAt = startedAt, endedAt = endedAt, status = status,
            timeZone = "Europe/Zurich", 
        )

    @Test
    fun `picks the latest eligible night strictly before the current one`() {
        val a = session(0)
        val b = session(10)
        val current = session(20)
        assertEquals(b.id, PreviousNight.pick(listOf(a, b, current), current)!!.id)
    }

    @Test
    fun `never picks the current session or a later one`() {
        val current = session(20)
        val later = session(30)
        assertNull(PreviousNight.pick(listOf(current, later), current))
    }

    @Test
    fun `skips interrupted and open nights`() {
        val interrupted = session(0, SessionStatus.INTERRUPTED)
        val open = session(5, SessionStatus.RECORDING, endedAt = null)
        val good = session(10)
        val current = session(20)
        assertEquals(good.id, PreviousNight.pick(listOf(interrupted, open, good, current), current)!!.id)
        assertNull(PreviousNight.pick(listOf(interrupted, open, current), current))
    }

    @Test
    fun `recovered nights are eligible`() {
        val recovered = session(0, SessionStatus.RECOVERED)
        val current = session(20)
        assertEquals(recovered.id, PreviousNight.pick(listOf(recovered, current), current)!!.id)
    }

    @Test
    fun `equal start timestamps break the tie by descending id`() {
        val earlierA = session(5)
        val earlierB = session(5) // same startedAt as earlierA, larger id
        val current = session(20)
        assertEquals(earlierB.id, PreviousNight.pick(listOf(earlierA, earlierB, current), current)!!.id)
    }

    @Test
    fun `no sessions means no comparison`() {
        val current = session(20)
        assertNull(PreviousNight.pick(emptyList(), current))
        assertNull(PreviousNight.pick(listOf(current), current))
    }

    @Test
    fun `prefers an older full night over a later short evening session`() {
        // Session 8-then-9 (2026-11-08): the short 1 h evening recording sat between the
        // current night and the last real night — the comparison must anchor on the night.
        val fullNight = session(0) // fixture default: 8 h in bed
        val shortEvening = session(10, endedAt = 10 + 3_600_000) // 1 h
        val current = session(20)
        assertEquals(
            fullNight.id,
            PreviousNight.pick(listOf(fullNight, shortEvening, current), current)!!.id,
        )
    }

    @Test
    fun `falls back to the latest short session when no full night exists`() {
        val shortA = session(0, endedAt = 0 + 3_600_000)
        val shortB = session(10, endedAt = 10 + 3_600_000)
        val current = session(20)
        assertEquals(shortB.id, PreviousNight.pick(listOf(shortA, shortB, current), current)!!.id)
    }

    @Test
    fun `a full night just over the threshold is night-like`() {
        val exact = session(0, endedAt = PreviousNight.NIGHT_LIKE_MIN_IN_BED_MS)
        val short = session(10, endedAt = 10 + 3_600_000)
        val current = session(20)
        assertEquals(exact.id, PreviousNight.pick(listOf(exact, short, current), current)!!.id)
    }
}
