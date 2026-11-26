package com.sleepanalysis.ui.report

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.data.db.entity.UserFactorEntity
import com.sleepanalysis.data.files.ClipStore
import com.sleepanalysis.data.garmin.GarminImportManager
import com.sleepanalysis.data.healthconnect.HealthConnectImportManager
import com.sleepanalysis.data.repo.FactorLogRepository
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.domain.factors.DailyFactor
import com.sleepanalysis.domain.labels.EventUserLabel
import com.sleepanalysis.domain.report.ClipRef
import com.sleepanalysis.domain.report.FactorEntry
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

/**
 * Pure gate for the morning report's Garmin step: it may import only when the manager allows
 * it at all (key stored, rate limit), nothing is already in flight, and the date's outcome
 * budget still has room — the exact gates the silent [maybeBackfillGarmin] runs.
 */
internal fun refreshGarminGateOpen(
    canImport: Boolean,
    importing: Boolean,
    budgetAllowed: Boolean,
): Boolean = canImport && !importing && budgetAllowed

/**
 * Pure diagnosis for the refresh's Garmin step: it was skipped BECAUSE the wake date's
 * attempt budget is exhausted (a recent failed attempt is still cooling down) — the case
 * the status line must name instead of the plain "not available". The Garmin card's
 * ungated Import button can still retry immediately, so the user is told that path
 * exists rather than left waiting out a cooldown they were never told about.
 */
internal fun garminStepBudgetBlocked(
    skipsImport: Boolean,
    canImport: Boolean,
    importing: Boolean,
    budgetAllowed: Boolean,
): Boolean = !skipsImport && canImport && !importing && !budgetAllowed

/**
 * Pure gate for the morning report's Health Connect step: the same three checks the silent
 * [maybeBackfillHealthConnect] runs — provider available, no import already in flight (the
 * singleton state flow is shared with the settings screen), and stage rows still missing
 * ([needsImport] makes every redundant call a cheap no-op).
 */
internal fun refreshHealthConnectGateOpen(
    available: Boolean,
    importing: Boolean,
    needsImport: Boolean,
): Boolean = available && !importing && needsImport

/**
 * Pure outcome check for the "stage timeline not available yet" line: neither Health Connect
 * nor Garmin produced stage rows for this night even after the refresh — the timeline is
 * expected around wake time and may land later, so the honest line is "try again later",
 * not a failure. Non-empty vendor stage totals alone satisfy the check (they can
 * survive while the timeline aged out).
 */
internal fun stagesStillMissing(hasStageTimeline: Boolean, garminStageMinutes: Map<String, Long>): Boolean =
    !hasStageTimeline && garminStageMinutes.isEmpty()

/** Phases of the manual refresh flow, shown verbatim under the button (no model change). */
enum class RefreshPhase { IDLE, SYNCING_GARMIN, SYNCING_HEALTHCONNECT, UPDATING_REPORT }

/** Refresh flow state: current phase plus when a refresh last completed (UI timestamp). */
data class RefreshState(
    val phase: RefreshPhase = RefreshPhase.IDLE,
    val completedAtMs: Long? = null,
    /** The last completed refresh skipped its Garmin step because the wake date's attempt
     *  budget was exhausted — the status line then names the unblock path (the Garmin
     *  card's ungated Import button) instead of the plain "not available". */
    val garminBudgetBlocked: Boolean = false,
)

/** What the refresh row shows under its button (ReportScreen.RefreshReportCard). */
enum class RefreshStatusLine { NONE, SYNCING, UPDATING, NOT_AVAILABLE, RETRY_BLOCKED, STAGES_PENDING, INCLUDED }

/**
 * Pure guard for the morning-notes editor's Cancel (ReportScreen.MorningNotesCard): with no
 * saved note there is nothing to revert to, so Cancel keeps the editor open — leaving edit
 * mode would render the null saved note (the morning-crash of the 2026-11-23 report).
 */
internal fun notesCancelReturnsToEdit(saved: String?): Boolean = saved != null

