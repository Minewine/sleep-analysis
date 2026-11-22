package com.sleepanalysis.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.prefs.UserPreferences
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.domain.policy.MissedNightPolicy
import com.sleepanalysis.domain.policy.MissedNightResult
import com.sleepanalysis.domain.policy.NightStopPolicy
import com.sleepanalysis.monitoring.schedule.RecordingScheduler
import com.sleepanalysis.monitoring.schedule.ScheduleTimes
import com.sleepanalysis.monitoring.service.InterruptedNotification
import com.sleepanalysis.monitoring.service.MonitorService
import com.sleepanalysis.monitoring.service.MonitorState
import com.sleepanalysis.monitoring.service.MonitorStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Pre-flight checklist for an unattended night (docs/NEXT_STEPS.md P0). */
data class PreFlightChecklist(
    val micGranted: Boolean,
    val notificationsGranted: Boolean,
    val batteryExempt: Boolean,
    val freeStorageMb: Long,
) {
    val storageOk: Boolean get() = freeStorageMb >= 500
    val allClear: Boolean get() = micGranted && notificationsGranted && batteryExempt && storageOk
}

/**
 * Pure guard for the user-confirmed finalize of an interrupted night: it may act only while the
 * live engine cannot own the session — Idle, or Interrupted (the known-dead-session state). A
 * Starting/Delayed/Recording state means the foreground service holds the mic behind an open
 * session; finalizing then would close the session row under a live recording.
 */
internal fun canFinalizeInterrupted(current: MonitorState): Boolean =
    current is MonitorState.Idle || current is MonitorState.Interrupted

