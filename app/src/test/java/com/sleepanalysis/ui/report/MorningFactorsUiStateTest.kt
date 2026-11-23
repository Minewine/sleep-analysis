package com.sleepanalysis.ui.report

import com.sleepanalysis.data.db.entity.UserFactorEntity
import com.sleepanalysis.domain.factors.DailyFactor
import com.sleepanalysis.domain.report.FactorEntry
import com.sleepanalysis.domain.report.NightDates
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The morning card's rating/tag chips (ReportScreen.MorningNotesCard / ReportViewModel):
 *  derivation from factor rows, the Save write plan, and their round trip. */
class MorningFactorsUiStateTest {

    private fun rows(vararg entries: FactorEntry) = entries.toList()

    private fun entry(key: String, num: Double? = null, text: String? = null) =
        FactorEntry(factorKey = key, valueNum = num, valueText = text)

    /** In-memory stand-in for the factor day: applies the Save write plan the way
     *  FactorLogRepository's single-key upsert/removal does. */
    private fun applyWrites(current: List<FactorEntry>, writes: List<Pair<String, UserFactorEntity?>>): List<FactorEntry> =
        writes.fold(current) { acc, (key, entity) ->
            acc.filterNot { it.factorKey == key } +
                listOfNotNull(entity?.let { FactorEntry(it.factorKey, it.valueNum, it.valueText) })
        }

    @Test
    fun `notes cancel keeps the editor open only when a note exists to revert to`() {
        // null saved note + Cancel must never leave edit mode — rendering the null note
        // was the NullPointerException crash of the 2026-11-23 morning report.
        assertEquals(false, notesCancelReturnsToEdit(null))
        // An empty-but-saved note renders as an empty text — safe to leave edit mode.
        assertEquals(true, notesCancelReturnsToEdit(""))
        assertEquals(true, notesCancelReturnsToEdit("slept well"))
    }

    @Test
    fun `initial chip state comes from the factor rows`() {
        val state = morningFactorsUiState(
            rows(entry("ALCOHOL", 2.0), entry("SICK", 1.0), entry("STRESS", 5.0), entry("MORNING_RATING", 3.0)),
        )
        assertEquals(3, state.rating)
        assertEquals(setOf(DailyFactor.ALCOHOL, DailyFactor.SICK, DailyFactor.STRESS), state.tags)
    }

    @Test
    fun `empty rows yield unselected chips`() {
        val state = morningFactorsUiState(emptyList())
        assertNull(state.rating)
        assertEquals(emptySet<DailyFactor>(), state.tags)
    }

    @Test
    fun `out-of-range rating row never selects a chip`() {
        // The repo clamps on write, so this only arises from legacy/external rows — the chip
        // must not present an impossible level as selected.
        assertNull(morningFactorsUiState(rows(entry("MORNING_RATING", 7.0))).rating)
        assertEquals(1, morningFactorsUiState(rows(entry("MORNING_RATING", 1.0))).rating)
        assertEquals(5, morningFactorsUiState(rows(entry("MORNING_RATING", 5.0))).rating)
    }

    @Test
    fun `select-deselect round trip reads back what was saved`() {
        val start = morningFactorsUiState(emptyList())

        // Select rating 4 + two tags, save, reload: the chips reflect the written rows.
        val selected = morningFactorWrites(
            day = "2026-11-19",
            rating = 4,
            tags = setOf(DailyFactor.SICK, DailyFactor.SLEPT_ON_BACK),
        )
        val afterSave = morningFactorsUiState(applyWrites(emptyList(), selected))
        assertEquals(4, afterSave.rating)
        assertEquals(setOf(DailyFactor.SICK, DailyFactor.SLEPT_ON_BACK), afterSave.tags)

        // Tap the same rating chip again (deselect) and untag SICK; the other tag survives.
        val deselected = morningFactorWrites(
            day = "2026-11-19",
            rating = null,
            tags = setOf(DailyFactor.SLEPT_ON_BACK),
        )
        val afterClear = morningFactorsUiState(applyWrites(applyWrites(emptyList(), selected), deselected))
        assertNull(afterClear.rating)
        assertEquals(setOf(DailyFactor.SLEPT_ON_BACK), afterClear.tags)
    }

    @Test
    fun `save writes one row per selected tag and removes unselected ones`() {
        val writes = morningFactorWrites("2026-11-19", rating = 2, tags = setOf(DailyFactor.LATE_MEAL, DailyFactor.STRESS))
        val byKey = writes.toMap()

        assertEquals(6, writes.size) // rating + the five quick tags, always one write per key
        assertEquals(2.0, byKey["MORNING_RATING"]!!.valueNum)
        assertEquals(1.0, byKey["LATE_MEAL"]!!.valueNum)
        assertEquals(5.0, byKey["STRESS"]!!.valueNum)
        assertNull(byKey["ALCOHOL"])
        assertNull(byKey["SICK"])
        assertNull(byKey["SLEPT_ON_BACK"])
        writes.forEach { (key, entity) -> entity?.let { assertEquals("2026-11-19", it.date) } }
    }

    @Test
    fun `save never touches the notes or other logged factors`() {
        // "Mixed with the existing notes save": the day also holds a NOTES row (and any Daily
        // log factor) — the write plan only lists rating/tag keys, so the merge leaves them.
        val day = listOf(
            entry("NOTES", text = "partner snoring"),
            entry("CAFFEINE_AFTER_14", 1.0),
            entry("SICK", 1.0),
        )
        val writes = morningFactorWrites("2026-11-19", rating = 5, tags = setOf(DailyFactor.ALCOHOL))
        assertTrue(writes.none { it.first == "NOTES" || it.first == "CAFFEINE_AFTER_14" })

        val afterSave = applyWrites(day, writes)
        assertEquals("partner snoring", afterSave.first { it.factorKey == "NOTES" }.valueText)
        assertEquals(1.0, afterSave.first { it.factorKey == "CAFFEINE_AFTER_14" }.valueNum)
        // SICK was not selected this time → its row is cleared by the untag write.
        assertNull(afterSave.firstOrNull { it.factorKey == "SICK" })
    }

