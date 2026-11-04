package com.sleepanalysis.ui.report

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.data.garmin.GarminImportManager
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.domain.labels.EventUserLabel
import com.sleepanalysis.domain.report.ClipRef
import com.sleepanalysis.domain.report.MorningReport
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ReportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val sessionRepository: SessionRepository,
    private val clipStore: ClipStore,
    internal val garminImportManager: GarminImportManager,
) : ViewModel() {

    val sessionId: Long = savedStateHandle["sessionId"] ?: -1L

    private val _report = MutableStateFlow<MorningReport?>(null)
    val report: StateFlow<MorningReport?> = _report.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            _report.value = sessionRepository.buildReport(sessionId)
        }
    }

    val garminImportState: StateFlow<GarminImportManager.GarminImportState> =
        garminImportManager.state

    /** Manual import for the calendar day this session started in. */
    fun importGarminForThisNight() {
        val r = report.value ?: return
        val date = Instant.ofEpochMilli(r.startedAt)
            .atZone(ZoneId.of(r.timeZone)).toLocalDate()
        viewModelScope.launch {
            garminImportManager.importForDate(date)
            reload() // pick up re-imported Garmin metrics
        }
    }

    /**
     * Applies or clears a user label for an event (tap the same chip again to clear).
     * Dataset input for Phase 2 model improvement — never alters Phase 1 scores.
     */
    fun labelEvent(eventId: Long, label: EventUserLabel?) {
        viewModelScope.launch {
            sessionRepository.labelEvent(eventId, label?.name)
            reload()
        }
    }

    /** Morning notes: typed or dictated after the night; stored on the session row. */
    fun saveMorningNotes(text: String) {
        viewModelScope.launch {
            sessionRepository.setMorningNotes(sessionId, text)
            reload()
        }
    }

    private val _playingEventId = MutableStateFlow<Long?>(null)
    val playingEventId: StateFlow<Long?> = _playingEventId.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    fun clipFile(clip: ClipRef): File = clipStore.absolutePath(clip.filePath)

    fun playClip(clip: ClipRef) {
        stopPlayback()
        val file = clipFile(clip)
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
            _playingEventId.value = clip.eventId
        }.onFailure {
            _playingEventId.value = null
            releasePlayer()
        }
    }

    fun stopPlayback() {
        runCatching {
            mediaPlayer?.stop()
        }
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
