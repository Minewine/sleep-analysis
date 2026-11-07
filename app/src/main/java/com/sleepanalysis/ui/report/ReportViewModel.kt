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
import com.sleepanalysis.domain.report.GarminLinkStatus
import com.sleepanalysis.domain.report.MorningReport
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
    private val userPreferences: com.sleepanalysis.data.prefs.UserPreferences,
    internal val garminImportManager: GarminImportManager,
) : ViewModel() {

    val sessionId: Long = savedStateHandle["sessionId"] ?: -1L

    private val _report = MutableStateFlow<MorningReport?>(null)
    val report: StateFlow<MorningReport?> = _report.asStateFlow()

    /** Async clip-audio scan result (null until the first report landed and the scan finished). */
    data class ClipQualityReport(
        val checked: Int,
        val clippedFilePaths: List<String>,
        val missingFiles: Int,
        val unreadableFiles: Int = 0,
    )

    private val _clipQuality = MutableStateFlow<ClipQualityReport?>(null)
    val clipQuality: StateFlow<ClipQualityReport?> = _clipQuality.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val loaded = sessionRepository.buildReport(sessionId)
            _report.value = loaded
            scanClipQuality(loaded)
            maybeBackfillGarmin(loaded)
        }
    }

    /**
     * Morning backfill (docs/NIGHT_REPORT.md §Garmin): if this night has no Garmin data yet —
     * first sync often runs before Garmin's payload is ready — silently import the wake date
     * once per date (one more try after a 6 h cooldown), then re-render. Silent no-op when no
     * key is stored; never blocks the report (the import state still shows honestly).
     */
    private suspend fun maybeBackfillGarmin(report: MorningReport?) {
        if (report == null) return
        if (report.garminStatus == GarminLinkStatus.LINKED) return
        if (!garminImportManager.canImport()) return
        val date = com.sleepanalysis.domain.report.NightDates
            .garminWakeDate(report.startedAt, report.endedAt, report.timeZone)
        if (!userPreferences.garminAutoRetryAllowed(date.toString(), System.currentTimeMillis())) return
        userPreferences.markGarminAutoRetry(date.toString())
        garminImportManager.importForDate(date, ZoneId.of(report.timeZone))
        _report.value = sessionRepository.buildReport(sessionId)
    }

    /**
     * Read-only clip scan for the data-quality banners (clipped at 0 dBFS / missing files).
     * Deliberately off the render path: the report shows first, the banner lands when the
     * scan finishes (it decodes every retained clip's PCM — bounded by the clip budget).
     * Skipped entirely when the clip set is unchanged — labeling/notes reloads must not
     * re-decode the night's audio.
     */
    private var lastScannedClipPaths: Set<String>? = null

    private fun scanClipQuality(report: MorningReport?) {
        if (report == null) {
            _clipQuality.value = null
            return
        }
        val refs = (report.clips.values + report.topClips)
            .distinctBy { it.filePath }
        val paths = refs.map { it.filePath }.toSet()
        if (paths.isEmpty()) {
            _clipQuality.value = ClipQualityReport(0, emptyList(), 0)
            return
        }
        if (paths == lastScannedClipPaths) return
        lastScannedClipPaths = paths
        viewModelScope.launch {
            val checks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                refs.map { ref ->
                    com.sleepanalysis.data.files.ClipIntegrity.check(
                        file = clipStore.absolutePath(ref.filePath),
                        filePath = ref.filePath,
                    )
                }
            }
            _clipQuality.value = ClipQualityReport(
                checked = checks.size,
                clippedFilePaths = checks.filter { it.clipped }.map { it.filePath },
                missingFiles = checks.count { !it.exists },
                unreadableFiles = checks.count { it.exists && !it.readable },
            )
        }
    }

    /** Shareable plain-text summary (share intent) — null while the report is loading. */
    fun buildShareText(): String? = report.value?.let { com.sleepanalysis.domain.report.NightSummaryText.build(it) }

    val garminImportState: StateFlow<GarminImportManager.GarminImportState> =
        garminImportManager.state

    /** Manual import for this night's WAKE date, in the session's own timezone. */
    fun importGarminForThisNight() {
        val r = report.value ?: return
        val date = com.sleepanalysis.domain.report.NightDates
            .garminWakeDate(r.startedAt, r.endedAt, r.timeZone)
        viewModelScope.launch {
            // The session's zone, not the device default: timestamps recorded while travelling
            // or across a DST boundary must land in the wake day's local window.
            garminImportManager.importForDate(date, ZoneId.of(r.timeZone))
            // A manual import also consumes the date's auto attempt: the backfill must not
            // fire a second network import right behind it.
            userPreferences.markGarminAutoRetry(date.toString())
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
