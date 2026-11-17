package com.sleepanalysis.ui.report

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.data.garmin.GarminImportManager
import com.sleepanalysis.data.healthconnect.HealthConnectImportManager
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

/**
 * Pure guard for the backfill's retry accounting (ReportViewModel.maybeBackfillGarmin): the
 * attempt must mark the counter its outcome drew from — a not-ready result (Garmin hasn't
 * processed the night, dailySleep AND hrv both 404) draws the looser not-ready budget so it
 * never exhausts the general 2/day cap; every other outcome (Success, Error) draws the
 * general one. The matching gate is UserPreferences.GarminNotReadyBounds.backfillAllowed.
 */
internal fun drawsNotReadyBudget(state: GarminImportManager.GarminImportState): Boolean =
    state is GarminImportManager.GarminImportState.NotReady

/**
 * Pure guard for the "Refresh with Garmin" button: a night whose Garmin payload is already
 * LINKED needs no second network import — a tap then only rebuilds the report from stored
 * rows. METRICS_ONLY still imports: the day metrics survived but the payload (window, score,
 * stage timeline) aged out, and a re-import is replace-not-duplicate (GarminImportManager).
 */
internal fun refreshSkipsImport(status: GarminLinkStatus): Boolean =
    status == GarminLinkStatus.LINKED

/** Phases of the manual refresh flow, shown verbatim under the button (no model change). */
enum class RefreshPhase { IDLE, SYNCING_GARMIN, UPDATING_REPORT }

/** Refresh flow state: current phase plus when a refresh last completed (UI timestamp). */
data class RefreshState(
    val phase: RefreshPhase = RefreshPhase.IDLE,
    val completedAtMs: Long? = null,
)

/** What the refresh row shows under its button (ReportScreen.RefreshReportCard). */
enum class RefreshStatusLine { NONE, SYNCING, UPDATING, NOT_AVAILABLE, INCLUDED }

/**
 * Pure mapping for the refresh row's status line: the running phases win; the outcome shows
 * only after a completed attempt — a first open with MISSING data is the quality banner's
 * job, not the button's ("Garmin data not available yet" must not imply a failed attempt
 * that never happened). LINKED and METRICS_ONLY both count as "Garmin included" (data is
 * on the report either way).
 */
internal fun refreshStatusLine(
    phase: RefreshPhase,
    garminStatus: GarminLinkStatus,
    completedAtMs: Long?,
): RefreshStatusLine = when {
    phase == RefreshPhase.SYNCING_GARMIN -> RefreshStatusLine.SYNCING
    phase == RefreshPhase.UPDATING_REPORT -> RefreshStatusLine.UPDATING
    completedAtMs == null -> RefreshStatusLine.NONE
    garminStatus == GarminLinkStatus.MISSING -> RefreshStatusLine.NOT_AVAILABLE
    else -> RefreshStatusLine.INCLUDED
}

