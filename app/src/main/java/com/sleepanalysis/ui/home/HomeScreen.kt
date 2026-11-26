package com.sleepanalysis.ui.home

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepanalysis.R
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.domain.policy.MissedNightResult
import com.sleepanalysis.domain.report.CaptureStats
import com.sleepanalysis.monitoring.service.MonitorState
import com.sleepanalysis.monitoring.service.canStartNewSession
import com.sleepanalysis.ui.components.Formatting
import com.sleepanalysis.ui.components.formatDateTime
import com.sleepanalysis.ui.components.formatDuration
import com.sleepanalysis.ui.components.formatMinutesOfDay
import java.time.LocalDate
import java.time.ZonedDateTime

@Composable
fun HomeScreen(
    onOpenReport: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBaseline: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenDailyLog: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.monitorState.collectAsStateWithLifecycle()
    val latest by viewModel.latestSession.collectAsStateWithLifecycle()
    val validityGrade by viewModel.latestValidityGrade.collectAsStateWithLifecycle()
    val preFlight by viewModel.preFlight.collectAsStateWithLifecycle()
    val interrupted by viewModel.interrupted.collectAsStateWithLifecycle()
    val experimentChip by viewModel.experimentChip.collectAsStateWithLifecycle()
    val recordingEnabled by viewModel.recordingEnabled.collectAsStateWithLifecycle()
    // scheduleEnabled is not rendered here, but this subscription is what keeps the ViewModel's
    // WhileSubscribed flow reading preferences — shouldConfirmStopNow() reads its value.
    val scheduleEnabled by viewModel.scheduleEnabled.collectAsStateWithLifecycle()
    val nextScheduledStart by viewModel.nextScheduledStart.collectAsStateWithLifecycle()
    val scheduleResolved by viewModel.scheduleResolved.collectAsStateWithLifecycle()
    val missedNight by viewModel.missedNight.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-run the pre-flight checks whenever Home becomes visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPreFlight()
                viewModel.refreshSchedule()
                viewModel.refreshExperimentChip()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Navigate to the fresh report when a session finishes; drop the interrupted banner
    // if the service recovered and went straight back to recording.
    LaunchedEffect(state) {
        val finished = state as? MonitorState.Finished
        if (finished != null) {
            viewModel.consumeFinished()
            onOpenReport(finished.sessionId)
        }
        if (state is MonitorState.Recording) viewModel.clearInterrupted()
    }

    // Permission-aware start: request what's missing, then start the service.
    var pendingStart by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshPreFlight()
        if (pendingStart) {
            pendingStart = false
            viewModel.startRecording()
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.refreshPreFlight()
            if (Build.VERSION.SDK_INT >= 33 && viewModel.preFlight.value?.notificationsGranted != true) {
                pendingStart = true
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.startRecording()
            }
        }
    }

    fun onStartClick() {
        val pf = preFlight
        if (pf != null && !pf.micGranted) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (Build.VERSION.SDK_INT >= 33 && pf?.notificationsGranted == false) {
            pendingStart = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startRecording()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            if (interrupted != null && state !is MonitorState.Recording) {
                InterruptedCard(
                    startedAt = interrupted!!.startedAt,
                    savedEvents = interrupted!!.savedEvents,
                    onFinalize = {
                        viewModel.finalizeInterrupted { sessionId -> onOpenReport(sessionId) }
                    },
                )
            }

            StatusCard(
                state = state,
                onStart = ::onStartClick,
                onStartNow = viewModel::startCaptureNow,
                onStop = viewModel::stopRecording,
                shouldConfirmStop = viewModel::shouldConfirmStopNow,
                // Start also gates on the storage pre-flight: below ~500 MB MonitorService bails
                // out before capture. Stop stays enabled for an active session (StatusCard).
                // P2-1: Start is disabled while an interrupted night awaits its Resume/Finalize
                // decision — starting a new night must never silently finalize it RECOVERED.
                startEnabled = recordingEnabled &&
                    (preFlight?.storageOk ?: true) &&
                    canStartNewSession(state),
            )
            val rowState = scheduleRowState(
                scheduleResolved = scheduleResolved,
                recordingEnabled = recordingEnabled,
                armedStart = nextScheduledStart,
            )
            when (rowState) {
                ScheduleRowState.Hidden -> Unit
                ScheduleRowState.Off -> ScheduleOffRow(onOpenSettings = onOpenSettings)
                is ScheduleRowState.Armed -> ScheduleRow(nextStart = rowState.nextStart, onSkip = viewModel::skipTonight)
            }
            (state as? MonitorState.StartError)?.let {
                Text(
                    stringResource(R.string.home_error_prefix, it.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            PreFlightCard(
                checklist = preFlight,
                onFixBattery = {
                    viewModel.batteryExemptionRequestIntent()?.let {
                        runCatching { context.startActivity(it) }
                            .onFailure { viewModel.openBatteryOptimizationSettings() }
                    }
                },
            )

            MissedNightBanner(missedNight)

            LastNightCard(
                session = latest,
                validityGrade = validityGrade,
                statusLabel = viewModel::statusLabel,
                experimentChip = experimentChip,
                onOpen = onOpenReport,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenDailyLog, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.factor_title))
                }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_settings))
                }
            }
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_history))
            }
            OutlinedButton(onClick = onOpenBaseline, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_baseline))
            }
            Text(
                text = stringResource(R.string.report_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(
    state: MonitorState,
    onStart: () -> Unit,
    onStartNow: () -> Unit,
    onStop: () -> Unit,
    shouldConfirmStop: () -> Boolean,
    startEnabled: Boolean,
) {
    val recording = state is MonitorState.Recording
    val delayed = state is MonitorState.Delayed
    val active = recording || delayed
    // Evaluated at TAP time (a 23:00 tap confirms, a 07:00 tap doesn't) — never at composition.
    // Saveable: the dialog must survive rotation mid-decision.
    var confirmStop by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // Ticks once per second so the elapsed readout is live while recording (and the countdown
    // live during the start delay).
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            kotlinx.coroutines.delay(1_000)
            nowTick = System.currentTimeMillis()
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MicIndicator(visible = recording)
            Text(
                text = when (state) {
                    is MonitorState.Recording -> stringResource(R.string.home_status_recording)
                    is MonitorState.Starting -> stringResource(R.string.home_status_starting)
                    is MonitorState.Delayed -> stringResource(
                        R.string.home_status_delayed,
                        formatDuration((state.captureStartsAtMs - nowTick).coerceAtLeast(0L)),
                    )
                    else -> stringResource(R.string.home_status_idle)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (recording) {
                Text(
                    formatDuration(nowTick - (state as MonitorState.Recording).startedAt),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Button(
                onClick = {
                    if (!active) onStart()
                    else if (recording && shouldConfirmStop()) confirmStop = true
                    else onStop()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = active || startEnabled,
            ) {
                Text(
                    if (active) stringResource(R.string.home_stop_recording)
                    else stringResource(R.string.home_start_recording)
                )
            }
            if (delayed) {
                // Start-delay skip: capture begins at the tap (the wait was armed on purpose —
                // this only ends it early). Not gated on the master switch, same as the
                // natural delay expiry it replaces mid-flight: the mic session is already open.
                OutlinedButton(onClick = onStartNow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_start_capture_now))
                }
            }
            if (!active && !startEnabled) {
                Text(
                    stringResource(R.string.home_recording_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.home_stop_confirm_title)) },
            text = { Text(stringResource(R.string.home_stop_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStop()
                }) { Text(stringResource(R.string.home_stop_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.home_stop_confirm_cancel))
                }
            },
        )
    }
}

/** Small schedule row: next armed bedtime start + one-tap skip (design: recording controls). */
@Composable
private fun ScheduleRow(nextStart: ZonedDateTime, onSkip: () -> Unit) {
    val timeText = formatMinutesOfDay(nextStart.hour * 60 + nextStart.minute)
    val label = if (nextStart.toLocalDate() == LocalDate.now()) {
        stringResource(R.string.home_schedule_next_tonight, timeText)
    } else {
        stringResource(R.string.home_schedule_next, formatDateTime(nextStart.toInstant().toEpochMilli()))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSkip) { Text(stringResource(R.string.home_schedule_skip)) }
    }
}

/** Schedule row's off-state: says so, and offers the shortcut to Settings (design: recording controls). */
@Composable
private fun ScheduleOffRow(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.home_schedule_off),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_schedule_enable)) }
    }
}

