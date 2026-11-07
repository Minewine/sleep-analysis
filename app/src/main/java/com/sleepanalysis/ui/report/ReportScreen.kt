package com.sleepanalysis.ui.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepanalysis.R
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.ui.components.Formatting
import com.sleepanalysis.ui.components.formatDateTime
import com.sleepanalysis.ui.components.formatDuration

/**
 * Morning report (docs/MVP_SCOPE.md F6): the central UX. Every value shown is an
 * algorithmic estimate with provenance in the database; the disclaimer card is mandatory
 * (docs/PRIVACY_AND_REGULATORY.md §5.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    onOpenSessionLog: () -> Unit = {},
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val playingEventId by viewModel.playingEventId.collectAsStateWithLifecycle()
    val garminImportState by viewModel.garminImportState.collectAsStateWithLifecycle()
    val clipQuality by viewModel.clipQuality.collectAsStateWithLifecycle()
    var selectedEventId by remember { mutableStateOf<Long?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.report_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                report?.let { r ->
                    IconButton(onClick = {
                        viewModel.buildShareText()?.let { text ->
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, null))
                        }
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.action_share),
                        )
                    }
                }
            },
        )
    }) { padding ->
        val r = report
        if (r == null) {
            Column(Modifier.padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.home_no_sessions))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(formatDateTime(r.startedAt), style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatDuration(r.timeInBedSeconds * 1000) + " · " +
                            r.timeZone.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { TldrCard(report = r) }
            item { QualityBannersCard(report = r, clipQuality = clipQuality) }
            item { DisclaimerCard() }
            item {
                SessionHealthCard(
                    timeInBedSeconds = r.timeInBedSeconds,
                    capture = r.capture,
                    warnings = r.healthWarnings,
                    detectorVersion = r.modelVersion,
                    shareUsesCapturedTime = r.shareUsesCapturedTime,
                    captureWindowStartMs = r.captureWindowStartMs,
                    captureWindowEndMs = r.endedAt,
                    startedAt = r.startedAt,
                    onViewLog = onOpenSessionLog,
                )
            }
            item { HeadlineGrid(r = r) }
            if (r.topClips.isNotEmpty()) {
                item {
                    TopClipsCard(
                        clips = r.topClips,
                        startedAt = r.startedAt,
                        worstBoutClip = r.worstBoutClip,
                        playingEventId = playingEventId,
                        onPlay = viewModel::playClip,
                        onStop = viewModel::stopPlayback,
                        onSelect = { selectedEventId = it },
                        labelFor = { id -> r.events.firstOrNull { it.id == id }?.userLabel },
                        onLabel = { id, label ->
                            val current = r.events.firstOrNull { it.id == id }?.userLabel
                            viewModel.labelEvent(id, if (current == label.name) null else label)
                        },
                    )
                }
            }
            item {
                MorningNotesCard(
                    saved = r.morningNotes,
                    onSave = viewModel::saveMorningNotes,
                )
            }

            item {
                Text(stringResource(R.string.report_intensity_breakdown), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "QUIET", "LIGHT", "LOUD", "EPIC",
                    ).forEach { tier ->
                        AssistChip(
                            onClick = {},
                            label = { Text("$tier: ${r.intensityBreakdown[tier] ?: 0}") },
                        )
                    }
                }
            }

            item { NightStructureCard(report = r) }

            item {
                Column {
                    Text(stringResource(R.string.report_loudest_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.report_loudest_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (r.loudestEpisodes.isEmpty()) {
                        Text(
                            stringResource(R.string.report_no_events),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        r.loudestEpisodes.forEach { ep ->
                            LoudEpisodeRow(
                                episode = ep,
                                playing = ep.clip?.eventId != null && ep.clip.eventId == playingEventId,
                                onPlay = { ep.clip?.let(viewModel::playClip) },
                                onStop = viewModel::stopPlayback,
                                onSelect = { selectedEventId = ep.events.first().id },
                            )
                        }
                    }
                }
            }

            item {
                Text(stringResource(R.string.report_timeline), style = MaterialTheme.typography.titleSmall)
                NightTimeline(
                    events = r.events,
                    durationMs = r.endedAt - r.startedAt,
                    selectedEventId = selectedEventId,
                    onSelect = { selectedEventId = it.id },
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.report_hourly), style = MaterialTheme.typography.titleSmall)
                HourlyBars(hourlySnoreSeconds = r.hourly.map { it.snoreSeconds })
            }

            r.integrityNotes.forEach { note ->
                item {
                    Text(
                        note,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                val selected = r.events.firstOrNull { it.id == selectedEventId }
                SelectedEventCard(
                    selected = selected,
                    playing = selected != null && selected.id == playingEventId,
                    hasClip = selected != null && r.clips.containsKey(selected.id),
                    onPlay = { sel ->
                        r.clips[sel.id]?.let { viewModel.playClip(it) }
                    },
                    onStop = viewModel::stopPlayback,
                    onLabel = { label ->
                        selected?.let { sel ->
                            viewModel.labelEvent(sel.id, if (sel.userLabel == label.name) null else label)
                        }
                    },
                )
            }

            item {
                Text(stringResource(R.string.report_events_title), style = MaterialTheme.typography.titleSmall)
                if (r.events.isEmpty()) {
                    Text(stringResource(R.string.report_no_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        stringResource(R.string.report_tap_event),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val events = r.events.takeLast(200).reversed()
            items(events.size) { index ->
                val event = events[index]
                EventRow(
                    event = event,
                    selected = event.id == selectedEventId,
                    onClick = { selectedEventId = event.id },
                )
            }

            item {
                Text(stringResource(R.string.report_baseline_section), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.report_baseline_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (r.baseline.isEmpty()) {
                item { Text(stringResource(R.string.baseline_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(r.baseline.size) { index ->
                    BaselineSummaryRow(r.baseline[index])
                }
            }

            item {
                GarminCard(
                    metrics = r.garmin,
                    snoreSeconds = r.snoreSeconds,
                    sleep = r.garminSleep,
                    longestBoutStage = r.longestBoutStage,
                    snoreMinutesByStage = r.snoreMinutesByStage,
                    garminStageMinutes = r.garminStageMinutes,
                    garminSleepScore = r.garminSleepScore,
                    captureWindowStartMs = r.captureWindowStartMs ?: r.startedAt,
                    captureWindowEndMs = r.endedAt,
                    importState = garminImportState,
                    onImport = viewModel::importGarminForThisNight,
                )
            }

            if (r.dailyFactors.isNotEmpty()) {
                item { DailyFactorsCard(factors = r.dailyFactors) }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/**
 * The 10-second night: how much snoring, how bad at its worst, and how it compares to the
 * previous night. The comparison is version-gated — a detector change hides the time delta,
 * a score-formula change hides the score delta (docs/NIGHT_REPORT.md §TLDR).
 */
