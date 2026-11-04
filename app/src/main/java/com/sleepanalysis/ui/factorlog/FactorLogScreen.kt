package com.sleepanalysis.ui.factorlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.R
import com.sleepanalysis.data.db.entity.UserFactorEntity
import com.sleepanalysis.data.repo.FactorLogRepository
import com.sleepanalysis.domain.factors.DailyFactor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FactorLogUiState(
    val date: String = LocalDate.now().toString(),
    val alcohol: Boolean = false,
    val alcoholUnits: String = "",
    val lateMeal: Boolean = false,
    val caffeineAfter14: Boolean = false,
    val exercise: Boolean = false,
    val medicationOrDevice: Boolean = false,
    val stress: Int? = null,
    val notes: String = "",
)

@HiltViewModel
class FactorLogViewModel @Inject constructor(
    private val repository: FactorLogRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(FactorLogUiState())
    val ui: StateFlow<FactorLogUiState> = _ui.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** Loads existing entries whenever the date changes. */
    fun loadDate(date: String) {
        val parsed = date.toLocalDateOrNull() ?: return
        _ui.value = _ui.value.copy(date = date)
        viewModelScope.launch {
            val factors = repository.factorsForDate(parsed)
            fun num(key: DailyFactor) = factors.firstOrNull { it.factorKey == key.key }?.valueNum
            _ui.value = FactorLogUiState(
                date = date,
                alcohol = factors.any { it.factorKey == DailyFactor.ALCOHOL.key },
                alcoholUnits = num(DailyFactor.ALCOHOL)?.takeIf { it > 1.0 }?.toInt()?.toString() ?: "",
                lateMeal = factors.any { it.factorKey == DailyFactor.LATE_MEAL.key },
                caffeineAfter14 = factors.any { it.factorKey == DailyFactor.CAFFEINE_AFTER_14.key },
                exercise = factors.any { it.factorKey == DailyFactor.EXERCISE.key },
                medicationOrDevice = factors.any { it.factorKey == DailyFactor.MEDICATION_OR_DEVICE.key },
                stress = num(DailyFactor.STRESS)?.toInt(),
                notes = factors.firstOrNull { it.factorKey == DailyFactor.NOTES.key }?.valueText ?: "",
            )
            _saved.value = false
        }
    }

    fun update(transform: (FactorLogUiState) -> FactorLogUiState) {
        _ui.value = transform(_ui.value)
        _saved.value = false
    }

    fun save() {
        val state = _ui.value
        val date = state.date.toLocalDateOrNull() ?: return
        val factors = buildList {
            val d = date.toString()
            if (state.alcohol) {
                add(
                    UserFactorEntity(
                        date = d,
                        factorKey = DailyFactor.ALCOHOL.key,
                        valueNum = state.alcoholUnits.toDoubleOrNull() ?: 1.0,
                    )
                )
            }
            if (state.lateMeal) add(UserFactorEntity(date = d, factorKey = DailyFactor.LATE_MEAL.key, valueNum = 1.0))
            if (state.caffeineAfter14) add(UserFactorEntity(date = d, factorKey = DailyFactor.CAFFEINE_AFTER_14.key, valueNum = 1.0))
            if (state.exercise) add(UserFactorEntity(date = d, factorKey = DailyFactor.EXERCISE.key, valueNum = 1.0))
            if (state.medicationOrDevice) add(UserFactorEntity(date = d, factorKey = DailyFactor.MEDICATION_OR_DEVICE.key, valueNum = 1.0))
            state.stress?.let { add(UserFactorEntity(date = d, factorKey = DailyFactor.STRESS.key, valueNum = it.toDouble())) }
            if (state.notes.isNotBlank()) add(UserFactorEntity(date = d, factorKey = DailyFactor.NOTES.key, valueText = state.notes.trim()))
        }
        viewModelScope.launch {
            repository.saveForDate(date, factors)
            _saved.value = true
        }
    }

    private fun String.toLocalDateOrNull(): LocalDate? =
        if (matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) runCatching { LocalDate.parse(this) }.getOrNull() else null
}

/** Lightweight daily factor log (docs/DATA_MODEL.md §3.6) — user-entered context, not measurements. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactorLogScreen(
    onBack: () -> Unit,
    viewModel: FactorLogViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val dateValid = ui.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))

    LaunchedEffect(ui.date) { viewModel.loadDate(ui.date) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.factor_title)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = ui.date,
                onValueChange = { v -> viewModel.update { it.copy(date = v) } },
                label = { Text(stringResource(R.string.factor_date)) },
                singleLine = true,
                isError = !dateValid,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.factor_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchRow(DailyFactor.ALCOHOL.label, ui.alcohol) { on ->
                        viewModel.update { s -> s.copy(alcohol = on, alcoholUnits = if (on) s.alcoholUnits else "") }
                    }
                    if (ui.alcohol) {
                        OutlinedTextField(
                            value = ui.alcoholUnits,
                            onValueChange = { v -> viewModel.update { it.copy(alcoholUnits = v) } },
                            label = { Text(stringResource(R.string.factor_alcohol_units)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SwitchRow(DailyFactor.LATE_MEAL.label, ui.lateMeal) { on ->
                        viewModel.update { it.copy(lateMeal = on) }
                    }
                    SwitchRow(DailyFactor.CAFFEINE_AFTER_14.label, ui.caffeineAfter14) { on ->
                        viewModel.update { it.copy(caffeineAfter14 = on) }
                    }
                    SwitchRow(DailyFactor.EXERCISE.label, ui.exercise) { on ->
                        viewModel.update { it.copy(exercise = on) }
                    }
                    SwitchRow(DailyFactor.MEDICATION_OR_DEVICE.label, ui.medicationOrDevice) { on ->
                        viewModel.update { it.copy(medicationOrDevice = on) }
                    }
                }
            }

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DailyFactor.STRESS.label, style = MaterialTheme.typography.bodyLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        (1..5).forEach { level ->
                            FilterChip(
                                selected = ui.stress == level,
                                onClick = {
                                    viewModel.update { s -> s.copy(stress = if (s.stress == level) null else level) }
                                },
                                label = { Text("$level") },
                            )
                        }
                        Text(
                            ui.stress?.let { "$it / 5" } ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DailyFactor.NOTES.label, style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = ui.notes,
                        onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            }

            Button(
                onClick = { viewModel.save() },
                enabled = dateValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.factor_save)) }
            if (saved) {
                Text(
                    stringResource(R.string.factor_saved),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
