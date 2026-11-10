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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.sleepanalysis.domain.insights.ConfidenceTier
import com.sleepanalysis.domain.insights.Insight
import com.sleepanalysis.domain.insights.InsightRules
import com.sleepanalysis.domain.insights.PersonalBaseline
import com.sleepanalysis.domain.insights.SleepRegularity
import com.sleepanalysis.ui.components.formatDuration

/**
 * The Insights screen (docs/INSIGHTS.md): the personal baseline card, the approximate
 * regularity row, and the deterministic rule insights — each claim with its confidence tier,
 * the evidence night behind it, and a dismissal. Tone: "your recordings suggest…" — never
 * clinical, never diagnosis, events ≠ arousals, pauses ≠ apneas.
 *
 * Slice 3 placeholder: playbook/escalation cards (post-insight guidance, docs/ROADMAP.md)
 * belong in a section BELOW the rule insights — deliberately not built in this slice.
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
                if (r.nightCount > 0 && r.eligibleCount == 0) {
                    item { Text(stringResource(R.string.insight_no_eligible)) }
                }
                item { BaselineCard(r) }
                item { RegularityCard(r) }
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
                    item {
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
            }
        }
    }
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
