package com.sleepanalysis.domain.experiments

import androidx.room.withTransaction
import com.sleepanalysis.data.db.AppDatabase
import com.sleepanalysis.data.db.entity.ExperimentStatus
import com.sleepanalysis.data.db.entity.SleepExperimentEntity
import com.sleepanalysis.data.repo.SessionRepository
import com.sleepanalysis.domain.factors.DailyFactor
import com.sleepanalysis.domain.insights.ConfidenceTier
import com.sleepanalysis.domain.insights.PersonalBaseline
import com.sleepanalysis.domain.insights.Playbook
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The N-of-1 experiment core (docs/INSIGHTS.md §Playbook, docs/EVIDENCE_BASE.md N-of-1 rows):
 * pure verdict math in [ExperimentMath], persistence/status-machine glue in [ExperimentTracker].
 *
 * Standards (documented, never invented here): ≥7 usable phase nights to a verdict
 * ([DEFAULT_PLANNED_NIGHTS], WWC 2010 floor ≥ [MIN_PHASE_NIGHTS] usable points — Goodwin 2024
 * single-case context); the verdict fires when the phase median sits outside the START baseline
 * band beyond 2 robust SD (|z| > 2, z = (median − median₀)/(1.4826·MAD₀), floor-limited like the
 * baseline bands); verdict copy is "changed beyond your usual night-to-night variation" + a
 * direction — NEVER "improved". Nights carrying a logged factor OTHER than the experiment's own
 * (docs/INSIGHTS.md §Playbook confound exclusion) never feed the verdict. An optional A-B-A
 * return-to-baseline phase may upgrade the verdict to MODERATE when the phase-2 median falls
 * back inside the start band.
 */
object ExperimentMath {

    /** Phase length (nights) before a verdict — docs/EVIDENCE_BASE.md (Goodwin 2024; app convention). */
    const val DEFAULT_PLANNED_NIGHTS = 7

    /** WWC 2010 single-case floor: a verdict never runs on fewer usable points. */
    const val MIN_PHASE_NIGHTS = 5

    /** Verdict trigger: phase median beyond 2 robust SD of the start baseline (|z| > 2). */
    const val VERDICT_ROBUST_Z = 2.0

    /** Upper bound on the phase length a user can plan (sanity clamp, app convention). */
    const val MAX_PLANNED_NIGHTS = 30

    /**
     * Factor keys that can confound a phase — every [DailyFactor] except NOTES (free text is
     * context, not a manipulation) and MORNING_RATING (an OUTCOME, not a manipulation: every
     * morning gets rated, so counting it confoundable would silently exclude every rated
     * night and stall all N-of-1 experiments at zero usable nights). A phase night carrying
     * any of these outside the experiment's own keys ([Playbook.ownFactorKeys]) is
     * confound-excluded.
     */
    val CONFOUNDABLE_FACTOR_KEYS: Set<String> =
        DailyFactor.entries
            .filter { it != DailyFactor.NOTES && it != DailyFactor.MORNING_RATING }
            .map { it.key }.toSet()

    /** The baseline band captured at experiment start (from PersonalBaseline.MetricBand JSON). */
    data class Band(val median: Double, val mad: Double, val low: Double, val high: Double)

    enum class Direction { UP, DOWN }

    /** One phase night: metric value (null = missing) + the day's logged factor keys. */
    data class PhaseNight(
        val sessionId: Long,
        val startedAtMs: Long,
        val value: Double?,
        val factorKeys: Set<String>,
    )

    /** The phase verdict — confidence starts LOW for a change; A-B-A confirmation may upgrade it. */
    data class PhaseVerdict(
        val changeDetected: Boolean,
        val direction: Direction?,
        val confidence: ConfidenceTier,
        val phaseMedian: Double?,
        val robustZ: Double?,
    )