@Composable
private fun TldrCard(report: com.sleepanalysis.domain.report.MorningReport) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.report_tldr_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val captureStart = report.captureWindowStartMs ?: report.startedAt
            val captured = (report.endedAt - captureStart).coerceAtLeast(0)
            val shareText = String.format(java.util.Locale.US, "%.0f%%", report.snoreSharePercent)
            Text(
                stringResource(
                    R.string.report_tldr_snored,
                    formatDuration(report.snoreSeconds * 1000),
                    shareText,
                    formatDuration(captured),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            report.boutStats?.let {
                Text(
                    stringResource(
                        R.string.report_tldr_worst,
                        formatDuration(it.longestBoutMs),
                        Formatting.time(it.longestBoutStartMs),
                        Formatting.time(it.longestBoutEndMs),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (report.quietGaps.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.report_tldr_quiet,
                        report.quietGaps.size,
                        formatDuration(report.quietGaps.sumOf { it.durationMs }),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            report.previousNight?.let { prev ->
                if (prev.sameDetectorVersion && prev.snoreSeconds != null) {
                    val delta = report.snoreSeconds - prev.snoreSeconds
                    val sign = if (delta > 0) "+" else if (delta < 0) "−" else ""
                    val deltaText = sign + formatDuration(kotlin.math.abs(delta) * 1000)
                    val scoreLine = if (prev.sameScoreFormula && prev.snoreScore != null) {
                        "${report.snoreScore} vs ${prev.snoreScore}"
                    } else {
                        stringResource(R.string.report_tldr_prev_score_nc, "${report.snoreScore}")
                    }
                    Text(
                        stringResource(
                            R.string.report_tldr_prev,
                            formatDuration(prev.snoreSeconds * 1000),
                            deltaText,
                            scoreLine,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.report_tldr_prev_hidden),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Non-blocking data-quality banners (docs/NIGHT_REPORT.md): clipped/missing/unreadable clips,
 * audio coverage, Garmin linkage, and version changes vs the previous night. Everything here
 * is evidence about the recording — none of it gates or rewrites the numbers above.
 */
@Composable
private fun QualityBannersCard(
    report: com.sleepanalysis.domain.report.MorningReport,
    clipQuality: com.sleepanalysis.ui.report.ReportViewModel.ClipQualityReport?,
) {
    val warnings = buildList {
        clipQuality?.let { q ->
            if (q.clippedFilePaths.isNotEmpty()) {
                add(stringResource(R.string.report_quality_clipped, q.clippedFilePaths.size, q.checked))
            }
            if (q.missingFiles > 0) add(stringResource(R.string.report_quality_missing, q.missingFiles))
            if (q.unreadableFiles > 0) add(stringResource(R.string.report_quality_unreadable, q.unreadableFiles))
        }
        report.clipCoverage?.takeIf { it.snoreEventCount > 0 }?.let { c ->
            add(
                stringResource(
                    R.string.report_quality_coverage,
                    formatDuration(c.coveredSeconds * 1000),
                    formatDuration(c.totalSeconds * 1000),
                    c.snoreEventsWithClip,
                    c.snoreEventCount,
                ),
            )
        }
        when (report.garminStatus) {
            com.sleepanalysis.domain.report.GarminLinkStatus.MISSING ->
                add(stringResource(R.string.report_quality_garmin_missing))
            com.sleepanalysis.domain.report.GarminLinkStatus.METRICS_ONLY ->
                add(stringResource(R.string.report_quality_garmin_metrics_only))
            com.sleepanalysis.domain.report.GarminLinkStatus.LINKED -> {}
        }
        report.previousNight?.let { prev ->
            if (!prev.sameDetectorVersion) {
                add(stringResource(R.string.report_quality_version_detector))
            } else if (!prev.sameScoreFormula) {
                add(stringResource(R.string.report_quality_version_score))
            }
        }
    }
    if (warnings.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.report_quality_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            warnings.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The "hear the worst moments" section: top retained clips, highest peak first. */
@Composable
private fun TopClipsCard(
    clips: List<com.sleepanalysis.domain.report.ClipRef>,
    startedAt: Long,
    worstBoutClip: com.sleepanalysis.domain.report.ClipRef?,
    playingEventId: Long?,
    onPlay: (com.sleepanalysis.domain.report.ClipRef) -> Unit,
    onStop: () -> Unit,
    onSelect: (Long) -> Unit,
    labelFor: (Long) -> String?,
    onLabel: (Long, com.sleepanalysis.domain.labels.EventUserLabel) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.report_top_clips_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.report_top_clips_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.report_label_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            worstBoutClip?.let { clip ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(clip.eventId) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.report_top_clips_worst_bout) + " · " +
                                Formatting.dbfs(clip.peakDbfs) + " · " + formatDuration(clip.durationMs),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            Formatting.time(startedAt + clip.offsetMs) + " · " + eventTypeLabel(clip.eventType),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (clip.eventId == playingEventId) {
                        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.report_stop_playback)) }
                    } else {
                        Button(onClick = { onPlay(clip) }) { Text(stringResource(R.string.report_play)) }
                    }
                }
                ClipLabelChips(
                    eventId = clip.eventId,
                    current = labelFor(clip.eventId),
                    onLabel = onLabel,
                )
            }
            clips.filter { it.eventId != worstBoutClip?.eventId }.forEach { clip ->
                val playing = clip.eventId == playingEventId
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(clip.eventId) },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Formatting.time(startedAt + clip.offsetMs) + " · " +
                                Formatting.dbfs(clip.peakDbfs) + " · " + formatDuration(clip.durationMs),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            eventTypeLabel(clip.eventType) + " · " + clip.intensityTier,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (playing) {
                        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.report_stop_playback)) }
                    } else {
                        Button(onClick = { onPlay(clip) }) { Text(stringResource(R.string.report_play)) }
                    }
                }
                ClipLabelChips(
                    eventId = clip.eventId,
                    current = labelFor(clip.eventId),
                    onLabel = onLabel,
                )
            }
        }
    }
}

