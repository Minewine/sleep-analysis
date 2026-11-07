package com.sleepanalysis.ui.settings

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.R
import com.sleepanalysis.data.garmin.GarminImportManager
import com.sleepanalysis.data.secure.GarminKeyStore
import com.sleepanalysis.ui.components.formatDateTime
import com.sleepanalysis.ui.components.formatMinutesOfDay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val keyStore: GarminKeyStore,
    private val prefs: com.sleepanalysis.data.prefs.UserPreferences,
    internal val importManager: GarminImportManager,
    private val scheduler: com.sleepanalysis.monitoring.schedule.RecordingScheduler,
) : ViewModel() {

    private val _keyStatus = MutableStateFlow(keyStore.maskedPreview())
    val keyStatus: StateFlow<String?> = _keyStatus.asStateFlow()

    private val _keyMessage = MutableStateFlow<String?>(null)
    val keyMessage: StateFlow<String?> = _keyMessage.asStateFlow()

    private val _debugEnter1 = MutableStateFlow(false)
    val debugEnter1: StateFlow<Boolean> = _debugEnter1.asStateFlow()

    // Recording controls (design: recording controls) — optimistic-set-then-persist like above.
    private val _recordingEnabled = MutableStateFlow(true)
    val recordingEnabled: StateFlow<Boolean> = _recordingEnabled.asStateFlow()

    private val _scheduleEnabled = MutableStateFlow(false)
    val scheduleEnabled: StateFlow<Boolean> = _scheduleEnabled.asStateFlow()

    private val _scheduleStartMinutes = MutableStateFlow(
        com.sleepanalysis.data.prefs.UserPreferences.DEFAULT_SCHEDULE_START_MINUTES
    )
    val scheduleStartMinutes: StateFlow<Int> = _scheduleStartMinutes.asStateFlow()

    private val _scheduleEndMinutes = MutableStateFlow(
        com.sleepanalysis.data.prefs.UserPreferences.DEFAULT_SCHEDULE_END_MINUTES
    )
    val scheduleEndMinutes: StateFlow<Int> = _scheduleEndMinutes.asStateFlow()

    private val _scheduleTimeError = MutableStateFlow(false)
    val scheduleTimeError: StateFlow<Boolean> = _scheduleTimeError.asStateFlow()

    private val _nextScheduledStart = MutableStateFlow<ZonedDateTime?>(null)
    val nextScheduledStart: StateFlow<ZonedDateTime?> = _nextScheduledStart.asStateFlow()

    private val _canScheduleExactAlarms = MutableStateFlow(true)
    val canScheduleExactAlarms: StateFlow<Boolean> = _canScheduleExactAlarms.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _startDelayMinutes = MutableStateFlow(0)
    val startDelayMinutes: StateFlow<Int> = _startDelayMinutes.asStateFlow()

    private val _noiseMinConfidence = MutableStateFlow(0.5)
    val noiseMinConfidence: StateFlow<Double> = _noiseMinConfidence.asStateFlow()

    init {
        viewModelScope.launch { _debugEnter1.value = prefs.debugEnterWindows1Now() }
        refreshExactAlarmPermission()
        refreshNotificationPermission()
        viewModelScope.launch {
            _recordingEnabled.value = prefs.recordingEnabledNow()
            _scheduleEnabled.value = prefs.scheduleEnabledNow()
            _scheduleStartMinutes.value = prefs.scheduleStartMinutesNow()
            _scheduleEndMinutes.value = prefs.scheduleEndMinutesNow()
            _startDelayMinutes.value = prefs.startDelayMinutesNow()
            _noiseMinConfidence.value = prefs.noiseDisplayMinConfidenceNow()
            _nextScheduledStart.value = scheduler.nextStartOrNull(ZonedDateTime.now())
        }
    }

    /** Applies to the NEXT recording start; a running session is unaffected. */
    fun setStartDelayMinutes(minutes: Int) {
        _startDelayMinutes.value = minutes
        viewModelScope.launch { prefs.setStartDelayMinutes(minutes) }
    }

    /** Report display filter for low-confidence noise; 0 = off. Affects the next report build. */
    fun setNoiseMinConfidence(value: Double) {
        _noiseMinConfidence.value = value
        viewModelScope.launch { prefs.setNoiseDisplayMinConfidence(value) }
    }

    fun setDebugEnter1(enabled: Boolean) {
        _debugEnter1.value = enabled
        viewModelScope.launch { prefs.setDebugEnterWindows1(enabled) }
    }

    fun setRecordingEnabled(enabled: Boolean) {
        _recordingEnabled.value = enabled
        viewModelScope.launch {
            prefs.setRecordingEnabled(enabled)
            rearmSchedule()
        }
    }

    fun setScheduleEnabled(enabled: Boolean) {
        _scheduleEnabled.value = enabled
        viewModelScope.launch {
            prefs.setScheduleEnabled(enabled)
            rearmSchedule()
        }
    }

    fun setScheduleStart(minutes: Int) {
        if (minutes == _scheduleEndMinutes.value) {
            _scheduleTimeError.value = true // rejected inline; keep the old value
            return
        }
        _scheduleTimeError.value = false
        _scheduleStartMinutes.value = minutes
        viewModelScope.launch {
            prefs.setScheduleStartMinutes(minutes)
            rearmSchedule()
        }
    }

    fun setScheduleEnd(minutes: Int) {
        if (minutes == _scheduleStartMinutes.value) {
            _scheduleTimeError.value = true // rejected inline; keep the old value
            return
        }
        _scheduleTimeError.value = false
        _scheduleEndMinutes.value = minutes
        viewModelScope.launch {
            prefs.setScheduleEndMinutes(minutes)
            rearmSchedule()
        }
    }

    fun skipTonight() {
        viewModelScope.launch {
            scheduler.skipNextStart()
            _nextScheduledStart.value = scheduler.nextStartOrNull(ZonedDateTime.now())
        }
    }

    /** System screen for granting exact-alarm permission (Android 12+). */
    fun exactAlarmRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${appContext.packageName}"))
        }.getOrNull()
    }

    /** System notification-settings screen for this app (POST_NOTIFICATIONS is granted there). */
    fun notificationsSettingsIntent(): Intent? = runCatching {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
    }.getOrNull()

    /**
     * Android 12+ gate (API 30 needs no permission for exact alarms). Refreshed on every
     * ON_RESUME — the user can grant/revoke it in the system screen and come straight back.
     */
    fun refreshExactAlarmPermission() {
        _canScheduleExactAlarms.value = if (Build.VERSION.SDK_INT >= 31) {
            appContext.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else true
    }

    /**
     * Notification grant gate: refreshed on every ON_RESUME alongside the exact-alarm check —
     * the user can grant/revoke it in the system screen and come straight back. Without the
     * grant the bedtime notification never appears, so a scheduled start silently never happens.
     */
    fun refreshNotificationPermission() {
        _notificationsEnabled.value =
            appContext.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    private suspend fun rearmSchedule() {
        scheduler.arm()
        _nextScheduledStart.value = scheduler.nextStartOrNull(ZonedDateTime.now())
    }

    fun saveKey(raw: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                keyStore.save(raw)
                _keyStatus.value = keyStore.maskedPreview()
                _keyMessage.value = null
            } catch (e: Exception) {
                _keyMessage.value = appContext.getString(R.string.settings_key_store_error, e.message)
            }
        }
    }

    fun clearKey() {
        viewModelScope.launch(Dispatchers.IO) {
            keyStore.clear()
            _keyStatus.value = null
        }
    }

    fun importForDate(date: String) {
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return
        // Manual import: the date is picked as shown on this device, so the device zone is
        // the correct explicit choice (the session report uses the session zone instead).
        viewModelScope.launch { importManager.importForDate(parsed, ZoneId.systemDefault()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val keyStatus by viewModel.keyStatus.collectAsStateWithLifecycle()
    val keyMessage by viewModel.keyMessage.collectAsStateWithLifecycle()
    val importState by viewModel.importManager.state.collectAsStateWithLifecycle()
    val debugEnter1 by viewModel.debugEnter1.collectAsStateWithLifecycle()
    val recordingEnabled by viewModel.recordingEnabled.collectAsStateWithLifecycle()
    val scheduleEnabled by viewModel.scheduleEnabled.collectAsStateWithLifecycle()
    val scheduleStartMinutes by viewModel.scheduleStartMinutes.collectAsStateWithLifecycle()
    val scheduleEndMinutes by viewModel.scheduleEndMinutes.collectAsStateWithLifecycle()
    val scheduleTimeError by viewModel.scheduleTimeError.collectAsStateWithLifecycle()
    val nextScheduledStart by viewModel.nextScheduledStart.collectAsStateWithLifecycle()
    val canScheduleExactAlarms by viewModel.canScheduleExactAlarms.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val startDelayMinutes by viewModel.startDelayMinutes.collectAsStateWithLifecycle()
    val noiseMinConfidence by viewModel.noiseMinConfidence.collectAsStateWithLifecycle()
    val scheduleOn = scheduleEnabled && recordingEnabled
    val context = androidx.compose.ui.platform.LocalContext.current

    // Re-read the exact-alarm and notification permissions whenever Settings becomes visible:
    // the user can grant/revoke them in the system screen and return straight here.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshExactAlarmPermission()
                viewModel.refreshNotificationPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var keyInput by remember { mutableStateOf("") }
    var importDate by remember { mutableStateOf(LocalDate.now().minusDays(1).toString()) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Recording controls (design: recording controls).
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.settings_recording_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_recording_enabled))
                            Text(
                                stringResource(R.string.settings_recording_enabled_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = recordingEnabled,
                            onCheckedChange = { viewModel.setRecordingEnabled(it) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_schedule_enabled))
                            Text(
                                stringResource(R.string.settings_schedule_enabled_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = scheduleEnabled,
                            onCheckedChange = { viewModel.setScheduleEnabled(it) },
                        )
                    }
                    StartDelayRow(
                        selectedMinutes = startDelayMinutes,
                        onSelect = { viewModel.setStartDelayMinutes(it) },
                    )
                    NoiseFilterRow(
                        selected = noiseMinConfidence,
                        onSelect = { viewModel.setNoiseMinConfidence(it) },
                    )
                    TimeRow(
                        label = stringResource(R.string.settings_schedule_start),
                        value = formatMinutesOfDay(scheduleStartMinutes),
                        enabled = scheduleOn,
                    ) { showStartPicker = true }
                    TimeRow(
                        label = stringResource(R.string.settings_schedule_end),
                        value = formatMinutesOfDay(scheduleEndMinutes),
                        enabled = scheduleOn,
                    ) { showEndPicker = true }
                    if (scheduleTimeError) {
                        Text(
                            stringResource(R.string.settings_schedule_equal_times),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val nextStart = nextScheduledStart
                    if (scheduleOn && nextStart != null) {
                        Text(
                            stringResource(
                                R.string.settings_schedule_next,
                                formatDateTime(nextStart.toInstant().toEpochMilli()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.settings_schedule_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (scheduleOn && !canScheduleExactAlarms) {
                        Text(
                            stringResource(R.string.settings_schedule_exact_alarm_missing),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            viewModel.exactAlarmRequestIntent()?.let {
                                runCatching { context.startActivity(it) }
                            }
                        }) { Text(stringResource(R.string.settings_schedule_exact_alarm_fix)) }
                    }
                    if (scheduleOn && !notificationsEnabled) {
                        Text(
                            stringResource(R.string.settings_schedule_notifications_missing),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            viewModel.notificationsSettingsIntent()?.let {
                                runCatching { context.startActivity(it) }
                            }
                        }) { Text(stringResource(R.string.settings_schedule_notifications_fix)) }
                    }
                    Text(
                        stringResource(R.string.settings_schedule_timing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_schedule_battery_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (scheduleOn && nextStart != null) {
                        TextButton(onClick = viewModel::skipTonight) {
                            Text(stringResource(R.string.settings_schedule_skip_tonight))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.settings_garmin_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.settings_garmin_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        keyStatus?.let { stringResource(R.string.settings_key_stored, it) }
                            ?: stringResource(R.string.settings_key_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text(stringResource(R.string.settings_key_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.saveKey(keyInput); keyInput = "" },
                            enabled = keyInput.isNotBlank(),
                        ) { Text(stringResource(R.string.settings_key_save)) }
                        OutlinedButton(onClick = { viewModel.clearKey() }, enabled = keyStatus != null) {
                            Text(stringResource(R.string.settings_key_clear))
                        }
                    }
                    keyMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_import_title), fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = importDate,
                        onValueChange = { importDate = it },
                        label = { Text(stringResource(R.string.settings_import_date)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { viewModel.importForDate(importDate) },
                        enabled = importDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                            importState !is GarminImportManager.GarminImportState.Importing,
                    ) { Text(stringResource(R.string.settings_import_action)) }
                    when (val s = importState) {
                        is GarminImportManager.GarminImportState.Importing ->
                            Text(stringResource(R.string.settings_import_running, s.date), style = MaterialTheme.typography.bodySmall)
                        is GarminImportManager.GarminImportState.Success -> {
                            Text(
                                stringResource(R.string.settings_import_done, s.metricsImported, s.endpointsStored),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            s.endpointStatuses.forEach { (endpoint, status) ->
                                Text("$endpoint → HTTP $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is GarminImportManager.GarminImportState.Error ->
                            Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else -> {}
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    stringResource(R.string.report_disclaimer),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_debug_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.settings_debug_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = debugEnter1,
                            onCheckedChange = { viewModel.setDebugEnter1(it) },
                        )
                    }
                }
            }
        }
    }

    if (showStartPicker) {
        ScheduleTimePickerDialog(
            title = stringResource(R.string.settings_schedule_start),
            initialMinutes = scheduleStartMinutes,
            onConfirm = {
                viewModel.setScheduleStart(it)
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        ScheduleTimePickerDialog(
            title = stringResource(R.string.settings_schedule_end),
            initialMinutes = scheduleEndMinutes,
            onConfirm = {
                viewModel.setScheduleEnd(it)
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false },
        )
    }
}

/** One time row: label + tappable "22:30" value; disabled while scheduling is off. */
@Composable
private fun TimeRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick, enabled = enabled) { Text(value) }
    }
}

/** Presets for the capture start delay; 0 = off. Values in minutes (night-2 dogfood idea). */
private val START_DELAY_OPTIONS = intArrayOf(0, 15, 30, 45, 60, 90)

/** Presets for the low-confidence noise display filter; 0.0 = off (docs/NIGHT_REPORT.md §4). */
private val NOISE_FILTER_OPTIONS = doubleArrayOf(0.0, 0.5, 0.7, 0.9)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoiseFilterRow(selected: Double, onSelect: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_noise_filter_title), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.settings_noise_filter_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NOISE_FILTER_OPTIONS.forEach { value ->
                FilterChip(
                    selected = kotlin.math.abs(value - selected) < 0.01,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            if (value == 0.0) stringResource(R.string.settings_noise_filter_off)
                            else String.format(java.util.Locale.US, "≥ %.2f", value)
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartDelayRow(selectedMinutes: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_delay_title), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.settings_delay_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Chips wrap onto as many lines as needed — six fixed-width buttons in one row collapse
        // into unreadable slivers on a phone display.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            START_DELAY_OPTIONS.forEach { minutes ->
                FilterChip(
                    selected = minutes == selectedMinutes,
                    onClick = { onSelect(minutes) },
                    label = {
                        Text(
                            if (minutes == 0) stringResource(R.string.settings_delay_off)
                            else stringResource(R.string.settings_delay_minutes, minutes)
                        )
                    },
                )
            }
        }
    }
}

/** Small dialog wrapping the Material3 TimePicker (24 h, matching the "22:30" display). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePickerDialog(
    title: String,
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timeState = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = timeState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour * 60 + timeState.minute) }) {
                Text(stringResource(R.string.settings_schedule_time_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_schedule_time_cancel))
            }
        },
    )
}