    /** One phase evaluation: which nights counted, which were excluded, and the verdict when ready. */
    data class PhaseResult(
        /** Nights feeding the verdict (eligible, unconfounded, value present), oldest first. */
        val usedNightIds: List<Long>,
        /** Confound-excluded nights (other logged factors) — listed in resultJson for honesty. */
        val excludedNightIds: List<Long>,
        /** Nights with a missing verification-metric value (not usable, not confounds). */
        val missingValueNightIds: List<Long>,
        /** Enough usable nights to render a verdict (≥ planned, never < [MIN_PHASE_NIGHTS]). */
        val ready: Boolean,
        val verdict: PhaseVerdict?,
    )

    /**
     * Partitions [nights] into (usable, confound-excluded, missing-value): a night is
     * confound-excluded when it carries a logged factor outside [ownFactorKeys] ∩
     * [CONFOUNDABLE_FACTOR_KEYS] — the logged flag only says the user logged it (no dose,
     * no timing — [DailyFactor]).
     */
    fun partitionNights(
        nights: List<PhaseNight>,
        ownFactorKeys: Set<String>,
    ): Triple<List<PhaseNight>, List<PhaseNight>, List<PhaseNight>> {
        val excluded = nights.filter { night ->
            (night.factorKeys intersect CONFOUNDABLE_FACTOR_KEYS).subtract(ownFactorKeys).isNotEmpty()
        }.toSet()
        val remaining = nights.filter { it !in excluded }
        val usable = remaining.filter { it.value != null }
        val missing = remaining.filter { it.value == null }
        return Triple(usable, nights.filter { it in excluded }, missing)
    }

    /**
     * Robust z of [value] against the start band: (value − median₀)/(1.4826·MAD₀), with the
     * spread never dropping below the metric's band floor ÷ band sigmas (the same floor-limited
     * spread the baseline bands use, so a zero-MAD band still yields a finite z).
     */
    fun robustZ(value: Double, band: Band, metricFloor: Double?): Double {
        val spread = maxOf(
            PersonalBaseline.MAD_SCALE * band.mad,
            (metricFloor ?: 0.0) / PersonalBaseline.BAND_SIGMAS,
        )
        if (spread <= 0.0) return if (value == band.median) 0.0 else Double.MAX_VALUE
        return (value - band.median) / spread
    }

    /** Per-metric band floor ([PersonalBaseline] floors; unknown metric → no floor). */
    fun metricFloor(metric: String): Double? = when (metric) {
        PersonalBaseline.Metrics.SNORE_SHARE -> PersonalBaseline.FLOOR_SNORE_SHARE
        PersonalBaseline.Metrics.LONGEST_BOUT_MS -> PersonalBaseline.FLOOR_LONGEST_BOUT_MS
        PersonalBaseline.Metrics.SCORE -> PersonalBaseline.FLOOR_SCORE
        PersonalBaseline.Metrics.GARMIN_AWAKE_MIN -> PersonalBaseline.FLOOR_GARMIN_AWAKE_MIN
        PersonalBaseline.Metrics.RMSSD_MS -> PersonalBaseline.FLOOR_RMSSD_MS
        else -> null
    }

    /**
     * Evaluates one phase ([nights] since the phase start, oldest first) against the start
     * band. Verdict renders only at ≥ [plannedNights] usable nights ([plannedNights] is
     * clamped to ≥ [MIN_PHASE_NIGHTS]); below it the result is `ready = false` with the
     * partition filled in for the progress display. [metric] selects the band floor for the
     * verdict z (the same floor the baseline band uses).
     */
    fun evaluatePhase(
        nights: List<PhaseNight>,
        ownFactorKeys: Set<String>,
        band: Band,
        metric: String,
        plannedNights: Int,
    ): PhaseResult {
        val (usable, excluded, missing) = partitionNights(nights, ownFactorKeys)
        val planned = plannedNights.coerceAtLeast(MIN_PHASE_NIGHTS)
        val ready = usable.size >= planned
        val verdict = if (!ready) {
            null
        } else {
            val median = PersonalBaseline.median(usable.map { it.value!! })
            val z = robustZ(median, band, metricFloor(metric))
            val change = abs(z) > VERDICT_ROBUST_Z
            PhaseVerdict(
                changeDetected = change,
                direction = when {
                    !change -> null
                    z > 0 -> Direction.UP
                    else -> Direction.DOWN
                },
                confidence = ConfidenceTier.LOW, // A-B-A confirmation may upgrade — never starts higher
                phaseMedian = median,
                robustZ = z,
            )
        }
        return PhaseResult(
            usedNightIds = usable.map { it.sessionId },
            excludedNightIds = excluded.map { it.sessionId },
            missingValueNightIds = missing.map { it.sessionId },
            ready = ready,
            verdict = verdict,
        )
    }

