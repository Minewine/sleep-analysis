package com.sleepanalysis.monitoring.audio

/**
 * Detects a stalled capture (P2-4): the clip ring's totalWritten stops advancing while the
 * engine still runs — a wedged AudioRecord.read, or a reader that returns nothing forever.
 * The old behavior recorded digital silence for the rest of the night and called it quiet.
 *
 * Pure seam so a JVM test can drive it with a fake clock. The engine arms it when audio
 * capture actually begins (never before — init may take arbitrarily long on OEM devices) and
 * reports progress once per hop read; two consumers share one instance:
 *  - the capture loop's `readBlocking` checks it in its `got == 0` retry path, bounding the
 *    previously unbounded sleep-retry loop;
 *  - a watchdog thread ticks every [TICK_MS] and calls Listener.onCaptureError when
 *    [isStalled] — catching a native read that never returns at all.
 *
 * onCaptureError finalizes the session INTERRUPTED (never silently auto-recovered).
 */
class CaptureStallWatchdog(
    private val stallLimitMs: Long = DEFAULT_STALL_LIMIT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var lastProgressMs = 0L
    @Volatile private var armed = false

    /** Starts stall accounting — called when audio capture begins, before the first read. */
    fun arm() {
        lastProgressMs = clock()
        armed = true
    }

    /** The clip ring advanced — resets the stall window. */
    fun onProgress() {
        lastProgressMs = clock()
    }

    /**
     * True when armed and no progress has been reported for [stallLimitMs]. Never true before
     * [arm].
     */
    fun isStalled(nowMs: Long = clock()): Boolean =
        armed && nowMs - lastProgressMs >= stallLimitMs

    companion object {
        /** No ring progress for 60 s means the mic is not delivering samples (P2-4). */
        const val DEFAULT_STALL_LIMIT_MS = 60_000L

        /** Watchdog thread tick — bounds detection latency to limit + tick. */
        const val TICK_MS = 5_000L
    }
}
