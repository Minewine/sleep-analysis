package com.sleepanalysis.ui.report

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.R
import com.sleepanalysis.ui.components.Formatting
import com.sleepanalysis.data.db.dao.ClipBackedEventRow
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.domain.labels.EventUserLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bulk clip labeling: every clip-backed event of the session in one scrollable list —
 * play, tap the label, done. Built for labeling ALL clips of a night in one sitting
 * (docs/EVAL_RUNBOOK.md step 1), which the report screen's per-episode flow makes tedious.
 */
data class LabelingItem(
    val eventId: Long,
    val startMs: Long,
    val eventType: String,
    val intensityTier: String,
    val clipPath: String,
    val label: EventUserLabel?,
)

data class LabelingUiState(
    val items: List<LabelingItem> = emptyList(),
    val hideLabeled: Boolean = false,
) {
    val labeledCount: Int get() = items.count { it.label != null }
    val totalCount: Int get() = items.size

    /**
     * Unlabeled first (oldest first within each group) — a tap must visibly change the row
     * (chip selects + 'Labeled:' appears) or labeling feels broken. [hideLabeled] optionally
     * drops labeled rows once they no longer need attention.
     */
    fun visible(): List<LabelingItem> {
        val sorted = items.sortedBy { (it.label != null).compareTo(false) }
        return if (hideLabeled) sorted.filter { it.label == null } else sorted
    }

    fun withLabel(eventId: Long, label: EventUserLabel?): LabelingUiState = copy(
        items = items.map {
            if (it.eventId == eventId) it.copy(label = label) else it
        },
    )
}

@HiltViewModel
class LabelingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val sessionRepository: SessionRepository,
    private val clipStore: ClipStore,
) : ViewModel() {

    val sessionId: Long = savedStateHandle["sessionId"] ?: -1L

    private val _state = MutableStateFlow(LabelingUiState())
    val state: StateFlow<LabelingUiState> = _state.asStateFlow()

    private val _playingEventId = MutableStateFlow<Long?>(null)
    val playingEventId: StateFlow<Long?> = _playingEventId.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    init {
        viewModelScope.launch {
            val rows: List<ClipBackedEventRow> =
                sessionRepository.clipBackedEventsForLabeling(sessionId)
            _state.value = LabelingUiState(
                items = rows.map {
                    LabelingItem(
                        eventId = it.event.id,
                        startMs = it.event.startMs,
                        eventType = it.event.type,
                        intensityTier = it.event.intensityTier,
                        clipPath = it.clipPath,
                        label = EventUserLabel.fromKey(it.event.userLabel),
                    )
                },
            )
        }
    }

    fun label(eventId: Long, label: EventUserLabel?) {
        // Optimistic: the chip must stick INSTANTLY. Updating only after the DB write left a
        // window where the chip still looked unselected — a second tap then hit the toggle
        // and cleared the label ("it doesn't stick"). The write trails the state; a failed
        // write reverts so the UI never lies.
        val previous = _state.value
        _state.value = previous.withLabel(eventId, label)
        viewModelScope.launch {
            runCatching { sessionRepository.labelEvent(eventId, label?.name) }
                .onFailure {
                    _state.value = previous.withLabel(eventId, null)
                }
        }
    }

    fun clipFile(item: LabelingItem): File = clipStore.absolutePath(item.clipPath)

    fun play(item: LabelingItem) {
        stopPlayback()
        val file = clipFile(item)
        if (!file.exists()) return
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    _playingEventId.value = null
                    releasePlayer()
                }
                prepare()
                start()
            }
            _playingEventId.value = item.eventId
        }.onFailure {
            _playingEventId.value = null
            releasePlayer()
        }
    }

    fun stopPlayback() {
        runCatching { mediaPlayer?.stop() }
        releasePlayer()
        _playingEventId.value = null
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelingScreen(
    onBack: () -> Unit,
    viewModel: LabelingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val playingEventId by viewModel.playingEventId.collectAsState()
    var hideLabeled by remember { mutableStateOf(false) }
    val visible = state.copy(hideLabeled = hideLabeled).visible()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.labeling_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.labeling_progress,
                        state.labeledCount,
                        state.totalCount,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.labeling_hide_labeled),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(0.dp))
                    Switch(
                        checked = hideLabeled,
                        onCheckedChange = { hideLabeled = it },
                    )
                }
            }
            LinearProgressIndicator(
                progress = {
                    if (state.totalCount == 0) 0f
                    else state.labeledCount.toFloat() / state.totalCount
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    if (state.totalCount == 0) stringResource(R.string.labeling_empty)
                    else stringResource(R.string.labeling_all_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it.eventId }) { item ->
                    LabelingRow(
                        item = item,
                        playing = playingEventId == item.eventId,
                        onPlay = { viewModel.play(item) },
                        onStop = viewModel::stopPlayback,
                        onLabel = { label ->
                            viewModel.label(
                                item.eventId,
                                if (item.label == label) null else label,
                            )
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LabelingRow(
    item: LabelingItem,
    playing: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onLabel: (EventUserLabel) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${Formatting.time(item.startMs)} · ${item.eventType} · ${item.intensityTier}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                item.label?.let {
                    Text(
                        stringResource(R.string.labeling_labeled_as, it.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (playing) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.report_stop_playback)) }
            } else {
                Button(onClick = onPlay) { Text(stringResource(R.string.report_play)) }
            }
        }
        // FlowRow: six chips exceed one screen width — a plain Row pushed the last labels
        // (Other / Not snore, Silence) off-screen and unreachable.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EventUserLabel.entries.forEach { label ->
                FilterChip(
                    selected = item.label == label,
                    onClick = { onLabel(label) },
                    label = { Text(label.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}