/** Label chips under a clip row — same dataset contract as SelectedEventCard: user ground truth, never a score. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ClipLabelChips(
    eventId: Long,
    current: String?,
    onLabel: (Long, com.sleepanalysis.domain.labels.EventUserLabel) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.sleepanalysis.domain.labels.EventUserLabel.entries.forEach { label ->
            FilterChip(
                selected = current == label.name,
                onClick = { onLabel(eventId, label) },
                label = { Text(label.label) },
            )
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            stringResource(R.string.report_disclaimer),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Observability for the night (docs/NEXT_STEPS.md P0): why a night looks the way it does. */

/** Morning notes: short user context typed (or dictated via the keyboard) after the night. */
@Composable
private fun MorningNotesCard(
    saved: String?,
    onSave: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(saved.isNullOrBlank()) }
    var draft by remember(saved) { mutableStateOf(saved.orEmpty()) }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.report_notes_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(R.string.report_notes_placeholder)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        onSave(draft)
                        editing = false
                    }) { Text(stringResource(R.string.report_notes_save)) }
                    OutlinedButton(onClick = {
                        draft = saved.orEmpty()
                        editing = false
                    }) { Text(stringResource(R.string.report_notes_cancel)) }
                }
            } else {
                Text(saved!!, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { editing = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.report_notes_edit))
                }
            }
        }
    }
}@Composable
private fun SessionHealthCard(
    timeInBedSeconds: Long,
    capture: com.sleepanalysis.domain.report.CaptureStats?,
    warnings: List<String>,
    detectorVersion: String?,
    shareUsesCapturedTime: Boolean,
    captureWindowStartMs: Long?,
    captureWindowEndMs: Long,
    startedAt: Long,
    onViewLog: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.report_health_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.report_health_recording), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(timeInBedSeconds * 1000), style = MaterialTheme.typography.bodyMedium)
            }
            if (captureWindowStartMs != null && captureWindowEndMs > captureWindowStartMs) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.report_health_capture_window), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        Formatting.time(captureWindowStartMs) + " – " + Formatting.time(captureWindowEndMs) +
                            " (" + formatDuration(captureWindowEndMs - captureWindowStartMs) + ")",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val delayMinutes = (captureWindowStartMs - startedAt) / 60_000
                if (delayMinutes > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.report_health_start_delay), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$delayMinutes min", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.report_health_share_denominator), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(
                        if (shareUsesCapturedTime) R.string.report_health_share_captured
                        else R.string.report_health_share_inbed,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val ambientMedian = capture?.ambientDbfsMedian
            if (ambientMedian != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.report_health_ambient), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        String.format("%.1f / %.1f dBFS", ambientMedian, capture?.ambientDbfsP95 ?: ambientMedian),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.report_health_detector), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detectorVersion ?: "—", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.report_health_windows), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    capture?.windowsProcessed?.let { p ->
                        val skipped = capture.windowsSkipped ?: 0
                        if (skipped > 0) "$p ($skipped skipped)" else "$p"
                    } ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.report_health_clips), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${capture?.clipCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
            }
            warnings.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onViewLog, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.report_health_view_log))
            }
        }
    }
}

