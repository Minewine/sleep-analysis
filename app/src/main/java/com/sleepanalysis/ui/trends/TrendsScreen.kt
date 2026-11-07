package com.sleepanalysis.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.R
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.domain.report.NightTrend
import com.sleepanalysis.ui.components.Formatting
import com.sleepanalysis.ui.components.formatDateTime
import com.sleepanalysis.ui.components.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads the last 7 capture-carrying nights once (same pull-once pattern as the report). */
@HiltViewModel
class TrendsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _trends = MutableStateFlow<List<NightTrend>>(emptyList())
    val trends: StateFlow<List<NightTrend>> = _trends.asStateFlow()

    /** False until the first load lands — keeps the empty state from flashing on open. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        viewModelScope.launch {
            _trends.value = sessionRepository.buildTrends()
            _loaded.value = true
        }
    }
}

/**
 * The last-N-nights trend (docs/NIGHT_REPORT.md §Trends). One tappable row per night — tap
 * opens that night's Morning Summary — plus a minutes-scaled bar per row for the at-a-glance
 * shape of the week. Nights whose detector or score-formula version differs from the previous
 * night are flagged instead of silently compared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit = {},
    viewModel: TrendsViewModel = hiltViewModel(),
) {
    val trends by viewModel.trends.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.trend_title)) },
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
            if (loaded && trends.isEmpty()) {
                item { Text(stringResource(R.string.trend_empty)) }
            }
            val maxMinutes = trends.maxOfOrNull { it.snoreMinutes ?: 0L } ?: 0L
            items(trends, key = { it.sessionId }) { night ->
                TrendRow(night, maxMinutes, onClick = { onOpenReport(night.sessionId) })
            }
            if (com.sleepanalysis.domain.report.TrendAssembler.anyVersionChange(trends)) {
                item {
                    Text(
                        stringResource(R.string.trend_version_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendRow(night: NightTrend, maxMinutes: Long, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatDateTime(night.startedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    night.snoreScore?.toString() ?: stringResource(R.string.trend_no_score),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                listOfNotNull(
                    night.snoreMinutes?.let { stringResource(R.string.trend_row_snoring, formatDuration(it * 60_000)) },
                    night.snoreSharePercent?.let {
                        String.format(
                            java.util.Locale.US, stringResource(R.string.trend_row_share), it,
                        )
                    },
                    night.longestBoutMs?.let { stringResource(R.string.trend_row_bout, formatDuration(it)) },
                    night.meanPeakDbfs?.let {
                        String.format(java.util.Locale.US, stringResource(R.string.trend_row_peak), it)
                    },
                    night.clipCoveragePercent?.let {
                        String.format(java.util.Locale.US, stringResource(R.string.trend_row_coverage), it)
                    },
                    // Quiet-gap totals: "quieter night" evidence beyond lower snore time.
                    // Rendered only when there IS quiet or uncaptured time; a gap count of
                    // zero gaps / unclassified-only nights show no quiet segment.
                    night.quietMinutes?.takeIf { it > 0 }?.let {
                        stringResource(
                            R.string.trend_row_quiet,
                            formatDuration(it * 60_000),
                            night.quietGapCount ?: 0,
                        )
                    },
                    night.uncapturedMinutes?.takeIf { it > 0 }?.let {
                        stringResource(
                            R.string.trend_row_uncaptured,
                            formatDuration(it * 60_000),
                        )
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Per-night provenance (score formula + detector), shortened for the row.
            listOfNotNull(
                night.detectorVersion?.let { stringResource(R.string.trend_provenance_detector, shortVersion(it)) },
                night.scoreFormulaVersion?.let { stringResource(R.string.trend_provenance_score, shortScoreVersion(it)) },
            ).takeIf { it.isNotEmpty() }?.let { tags ->
                Text(
                    tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Sparkline-style bar: minutes scaled to the week's heaviest night — the shape of
            // the week at a glance without leaving the row.
            if (maxMinutes > 0 && (night.snoreMinutes ?: 0L) > 0) {
                Row(
                    Modifier.fillMaxWidth().height(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .weight((night.snoreMinutes ?: 0L).toFloat())
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    androidx.compose.foundation.layout.Box(
                        Modifier.weight((maxMinutes - (night.snoreMinutes ?: 0L)).toFloat().coerceAtLeast(0.01f)),
                    )
                }
            }
            listOfNotNull(
                if (night.detectorChangedFromPrevious) stringResource(R.string.trend_detector_changed) else null,
                if (night.scoreFormulaChangedFromPrevious) stringResource(R.string.trend_score_changed) else null,
            ).takeIf { it.isNotEmpty() }?.let { markers ->
                Text(
                    markers.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** "energy-heuristic-v2-burst" → "v2-burst"; unknown shapes render verbatim. */
private fun shortVersion(version: String): String =
    version.removePrefix("energy-heuristic-").ifEmpty { version }

/** "snore-score-v2" → "v2" (the template adds "score "); legacy rows carry the detector
 *  string, so strip that prefix too; unknown shapes render verbatim. */
private fun shortScoreVersion(version: String): String = when {
    version.startsWith("snore-score-") -> version.removePrefix("snore-score-")
    version.startsWith("energy-heuristic-") -> version.removePrefix("energy-heuristic-")
    else -> version
}
