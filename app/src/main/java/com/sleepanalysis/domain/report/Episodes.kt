package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.EventType

/**
 * Display-level grouping of a night's acoustic events into episodes ("easily find the loudest
 * night noises" — night 3 review). Snoring arrives in breath cycles — 2–3 s events separated
 * by multi-second pauses — so a raw 1,475-event timeline is unreviewable. An episode is a run
 * of events whose start-to-previous-end gaps stay within [EPISODE_GAP_MS]. Grouping is
 * report-only and never touches stored rows: each event keeps its own label and features
 * (the Phase 2 dataset); episodes only change how the morning report presents the night.
 */
data class EventEpisode(
    val startMs: Long,
    val endMs: Long,
    /** Member events, loudest peak first — the first member is the episode's "sound". */
    val events: List<AcousticEventEntity>,
    val peakDbfs: Double,
    val snoreCount: Int,
    /** Clip of the loudest member that has one — the episode's playback entry point. */
    val clip: ClipRef?,
)

/** Maximum gap between consecutive events within one episode. */
const val EPISODE_GAP_MS = 10_000L

/** How many episodes the report's loudest-first section shows. */
const val MAX_LOUDEST_EPISODES = 10

/**
 * Groups [events] into episodes, attaching clips from [clipsById] (eventId → ClipRef).
 * Returned in chronological order.
 */
fun groupEpisodes(
    events: List<AcousticEventEntity>,
    clipsById: Map<Long, ClipRef>,
    gapMs: Long = EPISODE_GAP_MS,
): List<EventEpisode> {
    if (events.isEmpty()) return emptyList()
    data class Run(val events: MutableList<AcousticEventEntity>, var endMs: Long)

    val runs = mutableListOf<Run>()
    for (event in events.sortedBy { it.startMs }) {
        val run = runs.lastOrNull()
        when {
            run == null || event.startMs - run.endMs > gapMs ->
                runs.add(Run(mutableListOf(event), event.endMs))
            else -> {
                run.events.add(event)
                run.endMs = maxOf(run.endMs, event.endMs)
            }
        }
    }
    return runs.map { run ->
        val members = run.events.sortedByDescending { it.peakDbfs }
        EventEpisode(
            startMs = members.minOf { it.startMs },
            endMs = run.endMs,
            events = members,
            peakDbfs = members.first().peakDbfs,
            snoreCount = members.count { it.type == EventType.SNORE },
            clip = members.firstNotNullOfOrNull { clipsById[it.id] },
        )
    }.sortedBy { it.startMs }
}

/** The [n] loudest episodes, loudest peak first. */
fun loudestEpisodes(
    episodes: List<EventEpisode>,
    n: Int = MAX_LOUDEST_EPISODES,
): List<EventEpisode> = episodes.sortedByDescending { it.peakDbfs }.take(n)
