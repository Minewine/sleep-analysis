package com.sleepanalysis.data.repo

/**
 * Resolves an event draft's engine-relative offsets into stored absolute/session times.
 * Pure — unit tested (night 1 dogfood: adding the session start instead of the capture clock
 * base backdated every event of a resumed engine by the full downtime).
 */
data class EventTimes(val startMs: Long, val endMs: Long, val offsetMs: Long)

fun resolveEventTimes(
    sessionStartEpochMs: Long,
    captureTimeBaseEpochMs: Long,
    draftStartOffsetMs: Long,
    draftEndOffsetMs: Long,
): EventTimes {
    val startMs = captureTimeBaseEpochMs + draftStartOffsetMs
    return EventTimes(
        startMs = startMs,
        endMs = captureTimeBaseEpochMs + draftEndOffsetMs,
        offsetMs = (startMs - sessionStartEpochMs).coerceAtLeast(0),
    )
}