/**
 * Pure mapping for the refresh row's status line: the running phases win; the outcome shows
 * only after a completed attempt — a first open with MISSING data is the quality banner's
 * job, not the button's ("Garmin data not available yet" must not imply a failed attempt
 * that never happened). LINKED and METRICS_ONLY both count as "Garmin included" (data is
 * on the report either way). A completed refresh whose night still has neither stage
 * timeline nor vendor stage totals shows the distinct "stage timeline not available yet"
 * line — the watch usually writes stages around wake time, so a later retry may succeed.
 */
internal fun refreshStatusLine(
    phase: RefreshPhase,
    garminStatus: GarminLinkStatus,
    completedAtMs: Long?,
    hasStageTimeline: Boolean = true,
    garminStageMinutes: Map<String, Long> = emptyMap(),
    garminBudgetBlocked: Boolean = false,
): RefreshStatusLine = when {
    phase == RefreshPhase.SYNCING_GARMIN -> RefreshStatusLine.SYNCING
    phase == RefreshPhase.SYNCING_HEALTHCONNECT -> RefreshStatusLine.SYNCING
    phase == RefreshPhase.UPDATING_REPORT -> RefreshStatusLine.UPDATING
    completedAtMs == null -> RefreshStatusLine.NONE
    garminStatus == GarminLinkStatus.MISSING && garminBudgetBlocked -> RefreshStatusLine.RETRY_BLOCKED
    garminStatus == GarminLinkStatus.MISSING -> RefreshStatusLine.NOT_AVAILABLE
    stagesStillMissing(hasStageTimeline, garminStageMinutes) -> RefreshStatusLine.STAGES_PENDING
    else -> RefreshStatusLine.INCLUDED
}

/** Quick night tags the report's morning card edits — the DailyFactor subset it toggles. */
internal val MORNING_TAGS: List<DailyFactor> = listOf(
    DailyFactor.ALCOHOL,
    DailyFactor.LATE_MEAL,
    DailyFactor.STRESS,
    DailyFactor.SICK,
    DailyFactor.SLEPT_ON_BACK,
)

/** Chip state for the morning card (rating + quick tags), read from the report's factor rows —
 *  the same rows DailyFactorsCard renders, so the chips and the card can never disagree. */
data class MorningFactorsUiState(
    val rating: Int? = null,
    val tags: Set<DailyFactor> = emptySet(),
)

/** Derives the chips' initial state from the report's factor rows: a tag chip is selected when
 *  its factor row exists (any value — the Daily log may have refined it), the rating chip when
 *  the MORNING_RATING row holds an in-range 1..5 level. */
internal fun morningFactorsUiState(factors: List<FactorEntry>): MorningFactorsUiState = MorningFactorsUiState(
    rating = factors.firstOrNull { it.factorKey == DailyFactor.MORNING_RATING.key }?.valueNum
        ?.toInt()?.takeIf { it in 1..5 },
    tags = MORNING_TAGS.filter { tag -> factors.any { it.factorKey == tag.key } }.toSet(),
)

/** Value written for a selected quick tag. Booleans follow the Daily log's 1.0 convention;
 *  STRESS is a 1–5 scale and the quick tag means "stressful day", so it pins the worst level
 *  (5) — the only value that can never read the flag back as "calm". Refine the exact level
 *  in the Daily log. */
internal fun morningTagValue(tag: DailyFactor): Double = if (tag == DailyFactor.STRESS) 5.0 else 1.0

/** The per-key writes the morning card's Save performs (pure): the rating row — or its removal
 *  when null — then one row (or removal) per quick tag, in [MORNING_TAGS] order. Only these
 *  keys are touched; whatever else the day holds (NOTES, caffeine, …) stays as the Daily log
 *  saved it. */
internal fun morningFactorWrites(
    day: String,
    rating: Int?,
    tags: Set<DailyFactor>,
): List<Pair<String, UserFactorEntity?>> = buildList {
    add(
        DailyFactor.MORNING_RATING.key to rating?.let {
            UserFactorEntity(date = day, factorKey = DailyFactor.MORNING_RATING.key, valueNum = it.toDouble())
        },
    )
    MORNING_TAGS.forEach { tag ->
        add(
            tag.key to if (tag in tags) {
                UserFactorEntity(date = day, factorKey = tag.key, valueNum = morningTagValue(tag))
            } else {
                null
            },
        )
    }
}

private const val TAG = "ReportViewModel"

