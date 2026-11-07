package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.domain.scoring.SnoreMath

/**
 * Burst-vs-bout view of a night's snoring (report-only, never touches stored rows — same
 * contract as [groupEpisodes]).
 *
 * A BURST is one stored event. By detector construction (v2-burst: cross-draft merging off,
 * 2 s exit hysteresis) a stored event can never contain an internal silence of 2 s of quiet
 * stamps (4 hops) — the machine closes there and the chain splits — and consecutive events
 * are ≥ 1.5 s apart (starts are backdated one window). So every stored event is one acoustic
 * burst. A BOUT chains bursts whose inter-event gaps stay within [BOUT_GAP_MS] — continuous
 * snoring stretches, including trains the detector reports as several bursts a few seconds
 * apart. Chaining is deliberately report-time so the threshold can evolve without touching
 * stored data or the detector's replay-matches-live invariant.
 */
data class SnoreBout(
    val startMs: Long,
    val endMs: Long,
    /** Member bursts, chronological. */
    val bursts: List<AcousticEventEntity>,
    /** Union snoring time of the bout's bursts (overlaps counted once — SnoreMath). */
    val snoreSeconds: Long,
    val peakDbfs: Double,
)

/**
 * Maximum inter-event gap within one bout. Must exceed the detector's 2 s merge bound
 * (events closer than that are already one event) — 10 s chains breath trains separated by
 * short recovery pauses while still splitting distinct snoring periods (night Nov 5→6:
 * 220 bursts → 118 bouts, longest 21m32s). Deliberately the same bound as [EPISODE_GAP_MS]:
 * episodes group all sounds for clip review, bouts chain snoring for the night structure —
 * one gap definition keeps the two views reconcilable.
 */
const val BOUT_GAP_MS = 10_000L

/** Compact headline numbers for the report's structure card. */
data class BoutStats(
    val burstCount: Int,
    val boutCount: Int,
    val longestBurstMs: Long,
    val medianBurstMs: Long,
    val longestBoutMs: Long,
    val longestBoutStartMs: Long,
    val longestBoutEndMs: Long,
    val longestBoutBursts: Int,
)

/** Groups [snoreEvents] (any order) into snore bouts; returns them in chronological order. */
fun groupBouts(
    snoreEvents: List<AcousticEventEntity>,
    gapMs: Long = BOUT_GAP_MS,
): List<SnoreBout> {
    val events = snoreEvents.filter { it.type == EventType.SNORE }.sortedBy { it.startMs }
    if (events.isEmpty()) return emptyList()

    val bouts = mutableListOf<SnoreBout>()
    var current = mutableListOf(events.first())
    // Running max of member ends: stored events cannot overlap (the detector merges within
    // 2 s gaps), but chaining on the running max keeps that invariant dependency moot.
    var currentEndMs = events.first().endMs
    for (event in events.drop(1)) {
        if (event.startMs - currentEndMs > gapMs) {
            bouts += current.toBout()
            current = mutableListOf(event)
            currentEndMs = event.endMs
        } else {
            current += event
            currentEndMs = maxOf(currentEndMs, event.endMs)
        }
    }
    bouts += current.toBout()
    return bouts
}

private fun List<AcousticEventEntity>.toBout(): SnoreBout = SnoreBout(
    startMs = first().startMs,
    endMs = maxOf { it.endMs },
    bursts = this,
    snoreSeconds = SnoreMath.unionDurationMs(map { it.startMs to it.endMs }) / 1000,
    peakDbfs = maxOf { it.peakDbfs },
)

/** Headline numbers over [bouts]; null when the night had no snore bursts. */
fun boutStats(bouts: List<SnoreBout>): BoutStats? {
    if (bouts.isEmpty()) return null
    val burstDurations = bouts.flatMap { it.bursts }.map { it.durationMs }.sorted()
    val median = if (burstDurations.size % 2 == 1) {
        burstDurations[burstDurations.size / 2]
    } else {
        (burstDurations[burstDurations.size / 2 - 1] + burstDurations[burstDurations.size / 2]) / 2
    }
    val longest = bouts.maxBy { it.endMs - it.startMs }
    return BoutStats(
        burstCount = burstDurations.size,
        boutCount = bouts.size,
        longestBurstMs = burstDurations.last(),
        medianBurstMs = median,
        longestBoutMs = longest.endMs - longest.startMs,
        longestBoutStartMs = longest.startMs,
        longestBoutEndMs = longest.endMs,
        longestBoutBursts = longest.bursts.size,
    )
}
