package com.sleepanalysis.domain.labels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventUserLabelTest {

    @Test
    fun `exactly the five documented labels exist`() {
        assertEquals(5, EventUserLabel.entries.size)
        assertEquals(
            listOf("SNORE", "COUGH", "SPEECH", "MOVEMENT", "OTHER_NOT_SNORE"),
            EventUserLabel.entries.map { it.name },
        )
    }

    @Test
    fun `round trip by key`() {
        EventUserLabel.entries.forEach { label ->
            assertEquals(label, EventUserLabel.fromKey(label.name))
        }
    }

    @Test
    fun `unknown or null keys resolve to nothing`() {
        assertNull(EventUserLabel.fromKey(null))
        assertNull(EventUserLabel.fromKey("CONFIRMED")) // legacy value must not resurrect
        assertNull(EventUserLabel.fromKey("APNEA")) // forbidden clinical term can never be a label
    }
}
