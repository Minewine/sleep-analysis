package com.sleepanalysis.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepanalysis.R
import com.sleepanalysis.data.prefs.UserPreferences
import com.sleepanalysis.domain.experiments.ExperimentMath
import com.sleepanalysis.domain.experiments.ExperimentTracker
import com.sleepanalysis.domain.insights.ConfidenceTier
import com.sleepanalysis.domain.insights.EscalationRule
import com.sleepanalysis.domain.insights.Insight
import com.sleepanalysis.domain.insights.InsightRules
import com.sleepanalysis.domain.insights.PersonalBaseline
import com.sleepanalysis.domain.insights.Playbook
import com.sleepanalysis.domain.insights.SleepRegularity
import com.sleepanalysis.ui.components.formatDuration

/**
 * The Insights screen (docs/INSIGHTS.md): the escalation card, the personal baseline card, the
 * approximate regularity row, the deterministic rule insights, the N-of-1 experiment state and
 * the ranked playbook — every claim with its confidence tier, its evidence, and a dismissal.
 * Tone: "your recordings suggest…" — never clinical, never diagnosis, events ≠ arousals,
 * pauses ≠ apneas. Playbook actions are suggestions with their evidence cited, never cures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit = {},
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val report = state.report
    // m3 affordance: dismissed rules are preferences, never deletions — they can be re-shown
    // (docs/INSIGHTS.md §Guardrails 6).
    var showHidden by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.insight_title)) },
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
            if (state.loaded && (report == null || report.nightCount == 0)) {
                item { Text(stringResource(R.string.insight_empty)) }
            }
            report?.let { r ->
                // Escalation card — top of the screen (docs/INSIGHTS.md §Escalation).
                item(key = "escalation") {
                    EscalationCard(
                        escalation = state.escalation,
                        selfReport = state.selfReport,
                        onAnswer = viewModel::answerSelfReport,
                    )
                }
                if (r.nightCount > 0 && r.eligibleCount == 0) {
                    item { Text(stringResource(R.string.insight_no_eligible)) }
                }
                item(key = "baseline") { BaselineCard(r) }
                item(key = "regularity") { RegularityCard(r) }
                val visible = r.insights.filter { it.ruleId !in state.dismissedRules }
                // Key = ruleId + metric: baseline_excursion emits ONE insight per metric under
                // the same ruleId — a ruleId-only key would crash the lazy list on any night
                // with two excursions.
                items(visible, key = { "${it.ruleId}:${it.metricName ?: ""}" }) { insight ->
                    InsightCard(
                        insight,
                        onOpenReport = onOpenReport,
                        onDismiss = viewModel::dismiss,
                    )
                }
                if (state.dismissedRules.isNotEmpty()) {
                    item(key = "hidden-toggle") {
                        TextButton(onClick = { showHidden = !showHidden }) {
                            Text(
                                stringResource(
                                    if (showHidden) R.string.insight_hide_hidden else R.string.insight_show_hidden,
                                    state.dismissedRules.size,
                                ),
                            )
                        }
                    }
                    if (showHidden) {
                        val hidden = r.insights.filter { it.ruleId in state.dismissedRules }
                        items(hidden, key = { "hidden:${it.ruleId}:${it.metricName ?: ""}" }) { insight ->
                            DismissedInsightCard(insight, onRestore = { viewModel.restore(insight.ruleId) })
                        }
                    }
                }
                // Slice 3 — the guide layer below the rule insights (docs/INSIGHTS.md §Playbook).
                state.activeExperiment?.let { experiment ->
                    item(key = "experiment-active") {
                        ActiveExperimentCard(experiment, onAbandon = viewModel::abandonExperiment)
                    }
                }
                state.verdictExperiment?.let { experiment ->
                    item(key = "experiment-verdict") {
                        VerdictCard(
                            experiment = experiment,
                            onKeep = viewModel::keepVerdict,
                            onAbandon = viewModel::abandonExperiment,
                            onConfirm = viewModel::startConfirmPhase,
                        )
                    }
                }
                item(key = "playbook-header") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.playbook_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.playbook_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.startError?.let { error ->
                    item(key = "experiment-start-error") { StartErrorText(error, r.baseline.eligibleCount) }
                }
                items(state.rankedActions, key = { "playbook-${it.id}" }) { action ->
                    PlaybookActionCard(
                        action = action,
                        experimentRunning = state.activeExperiment != null,
                        onStart = viewModel::startExperiment,
                    )
                }
                // Context-only actions (experimentCapable = false) never rank — they render as
                // static context below the ranked cards.
                items(
                    Playbook.ACTIONS.filter { !it.experimentCapable },
                    key = { "playbook-context-${it.id}" },
                ) { action ->
                    PlaybookContextCard(action)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Escalation (docs/INSIGHTS.md §Escalation) — evaluation, never diagnosis.
// ---------------------------------------------------------------------------

@Composable
private fun EscalationCard(
    escalation: EscalationRule.Result?,
    selfReport: UserPreferences.EscalationSelfReport?,
    onAnswer: (Boolean, Boolean, Boolean) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.escalation_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (escalation?.escalate == true) {
                Text(stringResource(R.string.escalation_triggered), style = MaterialTheme.typography.bodyMedium)
                if (escalation?.acousticTriggered == true) {
                    Text(
                        stringResource(R.string.escalation_acoustic_arm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (escalation?.selfReportTriggered == true) {
                    Text(
                        stringResource(R.string.escalation_selfreport_arm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.escalation_collecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.escalation_context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelfReportSection(selfReport, onAnswer)
        }
    }
}

@Composable
private fun SelfReportSection(
    selfReport: UserPreferences.EscalationSelfReport?,
    onAnswer: (Boolean, Boolean, Boolean) -> Unit,
) {
    var editing by remember(selfReport?.answered) { mutableStateOf(selfReport?.answered != true) }
    var loud by remember(selfReport?.answered) { mutableStateOf(selfReport?.snoringLoud ?: false) }
    var pauses by remember(selfReport?.answered) { mutableStateOf(selfReport?.witnessedPauses ?: false) }
    var sleepy by remember(selfReport?.answered) { mutableStateOf(selfReport?.daytimeSleepiness ?: false) }

    if (!editing) {
        TextButton(onClick = { editing = true }) {
            Text(stringResource(R.string.escalation_edit))
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        YesNoQuestion(stringResource(R.string.escalation_question_s), loud, { loud = it })
        YesNoQuestion(stringResource(R.string.escalation_question_o), pauses, { pauses = it })
        YesNoQuestion(stringResource(R.string.escalation_question_t), sleepy, { sleepy = it })
        Button(onClick = { onAnswer(loud, pauses, sleepy) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.escalation_save))
        }
    }
}

@Composable
private fun YesNoQuestion(question: String, answer: Boolean, onAnswer: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(question, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YesNoButton(
                selected = answer,
                label = stringResource(R.string.escalation_yes),
                onClick = { onAnswer(true) },
            )
            YesNoButton(
                selected = !answer,
                label = stringResource(R.string.escalation_no),
                onClick = { onAnswer(false) },
            )
        }
    }
}

/** The selected answer renders filled — a re-tap is a no-op, the other side switches. */
@Composable
private fun YesNoButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

