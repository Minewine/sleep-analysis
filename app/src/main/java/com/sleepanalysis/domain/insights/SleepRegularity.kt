package com.sleepanalysis.domain.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sleep regularity metrics over nightly Garmin sleep windows (docs/INSIGHTS.md §SRI/SJL).
 * Both are APPROXIMATIONS and say so everywhere they appear:
 *  - inputs are the NIGHTLY windows only (no nap epochs, no per-epoch device stages), so
 *    daytime is approximated as wake;
 *  - the windows are wrist-derived (Garmin), with wrist validation error on top of every
 *    number — never a clinical sleep measure (docs/MEASUREMENT_RELIABILITY.md M13).
 *
 * SRI — Sleep Regularity Index per Windred 2024 (Sleep): `100 × mean[I(state_e == state_(e+24h))]`
 * over 60 s epochs, comparing each epoch's asleep/awake state with the epoch exactly 24 h
 * later (epoch-anchored: on DST transition days a "24 h" step is 23/25 wall-clock hours —
 * the honest epoch arithmetic). Needs ≥ [MIN_SRI_DAYS] valid days, else the state stays
 * COLLECTING and no SRI is produced.
 *
 * Social jetlag — |midpoint(free days) − midpoint(work days)| over the windows (Roenneberg
 * 2012, Current Biology): weekend (Sat/Sun) vs weekday midpoints, each anchored to the
 * preceding local noon so multi-day averaging works. Classification uses the LOCAL MORNING
 * the window ends on (the day its midpoint falls on), in the caller's zone. The flag is
 * labeled CONVENTION in all copy: the >1 h trigger is the literature's descriptive ordering,
 * the app cannot know the user's actual work schedule, so an office worker with a Saturday
 * shift or a night-shift worker would be misclassified.
 */
object SleepRegularity {

    /** SRI epoch grid (Windred 2024 uses 30–60 s; 60 s keeps 14 nights ≈ 20 k epochs). */
    const val EPOCH_MS = 60_000L

    /** The comparison step ("e + 24 h"), epoch arithmetic — not always a local 24 h on DST days. */
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Valid days needed before an SRI is produced (docs/INSIGHTS.md: ≥5). */
    const val MIN_SRI_DAYS = 5

    /** Social-jetlag soft flag threshold in hours (Roenneberg 2012; CONVENTION, see object KDoc). */
    const val SOCIAL_JETLAG_FLAG_HOURS = 1.0

    /** Weekend/weekday midpoints needed each before an SJL is produced (app convention). */
    const val MIN_SJL_PER_GROUP = 2

    enum class State { COLLECTING, READY }

    /** One nightly Garmin sleep window (epoch ms) with its ISO wake date. */
    data class NightWindow(val date: String, val startMs: Long, val endMs: Long)

    data class Result(
        val state: State,
        /** 0..100; null while COLLECTING or without a comparable horizon. */
        val sri: Double?,
        /** Epoch pairs actually compared (transparency for the UI/tests). */
        val comparedEpochPairs: Int,
        /** |weekend − weekday| sleep-midpoint difference in hours; null while insufficient. */
        val socialJetlagHours: Double?,
        /** True when [socialJetlagHours] > [SOCIAL_JETLAG_FLAG_HOURS] (CONVENTION flag). */
        val socialJetlagFlagged: Boolean,
        /** Distinct valid days the SRI was built from. */
        val dayCount: Int,
    )

    /**
     * Computes both metrics over [windows] (deduplicated per date, degenerate spans dropped).
     * [zone] classifies windows into weekend/weekday by the local morning they end on.
     */
    fun compute(windows: List<NightWindow>, zone: ZoneId): Result {
        val valid = windows
            .filter { it.endMs > it.startMs }
            .distinctBy { it.date }
        val sri = sleepRegularIndex(valid)
        val sjl = socialJetlagHours(valid, zone)
        return Result(
            state = if (sri != null) State.READY else State.COLLECTING,
            sri = sri?.index,
            comparedEpochPairs = sri?.comparedPairs ?: 0,
            socialJetlagHours = sjl,
            socialJetlagFlagged = (sjl ?: 0.0) > SOCIAL_JETLAG_FLAG_HOURS,
            dayCount = valid.size,
        )
    }

    data class SriResult(val index: Double, val comparedPairs: Int)

    /**
     * The pure SRI over [windows]: 60 s epochs spanned by the union horizon, each epoch
     * compared with the epoch one [DAY_MS] later. Both sides of every comparison must lie
     * inside the horizon — a trailing edge shorter than 24 h simply contributes no pair.
     */
    fun sleepRegularIndex(windows: List<NightWindow>): SriResult? {
        val valid = windows.filter { it.endMs > it.startMs }.distinctBy { it.date }
        if (valid.size < MIN_SRI_DAYS) return null
        val horizonStart = valid.minOf { it.startMs } / EPOCH_MS * EPOCH_MS
        val horizonEnd = (valid.maxOf { it.endMs } + EPOCH_MS - 1) / EPOCH_MS * EPOCH_MS
        if (horizonEnd - horizonStart < DAY_MS) return null
        var matched = 0L
        var compared = 0L
        var epoch = horizonStart
        while (epoch + DAY_MS <= horizonEnd) {
            if (asleepAt(epoch, valid) == asleepAt(epoch + DAY_MS, valid)) matched++
            compared++
            epoch += EPOCH_MS
        }
        if (compared == 0L) return null
        return SriResult(index = matched * 100.0 / compared, comparedPairs = compared.toInt())
    }

    /** Asleep = the instant falls inside any nightly window ([startMs, endMs)). */
    private fun asleepAt(epochMs: Long, windows: List<NightWindow>): Boolean =
        windows.any { epochMs >= it.startMs && epochMs < it.endMs }

    /**
     * |midpoint(weekend) − midpoint(weekday)| in hours, midpoints anchored to the preceding
     * local noon (Roenneberg 2012) so cross-day averaging stays meaningful. Needs
     * [MIN_SJL_PER_GROUP] windows per group; null otherwise (never a guess from one night).
     */
    fun socialJetlagHours(windows: List<NightWindow>, zone: ZoneId): Double? {
        val anchored = windows.mapNotNull { w ->
            val localEnd = Instant.ofEpochMilli(w.endMs).atZone(zone).toLocalDate()
            val noonBefore = noonBeforeEndDay(localEnd, zone) ?: return@mapNotNull null
            val group = when (localEnd.dayOfWeek) {
                java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY -> Group.WEEKEND
                else -> Group.WEEKDAY
            }
            group to ((w.startMs + w.endMs) / 2.0 - noonBefore) / 60_000.0 // minutes since that noon
        }
        val weekend = anchored.filter { it.first == Group.WEEKEND }.map { it.second }
        val weekday = anchored.filter { it.first == Group.WEEKDAY }.map { it.second }
        if (weekend.size < MIN_SJL_PER_GROUP || weekday.size < MIN_SJL_PER_GROUP) return null
        return kotlin.math.abs(weekend.average() - weekday.average()) / 60.0
    }

    /**
     * The local noon preceding a night's midpoint: noon of the day BEFORE the wake day
     * (midpoints 00:00–11:59 belong to the preceding noon's cycle) — for a window ending
     * 2026-01-12 morning, midpoints anchor to 2026-01-11 12:00 local.
     */
    private fun noonBeforeEndDay(endDay: LocalDate, zone: ZoneId): Long? = runCatching {
        endDay.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    private enum class Group { WEEKEND, WEEKDAY }
}