/**
 * Burst-vs-bout view of the night plus its quiet stretches (docs/NIGHT_REPORT.md). Every
 * number traces back to stored events: a burst is one stored event (the detector already
 * guarantees no internal silence > 2 s inside an event), a bout chains bursts with gaps ≤ 10 s.
 */
@Composable
private fun NightStructureCard(report: com.sleepanalysis.domain.report.MorningReport) {
    val stats = report.boutStats
    val gaps = report.quietGaps
    if (stats == null && gaps.isEmpty()) return
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stats != null) {
                Text(
                    stringResource(R.string.report_structure_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        R.string.report_structure_bursts,
                        stats.burstCount,
                        formatDuration(stats.medianBurstMs),
                        formatDuration(stats.longestBurstMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.report_structure_bouts,
                        stats.boutCount,
                        formatDuration(stats.longestBoutMs),
                        stats.longestBoutBursts,
                        Formatting.time(stats.longestBoutStartMs),
                        Formatting.time(stats.longestBoutEndMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                report.boutStats5s?.let { tight ->
                    Text(
                        stringResource(
                            R.string.report_structure_bouts_tight,
                            tight.boutCount,
                            formatDuration(tight.longestBoutMs),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (gaps.isNotEmpty()) {
                Text(
                    stringResource(R.string.report_gaps_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                gaps.take(8).forEach { gap ->
                    val classification = when (gap.kind) {
                        com.sleepanalysis.domain.report.QuietGapKind.QUIET ->
                            gap.meanDbfs?.let {
                                stringResource(R.string.report_gap_kind_quiet, Formatting.dbfs(it))
                            } ?: "" // stored QUIET rows always carry evidence; defensive
                        com.sleepanalysis.domain.report.QuietGapKind.UNCAPTURED ->
                            stringResource(R.string.report_gap_kind_uncaptured)
                        else -> ""
                    }
                    Text(
                        stringResource(
                            R.string.report_gap_row,
                            Formatting.time(gap.startMs),
                            Formatting.time(gap.endMs),
                            formatDuration(gap.durationMs),
                        ) + classification,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (gaps.size > 8) {
                    Text("+${gaps.size - 8}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    stringResource(R.string.report_gaps_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeadlineGrid(r: com.sleepanalysis.domain.report.MorningReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HeadlineCell(Modifier.weight(1f), stringResource(R.string.report_time_in_bed), formatDuration(r.timeInBedSeconds * 1000))
        HeadlineCell(
            Modifier.weight(1f),
            stringResource(R.string.report_snore_score),
            "${r.snoreScore}",
            subtitle = r.snoreEventCount.takeIf { it > 0 }?.let {
                stringResource(
                    R.string.report_score_breakdown,
                    String.format(java.util.Locale.US, "%.0f", r.scoreBreakdown.sharePoints),
                    String.format(java.util.Locale.US, "%.0f", r.scoreBreakdown.intensityPoints),
                )
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HeadlineCell(Modifier.weight(1f), stringResource(R.string.report_snore_time), formatDuration(r.snoreSeconds * 1000))
        HeadlineCell(Modifier.weight(1f), stringResource(R.string.report_snore_share), String.format("%.1f%%", r.snoreSharePercent))
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HeadlineCell(Modifier.weight(1f), stringResource(R.string.report_snore_events), "${r.snoreEventCount}")
        HeadlineCell(
            Modifier.weight(1f),
            stringResource(R.string.report_peak_intensity),
            r.peakDbfs?.let { Formatting.dbfs(it) } ?: "—",
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HeadlineCell(
            Modifier.weight(1f),
            stringResource(R.string.report_longest_burst),
            r.longestSnore?.let { formatDuration(it.durationMs) } ?: "—",
        )
        HeadlineCell(
            Modifier.weight(1f),
            stringResource(R.string.report_longest_bout),
            r.boutStats?.let { formatDuration(it.longestBoutMs) } ?: "—",
        )
    }
}

@Composable
private fun HeadlineCell(modifier: Modifier, label: String, value: String, subtitle: String? = null) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One row of the loudest-first section: the episode's peak, its snore count and time range,
 * and a play button when any member event carries a clip. Tapping the row selects the
 * episode's loudest event (details + labeling appear in [SelectedEventCard]).
 */
@Composable
private fun LoudEpisodeRow(
    episode: com.sleepanalysis.domain.report.EventEpisode,
    playing: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.report_episode_summary,
                    episode.snoreCount,
                    Formatting.dbfs(episode.peakDbfs),
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${Formatting.time(episode.startMs)} – ${Formatting.time(episode.endMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (episode.clip != null) {
            if (playing) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.report_stop_playback)) }
            } else {
                Button(onClick = onPlay) { Text(stringResource(R.string.report_play)) }
            }
        } else {
            Text(
                stringResource(R.string.report_no_clip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SelectedEventCard(
    selected: com.sleepanalysis.data.db.entity.AcousticEventEntity?,
    playing: Boolean,
    hasClip: Boolean,
    onPlay: (com.sleepanalysis.data.db.entity.AcousticEventEntity) -> Unit,
    onStop: () -> Unit,
    onLabel: (com.sleepanalysis.domain.labels.EventUserLabel) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected == null) {
                Text(stringResource(R.string.report_tap_event), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${eventTypeLabel(selected.type)} · ${Formatting.time(selected.startMs)} · " +
                        formatDuration(selected.durationMs) + " · " + selected.intensityTier,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Formatting.dbfs(selected.peakDbfs) + " peak · " +
                        "confidence ${String.format("%.2f", selected.confidence)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasClip) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (playing) {
                            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.report_stop_playback)) }
                        } else {
                            Button(onClick = { onPlay(selected) }) { Text(stringResource(R.string.report_play)) }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.report_no_clip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Detector decision inputs (debug visibility — not measurements, no clinical meaning).
                com.sleepanalysis.monitoring.detection.EventFeatures.fromJson(selected.featuresJson)?.let { f ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.report_features_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.report_features_snr, "%.1f".format(f.snrPeakDb), "%.1f".format(f.snrMeanDb)) + "\n" +
                                stringResource(R.string.report_features_ratio, "%.2f".format(f.lowBandRatioPeak), "%.2f".format(f.lowBandRatioMean)) + "\n" +
                                stringResource(R.string.report_features_floor, "%.1f".format(f.noiseFloorDbfsMean)) + "\n" +
                                stringResource(R.string.report_features_enter, f.enterWindows),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    stringResource(R.string.report_label_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.sleepanalysis.domain.labels.EventUserLabel.entries.forEach { label ->
                        FilterChip(
                            selected = selected.userLabel == label.name,
                            onClick = { onLabel(label) },
                            label = { Text(label.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: com.sleepanalysis.data.db.entity.AcousticEventEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    )) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    Formatting.time(event.startMs) + "  ·  " + eventTypeLabel(event.type) +
                        "  ·  " + event.intensityTier,
                    style = MaterialTheme.typography.bodyMedium,
                )
                com.sleepanalysis.domain.labels.EventUserLabel.fromKey(event.userLabel)?.let { labeled ->
                    Text(
                        stringResource(R.string.report_labeled_prefix) + " " + labeled.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                formatDuration(event.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BaselineSummaryRow(baseline: com.sleepanalysis.data.db.entity.ClinicalBaselineEntity) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                baseline.studyDate + " · " + baseline.lab +
                    (baseline.nightLabel?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val metrics = buildList {
                baseline.ahiPerHour?.let { add("AHI $it/h") }
                baseline.totalSleepTimeMinutes?.let { add("TST ${it / 60}h ${it % 60}m") }
                baseline.sleepEfficiencyPercent?.let { add("Eff ${it}%") }
                baseline.arousalIndexPerHour?.let { add("Arousal ${it}/h") }
                baseline.n3Percent?.let { add("N3 ${it}%") }
            }
            Text(
                metrics.joinToString(" · ").ifEmpty { baseline.notes ?: "" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.baseline_clinically_measured) + " (" + baseline.studyType + ")",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun eventTypeLabel(type: String): String = when (type) {
    EventType.SNORE -> "Snoring"
    EventType.COUGH -> "Cough"
    EventType.SPEECH -> "Speech"
    EventType.LOUD_BREATHING -> "Loud breathing"
    EventType.OTHER_NOISE -> "Other noise"
    else -> "Sound"
}

/** Garmin device-reported metrics — always labeled "Garmin estimate", never clinical (M10–M13, M18). */
@Composable
private fun GarminCard(
    metrics: List<com.sleepanalysis.domain.report.GarminMetric>,
    snoreSeconds: Long,
    sleep: com.sleepanalysis.domain.report.GarminSleepSummary?,
    longestBoutStage: com.sleepanalysis.domain.report.BoutStage?,
    snoreMinutesByStage: List<com.sleepanalysis.domain.report.StageSnoreMinutes>,
    garminStageMinutes: Map<String, Long>,
    garminSleepScore: Double?,
    captureWindowStartMs: Long,
    captureWindowEndMs: Long,
    importState: com.sleepanalysis.data.garmin.GarminImportManager.GarminImportState,
    onImport: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.report_garmin_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.report_garmin_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sleep != null) {
                // The vendor/acoustic join made honest: Garmin's own window vs the captured
                // night. A zero overlap means the payload belongs to a different night.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.report_garmin_windows),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Phone " + Formatting.time(captureWindowStartMs) + "–" + Formatting.time(captureWindowEndMs) +
                            " · Garmin " + Formatting.time(sleep.sleepStartMs) + "–" + Formatting.time(sleep.sleepEndMs),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    stringResource(R.string.report_garmin_linked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (sleep.overlapWithCaptureMs > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.report_garmin_overlap),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatDuration(sleep.overlapWithCaptureMs) + " / " +
                                formatDuration(sleep.captureWindowEndMs - sleep.captureWindowStartMs),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.report_garmin_no_overlap),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Headline vendor number + where the worst stretch fell (timeline when stored).
                (sleep.sleepScore ?: garminSleepScore)?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.report_garmin_score),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            String.format(java.util.Locale.US, "%.0f / 100", it),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (garminStageMinutes.isNotEmpty()) {
                    Text(
                        garminStageMinutes.entries.joinToString(" · ") { (stage, minutes) ->
                            "$stage ${formatDuration(minutes * 60_000)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (snoreMinutesByStage.isNotEmpty()) {
                    // Timeline available: snoring minutes per vendor stage.
                    Text(
                        snoreMinutesByStage.joinToString(" · ") {
                            String.format(java.util.Locale.US, "%s %dm", it.stage, it.snoreMinutes)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (garminStageMinutes.isNotEmpty()) {
                    // Totals only (no sleepLevels timeline): the totals-level comparison.
                    Text(
                        stringResource(
                            R.string.report_garmin_snore_vs_stages,
                            formatDuration(snoreSeconds * 1000),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                longestBoutStage?.let { bs ->
                    Text(
                        stringResource(
                            R.string.report_garmin_bout_stage,
                            bs.stage,
                            String.format(java.util.Locale.US, "%.0f", bs.coveredFraction * 100),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (snoreMinutesByStage.isNotEmpty()) {
                    Text(
                        snoreMinutesByStage.joinToString(" · ") {
                            String.format(
                                java.util.Locale.US, "%s %dm", it.stage, it.snoreMinutes,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (metrics.isEmpty()) {
                Text(
                    stringResource(R.string.report_garmin_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                metrics.forEach { metric ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            garminMetricLabel(metric.measurementType),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatGarminValue(metric) + "  (Garmin estimate)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            when (val s = importState) {
                is com.sleepanalysis.data.garmin.GarminImportManager.GarminImportState.Importing ->
                    Text(stringResource(R.string.settings_import_running, s.date), style = MaterialTheme.typography.bodySmall)
                is com.sleepanalysis.data.garmin.GarminImportManager.GarminImportState.Error ->
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                is com.sleepanalysis.data.garmin.GarminImportManager.GarminImportState.Success ->
                    Text(
                        stringResource(R.string.settings_import_done, s.metricsImported, s.endpointsStored),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                else -> {}
            }
            OutlinedButton(onClick = onImport, enabled = importState !is com.sleepanalysis.data.garmin.GarminImportManager.GarminImportState.Importing) {
                Text(stringResource(R.string.report_garmin_import))
            }
        }
    }
}

private fun garminMetricLabel(type: String): String = when (type) {
    "VENDOR_SLEEP_DURATION" -> "Sleep duration"
    "VENDOR_SLEEP_SCORE" -> "Sleep score"
    "VENDOR_HRV_RMSSD" -> "HRV (rMSSD)"
    "VENDOR_BODY_BATTERY" -> "Body Battery"
    "VENDOR_RESTING_HEART_RATE" -> "Resting heart rate"
    "VENDOR_DEEP_SLEEP" -> "Deep sleep"
    "VENDOR_LIGHT_SLEEP" -> "Light sleep"
    "VENDOR_REM_SLEEP" -> "REM sleep"
    "VENDOR_AWAKE_SLEEP" -> "Awake during sleep"
    "VENDOR_AVG_HEART_RATE_SLEEP" -> "Avg heart rate (sleep)"
    "VENDOR_RESPIRATION_AVG_SLEEP" -> "Avg respiration (sleep)"
    "VENDOR_SLEEP_STRESS_AVG" -> "Avg sleep stress"
    "VENDOR_STRESS_DAILY_AVG" -> "Daily stress average"
    "VENDOR_SPO2_AVG_SLEEP" -> "SpO₂ average (sleep)"
    "VENDOR_SPO2_LOWEST_SLEEP" -> "SpO₂ lowest (sleep)"
    "VENDOR_SLEEP_AWAKENINGS" -> "Awakenings"
    "VENDOR_NAP_TIME" -> "Nap time"
    "VENDOR_SLEEP_NEED" -> "Sleep need (Garmin baseline)"
    "VENDOR_SLEEP_FACTOR_DURATION" -> "Sleep score: duration"
    "VENDOR_SLEEP_FACTOR_QUALITY" -> "Sleep score: quality"
    "VENDOR_SLEEP_FACTOR_STRESS" -> "Sleep score: stress"
    "VENDOR_SLEEP_FACTOR_TIMING" -> "Sleep score: timing"
    else -> type.lowercase().replace('_', ' ')
}

private fun formatGarminValue(metric: com.sleepanalysis.domain.report.GarminMetric): String = when (metric.unit) {
    "SECONDS" -> formatDuration(metric.value.toLong() * 1000)
    "BPM" -> "${metric.value.toInt()} bpm"
    "MS" -> "${metric.value.toInt()} ms"
    "PERCENT" -> "${metric.value.toInt()} %"
    "BREATHS_PER_MINUTE" -> "${metric.value.toInt()} brpm"
    "MINUTES" -> "${metric.value.toInt()} min"
    else -> "${metric.value.toInt()}"
}

/** Self-logged lifestyle factors for the session's date — user-entered context, not measurements. */
@Composable
private fun DailyFactorsCard(factors: List<com.sleepanalysis.domain.report.FactorEntry>) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.report_factors_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            factors.forEach { factor ->
                val label = com.sleepanalysis.domain.factors.DailyFactor.fromKey(factor.factorKey)?.label
                    ?: factor.factorKey.lowercase().replace('_', ' ')
                when {
                    factor.factorKey == com.sleepanalysis.domain.factors.DailyFactor.NOTES.key -> {
                        Text("$label: ${factor.valueText}", style = MaterialTheme.typography.bodyMedium)
                    }
                    factor.factorKey == com.sleepanalysis.domain.factors.DailyFactor.ALCOHOL.key -> {
                        val units = factor.valueNum?.takeIf { it > 1.0 }?.toInt()
                        Text(
                            if (units != null) "$label: $units units" else label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    factor.factorKey == com.sleepanalysis.domain.factors.DailyFactor.STRESS.key -> {
                        Text("$label: ${factor.valueNum?.toInt()} / 5", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                stringResource(R.string.report_factors_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
