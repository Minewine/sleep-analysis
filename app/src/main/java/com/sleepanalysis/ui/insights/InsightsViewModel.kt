package com.sleepanalysis.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.data.db.entity.ExperimentStatus
import com.sleepanalysis.data.db.entity.SleepExperimentEntity
import com.sleepanalysis.data.prefs.UserPreferences
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.domain.experiments.ExperimentMath
import com.sleepanalysis.domain.experiments.ExperimentTracker
import com.sleepanalysis.domain.experiments.parseConfirmPhase
import com.sleepanalysis.domain.experiments.parseExperimentVerdict
import com.sleepanalysis.domain.insights.ConfidenceTier
import com.sleepanalysis.domain.insights.EscalationRule
import com.sleepanalysis.domain.insights.InsightsReport
import com.sleepanalysis.domain.insights.Playbook
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Loads the insights bundle once (same pull-once pattern as the trends screen) and owns the
 * dismissed-rule persistence (docs/INSIGHTS.md): a dismissed rule id hides the card here and
 * everywhere until re-shown — dismissal is a preference, never a data deletion.
 *
 * Slice 3 additions: the ranked playbook actions, the N-of-1 experiment state (one ACTIVE row —
 * chip/active card/verdict card) and the clinician-escalation card state (acoustic arm over the
 * SAME eligible night set the rules ran on + the persisted self-report). All judgment stays in
 * the domain objects ([Playbook.rank], [EscalationRule.evaluate], [ExperimentTracker]); this
 * ViewModel only gathers and re-loads (every mutation re-runs [load]).
 */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val userPreferences: UserPreferences,
    private val experimentTracker: ExperimentTracker,
) : ViewModel() {

    /** Why a "Try this for 7 nights" tap was refused — the screen renders the honest copy. */
    enum class ExperimentStartError { ACTIVE_EXISTS, BASELINE_COLLECTING, NO_BASELINE_BAND }

    /** The active/verdict experiment as the cards render it. */
    data class ExperimentView(
        val id: Long,
        val actionId: String?,
        val metric: String,
        val phase: ExperimentTracker.Phase,
        val usableNights: Int,
        val plannedNights: Int,
        val excludedNights: Int,
        val missingValues: Int,
        val ready: Boolean,
        val verdict: VerdictView?,
    )

    data class VerdictView(
        val changeDetected: Boolean,
        /** null = no change detected; otherwise UP/DOWN relative to the start baseline. */
        val directionUp: Boolean?,
        val confidence: ConfidenceTier,
        /** A-B-A confirm outcome; null = no confirm phase ran yet. */
        val confirmed: Boolean?,
        /** True once a confirm phase COMPLETED (the confirm button disappears). */
        val confirmRan: Boolean,
        val kept: Boolean,
    )

    data class UiState(
        val report: InsightsReport? = null,
        val dismissedRules: Set<String> = emptySet(),
        /** False until the first load lands — keeps the empty state from flashing on open. */
        val loaded: Boolean = false,
        val rankedActions: List<Playbook.PlaybookAction> = emptyList(),
        val activeExperiment: ExperimentView? = null,
        /** The newest DONE experiment with an unkept verdict — the verdict card. */
        val verdictExperiment: ExperimentView? = null,
        val escalation: EscalationRule.Result? = null,
        val selfReport: UserPreferences.EscalationSelfReport? = null,
        val startError: ExperimentStartError? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun dismiss(ruleId: String) {
        viewModelScope.launch {
            userPreferences.setInsightRuleDismissed(ruleId, true)
            val dismissed = _state.value.dismissedRules + ruleId
            _state.value = _state.value.copy(
                dismissedRules = dismissed,
                rankedActions = _state.value.report?.let { Playbook.rank(it.insights, dismissed) } ?: emptyList(),
            )
        }
    }

    /** Re-shows a dismissed rule (docs/INSIGHTS.md §Guardrails 6 — dismissal is a preference). */
    fun restore(ruleId: String) {
        viewModelScope.launch {
            userPreferences.setInsightRuleDismissed(ruleId, false)
            val dismissed = _state.value.dismissedRules - ruleId
            _state.value = _state.value.copy(
                dismissedRules = dismissed,
                rankedActions = _state.value.report?.let { Playbook.rank(it.insights, dismissed) } ?: emptyList(),
            )
        }
    }

    /**
     * One-mutation-at-a-time guard for the experiment actions: a second tap while one is in
     * flight is dropped entirely (the single-ACTIVE transaction in the tracker is the real
     * gate — this only avoids a confusing "finish current experiment first" from a double-tap).
     */
    private val experimentActionMutex = kotlinx.coroutines.sync.Mutex()

    /** Maps a tracker rejection to the UI error the screen renders. */
    private fun ExperimentTracker.StartResult.Reason.toUiError(): ExperimentStartError = when (this) {
        ExperimentTracker.StartResult.Reason.ACTIVE_EXISTS -> ExperimentStartError.ACTIVE_EXISTS
        ExperimentTracker.StartResult.Reason.BASELINE_COLLECTING -> ExperimentStartError.BASELINE_COLLECTING
        ExperimentTracker.StartResult.Reason.NO_BASELINE_BAND -> ExperimentStartError.NO_BASELINE_BAND
    }

    /**
     * Starts the N-of-1 experiment for a playbook action against the CURRENT baseline.
     * Double-tap safe: the mutex is held across the whole suspend flow (a second tap is
     * dropped), and the tracker's transaction makes the single-ACTIVE check + insert atomic.
     */
    fun startExperiment(action: Playbook.PlaybookAction) {
        viewModelScope.launch {
            if (!experimentActionMutex.tryLock()) return@launch
            try {
                val report = _state.value.report ?: return@launch
                when (val result = experimentTracker.startExperiment(
                    actionId = action.id,
                    ruleId = action.relatedRuleId,
                    metric = action.verifyMetricId,
                    baseline = report.baseline,
                )) {
                    is ExperimentTracker.StartResult.Started -> load()
                    is ExperimentTracker.StartResult.Rejected -> _state.value = _state.value.copy(
                        startError = result.reason.toUiError(),
                    )
                }
            } finally {
                experimentActionMutex.unlock()
            }
        }
    }

    fun abandonExperiment(experimentId: Long) {
        viewModelScope.launch {
            experimentTracker.abandon(experimentId)
            load()
        }
    }

    /** Keeps the verdict: the result stands and the verdict card stops rendering. */
    fun keepVerdict(experimentId: Long) {
        viewModelScope.launch {
            experimentTracker.keepVerdict(experimentId)
            load()
        }
    }

    /**
     * Starts the A-B-A return-to-baseline phase on a DONE change-detected experiment. A
     * rejection (another experiment ACTIVE, or a row the confirm cannot attach to) is
     * surfaced through the same [UiState.startError] line the start flow uses — never silent.
     */
    fun startConfirmPhase(experimentId: Long) {
        viewModelScope.launch {
            if (!experimentActionMutex.tryLock()) return@launch
            try {
                when (val result = experimentTracker.startConfirmPhase(experimentId)) {
                    is ExperimentTracker.StartResult.Started -> load()
                    is ExperimentTracker.StartResult.Rejected -> _state.value = _state.value.copy(
                        startError = result.reason.toUiError(),
                    )
                }
            } finally {
                experimentActionMutex.unlock()
            }
        }
    }

    /** Persists the three screening answers (collected once, editable) and re-evaluates. */
    fun answerSelfReport(loud: Boolean, witnessedPauses: Boolean, daytimeSleepiness: Boolean) {
        viewModelScope.launch {
            userPreferences.setEscalationSelfReport(
                EscalationRule.SelfReport(
                    snoringLoud = loud,
                    witnessedPauses = witnessedPauses,
                    daytimeSleepiness = daytimeSleepiness,
                ),
            )
            load()
        }
    }

    private suspend fun load() {
        val report = sessionRepository.buildInsights()
        val dismissed = userPreferences.dismissedInsightRulesNow()
        // Auto-finalize the active experiment at its night threshold BEFORE reading state —
        // a verdict that became due renders immediately (docs/INSIGHTS.md §Playbook).
        experimentTracker.refreshActiveVerdict()
        val activeEntity = experimentTracker.activeExperiment()
        val activeProgress = activeEntity?.let { experimentTracker.progress(it) }
        // Verdict card: the newest DONE experiment with an unkept verdict (kept/abandoned ones
        // never render again; abandoned rows are excluded by status).
        val verdictEntity = experimentTracker.history().firstOrNull { entity ->
            entity.status == ExperimentStatus.DONE && entity.resultJson != null &&
                parseExperimentVerdict(entity.resultJson)?.kept != true
        }
        val selfReport = userPreferences.escalationSelfReportNow()
        _state.value = UiState(
            report = report,
            dismissedRules = dismissed,
            loaded = true,
            rankedActions = Playbook.rank(report.insights, dismissed),
            activeExperiment = activeEntity?.let { experimentView(it, activeProgress) },
            verdictExperiment = verdictEntity?.let { entity ->
                experimentView(entity, experimentTracker.progress(entity))
            },
            escalation = EscalationRule.evaluate(report.eligibleRecords, selfReport.toRuleReport()),
            selfReport = selfReport,
            startError = null,
        )
    }

    private fun experimentView(
        entity: SleepExperimentEntity,
        progress: ExperimentTracker.Progress?,
    ): ExperimentView {
        val verdictRow = parseExperimentVerdict(entity.resultJson)
        val confirming = parseConfirmPhase(entity.confirmPhaseJson) != null &&
            entity.status == ExperimentStatus.ACTIVE
        return ExperimentView(
            id = entity.id,
            actionId = entity.playbookActionId,
            metric = entity.metric,
            phase = if (confirming) ExperimentTracker.Phase.CONFIRM else ExperimentTracker.Phase.BASELINE_PHASE,
            usableNights = progress?.usableNights ?: 0,
            plannedNights = progress?.plannedNights ?: entity.plannedNights,
            excludedNights = progress?.excludedNights ?: 0,
            missingValues = progress?.missingValues ?: 0,
            ready = progress?.ready ?: false,
            verdict = verdictRow?.let { row ->
                VerdictView(
                    changeDetected = row.verdict?.changeDetected ?: false,
                    directionUp = row.verdict?.direction?.let { it == ExperimentMath.Direction.UP },
                    confidence = row.verdict?.confidence ?: ConfidenceTier.LOW,
                    confirmed = row.confirmed,
                    confirmRan = row.confirmReturnedToBand != null,
                    kept = row.kept,
                )
            },
        )
    }
}