    /** Confirm-phase outcome: did the phase-2 median fall back inside the start band? */
    data class ConfirmOutcome(
        val ready: Boolean,
        val phase2Median: Double?,
        val returnedToBand: Boolean,
        /** True = the A-B-A pattern supports the phase-1 change → MODERATE confidence. */
        val confirmed: Boolean,
        val confidence: ConfidenceTier,
    )

    /**
     * A-B-A confirmation ([phase1] must have detected a change): after another
     * [plannedNights] usable nights, a phase-2 median back INSIDE the start band edges
     * ("consistent with the change") upgrades the verdict to MODERATE; staying outside keeps
     * LOW ("effect not confirmed").
     */
    fun evaluateConfirm(
        phaseResult: PhaseResult,
        band: Band,
        phase1: PhaseVerdict?,
        plannedNights: Int,
    ): ConfirmOutcome {
        val ready = phaseResult.ready
        val median = phaseResult.verdict?.phaseMedian
        val returned = median != null && !band.let { median < it.low || median > it.high }
        val confirmed = ready && phase1?.changeDetected == true && returned
        return ConfirmOutcome(
            ready = ready,
            phase2Median = median,
            returnedToBand = returned,
            confirmed = confirmed,
            confidence = if (confirmed) ConfidenceTier.MODERATE else ConfidenceTier.LOW,
        )
    }
}

/**
 * The experiment tracker: persistence + status machine over `sleep_experiment`
 * (docs/DATA_MODEL.md §3.12). Status machine: ACTIVE → (auto at ≥ planned usable nights)
 * DONE with the verdict → optional CONFIRM phase re-activates the SAME row → DONE/final;
 * ABANDONED any time by the user. Exactly ONE ACTIVE row — enforced here before every insert
 * (not in SQL), surfaced as "finish current experiment first".
 */
