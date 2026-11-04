package com.sleepanalysis.monitoring.audio

/**
 * Fixed-capacity circular buffer for 16-bit PCM. Holds the last N samples so event-triggered
 * clips can be flushed from memory — full-night audio is never persisted
 * (docs/ARCHITECTURE.md §7). Not thread-safe by design: it lives entirely on the capture thread.
 */
class RingBuffer(private val capacity: Int) {

    private val buffer = ShortArray(capacity)
    private var writeIndex = 0
    var totalWritten = 0L
        private set

    val available: Int get() = availableSamples(totalWritten, capacity)

    fun write(chunk: ShortArray, length: Int = chunk.size) {
        var remaining = length
        var offset = 0
        while (remaining > 0) {
            val n = minOf(remaining, capacity - writeIndex)
            System.arraycopy(chunk, offset, buffer, writeIndex, n)
            writeIndex = (writeIndex + n) % capacity
            offset += n
            remaining -= n
        }
        totalWritten += length
    }

    /** Ordered copy of the last [n] samples (fewer if the buffer is not yet full). */
    fun snapshotLast(n: Int): ShortArray {
        val count = minOf(n, available)
        val out = ShortArray(count)
        var start = (writeIndex - count + capacity) % capacity
        for (i in 0 until count) {
            out[i] = buffer[start]
            start = (start + 1) % capacity
        }
        return out
    }

    companion object {
        /**
         * Samples readable from a ring of [capacity] after [totalWritten] writes. Clamped
         * before the long→int conversion: past 2^31 total samples (~37 h at 16 kHz) a naive
         * `toInt()` wraps negative and [RingBuffer.snapshotLast] would throw
         * NegativeArraySizeException mid-capture.
         */
        fun availableSamples(totalWritten: Long, capacity: Int): Int =
            minOf(totalWritten, capacity.toLong()).toInt()

        /**
         * True when a ring whose newest sample is stamped [ringEndMs] (relative ms) still
         * holds audio of an event whose last active window ended at [eventEndMs]: the ring
         * spans the last [spanMs] of audio, i.e. stamps (ringEndMs − spanMs, ringEndMs], so
         * the event's tail must end strictly inside that window. Deliberately keyed on the
         * event END, not its start — a long event whose head slid past the horizon still has
         * its tail in the ring, and refusing that clip would drop audio today's engine keeps.
         * Guard used by AudioEngine.deliver so a thermally-delayed emission can never persist
         * (and budget-book) a clip that contains zero event audio.
         */
        fun holdsEventAudio(ringEndMs: Long, eventEndMs: Long, spanMs: Long): Boolean =
            ringEndMs - eventEndMs < spanMs
    }
}
