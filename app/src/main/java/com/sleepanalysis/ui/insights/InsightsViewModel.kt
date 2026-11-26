package com.sleepanalysis.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepanalysis.data.prefs.UserPreferences
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.domain.insights.InsightsReport
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
 */
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    data class UiState(
        val report: InsightsReport? = null,
        val dismissedRules: Set<String> = emptySet(),
        /** False until the first load lands — keeps the empty state from flashing on open. */
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun dismiss(ruleId: String) {
        viewModelScope.launch {
            userPreferences.setInsightRuleDismissed(ruleId, true)
            _state.value = _state.value.copy(dismissedRules = _state.value.dismissedRules + ruleId)
        }
    }

    /** Re-shows a dismissed rule (docs/INSIGHTS.md §Guardrails 6 — dismissal is a preference). */
    fun restore(ruleId: String) {
        viewModelScope.launch {
            userPreferences.setInsightRuleDismissed(ruleId, false)
            _state.value = _state.value.copy(dismissedRules = _state.value.dismissedRules - ruleId)
        }
    }

    private suspend fun load() {
        val report = sessionRepository.buildInsights()
        val dismissed = userPreferences.dismissedInsightRulesNow()
        _state.value = UiState(
            report = report,
            dismissedRules = dismissed,
            loaded = true,
        )
    }
}