// ---------------------------------------------------------------------------
// Experiment cards (docs/DATA_MODEL.md §3.12, docs/INSIGHTS.md §Playbook).
// ---------------------------------------------------------------------------

@Composable
private fun ActiveExperimentCard(
    experiment: InsightsViewModel.ExperimentView,
    onAbandon: (Long) -> Unit,
) {
    val instruction = playbookInstruction(experiment.actionId)
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.experiment_active_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (experiment.phase == ExperimentTracker.Phase.CONFIRM) {
                Text(
                    stringResource(
                        R.string.experiment_confirm_chip,
                        experiment.usableNights,
                        experiment.plannedNights,
                        instruction,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.experiment_confirm_intro, experiment.plannedNights),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(
                        R.string.experiment_chip,
                        experiment.usableNights,
                        experiment.plannedNights,
                        instruction,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(
                    R.string.experiment_progress,
                    experiment.usableNights,
                    experiment.plannedNights,
                    experiment.excludedNights,
                    experiment.missingValues,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.experiment_below_pace),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onAbandon(experiment.id) }) {
                Text(stringResource(R.string.experiment_abandon))
            }
        }
    }
}

@Composable
private fun VerdictCard(
    experiment: InsightsViewModel.ExperimentView,
    onKeep: (Long) -> Unit,
    onAbandon: (Long) -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val verdict = experiment.verdict ?: return
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.experiment_active_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (verdict.changeDetected) {
                Text(
                    stringResource(
                        R.string.experiment_verdict_change,
                        metricLabel(experiment.metric),
                        stringResource(
                            if (verdict.directionUp == true) {
                                R.string.experiment_direction_up
                            } else {
                                R.string.experiment_direction_down
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(R.string.experiment_verdict_no_change),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(
                    if (verdict.confidence == ConfidenceTier.MODERATE) {
                        R.string.insight_confidence_moderate
                    } else {
                        R.string.insight_confidence_low
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (verdict.confirmed == true) {
                Text(
                    stringResource(R.string.experiment_confirmed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (verdict.confirmRan && verdict.confirmed == false) {
                Text(
                    stringResource(R.string.experiment_not_confirmed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (verdict.changeDetected && !verdict.confirmRan && verdict.confirmed == null) {
                Text(
                    stringResource(R.string.experiment_confirm_intro, experiment.plannedNights),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { onConfirm(experiment.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.experiment_confirm_start, experiment.plannedNights))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onKeep(experiment.id) }) {
                    Text(stringResource(R.string.experiment_keep))
                }
                TextButton(onClick = { onAbandon(experiment.id) }) {
                    Text(stringResource(R.string.experiment_abandon))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Playbook cards (docs/INSIGHTS.md §Playbook) — suggestions, never cures.
// ---------------------------------------------------------------------------

@Composable
private fun PlaybookActionCard(
    action: Playbook.PlaybookAction,
    experimentRunning: Boolean,
    onStart: (Playbook.PlaybookAction) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(playbookTitle(action.id), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(playbookInstruction(action.id), style = MaterialTheme.typography.bodyMedium)
            Text(
                playbookEvidence(action.evidenceKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.experiment_verify, metricLabel(action.verifyMetricId)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.experiment_recheck),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action.experimentCapable) {
                Button(
                    onClick = { onStart(action) },
                    enabled = !experimentRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.experiment_start_button, ExperimentMath.DEFAULT_PLANNED_NIGHTS))
                }
                if (experimentRunning) {
                    Text(
                        stringResource(R.string.experiment_finish_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A context-only action (experimentCapable = false): read-only context, no experiment button. */
@Composable
private fun PlaybookContextCard(action: Playbook.PlaybookAction) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(playbookTitle(action.id), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(playbookInstruction(action.id), style = MaterialTheme.typography.bodyMedium)
            Text(
                playbookEvidence(action.evidenceKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StartErrorText(error: InsightsViewModel.ExperimentStartError, eligibleCount: Int) {
    val text = when (error) {
        InsightsViewModel.ExperimentStartError.ACTIVE_EXISTS -> stringResource(R.string.experiment_finish_first)
        InsightsViewModel.ExperimentStartError.BASELINE_COLLECTING ->
            stringResource(R.string.experiment_baseline_collecting, eligibleCount)
        InsightsViewModel.ExperimentStartError.NO_BASELINE_BAND -> stringResource(R.string.experiment_no_band)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

// ---------------------------------------------------------------------------
// Copy resolution — string-resource NAME convention (docs/INSIGHTS.md), shared with Home.
// ---------------------------------------------------------------------------

/** The action's instruction copy by playbook action id (the Home experiment chip uses it too). */
@Composable
fun playbookInstruction(actionId: String?): String = when (actionId) {
    "stimulus_control" -> stringResource(R.string.playbook_stimulus_control_instruction)
    "fixed_wake" -> stringResource(R.string.playbook_fixed_wake_instruction)
    "morning_light" -> stringResource(R.string.playbook_morning_light_instruction)
    "wind_down" -> stringResource(R.string.playbook_wind_down_instruction)
    "slow_breathing" -> stringResource(R.string.playbook_slow_breathing_instruction)
    "mindfulness" -> stringResource(R.string.playbook_mindfulness_instruction)
    "bedroom_temp" -> stringResource(R.string.playbook_bedroom_temp_instruction)
    "nap_limit" -> stringResource(R.string.playbook_nap_limit_instruction)
    "screens_optional" -> stringResource(R.string.playbook_screens_optional_instruction)
    "side_sleeping" -> stringResource(R.string.playbook_side_sleeping_instruction)
    "myofunctional" -> stringResource(R.string.playbook_myofunctional_instruction)
    "bed_elevation" -> stringResource(R.string.playbook_bed_elevation_instruction)
    "nasal_dilator" -> stringResource(R.string.playbook_nasal_dilator_instruction)
    "no_evening_alcohol" -> stringResource(R.string.playbook_no_evening_alcohol_instruction)
    "weight_context" -> stringResource(R.string.playbook_weight_context_instruction)
    else -> "" // unknown/legacy action id renders no instruction — never a fabricated claim
}

/** The action's short title by playbook action id. */
@Composable
private fun playbookTitle(actionId: String?): String = when (actionId) {
    "stimulus_control" -> stringResource(R.string.playbook_stimulus_control_title)
    "fixed_wake" -> stringResource(R.string.playbook_fixed_wake_title)
    "morning_light" -> stringResource(R.string.playbook_morning_light_title)
    "wind_down" -> stringResource(R.string.playbook_wind_down_title)
    "slow_breathing" -> stringResource(R.string.playbook_slow_breathing_title)
    "mindfulness" -> stringResource(R.string.playbook_mindfulness_title)
    "bedroom_temp" -> stringResource(R.string.playbook_bedroom_temp_title)
    "nap_limit" -> stringResource(R.string.playbook_nap_limit_title)
    "screens_optional" -> stringResource(R.string.playbook_screens_optional_title)
    "side_sleeping" -> stringResource(R.string.playbook_side_sleeping_title)
    "myofunctional" -> stringResource(R.string.playbook_myofunctional_title)
    "bed_elevation" -> stringResource(R.string.playbook_bed_elevation_title)
    "nasal_dilator" -> stringResource(R.string.playbook_nasal_dilator_title)
    "no_evening_alcohol" -> stringResource(R.string.playbook_no_evening_alcohol_title)
    "weight_context" -> stringResource(R.string.playbook_weight_context_title)
    else -> actionId ?: ""
}

/** The evidence citation line by [Playbook.PlaybookAction.evidenceKey] (docs/EVIDENCE_BASE.md row). */
@Composable
private fun playbookEvidence(evidenceKey: String): String = when (evidenceKey) {
    Playbook.EvidenceKeys.EDINGER_2021 -> stringResource(R.string.playbook_evidence_edinger_2021)
    Playbook.EvidenceKeys.WINDRED_2024 -> stringResource(R.string.playbook_evidence_windred_2024)
    Playbook.EvidenceKeys.ROENNEBERG_2012 -> stringResource(R.string.playbook_evidence_roenneberg_2012)
    Playbook.EvidenceKeys.BALBAN_2023 -> stringResource(R.string.playbook_evidence_balban_2023)
    Playbook.EvidenceKeys.RUSCH_2019 -> stringResource(R.string.playbook_evidence_rusch_2019)
    Playbook.EvidenceKeys.LAN_2017 -> stringResource(R.string.playbook_evidence_lan_2017)
    Playbook.EvidenceKeys.DUTHEIL_2021 -> stringResource(R.string.playbook_evidence_dutheil_2021)
    Playbook.EvidenceKeys.BROSNAN_2024 -> stringResource(R.string.playbook_evidence_brosnan_2024)
    Playbook.EvidenceKeys.RAVESLOOT_2017 -> stringResource(R.string.playbook_evidence_ravesloot_2017)
    Playbook.EvidenceKeys.CAMACHO_2015 -> stringResource(R.string.playbook_evidence_camacho_2015)
    Playbook.EvidenceKeys.SOUZA_2017 -> stringResource(R.string.playbook_evidence_souza_2017)
    Playbook.EvidenceKeys.MAZZA_2019 -> stringResource(R.string.playbook_evidence_mazza_2019)
    Playbook.EvidenceKeys.EBRAHIM_2013 -> stringResource(R.string.playbook_evidence_ebrahim_2013)
    Playbook.EvidenceKeys.PEPPARD_2000 -> stringResource(R.string.playbook_evidence_peppard_2000)
    else -> evidenceKey
}

@Composable
private fun BaselineCard(report: com.sleepanalysis.domain.insights.InsightsReport) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.insight_baseline_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val note = if (report.baseline.state == PersonalBaseline.State.READY) {
                stringResource(R.string.insight_baseline_ready, report.baseline.eligibleCount)
            } else {
                stringResource(R.string.insight_collecting, report.baseline.eligibleCount)
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            report.baseline.bands.forEach { (metric, band) ->
                Text(
                    stringResource(
                        R.string.insight_baseline_metric,
                        metricLabel(metric),
                        formatBandValue(metric, band.median),
                        formatBandValue(metric, band.low),
                        formatBandValue(metric, band.high),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RegularityCard(report: com.sleepanalysis.domain.insights.InsightsReport) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.insight_regularity_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val regularity: SleepRegularity.Result = report.regularity
            if (regularity.sri != null) {
                Text(
                    stringResource(R.string.insight_regularity_sri, regularity.sri),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(R.string.insight_regularity_collecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // SJL renders independently of the SRI: it needs only 2+2 windows, far fewer than
            // the SRI's ≥5-day horizon — gating it on SRI readiness hid a working number.
            regularity.socialJetlagHours?.let { sjl ->
                Text(
                    stringResource(R.string.insight_regularity_sjl, sjl),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight, onOpenReport: (Long) -> Unit, onDismiss: (String) -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(insightCopy(insight), style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (insight.confidenceTier == ConfidenceTier.MODERATE) {
                            R.string.insight_confidence_moderate
                        } else {
                            R.string.insight_confidence_low
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onDismiss(insight.ruleId) }) {
                    Text(stringResource(R.string.insight_dismiss))
                }
            }
            // Evidence link: the claim always names the night(s) it rests on; tapping opens the
            // most recent evidence night's Morning Summary (the night the copy cites).
            insight.evidenceNightIds.lastOrNull()?.let { sessionId ->
                TextButton(onClick = { onOpenReport(sessionId) }) {
                    Text(stringResource(R.string.insight_evidence_link))
                }
            }
        }
    }
}

/** A dismissed insight, shown only through the "Show hidden" toggle, with its restore action. */
@Composable
private fun DismissedInsightCard(insight: Insight, onRestore: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(insightCopy(insight), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRestore) {
                Text(stringResource(R.string.insight_restore))
            }
        }
    }
}

/** Rule copy by [Insight.copyKey] — the string-resource NAME convention (docs/INSIGHTS.md). */
@Composable
private fun insightCopy(insight: Insight): String = when (insight.copyKey) {
    InsightRules.CopyKeys.BEDTIME_VARIABILITY -> stringResource(R.string.insight_rule_bedtime_variability)
    InsightRules.CopyKeys.SRI_DIP -> stringResource(R.string.insight_rule_sri_dip)
    InsightRules.CopyKeys.SOCIAL_JETLAG -> stringResource(R.string.insight_rule_social_jetlag)
    InsightRules.CopyKeys.SECOND_HALF_ALCOHOL -> stringResource(R.string.insight_rule_second_half_alcohol)
    InsightRules.CopyKeys.SECOND_HALF_UNTAGGED -> stringResource(R.string.insight_rule_second_half_untagged)
    InsightRules.CopyKeys.BASELINE_EXCURSION ->
        stringResource(R.string.insight_rule_baseline_excursion, insight.metricName?.let { metricLabel(it) } ?: "")
    InsightRules.CopyKeys.ALCOHOL_NIGHTS -> stringResource(R.string.insight_rule_alcohol_nights)
    InsightRules.CopyKeys.CAFFEINE_NIGHTS -> stringResource(R.string.insight_rule_caffeine_nights)
    InsightRules.CopyKeys.EXERCISE_EVENING -> stringResource(R.string.insight_rule_exercise_evening)
    InsightRules.CopyKeys.STRESS_HIGH -> stringResource(R.string.insight_rule_stress_high)
    else -> insight.copyKey // unknown key renders its name — never a fabricated claim
}

@Composable
private fun metricLabel(metric: String): String = when (metric) {
    PersonalBaseline.Metrics.SNORE_SHARE -> stringResource(R.string.insight_metric_snore_share)
    PersonalBaseline.Metrics.LONGEST_BOUT_MS -> stringResource(R.string.insight_metric_longest_bout_ms)
    PersonalBaseline.Metrics.SCORE -> stringResource(R.string.insight_metric_score)
    PersonalBaseline.Metrics.GARMIN_AWAKE_MIN -> stringResource(R.string.insight_metric_garmin_awake_min)
    PersonalBaseline.Metrics.RMSSD_MS -> stringResource(R.string.insight_metric_rmssd_ms)
    "bedtime_std_min" -> stringResource(R.string.insight_metric_bedtime_std_min)
    "sri" -> stringResource(R.string.insight_metric_sri)
    "social_jetlag_h" -> stringResource(R.string.insight_metric_social_jetlag_h)
    "snore_share_second_half" -> stringResource(R.string.insight_metric_snore_share_second_half)
    else -> metric
}

/** Metric-appropriate value formatting (share as %, bout as duration, the rest as numbers). */
private fun formatBandValue(metric: String, value: Double): String = when (metric) {
    PersonalBaseline.Metrics.SNORE_SHARE ->
        String.format(java.util.Locale.US, "%.1f%%", value * 100)
    PersonalBaseline.Metrics.LONGEST_BOUT_MS -> formatDuration(value.toLong())
    else -> String.format(java.util.Locale.US, "%.0f", value)
}