@HiltViewModel
class ReportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val sessionRepository: SessionRepository,
    private val factorLogRepository: FactorLogRepository,
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
        if (!garminImportManager.canImport()) {
            Log.d(TAG, "backfill: skipped — no API key stored")
            return
        }
        // An import already in flight must not double-fire: the outcome-based mark below only
        // claims the budget AFTER the network round-trip (it needs the outcome to pick the kind).
        if (garminImportManager.state.value is GarminImportManager.GarminImportState.Importing) {
            Log.d(TAG, "backfill: skipped — an import is already in flight")
            return
        }
        val date = com.sleepanalysis.domain.report.NightDates
            .garminWakeDate(report.startedAt, report.endedAt, report.timeZone)
        if (!userPreferences.garminBackfillAllowed(date.toString(), System.currentTimeMillis())) {
            Log.d(TAG, "backfill: skipped — attempt budget exhausted for wake date $date")
            return
        }
        Log.i(TAG, "backfill: importing Garmin wake date $date")
        garminImportManager.importForDate(date, ZoneId.of(report.timeZone))
        markGarminAttemptBudget(date.toString())
        Log.i(TAG, "backfill: outcome ${garminImportManager.state.value::class.simpleName}")
        _report.value = sessionRepository.buildReport(sessionId)
    }

    /**
     * Marks a finished attempt on the budget its outcome drew from (which counter the next
     * backfill gate consults): a not-ready result must not consume the general 2/day cap, so
     * it marks the separate not-ready counter instead (UserPreferences.GarminNotReadyBounds).
     */
    private suspend fun markGarminAttemptBudget(date: String) {
        if (drawsNotReadyBudget(garminImportManager.state.value)) {
            Log.d(TAG, "budget: not-ready attempt marked for $date")
            userPreferences.markGarminNotReadyRetry(date)
        } else {
            Log.d(TAG, "budget: general attempt marked for $date")
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
        Log.i(TAG, "manual garmin import: wake date $date (session zone ${r.timeZone})")
        garminImportManager.importForDate(date, ZoneId.of(r.timeZone))
        // A manual import also consumes the date's budget — of the kind its outcome drew
        // from — so the backfill must not fire a second network import right behind it.
        markGarminAttemptBudget(date.toString())
    }

    /**
     * "Generate morning report" (top of the report): pull this night's wearable data and
     * rebuild the Morning Summary in place. Garmin step first — an already-LINKED night
     * skips the network import ([refreshSkipsImport]); otherwise the Garmin card's exact
     * import → budget path runs under the same gates as the silent backfill
     * ([refreshGarminGateOpen]: [GarminImportManager.canImport], nothing in flight, the
     * date's outcome budget). Then the Health Connect step under [maybeBackfillHealthConnect]'s
     * exact gates ([refreshHealthConnectGateOpen]: provider available, nothing in flight,
     * [HealthConnectImportManager.needsImport]). Finally the rebuild — [reloadSuspend], whose
     * trailing silent backfills stay unchanged. No duplicate imports: a re-import is
     * replace-not-duplicate, and the marked budget keeps the silent backfill from firing
     * right behind it. Blocks while this manager is already importing (the singleton flow is
     * shared with the settings screen and the auto-backfill). A gated step is skipped
     * silently — the rebuild still runs on what is stored, and the status line reports the
     * honest outcome.
     */
    fun refreshMorningReport() {
        val r = report.value ?: return
        if (_refreshState.value.phase != RefreshPhase.IDLE) return
        if (garminImportManager.state.value is GarminImportManager.GarminImportState.Importing) return
        viewModelScope.launch {
            var garminBudgetBlocked = false
            try {
                if (!refreshSkipsImport(r.garminStatus)) {
                    val date = com.sleepanalysis.domain.report.NightDates
                        .garminWakeDate(r.startedAt, r.endedAt, r.timeZone)
                    val canImport = garminImportManager.canImport()
                    val importing =
                        garminImportManager.state.value is GarminImportManager.GarminImportState.Importing
                    val budgetAllowed = userPreferences.garminBackfillAllowed(
                        date.toString(),
                        System.currentTimeMillis(),
                    )
                    val gateOpen = refreshGarminGateOpen(canImport, importing, budgetAllowed)
                    garminBudgetBlocked = garminStepBudgetBlocked(
                        skipsImport = refreshSkipsImport(r.garminStatus),
                        canImport = canImport,
                        importing = importing,
                        budgetAllowed = budgetAllowed,
                    )
                    Log.d(
                        TAG,
                        "refresh: garmin gateOpen=$gateOpen budgetBlocked=$garminBudgetBlocked " +
                            "canImport=$canImport budgetAllowed=$budgetAllowed wakeDate=$date",
                    )
                    if (gateOpen) {
                        _refreshState.value =
                            RefreshState(RefreshPhase.SYNCING_GARMIN, _refreshState.value.completedAtMs)
                        garminImportManager.importForDate(date, ZoneId.of(r.timeZone))
                        markGarminAttemptBudget(date.toString())
                        _report.value = sessionRepository.buildReport(sessionId)
                    }
                }
                // The Health Connect step gates on the freshly imported report (a Garmin
                // import may have changed the wake date's window — same rebuild the silent
                // backfill does between its steps).
                val hc = _report.value ?: r
                val hcGateOpen = refreshHealthConnectGateOpen(
                    available = healthConnectImportManager.isAvailable(),
                    importing = healthConnectImportManager.state.value
                        is HealthConnectImportManager.HealthConnectImportState.Importing,
                    needsImport = healthConnectImportManager.needsImport(sessionId),
                )
                if (hcGateOpen) {
                    _refreshState.value =
                        RefreshState(RefreshPhase.SYNCING_HEALTHCONNECT, _refreshState.value.completedAtMs)
                    val date = com.sleepanalysis.domain.report.NightDates
                        .garminWakeDate(hc.startedAt, hc.endedAt, hc.timeZone)
                    healthConnectImportManager.importForDate(date, ZoneId.of(hc.timeZone))
                    _report.value = sessionRepository.buildReport(sessionId)
                }
                _refreshState.value = RefreshState(RefreshPhase.UPDATING_REPORT, _refreshState.value.completedAtMs)
                reloadSuspend()
                _refreshState.value = RefreshState(RefreshPhase.IDLE, System.currentTimeMillis(), garminBudgetBlocked)
            } finally {
                // A failed attempt (DataStore/Room hiccup) must still re-enable the button —
                // a stuck non-IDLE phase freezes it and its status line for the screen's life.
                if (_refreshState.value.phase != RefreshPhase.IDLE) {
                    _refreshState.value =
                        RefreshState(RefreshPhase.IDLE, _refreshState.value.completedAtMs, garminBudgetBlocked)
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

    /**
     * Saves the morning card's rating + quick tags for this night's FACTOR DAY — the session's
     * START date ([NightDates.factorDay], the same key SessionRepository.factorsForSessionDay
     * and DailyFactorsCard read), so the card and the Daily log always show what was saved
     * here. One merge upsert/removal per key ([morningFactorWrites]) — never a whole-day
     * replace, which would clobber factors logged earlier for that day. reload() only: user
     * factors never feed scores, so there is no recompute cascade.
     */
    fun saveMorningFactors(rating: Int?, tags: Set<DailyFactor>) {
        val r = report.value ?: return
        viewModelScope.launch {
            val day = com.sleepanalysis.domain.report.NightDates.factorDay(r.startedAt, r.timeZone)
            morningFactorWrites(day.toString(), rating, tags).forEach { (key, entity) ->
                if (entity == null) {
                    factorLogRepository.removeFactorForDate(day, key)
                } else {
                    factorLogRepository.saveFactorForDate(day, entity)
                }
            }
            reload()
        }
    }

    private val _playingEventId = MutableStateFlow<Long?>(null)
    val playingEventId: StateFlow<Long?> = _playingEventId.asStateFlow()

    // One shared clip player (plan T4e) — stop-then-play, release on completion/failure/clear.
    private val clipPlayer = com.sleepanalysis.ui.components.ClipPlayer { _playingEventId.value = it }

    fun clipFile(clip: ClipRef): File = clipStore.absolutePath(clip.filePath)

    fun playClip(clip: ClipRef) = clipPlayer.play(clip.eventId, clipFile(clip))

    fun stopPlayback() = clipPlayer.stopPlayback()

    override fun onCleared() {
        clipPlayer.release()
        super.onCleared()
    }
}
