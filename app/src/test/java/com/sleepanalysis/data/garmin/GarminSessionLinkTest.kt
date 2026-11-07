package com.sleepanalysis.data.garmin

import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The time-window vendor↔session link (docs/GARMIN_INTEGRATION.md §6). */
class GarminSessionLinkTest {

    private fun session(id: Long, start: Long, end: Long?, status: String = SessionStatus.COMPLETED) =
        SleepSessionEntity(
            id = id, startedAt = start, endedAt = end, status = status,
            timeZone = "Europe/Zurich", deviceModel = "Test", osVersion = "30", appVersion = "0.1.0",
        )

    @Test
    fun `picks the session with the largest overlap`() {
        // Garmin 02:48–09:15; the phone night overlaps partially, an evening nap barely.
        val garminStart = 0L
        val garminEnd = 6_400_000L
        val phone = session(3, start = -4_000_000, end = 5_000_000) // overlaps 4.4M
        val nap = session(2, start = garminEnd - 1_000_000, end = garminEnd + 1_000_000) // overlaps 1M
        val linked = GarminImportManager.bestSessionOverlapping(
            listOf(nap, phone), garminStart to garminEnd,
        )
        assertEquals(3L, linked!!.id)
    }

    @Test
    fun `open sessions qualify with a growing window`() {
        val current = session(9, start = -1_000_000, end = null, status = SessionStatus.RECORDING)
        val linked = GarminImportManager.bestSessionOverlapping(
            listOf(current), -2_000_000L to 2_000_000L, nowMs = 1_500_000,
        )
        assertEquals(9L, linked!!.id)
    }

    @Test
    fun `no overlap yields null (date-window fallback keeps rows unlinked)`() {
        val night = session(3, start = 0, end = 1_000_000)
        assertNull(
            GarminImportManager.bestSessionOverlapping(
                listOf(night), 5_000_000L to 6_000_000L,
            ),
        )
        assertNull(GarminImportManager.bestSessionOverlapping(emptyList(), 0L to 1L))
    }

    @Test
    fun `tie on overlap resolves to the later-started session`() {
        val a = session(1, start = 0, end = 2_000_000)
        val b = session(2, start = 1_000_000, end = 3_000_000) // same overlap, later start
        val linked = GarminImportManager.bestSessionOverlapping(listOf(a, b), 1_000_000L to 2_000_000L)
        assertEquals(2L, linked!!.id)
    }
}
