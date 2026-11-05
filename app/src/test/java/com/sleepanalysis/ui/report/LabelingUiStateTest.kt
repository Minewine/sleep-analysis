package com.sleepanalysis.ui.report

import com.sleepanalysis.domain.labels.EventUserLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelingUiStateTest {

    private fun item(id: Long, label: EventUserLabel? = null) = LabelingItem(
        eventId = id,
        startMs = id,
        eventType = "SNORE",
        intensityTier = "LOUD",
        clipPath = "session_1/evt_$id.wav",
        label = label,
    )

    @Test
    fun `progress counts labeled items`() {
        val state = LabelingUiState(items = listOf(item(1), item(2, EventUserLabel.SNORE), item(3)))
        assertEquals(3, state.totalCount)
        assertEquals(1, state.labeledCount)
    }

    @Test
    fun `unlabeled-only filter hides labeled rows`() {
        val state = LabelingUiState(
            items = listOf(item(1), item(2, EventUserLabel.SNORE), item(3, EventUserLabel.COUGH)),
            showUnlabeledOnly = true,
        )
        assertTrue(state.visible().all { it.label == null })
        assertEquals(listOf(1L), state.visible().map { it.eventId })
    }

    @Test
    fun `filter off shows everything`() {
        val state = LabelingUiState(
            items = listOf(item(1), item(2, EventUserLabel.SNORE)),
            showUnlabeledOnly = false,
        )
        assertEquals(2, state.visible().size)
    }

    @Test
    fun `withLabel updates only the target and can clear`() {
        val state = LabelingUiState(items = listOf(item(1), item(2)))
        val labeled = state.withLabel(2, EventUserLabel.MOVEMENT)
        assertEquals(EventUserLabel.MOVEMENT, labeled.items.first { it.eventId == 2L }.label)
        assertEquals(null, labeled.items.first { it.eventId == 1L }.label)

        val cleared = labeled.withLabel(2, null)
        assertEquals(null, cleared.items.first { it.eventId == 2L }.label)
    }
}