@Singleton
class ExperimentTracker @Inject constructor(
    private val db: AppDatabase,
    private val sessionRepository: SessionRepository,
) {

    /** Phase the experiment row is currently collecting. */
    enum class Phase { BASELINE_PHASE, CONFIRM }

    data class Progress(
        val phase: Phase,
        val usableNights: Int,
        val excludedNights: Int,
        val missingValues: Int,
        val plannedNights: Int,
        val ready: Boolean,
    )

    sealed interface StartResult {
        data class Started(val experimentId: Long) : StartResult
        data class Rejected(val reason: Reason) : StartResult
        enum class Reason { ACTIVE_EXISTS, BASELINE_COLLECTING, NO_BASELINE_BAND }
    }

    suspend fun activeExperiment(): SleepExperimentEntity? = db.sleepExperimentDao().active()

    suspend fun history(): List<SleepExperimentEntity> = db.sleepExperimentDao().history()

    /**
     * Starts an experiment against [baseline] (the CURRENT personal baseline, captured at
     * start): requires READY baseline and a band for [metric]; rejects while another
     * experiment is ACTIVE. The single-ACTIVE check and the insert run in ONE transaction —
     * two rapid starts can never race into two ACTIVE rows (the second sees the first's
     * insert and is rejected).
     */
    suspend fun startExperiment(
        actionId: String?,
        ruleId: String?,
        metric: String,
        baseline: PersonalBaseline.Baseline,
        plannedNights: Int = ExperimentMath.DEFAULT_PLANNED_NIGHTS,
    ): StartResult = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (db.sleepExperimentDao().active() != null) {
                return@withTransaction StartResult.Rejected(StartResult.Reason.ACTIVE_EXISTS)
            }
            if (baseline.state != PersonalBaseline.State.READY) {
                return@withTransaction StartResult.Rejected(StartResult.Reason.BASELINE_COLLECTING)
            }
            val baselineBand = baseline.bands[metric]
                ?: return@withTransaction StartResult.Rejected(StartResult.Reason.NO_BASELINE_BAND)
            val band = ExperimentMath.Band(
                median = baselineBand.median,
                mad = baselineBand.mad,
                low = baselineBand.low,
                high = baselineBand.high,
            )
            val id = db.sleepExperimentDao().insert(
                SleepExperimentEntity(
                    hypothesisRuleId = ruleId,
                    playbookActionId = actionId,
                    metric = metric,
                    startedAt = System.currentTimeMillis(),
                    plannedNights = plannedNights
                        .coerceIn(ExperimentMath.MIN_PHASE_NIGHTS, ExperimentMath.MAX_PLANNED_NIGHTS),
                    status = ExperimentStatus.ACTIVE,
                    baselineStatsJson = experimentBaselineJson(metric, band),
                ),
            )
            StartResult.Started(id)
        }
    }

    /** Progress over the current phase's stored nights (honest counts — exclusions shown). */
    suspend fun progress(experiment: SleepExperimentEntity): Progress? = withContext(Dispatchers.IO) {
        val band = parseExperimentBaseline(experiment.baselineStatsJson)?.second ?: return@withContext null
        val own = ownFactorKeys(experiment.playbookActionId)
        val phase = phaseOf(experiment)
        val result = ExperimentMath.evaluatePhase(
            nights = phaseNights(experiment, phase),
            ownFactorKeys = own,
            band = band,
            metric = experiment.metric,
            plannedNights = experiment.plannedNights,
        )
        Progress(
            phase = phase,
            usableNights = result.usedNightIds.size,
            excludedNights = result.excludedNightIds.size,
            missingValues = result.missingValueNightIds.size,
            plannedNights = experiment.plannedNights.coerceAtLeast(ExperimentMath.MIN_PHASE_NIGHTS),
            ready = result.ready,
        )
    }

    /**
     * Auto-finalize at the night threshold (called on screen load): a phase with ≥ planned
     * usable nights lands its verdict (ACTIVE → DONE; confirm phase → final DONE with the
     * upgraded verdict). Returns the updated row, or null when nothing finalized.
     */
    suspend fun refreshActiveVerdict(): SleepExperimentEntity? = withContext(Dispatchers.IO) {
        val experiment = db.sleepExperimentDao().active() ?: return@withContext null
        val (metric, band) = parseExperimentBaseline(experiment.baselineStatsJson)
            ?: return@withContext null
        val own = ownFactorKeys(experiment.playbookActionId)
        val planned = experiment.plannedNights.coerceAtLeast(ExperimentMath.MIN_PHASE_NIGHTS)
        val phase = phaseOf(experiment)
        val result = ExperimentMath.evaluatePhase(phaseNights(experiment, phase), own, band, metric, planned)
        if (!result.ready) return@withContext null
        when (phase) {
            Phase.BASELINE_PHASE -> db.sleepExperimentDao().finish(experiment.id, experimentVerdictJson(result, planned))
            Phase.CONFIRM -> {
                val phase1 = parseExperimentVerdict(experiment.resultJson)
                val confirmStartedAt = parseConfirmPhase(experiment.confirmPhaseJson)?.startedAt ?: return@withContext null
                val outcome = ExperimentMath.evaluateConfirm(result, band, phase1?.verdict, planned)
                db.sleepExperimentDao().finishConfirm(
                    experiment.id,
                    confirmFinalResultJson(phase1, planned, outcome),
                    confirmPhaseJson(confirmStartedAt, outcome),
                )
            }
        }
        db.sleepExperimentDao().byId(experiment.id)
    }

    /** User abandons the experiment (ACTIVE or DONE); an ABANDONED row is terminal. */
    suspend fun abandon(experimentId: Long) = withContext(Dispatchers.IO) {
        db.sleepExperimentDao().abandon(experimentId)
    }

    /**
     * A-B-A confirmation: the user REVERTS the change (back to the old routine) for another
     * ≥ planned nights; if the metric then returns to the start band, the phase-1 verdict is
     * upgraded to MODERATE ("consistent with the change"). Requires a DONE phase-1 verdict
     * that detected a change and no confirm phase yet; the SAME row re-activates. The
     * single-ACTIVE check and the re-activation run in ONE transaction (no race with a
     * concurrent start).
     */
    suspend fun startConfirmPhase(experimentId: Long): StartResult = withContext(Dispatchers.IO) {
        db.withTransaction {
            val experiment = db.sleepExperimentDao().byId(experimentId)
                ?: return@withTransaction StartResult.Rejected(StartResult.Reason.ACTIVE_EXISTS)
            if (db.sleepExperimentDao().active() != null) {
                return@withTransaction StartResult.Rejected(StartResult.Reason.ACTIVE_EXISTS)
            }
            val verdict = parseExperimentVerdict(experiment.resultJson)?.verdict
            if (experiment.status != ExperimentStatus.DONE || verdict?.changeDetected != true) {
                return@withTransaction StartResult.Rejected(StartResult.Reason.NO_BASELINE_BAND)
            }
            db.sleepExperimentDao().beginConfirmPhase(
                experimentId,
                confirmPhaseJson(startedAt = System.currentTimeMillis(), outcome = null),
            )
            StartResult.Started(experimentId)
        }
    }

    /** User keeps the verdict: the resultJson gains kept=true (the verdict card stops rendering). */
    suspend fun keepVerdict(experimentId: Long) = withContext(Dispatchers.IO) {
        val experiment = db.sleepExperimentDao().byId(experimentId) ?: return@withContext
        val verdict = parseExperimentVerdict(experiment.resultJson) ?: return@withContext
        db.sleepExperimentDao().updateResult(
            experimentId,
            experimentVerdictJson(
                ExperimentMath.PhaseResult(
                    usedNightIds = verdict.usedNightIds,
                    excludedNightIds = verdict.excludedNightIds,
                    missingValueNightIds = emptyList(),
                    ready = true,
                    verdict = verdict.verdict,
                ),
                experiment.plannedNights,
                verdictOverride = verdict.verdict,
                confirmed = verdict.confirmed,
                confirmMedian = verdict.confirmMedian,
                confirmReturnedToBand = verdict.confirmReturnedToBand,
                kept = true,
            ),
        )
    }

    private fun phaseOf(experiment: SleepExperimentEntity): Phase =
        if (parseConfirmPhase(experiment.confirmPhaseJson) != null &&
            experiment.status == ExperimentStatus.ACTIVE
        ) {
            Phase.CONFIRM
        } else {
            Phase.BASELINE_PHASE
        }

    private suspend fun phaseNights(
        experiment: SleepExperimentEntity,
        phase: Phase,
    ): List<ExperimentMath.PhaseNight> {
        val sinceMs = when (phase) {
            Phase.CONFIRM -> parseConfirmPhase(experiment.confirmPhaseJson)?.startedAt ?: experiment.startedAt
            Phase.BASELINE_PHASE -> experiment.startedAt
        }
        return sessionRepository.experimentPhaseNights(experiment.metric, sinceMs)
    }

    private fun ownFactorKeys(actionId: String?): Set<String> =
        actionId?.let { Playbook.ownFactorKeys(it) } ?: emptySet()
}

