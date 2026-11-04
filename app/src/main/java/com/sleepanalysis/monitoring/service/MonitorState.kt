package com.sleepanalysis.monitoring.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** UI-visible lifecycle of the overnight monitor (single source: [MonitorStateHolder]). */
sealed interface MonitorState {
    data object Idle : MonitorState
    data object Starting : MonitorState
    data class Recording(val sessionId: Long, val startedAt: Long) : MonitorState

    /**
     * Session open and the foreground service running (mic access held), but audio capture is
     * intentionally deferred by the configurable start delay: the user tapped start and is
     * falling asleep. Time in bed runs from the tap; events/clips/ambient stats begin at
     * [captureStartsAtMs]. A stop during this state finalizes the session with zero capture.
     */
    data class Delayed(val sessionId: Long, val startedAt: Long, val captureStartsAtMs: Long) : MonitorState
    data class StartError(val message: String) : MonitorState

    /**
     * An open session exists but no live service is recording behind it (process was killed
     * overnight and not yet resumed). Home must surface this prominently and let the user
     * finalize — never silently auto-recover.
     */
    data class Interrupted(val sessionId: Long, val startedAt: Long) : MonitorState

    /** Transient: a session was finalized — UI navigates to its report. */
    data class Finished(val sessionId: Long) : MonitorState
}

/**
 * Pure guard for the delayed-capture resume: after the start delay elapses, capture may begin
 * only if the state is still Delayed for the SAME session with the SAME planned capture start.
 * A stop during the delay (Finished/Idle), a concurrent finalize, or a sticky restart that
 * recomputed a different capture time all fail this check and abort instead of double-starting.
 */
fun canBeginCaptureAfterDelay(
    current: MonitorState,
    sessionId: Long,
    expectedCaptureStartAtMs: Long,
): Boolean =
    current is MonitorState.Delayed &&
        current.sessionId == sessionId &&
        current.captureStartsAtMs == expectedCaptureStartAtMs

@Singleton
class MonitorStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<MonitorState>(MonitorState.Idle)
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    fun set(state: MonitorState) {
        _state.value = state
    }
}
