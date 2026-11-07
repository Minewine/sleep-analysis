package com.sleepanalysis.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.ui.components.formatDateTime
import com.sleepanalysis.ui.components.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    val sessions: StateFlow<List<SleepSessionEntity>> = sessionRepository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteNight(sessionId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            sessionRepository.deleteNight(sessionId)
            onDone()
        }
    }

    fun deleteAll(onDone: () -> Unit) {
        viewModelScope.launch {
            sessionRepository.deleteAllData()
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit,
    onOpenTrends: () -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var deleteNightId by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val deletedMsg = stringResource(R.string.history_deleted_toast)
    val deletedAllMsg = stringResource(R.string.history_delete_all_done)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = onOpenTrends) {
                        Text(stringResource(R.string.history_trends))
                    }
                    TextButton(onClick = { confirmDeleteAll = true }) {
                        Text(stringResource(R.string.history_delete_all))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sessions.isEmpty()) {
                item { Text(stringResource(R.string.history_empty)) }
            }
            items(sessions.size) { index ->
                val session = sessions[index]
                Card(onClick = { onOpenReport(session.id) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDateTime(session.startedAt), fontWeight = FontWeight.SemiBold)
                            Text(
                                (session.endedAt?.let { formatDuration(it - session.startedAt) } ?: stringResource(R.string.history_in_progress)) +
                                    " · " + session.status.lowercase(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { deleteNightId = session.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.history_delete_night))
                        }
                    }
                }
            }
        }
    }

    deleteNightId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteNightId = null },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNight(id) {
                        deleteNightId = null
                        scope.launch { snackbarHostState.showSnackbar(deletedMsg) }
                    }
                }) { Text(stringResource(R.string.history_delete_night)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteNightId = null }) { Text(stringResource(R.string.history_cancel)) }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.history_delete_all_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAll {
                        confirmDeleteAll = false
                        scope.launch { snackbarHostState.showSnackbar(deletedAllMsg) }
                    }
                }) { Text(stringResource(R.string.history_delete_all)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.history_cancel)) }
            },
        )
    }
}