@HiltViewModel
class ReportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val sessionRepository: SessionRepository,
    private val clipStore: ClipStore,
    private val userPreferences: com.sleepanalysis.data.prefs.UserPreferences,
    internal val garminImportManager: GarminImportManager,
    internal val healthConnectImportManager: HealthConnectImportManager,
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
        viewModelScope.launch { reloadSuspend() }
    }

    private suspend fun reloadSuspend() {
        val loaded = sessionRepository.buildReport(sessionId)
        _report.value = loaded
        scanClipQuality(loaded)
        maybeBackfillGarmin(loaded)
        maybeBackfillHealthConnect(loaded)
    }

    /**
     * Morning backfill (docs/NIGHT_REPORT.md §Garmin): if this night has no Garmin data yet —
     * first sync often runs before Garmin's payload is ready — silently import the wake date,
     * then re-render. Silent no-op when no key is stored; never blocks the report (the import
     * state still shows honestly). The attempt draws from the budget of the date's last
     * outcome kind: a not-ready morning gets the looser not-ready bounds (4/day, 1 h
     * cooldown), any other outcome the general ones (2/day, 6 h) — see
     * UserPreferences.GarminNotReadyBounds.
     */
    private suspend fun maybeBackfillGarmin(report: MorningReport?) {
        if (report == null) return
        if (report.garminStatus == GarminLinkStatus.LINKED) return
        if (!garminImportManager.canImport()) return
        // An import already in flight must not double-fire: the outcome-based mark below only
        // claims the budget AFTER the network round-trip (it needs the outcome to pick the kind).
        if (garminImportManager.state.value is GarminImportManager.GarminImportState.Importing) return
        val date = com.sleepanalysis.domain.report.NightDates
            .garminWakeDate(report.startedAt, report.endedAt, report.timeZone)
        if (!userPreferences.garminBackfillAllowed(date.toString(), System.currentTimeMillis())) return
        garminImportManager.importForDate(date, ZoneId.of(report.timeZone))
        markGarminAttemptBudget(date.toString())
        _report.value = sessionRepository.buildReport(sessionId)
    }

    /**
     * Marks a finished attempt on the budget its outcome drew from (which counter the next
     * backfill gate consults): a not-ready result must not consume the general 2/day cap, so
     * it marks the separate not-ready counter instead (UserPreferences.GarminNotReadyBounds).
     */
    private suspend fun markGarminAttemptBudget(date: String) {
        if (drawsNotReadyBudget(garminImportManager.state.value)) {
            userPreferences.markGarminNotReadyRetry(date)
        } else {
            userPreferences.markGarminAutoRetry(date)
        }
    }

    /**
     * Morning backfill for Health Connect: if this night has no wearable stage rows yet —
     * the watch or phone app usually writes them around wake time, often after this report
     * is first opened — silently import the wake date, then re-render. Deliberately NO
     * attempt budget, unlike [maybeBackfillGarmin]: the import is local IPC against the
     * Health Connect provider, not a paid network round-trip, so re-gating on [needsImport]
     * (rows still missing) already makes every redundant open a cheap no-op — there is no
     * quota to exhaust and nothing to mark.
     */
    private suspend fun maybeBackfillHealthConnect(report: MorningReport?) {
        if (report == null) return
        if (!healthConnectImportManager.isAvailable()) return
        // An import already in flight must not double-fire — the singleton state flow is
        // shared with the settings screen's manual import (same guard as Garmin's backfill).
        if (healthConnectImportManager.state.value
            is HealthConnectImportManager.HealthConnectImportState.Importing
        ) return
        if (!healthConnectImportManager.needsImport(sessionId)) return
        val date = com.sleepanalysis.domain.report.NightDates
            .garminWakeDate(report.startedAt, report.endedAt, report.timeZone)
        healthConnectImportManager.importForDate(date, ZoneId.of(report.timeZone))
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
            // The counts come from the SHARED integrity aggregation (NightValidity) — the same
            // one finalize's validity row uses, so the banner and the badge can never disagree.
            val summary = com.sleepanalysis.domain.validity.NightValidity.clipQualitySummary(checks)
            _clipQuality.value = ClipQualityReport(
                checked = summary.clipCount,
                clippedFilePaths = checks.filter { it.clipped }.map { it.filePath },
                missingFiles = summary.missingCount,
                unreadableFiles = summary.unreadableCount,
            )
        }
    }

    /** Shareable plain-text summary (share intent) — null while the report is loading. */
    fun buildShareText(): String? = report.value?.let { com.sleepanalysis.domain.report.NightSummaryText.build(it) }

    val garminImportState: StateFlow<GarminImportManager.GarminImportState> =
        garminImportManager.state

    private val _refreshState = MutableStateFlow(RefreshState())
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    /** Manual import for this night's WAKE date, in the session's own timezone. */
    fun importGarminForThisNight() {
        viewModelScope.launch {
            importThisNight()
            reload() // pick up re-imported Garmin metrics
        }
    }

    private suspend fun importThisNight() {
        val r = report.value ?: return
        val date = com.sleepanalysis.domain.report.NightDates
            .garminWakeDate(r.startedAt, r.endedAt, r.timeZone)
        // The session's zone, not the device default: timestamps recorded while travelling
        // or across a DST boundary must land in the wake day's local window.
        garminImportManager.importForDate(date, ZoneId.of(r.timeZone))
        // A manual import also consumes the date's budget — of the kind its outcome drew
        // from — so the backfill must not fire a second network import right behind it.
        markGarminAttemptBudget(date.toString())
    }

    /**
     * "Refresh with Garmin" (top of the report): rebuild the Morning Summary with the latest
     * acoustic + Garmin data, in place. An already-LINKED night skips the network import
     * ([refreshSkipsImport]) and only rebuilds; otherwise the Garmin card's exact import →
     * budget → rebuild path runs, wrapped in the two progress phases. No duplicate imports:
     * a re-import is replace-not-duplicate, and the marked budget keeps the silent backfill
     * from firing right behind it. Blocks while this manager is already importing (the
     * singleton flow is shared with the settings screen and the auto-backfill).
     */
    fun refreshWithGarmin() {
        val r = report.value ?: return
        if (_refreshState.value.phase != RefreshPhase.IDLE) return
        if (garminImportManager.state.value is GarminImportManager.GarminImportState.Importing) return
        viewModelScope.launch {
            try {
                if (refreshSkipsImport(r.garminStatus)) {
                    _refreshState.value = RefreshState(RefreshPhase.UPDATING_REPORT, _refreshState.value.completedAtMs)
                } else {
                    _refreshState.value = RefreshState(RefreshPhase.SYNCING_GARMIN, _refreshState.value.completedAtMs)
                    importThisNight()
                    _refreshState.value = RefreshState(RefreshPhase.UPDATING_REPORT, _refreshState.value.completedAtMs)
                }
                reloadSuspend()
                _refreshState.value = RefreshState(RefreshPhase.IDLE, System.currentTimeMillis())
            } finally {
                // A failed attempt (DataStore/Room hiccup) must still re-enable the button —
                // a stuck non-IDLE phase freezes it and its status line for the screen's life.
                if (_refreshState.value.phase != RefreshPhase.IDLE) {
                    _refreshState.value = RefreshState(RefreshPhase.IDLE, _refreshState.value.completedAtMs)
                }
            }
        }
    }

    /**
     * Applies or clears a user label for an event (tap the same chip again to clear). Labels
     * are the Phase 2 training dataset AND re-weigh the night since snore-score-v3: the
     * repository rewrites the session's snore-aggregate rows inside the same transaction
     * (docs/MEASUREMENT_RELIABILITY.md §7.1) — [reload] picks up the corrected values.
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