@Composable
private fun MicIndicator(visible: Boolean) {
    // Visible microphone-active indication (docs/PRIVACY_AND_REGULATORY.md §6 requirement).
    val description = stringResource(R.string.home_mic_indicator)
    Box(
        modifier = Modifier
            .size(16.dp)
            .semantics { contentDescription = description }
            .background(
                color = if (visible) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
            ),
    )
}

/** Pre-flight checklist for tonight — every silent-failure source made visible (P0 hardening). */
@Composable
private fun PreFlightCard(
    checklist: com.sleepanalysis.ui.home.PreFlightChecklist?,
    onFixBattery: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.home_preflight_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (checklist == null) return@Column
            CheckRow(stringResource(R.string.home_preflight_mic), checklist.micGranted)
            if (Build.VERSION.SDK_INT >= 33) {
                CheckRow(stringResource(R.string.home_preflight_notifications), checklist.notificationsGranted)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_preflight_battery), style = MaterialTheme.typography.bodyMedium)
                if (checklist.batteryExempt) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = onFixBattery, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                        Text(stringResource(R.string.home_preflight_fix))
                    }
                }
            }
            CheckRow(
                stringResource(
                    R.string.home_preflight_storage,
                    formatStorage(checklist.freeStorageMb),
                ),
                checklist.storageOk,
            )
        }
    }
}

private fun formatStorage(freeMb: Long): String =
    if (freeMb < 0) "?" else if (freeMb >= 1024) "%.1f GB".format(freeMb / 1024f) else "$freeMb MB"

