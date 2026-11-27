package com.sleepanalysis.domain.factors

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyFactorTest {

    @Test
    fun `exactly the ten documented factors exist`() {
        assertEquals(10, DailyFactor.entries.size)
        assertEquals(
            listOf(
                "ALCOHOL", "LATE_MEAL", "CAFFEINE_AFTER_14", "EXERCISE", "MEDICATION_OR_DEVICE",
                "SICK", "SLEPT_ON_BACK", "STRESS", "MORNING_RATING", "NOTES",
            ),
            DailyFactor.entries.map { it.name },
        )
    }

    @Test
    fun `morning rating is a 1-5 scale and the night tags are booleans`() {
        // MORNING_RATING shares STRESS's SCALE_1_5 kind (rendered "N / 5" everywhere);
        // SICK and SLEPT_ON_BACK match the plain boolean factors (LATE_MEAL & co).
        assertEquals(DailyFactor.Kind.SCALE_1_5, DailyFactor.MORNING_RATING.kind)
        assertEquals(DailyFactor.Kind.BOOLEAN, DailyFactor.SICK.kind)
        assertEquals(DailyFactor.Kind.BOOLEAN, DailyFactor.SLEPT_ON_BACK.kind)
        assertEquals(DailyFactor.Kind.BOOLEAN, DailyFactor.LATE_MEAL.kind)
    }

    @Test
    fun `round trip by key`() {
        DailyFactor.entries.forEach { factor ->
            assertEquals(factor, DailyFactor.fromKey(factor.key))
        }
    }
}
