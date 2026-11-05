package com.sleepanalysis.monitoring.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RingBuffer arithmetic invariants: the readable-sample clamp at the long→int boundary
 * (past ~37 h of 16 kHz audio a naive `toInt()` wraps negative) and the clip-ring horizon
 * guard used by AudioEngine.deliver.
 */
class RingBufferTest {

    // --- availableSamples ---------------------------------------------------------------

    @Test
    fun `available samples are exact below the capacity`() {
        assertEquals(0, RingBuffer.availableSamples(0, 100))
        assertEquals(50, RingBuffer.availableSamples(50, 100))
    }

    @Test
    fun `available samples clamp at the capacity`() {
        assertEquals(100, RingBuffer.availableSamples(100, 100))
        assertEquals(100, RingBuffer.availableSamples(1_000_000, 100))
    }

    @Test
    fun `available samples stay non-negative past the int overflow boundary`() {
        // 2^31 total samples (~37.3 h at 16 kHz): a naive toInt() wraps negative right here,
        // and snapshotLast would throw NegativeArraySizeException mid-capture.
        val overflow = 1L shl 31
        assertEquals(100, RingBuffer.availableSamples(overflow, 100))
        assertEquals(100, RingBuffer.availableSamples(overflow + 12_345, 100))
        assertEquals(100, RingBuffer.availableSamples(Long.MAX_VALUE, 100))
        assertEquals(100, RingBuffer.availableSamples(overflow - 1, 100))
    }

    @Test
    fun `ring keeps the last n samples and reports availability`() {
        val ring = RingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3))
        assertEquals(3, ring.available)
        assertTrue(ring.snapshotLast(4).contentEquals(shortArrayOf(1, 2, 3)))
        ring.write(shortArrayOf(4, 5, 6))
        assertEquals(4, ring.available)
        assertTrue(ring.snapshotLast(4).contentEquals(shortArrayOf(3, 4, 5, 6)))
        assertEquals(6, ring.totalWritten)
    }

    // --- holdsEventAudio (AudioEngine.deliver horizon guard) ----------------------------

    @Test
    fun `horizon guard keeps clips whose event tail ends inside the ring`() {
        // Ring spans stamps (ringEnd - span, ringEnd]; the event's last window ends one hop
        // inside — its tail (samples (2000, 2500]) is still buffered.
        assertTrue(RingBuffer.holdsEventAudio(ringEndMs = 10_000, eventEndMs = 2_500, spanMs = 8_000))
    }

    @Test
    fun `horizon guard refuses a clip when the event ends exactly on the horizon`() {
        // ringEnd - eventEnd == span: the ring holds samples (2500, 10500] while the event's
        // last sample is at 2500 — zero event audio, exactly the thermally-delayed case.
        assertFalse(RingBuffer.holdsEventAudio(ringEndMs = 10_500, eventEndMs = 2_500, spanMs = 8_000))
        assertFalse(RingBuffer.holdsEventAudio(ringEndMs = 20_000, eventEndMs = 2_500, spanMs = 8_000))
    }

    @Test
    fun `horizon guard holds an event newer than the ring end`() {
        assertTrue(RingBuffer.holdsEventAudio(ringEndMs = 1_000, eventEndMs = 2_500, spanMs = 8_000))
    }
}