@Composable
private fun CheckRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

/** Interrupted-night banner: clearly shows what happened and offers the saved data (P0 hardening). */
@Composable
private fun InterruptedCard(
    startedAt: Long,
    savedEvents: Int,
    onFinalize: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.home_interrupted_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.home_interrupted_body, formatDateTime(startedAt), savedEvents),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onFinalize, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_interrupted_action))
            }
        }
    }
}

/**
 * Pure pairing behind the "Captured X – Y" line on the last-night card: capture start (after
 * a start delay) to session end as formatted clock times, null while the night is still open.
 * Window start honors the recorded capture start and falls back to the tap time — the same
 * rule the report's SessionHealthCard applies. A non-positive capture_started_at (payload
 * garbage) counts as absent: the epoch must never render as the start label.
 */
internal fun captureWindowLabel(
    captureStatsJson: String?,
    startedAt: Long,
    endedAt: Long?,
): Pair<String, String>? {
    val end = endedAt ?: return null
    val startMs = CaptureStats.parse(captureStatsJson)?.captureStartMs?.takeIf { it > 0 } ?: startedAt
    return Formatting.time(startMs) to Formatting.time(end)
}

/**
 * What the Home schedule row shows (design: recording controls). Hidden until the first
 * schedule read completes — until then an off schedule and a not-yet-read one look alike, and
 * an on-schedule user must never see the off-row flash — and hidden whenever recording is
 * disabled. Otherwise the scheduler's answer decides: an armed start is shown, null (what it
 * answers when the schedule is off) is the explicit off-state. 2026-11-23: a row that vanished
 * without a trace read as the feature being removed, and a night went unrecorded.
 */
internal sealed interface ScheduleRowState {
    data object Hidden : ScheduleRowState
    data object Off : ScheduleRowState
    data class Armed(val nextStart: ZonedDateTime) : ScheduleRowState
}

internal fun scheduleRowState(
    scheduleResolved: Boolean,
    recordingEnabled: Boolean,
    armedStart: ZonedDateTime?,
): ScheduleRowState = when {
    !scheduleResolved -> ScheduleRowState.Hidden
    !recordingEnabled -> ScheduleRowState.Hidden
    else -> armedStart?.let(ScheduleRowState::Armed) ?: ScheduleRowState.Off
}

/**
 * Calm banner above the last-night card: the night before today produced nothing usable
 * (missed) or was cut short — absence made visible (P0 silent-failure discipline). Skipped
 * and recorded nights render nothing on purpose: nothing went wrong.
 */
@Composable
private fun MissedNightBanner(result: MissedNightResult?) {
    when (result) {
        is MissedNightResult.Missed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.home_missed_night_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.home_missed_night_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is MissedNightResult.Short -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.home_short_night_title, formatDuration(result.capturedInBedMs)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.home_short_night_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun LastNightCard(
    session: SleepSessionEntity?,
    validityGrade: String?,
    statusLabel: (String) -> String,
    experimentChip: HomeViewModel.ExperimentChip?,
    onOpen: (Long) -> Unit,
) {
    Card(onClick = { session?.let { onOpen(it.id) } }, enabled = session != null) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_last_night), style = MaterialTheme.typography.titleSmall)
            if (session == null) {
                Text(stringResource(R.string.home_no_sessions), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(formatDateTime(session.startedAt), style = MaterialTheme.typography.bodyLarge)
                // The actual capture window once the night has ended (post-start-delay start),
                // same fallback rule as the report's SessionHealthCard: capture_started_at from
                // the stats JSON, else the tap time. Stats are parsed once per session row,
                // not per recomposition; startedAt/endedAt are keys too so an open→finalized
                // transition (endedAt set, captureStats unchanged) still recomputes.
                val windowLabel = remember(session.id, session.captureStats, session.startedAt, session.endedAt) {
                    captureWindowLabel(session.captureStats, session.startedAt, session.endedAt)
                }
                windowLabel?.let { (start, end) ->
                    Text(
                        stringResource(R.string.home_capture_window, start, end),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    Text(
                        session.endedAt?.let { formatDuration(it - session.startedAt) } ?: "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        statusLabel(session.status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The night's stored validity grade (A–D) — the "was this night solid?"
                    // glance, same source as the report's badge (docs/NIGHT_REPORT.md).
                    validityGrade?.let { grade ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            grade,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (grade == "D") {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
            // Active N-of-1 experiment chip (docs/INSIGHTS.md §Playbook): honest day count,
            // same instruction copy the Insights screen shows.
            experimentChip?.let { chip ->
                val instruction = com.sleepanalysis.ui.insights.playbookInstruction(chip.actionId) // cross-screen shared copy
                Text(
                    if (chip.confirming) {
                        stringResource(
                            R.string.experiment_confirm_chip,
                            chip.usableNights,
                            chip.plannedNights,
                            instruction,
                        )
                    } else {
                        stringResource(R.string.experiment_chip, chip.usableNights, chip.plannedNights, instruction)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
