package com.sleepanalysis.domain.policy

/**
 * Pure math for the configurable start delay (night-2 dogfood idea): after the user taps start
 * (manually or via the bedtime notification), actual audio capture begins [delayMinutes] later,
 * letting the user fall asleep first. The foreground service starts immediately — the tap grants
 * while-in-use microphone access, which persists for the service's lifetime — only the *reading*
 * of the mic waits. Falling-asleep time still counts as time in bed (the session row starts at
 * the tap); only events/clips/ambient stats are deferred.
 *
 * The delay is bounded: 0 (off) to [MAX_DELAY_MINUTES]. Zero means capture starts immediately
 * (today's behavior).
 */
object StartDelayPolicy {

    const val MAX_DELAY_MINUTES = 120
    const val DEFAULT_DELAY_MINUTES = 0

    /** Sanitizes the raw preference value: null/negative/over-max clamp to the valid range. */
    fun sanitizeDelayMinutes(raw: Int?): Int =
        when {
            raw == null -> DEFAULT_DELAY_MINUTES
            raw < 0 -> DEFAULT_DELAY_MINUTES
            raw > MAX_DELAY_MINUTES -> MAX_DELAY_MINUTES
            else -> raw
        }

    /** Epoch ms at which audio capture should begin for a session started at [sessionStartedAtMs]. */
    fun captureStartAtMs(delayMinutesRaw: Int, sessionStartedAtMs: Long): Long =
        sessionStartedAtMs + sanitizeDelayMinutes(delayMinutesRaw) * 60_000L

    /**
     * Remaining wait before capture for a session started at [sessionStartedAtMs]; 0 when the
     * delay has elapsed (or is off). Used both for the first wait and for recomputing the
     * remainder after a START_STICKY restart mid-delay.
     */
    fun remainingMs(delayMinutesRaw: Int, sessionStartedAtMs: Long, nowMs: Long): Long =
        (captureStartAtMs(delayMinutesRaw, sessionStartedAtMs) - nowMs).coerceAtLeast(0L)
}