private const val TAG = "HomeViewModel"

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessionRepository: SessionRepository,
    private val stateHolder: MonitorStateHolder,
    private val prefs: UserPreferences,
    private val scheduler: RecordingScheduler,
    private val experimentTracker: com.sleepanalysis.domain.experiments.ExperimentTracker,
) : ViewModel() {

    val monitorState: StateFlow<MonitorState> = stateHolder.state

    // Recording controls (design: recording controls): the start button gates on the master
    // switch; the schedule row shows the next armed bedtime start.
    val recordingEnabled: StateFlow<Boolean> = prefs.recordingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val scheduleEnabled: StateFlow<Boolean> = prefs.scheduleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val scheduleEndMinutes: StateFlow<Int> = prefs.scheduleEndMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 6 * 60 + 30)

    private val _nextScheduledStart = MutableStateFlow<ZonedDateTime?>(null)
    val nextScheduledStart: StateFlow<ZonedDateTime?> = _nextScheduledStart.asStateFlow()

    val sessions: StateFlow<List<SleepSessionEntity>> = sessionRepository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latestSession: StateFlow<SleepSessionEntity?> = sessions
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Was the night before today recorded, skipped, or missed (MissedNightPolicy)? Null when
     * there is nothing to say: no session history at all (the last-night card's empty state
     * speaks), or a session is open — the recording status and the interrupted banner own
     * that story. Recomputed whenever sessions or the skip history change.
     */
    val missedNight: StateFlow<MissedNightResult?> = combine(
        sessions,
        prefs.scheduleSkipHistoryDays,
    ) { allSessions, skippedDays ->
        when {
            allSessions.isEmpty() || allSessions.any { it.endedAt == null } -> null
            else -> MissedNightPolicy.evaluate(
                allSessions,
                // P2-6: "today" anchors in the most recent session's zone — the same zone its
                // wake date uses — never the device zone (travel consistency).
                LocalDate.now(MissedNightPolicy.todayZone(allSessions)).toEpochDay(),
                skippedDays,
                hasCaptureEvidence = { session ->
                    com.sleepanalysis.domain.report.CaptureStats.parse(session.captureStats)
                        ?.anyProcessedWindows() == true
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The last night's stored validity grade letter (A–D, domain/validity.NightValidity) for
     * the Home card — read from the NIGHT_VALIDITY row, null when the night predates it.
     */
    val latestValidityGrade: StateFlow<String?> = latestSession
        .map { session -> session?.let { sessionRepository.validityGradeFor(it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _preFlight = MutableStateFlow<PreFlightChecklist?>(null)
    val preFlight: StateFlow<PreFlightChecklist?> = _preFlight.asStateFlow()

    /** Saved-data summary for the interrupted-night banner. */
    data class InterruptedSummary(val sessionId: Long, val startedAt: Long, val savedEvents: Int)

    private val _interrupted = MutableStateFlow<InterruptedSummary?>(null)
    val interrupted: StateFlow<InterruptedSummary?> = _interrupted.asStateFlow()

    /**
     * The active N-of-1 experiment's chip state (docs/INSIGHTS.md §Playbook): shown on the
     * Home last-night card — "Experiment day n/7: <instruction>" (n = usable nights, honest
     * even when it lags; capped at the planned count — past the threshold the verdict is due).
     * Read-only here; the Insights screen owns start/abandon/verdicts.
     */
    data class ExperimentChip(
        val usableNights: Int,
        val plannedNights: Int,
        val actionId: String?,
        val confirming: Boolean,
    )

    private val _experimentChip = MutableStateFlow<ExperimentChip?>(null)
    val experimentChip: StateFlow<ExperimentChip?> = _experimentChip.asStateFlow()

    init {
        // Safety net for process recreation: the open session row is ground truth. If the live
        // service is already Recording (same process, or sticky restart got here first), keep
        // that state; otherwise surface the night as INTERRUPTED — never silently auto-recover.
        viewModelScope.launch {
            if (stateHolder.state.value is MonitorState.Idle) {
                val open = sessionRepository.findOpenSession()
                // Re-check after the suspend DB read: a service restart (scheduled or sticky)
                // may have begun recording while we read — its state must never be clobbered
                // with `Interrupted`.
                if (open != null && stateHolder.state.value is MonitorState.Idle) {
                    stateHolder.set(MonitorState.Interrupted(open.id, open.startedAt))
                    _interrupted.value = InterruptedSummary(
                        sessionId = open.id,
                        startedAt = open.startedAt,
                        savedEvents = sessionRepository.savedEventCountForSession(open.id),
                    )
                }
            }
        }
        refreshPreFlight()
        refreshSchedule()
        // Self-heal the nightly alarms on app open: force-stop and package updates cancel
        // them, and nothing else re-arms until the next settings change or boot.
        viewModelScope.launch { scheduler.arm() }
        refreshExperimentChip()
    }

    /**
     * Read-only chip refresh — called on init AND every Home ON_RESUME: a night that landed,
     * or an experiment started/finished on the Insights screen, must not leave a stale chip.
     * (Non-suspend wrapper, same pattern as [refreshPreFlight]/[refreshSchedule].)
     */
    fun refreshExperimentChip() {
        viewModelScope.launch { loadExperimentChip() }
    }

    /** The chip load itself: the ACTIVE experiment's honest usable-night count (if any). */
    private suspend fun loadExperimentChip() {
        val active = experimentTracker.activeExperiment()
        val progress = active?.let { experimentTracker.progress(it) }
        _experimentChip.value = progress?.let {
            ExperimentChip(
                usableNights = it.usableNights.coerceAtMost(it.plannedNights),
                plannedNights = it.plannedNights,
                actionId = active?.playbookActionId,
                confirming = it.phase == com.sleepanalysis.domain.experiments.ExperimentTracker.Phase.CONFIRM,
            )
        }
    }

    /** Recomputed on every Home resume so permission/whitelist changes are reflected. */
    fun refreshPreFlight() {
        _preFlight.value = PreFlightChecklist(
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = if (Build.VERSION.SDK_INT >= 33) {
                granted(Manifest.permission.POST_NOTIFICATIONS)
            } else true,
            batteryExempt = batteryOptimizationIgnored(),
            freeStorageMb = runCatching {
                Environment.getDataDirectory().usableSpace / (1024 * 1024)
            }.getOrDefault(-1L),
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Recomputed on Home resume alongside the pre-flight checks (next armed bedtime start). */
    fun refreshSchedule() {
        viewModelScope.launch {
            _nextScheduledStart.value = scheduler.nextStartOrNull(ZonedDateTime.now())
        }
    }

    /** Marks tonight's scheduled start as skipped for one night and re-arms the alarms. */
    fun skipTonight() {
        viewModelScope.launch {
            scheduler.skipNextStart()
            _nextScheduledStart.value = scheduler.nextStartOrNull(ZonedDateTime.now())
        }
    }

    fun startRecording() {
        MonitorService.start(appContext)
    }

    /** Skips the remaining start delay and begins audio capture immediately (Home "start now"). */
    fun startCaptureNow() {
        MonitorService.startNow(appContext)
    }

    /**
     * Whether a stop tap should ask for confirmation first: a manual stop before tonight's
     * scheduled end is how nights get cut short (2026-11-08: a 04:13 stop while the watch
     * showed sleep until 06:41). Pure decision in [NightStopPolicy]; the tap-time evaluation
     * (not composition time) is why this is a function, not a state read.
     */
    fun shouldConfirmStopNow(): Boolean {
        val now = ZonedDateTime.now()
        val nextEnd = ScheduleTimes.nextOccurrence(now, scheduleEndMinutes.value)
        return NightStopPolicy.shouldConfirmStop(now, scheduleEnabled.value, nextEnd)
    }

    fun stopRecording() {
        MonitorService.stop(appContext)
    }

    fun consumeFinished() {
        if (stateHolder.state.value is MonitorState.Finished) {
            stateHolder.set(MonitorState.Idle)
        }
    }

    /** The service resumed the interrupted session — the banner is obsolete. */
    fun clearInterrupted() {
        if (stateHolder.state.value is MonitorState.Recording) {
            _interrupted.value = null
        }
    }

    /** Direct system dialog for the battery-optimization exemption (sideloaded personal app). */
    fun batteryExemptionRequestIntent(): Intent? {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${appContext.packageName}"))
        return runCatching { intent }.getOrNull()
    }

    fun batteryOptimizationIgnored(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    /** User-confirmed finalization of an interrupted night → navigate to the recovered report. */
    fun finalizeInterrupted(onReady: (Long) -> Unit) {
        val current = stateHolder.state.value
        if (!canFinalizeInterrupted(current)) {
            // The banner can linger while the service resumes behind the scenes (state flips to
            // Starting/Delayed/Recording); closing the session row then would kill a live recording.
            Log.w(TAG, "finalizeInterrupted ignored — state is $current")
            return
        }
        viewModelScope.launch {
            val finalized = sessionRepository.finalizeOpenAsRecovered()
            _interrupted.value = null
            if (finalized > 0) {
                // The banner notification is stale now: the service only cancels it when the
                // night is resumed through it, never when the banner's finalize wins the race.
                InterruptedNotification.cancel(appContext)
                sessionRepository.latestSession()?.let { onReady(it.id) }
            }
            stateHolder.set(MonitorState.Idle)
        }
    }

    fun statusLabel(status: String): String = when (status) {
        "COMPLETED" -> "complete"
        "INTERRUPTED" -> "interrupted"
        "RECOVERED" -> "recovered"
        else -> "recording"
    }
}
