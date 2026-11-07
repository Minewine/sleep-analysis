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
            timeZone = "Europe/Zurich", deviceModel = "Test", osVersion = "30", appVersion = "0.1.0",
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
}