// ---------------------------------------------------------------------------
// JSON codecs (org.json — the repo-glue convention, parsed tolerantly like CaptureStats).
// Written with stable key order; readers accept missing fields defensively.
// ---------------------------------------------------------------------------

/** Baseline band captured at start: {"metric":…,"median":…,"mad":…,"low":…,"high":…}. */
internal fun experimentBaselineJson(metric: String, band: ExperimentMath.Band): String =
    "{\"metric\":\"$metric\",\"median\":${band.median},\"mad\":${band.mad}," +
        "\"low\":${band.low},\"high\":${band.high}}"

/** Tolerant parse of [experimentBaselineJson]; null when malformed. */
internal fun parseExperimentBaseline(json: String?): Pair<String, ExperimentMath.Band>? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        val metric = o.getString("metric")
        metric to ExperimentMath.Band(
            median = o.getDouble("median"),
            mad = o.getDouble("mad"),
            low = o.getDouble("low"),
            high = o.getDouble("high"),
        )
    }.getOrNull()
}

/** Persisted verdict row (resultJson). */
data class ExperimentVerdictRow(
    val verdict: ExperimentMath.PhaseVerdict?,
    val usedNightIds: List<Long>,
    val excludedNightIds: List<Long>,
    val plannedNights: Int,
    val kept: Boolean,
    /** A-B-A confirmation result; null = no confirm phase ran yet. */
    val confirmed: Boolean?,
    val confirmMedian: Double?,
    val confirmReturnedToBand: Boolean?,
)