    @Test
    fun `stress chip is selected for any stored level`() {
        // The invariant: row-present ⇔ chip selected, whatever levelNum the Daily log refined
        // the stress to — even 1 (calm) still reads as "stress was logged", never unselected.
        for (level in listOf(1.0, 3.0, 5.0)) {
            val state = morningFactorsUiState(rows(entry("STRESS", level)))
            assertEquals(setOf(DailyFactor.STRESS), state.tags)
        }
        // And the mirror side: no row ⇔ unselected.
        assertEquals(emptySet<DailyFactor>(), morningFactorsUiState(rows()).tags)
    }

    @Test
    fun `unknown or unrelated factor rows never reach the chips`() {
        // A future enum entry, a real factor outside MORNING_TAGS, and free text must be
        // ignored by the state builder — no crash, no accidental chip.
        val state = morningFactorsUiState(
            rows(
                entry("FUTURE_FACTOR", 2.0),
                entry("CAFFEINE_AFTER_14", 1.0),
                entry("NOTES", text = "slept badly"),
            ),
        )
        assertNull(state.rating)
        assertEquals(emptySet<DailyFactor>(), state.tags)
    }

    @Test
    fun `non-integer rating row truncates to its chip`() {
        // The repo clamps on write but never rounds; a legacy/external 2.5 row reads as chip 2
        // (toInt truncation) — in range, renderable, never a crash.
        assertEquals(2, morningFactorsUiState(rows(entry("MORNING_RATING", 2.5))).rating)
        assertEquals(4, morningFactorsUiState(rows(entry("MORNING_RATING", 4.9))).rating)
    }

    @Test
    fun `all-unset save is a pure-removal plan`() {
        // Rating deselected + every tag off: one null write per key, nothing written — the
        // day's unrelated rows are the only thing left once the merge applies it.
        val writes = morningFactorWrites("2026-11-19", rating = null, tags = emptySet())
        assertEquals(6, writes.size) // rating + the five quick tags, always one write per key
        assertTrue(writes.all { (_, entity) -> entity == null })

        val day = listOf(
            entry("NOTES", text = "slept badly"),
            entry("CAFFEINE_AFTER_14", 1.0),
            entry("STRESS", 3.0),
            entry("MORNING_RATING", 4.0),
        )
        val after = applyWrites(day, writes)
        assertEquals(listOf("CAFFEINE_AFTER_14", "NOTES"), after.map { it.factorKey }.sorted())
    }

    @Test
    fun `untagging stress deletes the daily-log refinement - removal wins`() {
        // The Daily log refined stress to 3 after the morning card tagged it; a Save with
        // stress untagged writes null for STRESS — the merge removes the row (it is NOT
        // overwritten with the quick tag's 5.0), while the untouched rows survive.
        val day = listOf(entry("STRESS", 3.0), entry("NOTES", text = "deadline"))
        val writes = morningFactorWrites("2026-11-19", rating = 4, tags = emptySet())

        val after = applyWrites(day, writes)
        assertNull(after.firstOrNull { it.factorKey == "STRESS" })
        assertEquals(4.0, after.first { it.factorKey == "MORNING_RATING" }.valueNum)
        assertEquals("deadline", after.first { it.factorKey == "NOTES" }.valueText)
    }

    @Test
    fun `saving after loading a non-integer rating rewrites it as the chip's integer`() {
        // 2.5 seeds chip 2; the next Save writes valueNum 2.0 — the row is normalized by the
        // round trip (same key, same single row), never duplicated or kept fractional.
        val day = listOf(entry("MORNING_RATING", 2.5))
        val seeded = morningFactorsUiState(day)
        assertEquals(2, seeded.rating)

        val after = applyWrites(day, morningFactorWrites("2026-11-19", rating = seeded.rating, tags = seeded.tags))
        assertEquals(1, after.count { it.factorKey == "MORNING_RATING" })
        assertEquals(2.0, after.single { it.factorKey == "MORNING_RATING" }.valueNum)
    }

    @Test
    fun `sessions starting 23_30 and 00_30 land on different factor days`() {
        // saveMorningFactors anchors on the session's START date (NightDates.factorDay): a
        // night begun 23:30 keys its factors under day D, the next night begun 00:30 under
        // D+1 — each morning card writes its own day, never the previous night's log.
        val zone = ZoneId.of("Europe/Zurich")
        fun start(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
            ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

        val lateEvening = start(2026, 11, 19, 23, 30)
        val justAfterMidnight = start(2026, 11, 20, 0, 30)
        assertEquals(LocalDate.of(2026, 11, 19), NightDates.factorDay(lateEvening, zone.id))
        assertEquals(LocalDate.of(2026, 11, 20), NightDates.factorDay(justAfterMidnight, zone.id))

        // The write plan carries that derived day string on every written row.
        val day = NightDates.factorDay(lateEvening, zone.id).toString()
        val writes = morningFactorWrites(day, rating = 3, tags = setOf(DailyFactor.SICK))
        assertEquals("2026-11-19", writes.first { it.first == "MORNING_RATING" }.second!!.date)
    }
}
