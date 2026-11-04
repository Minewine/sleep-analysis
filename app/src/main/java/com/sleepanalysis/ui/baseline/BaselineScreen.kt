package com.sleepanalysis.ui.baseline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.R
import com.sleepanalysis.data.db.entity.ClinicalBaselineEntity
import com.sleepanalysis.data.repo.BaselineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BaselineViewModel @Inject constructor(
    private val repository: BaselineRepository,
) : ViewModel() {

    val baselines: StateFlow<List<ClinicalBaselineEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(entry: ClinicalBaselineEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.add(entry)
            onDone()
        }
    }
}

/** Read-only clinical reference data (docs/MVP_SCOPE.md F7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaselineScreen(
    onBack: () -> Unit,
    viewModel: BaselineViewModel = hiltViewModel(),
) {
    val baselines by viewModel.baselines.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.baseline_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.baseline_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (baselines.isEmpty()) {
                item { Text(stringResource(R.string.baseline_empty)) }
            }
            items(baselines.size) { index ->
                BaselineCard(baselines[index])
            }
            item {
                Button(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.baseline_add))
                }
            }
        }
    }

    if (showAddDialog) {
        AddBaselineDialog(
            onDismiss = { showAddDialog = false },
            onSave = { entry ->
                viewModel.add(entry) { showAddDialog = false }
            },
        )
    }
}

@Composable
private fun BaselineCard(entry: ClinicalBaselineEntity) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.studyDate + " · " + entry.lab + (entry.nightLabel?.let { " · $it" } ?: ""),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            MetricRow("Total sleep time", entry.totalSleepTimeMinutes?.let { "${it / 60}h ${it % 60}m" })
            MetricRow("Sleep efficiency", entry.sleepEfficiencyPercent?.let { "$it %" })
            MetricRow("Arousal index", entry.arousalIndexPerHour?.let { "$it /h" })
            MetricRow("N3 (deep sleep)", entry.n3Percent?.let { "$it %" })
            MetricRow("AHI", entry.ahiPerHour?.let { "$it /h" })
            entry.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            entry.sourceDocument?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.baseline_clinically_measured) + " · " + entry.studyType,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AddBaselineDialog(onDismiss: () -> Unit, onSave: (ClinicalBaselineEntity) -> Unit) {
    var studyDate by remember { mutableStateOf("") }
    var lab by remember { mutableStateOf("HUG") }
    var nightLabel by remember { mutableStateOf("") }
    var tst by remember { mutableStateOf("") }
    var efficiency by remember { mutableStateOf("") }
    var arousal by remember { mutableStateOf("") }
    var n3 by remember { mutableStateOf("") }
    var ahi by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var sourceDoc by remember { mutableStateOf("") }
    var dateError by remember { mutableStateOf(false) }

    val dateValid = studyDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.baseline_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = studyDate, onValueChange = { studyDate = it; dateError = false },
                    label = { Text(stringResource(R.string.baseline_field_date)) },
                    isError = dateError, singleLine = true,
                )
                if (dateError) {
                    Text(stringResource(R.string.baseline_invalid_date), color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(value = lab, onValueChange = { lab = it }, label = { Text(stringResource(R.string.baseline_field_lab)) }, singleLine = true)
                OutlinedTextField(value = nightLabel, onValueChange = { nightLabel = it }, label = { Text(stringResource(R.string.baseline_field_night)) }, singleLine = true)
                OutlinedTextField(value = tst, onValueChange = { tst = it }, label = { Text(stringResource(R.string.baseline_field_tst)) }, singleLine = true)
                OutlinedTextField(value = efficiency, onValueChange = { efficiency = it }, label = { Text(stringResource(R.string.baseline_field_efficiency)) }, singleLine = true)
                OutlinedTextField(value = arousal, onValueChange = { arousal = it }, label = { Text(stringResource(R.string.baseline_field_arousal)) }, singleLine = true)
                OutlinedTextField(value = n3, onValueChange = { n3 = it }, label = { Text(stringResource(R.string.baseline_field_n3)) }, singleLine = true)
                OutlinedTextField(value = ahi, onValueChange = { ahi = it }, label = { Text(stringResource(R.string.baseline_field_ahi)) }, singleLine = true)
                OutlinedTextField(value = sourceDoc, onValueChange = { sourceDoc = it }, label = { Text(stringResource(R.string.baseline_field_source_doc)) }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text(stringResource(R.string.baseline_field_notes)) })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!dateValid) {
                    dateError = true
                    return@TextButton
                }
                onSave(
                    ClinicalBaselineEntity(
                        studyDate = studyDate,
                        lab = lab.ifBlank { "?" },
                        studyType = "PSG",
                        nightLabel = nightLabel.ifBlank { null },
                        totalSleepTimeMinutes = tst.toIntOrNull(),
                        sleepEfficiencyPercent = efficiency.toDoubleOrNull(),
                        arousalIndexPerHour = arousal.toDoubleOrNull(),
                        n3Percent = n3.toDoubleOrNull(),
                        ahiPerHour = ahi.toDoubleOrNull(),
                        notes = notes.ifBlank { null },
                        sourceDocument = sourceDoc.ifBlank { null },
                    )
                )
            }) { Text(stringResource(R.string.baseline_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(android.R.string.cancel)) }
        },
    )
}