/**
 * Verdict JSON (resultJson): `{"schema_version":1,"change_detected":…,"direction":…,
 * "confidence":…,"phase_median":…,"robust_z":…,"phase_nights":…,"planned_nights":…,
 * "used_night_ids":[…],"excluded_night_ids":[…],"kept":…}` plus, after an A-B-A confirm,
 * `"confirmed":…,"confirm_median":…,"confirm_returned_to_band":…`.
 */
internal fun experimentVerdictJson(
    result: ExperimentMath.PhaseResult,
    plannedNights: Int,
    verdictOverride: ExperimentMath.PhaseVerdict? = null,
    confirmed: Boolean? = null,
    confirmMedian: Double? = null,
    confirmReturnedToBand: Boolean? = null,
    kept: Boolean = false,
): String {
    val verdict = verdictOverride ?: result.verdict
    return buildString {
        append("{\"schema_version\":1")
        append(",\"change_detected\":${verdict?.changeDetected ?: false}")
        append(",\"direction\":${verdict?.direction?.toString()?.let { "\"$it\"" } ?: "null"}")
        append(",\"confidence\":\"${verdict?.confidence?.name ?: ConfidenceTier.LOW.name}\"")
        append(",\"phase_median\":${verdict?.phaseMedian ?: "null"}")
        append(",\"robust_z\":${verdict?.robustZ ?: "null"}")
        append(",\"phase_nights\":${result.usedNightIds.size}")
        append(",\"planned_nights\":$plannedNights")
        append(",\"used_night_ids\":${idsJson(result.usedNightIds)}")
        append(",\"excluded_night_ids\":${idsJson(result.excludedNightIds)}")
        append(",\"kept\":$kept")
        if (confirmed != null) append(",\"confirmed\":$confirmed")
        if (confirmMedian != null) append(",\"confirm_median\":$confirmMedian")
        if (confirmReturnedToBand != null) append(",\"confirm_returned_to_band\":$confirmReturnedToBand")
        append("}")
    }
}

/**
 * The final resultJson for a completed confirm phase, rebuilt from the PARSED phase-1 row:
 * the phase-1 verdict stands (its median, z, direction, used/excluded nights), the confidence
 * is upgraded per [outcome], and the user's `kept` intent survives the rebuild — a Keep tapped
 * before/during the confirm phase must never be silently lost (the confirm finalize rewrites
 * the whole resultJson).
 */
internal fun confirmFinalResultJson(
    phase1: ExperimentVerdictRow?,
    plannedNights: Int,
    outcome: ExperimentMath.ConfirmOutcome,
): String = experimentVerdictJson(
    ExperimentMath.PhaseResult(
        usedNightIds = phase1?.usedNightIds ?: emptyList(),
        excludedNightIds = phase1?.excludedNightIds ?: emptyList(),
        missingValueNightIds = emptyList(),
        ready = true,
        verdict = phase1?.verdict,
    ),
    plannedNights,
    verdictOverride = ExperimentMath.PhaseVerdict(
        changeDetected = phase1?.verdict?.changeDetected ?: false,
        direction = phase1?.verdict?.direction,
        confidence = outcome.confidence,
        phaseMedian = phase1?.verdict?.phaseMedian,
        robustZ = phase1?.verdict?.robustZ,
    ),
    confirmed = outcome.confirmed,
    confirmMedian = outcome.phase2Median,
    confirmReturnedToBand = outcome.returnedToBand,
    kept = phase1?.kept ?: false,
)

/** Tolerant parse of [experimentVerdictJson]; null when malformed. */
internal fun parseExperimentVerdict(json: String?): ExperimentVerdictRow? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        val change = o.optBoolean("change_detected", false)
        val direction = o.optString("direction", "null").takeIf { it != "null" }
            ?.let { runCatching { ExperimentMath.Direction.valueOf(it) }.getOrNull() }
        val confidence = runCatching { ConfidenceTier.valueOf(o.getString("confidence")) }
            .getOrDefault(ConfidenceTier.LOW)
        val hasMedian = o.has("phase_median") && !o.isNull("phase_median")
        val hasZ = o.has("robust_z") && !o.isNull("robust_z")
        ExperimentVerdictRow(
            verdict = ExperimentMath.PhaseVerdict(
                changeDetected = change,
                direction = direction,
                confidence = confidence,
                phaseMedian = if (hasMedian) o.getDouble("phase_median") else null,
                robustZ = if (hasZ) o.getDouble("robust_z") else null,
            ),
            usedNightIds = o.optJSONArray("used_night_ids")?.let(::longs) ?: emptyList(),
            excludedNightIds = o.optJSONArray("excluded_night_ids")?.let(::longs) ?: emptyList(),
            plannedNights = o.optInt("planned_nights", ExperimentMath.DEFAULT_PLANNED_NIGHTS),
            kept = o.optBoolean("kept", false),
            confirmed = if (o.has("confirmed") && !o.isNull("confirmed")) o.getBoolean("confirmed") else null,
            confirmMedian = if (o.has("confirm_median") && !o.isNull("confirm_median")) o.getDouble("confirm_median") else null,
            confirmReturnedToBand = if (o.has("confirm_returned_to_band") && !o.isNull("confirm_returned_to_band")) {
                o.getBoolean("confirm_returned_to_band")
            } else {
                null
            },
        )
    }.getOrNull()
}

/** Confirm-phase JSON: collecting `{"phase":"CONFIRM","started_at":…}` → final adds the outcome. */
internal fun confirmPhaseJson(startedAt: Long, outcome: ExperimentMath.ConfirmOutcome?): String {
    val base = "{\"schema_version\":1,\"phase\":\"CONFIRM\",\"started_at\":$startedAt"
    if (outcome == null || !outcome.ready) return "$base}"
    return base +
        ",\"phase2_median\":${outcome.phase2Median}" +
        ",\"returned_to_band\":${outcome.returnedToBand}" +
        ",\"confirmed\":${outcome.confirmed}" +
        ",\"confidence\":\"${outcome.confidence.name}\"}"
}

data class ConfirmPhaseRow(val startedAt: Long)

/** Tolerant parse of [confirmPhaseJson]; null when absent/malformed (no confirm phase). */
internal fun parseConfirmPhase(json: String?): ConfirmPhaseRow? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(json)
        if (o.optString("phase") != "CONFIRM") return@runCatching null
        ConfirmPhaseRow(startedAt = o.getLong("started_at"))
    }.getOrNull()
}

private fun idsJson(ids: List<Long>): String = ids.joinToString(",", "[", "]")

private fun longs(array: JSONArray): List<Long> = List(array.length()) { i -> array.getLong(i) }
