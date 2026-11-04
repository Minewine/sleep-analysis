package com.sleepanalysis.domain.report

import com.sleepanalysis.data.db.entity.AcousticEventEntity
import com.sleepanalysis.data.db.entity.AudioClipEntity
import com.sleepanalysis.data.db.entity.ClinicalBaselineEntity
import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.data.db.entity.SessionStatus
import com.sleepanalysis.data.db.entity.SleepSessionEntity
import com.sleepanalysis.domain.scoring.SnoreScore
import kotlin.math.floor

data class HourlyBucket(
    /** Offset from session start, floored to the hour. */
    val hourStartOffsetMs: Long,
    val snoreSeconds: Long,
    val maxTier: String?,
)

data class ClipRef(
    val eventId: Long,
    val filePath: String,
    val offsetMs: Long,
    val durationMs: Long,
    val intensityTier: String,
    val eventType: String,
)

data class GarminMetric(
    val measurementType: String,
    val value: Double,
    val unit: String,
    val timestampMs: Long,
)

/** Self-logged daily factor (user-entered context — never a measurement, no tier). */
data class FactorEntry(
    val factorKey: String,
    val valueNum: Double?,
    val valueText: String?,
)

/**
 * The morning report — the central UX (docs/PRODUCT_VISION.md §5). Pure domain: assembled only
 * from Established-tier phone measurements, device-reported Garmin metrics (labeled "Garmin
 * estimate" in the UI), and read-only clinical context — every number is provenance-traceable.
 * No breathing/stage/apnea values exist anywhere in this model by design
 * (docs/MEASUREMENT_RELIABILITY.md).
 */
data class MorningReport(
    val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val status: String,
    val timeZone: String,
    val timeInBedSeconds: Long,
    val snoreSeconds: Long,
    val snoreSharePercent: Double,
    val snoreEventCount: Int,
    val snoreScore: Int,
    val intensityBreakdown: Map<String, Int>,
    val eventCountsByType: Map<String, Int>,
    val hourly: List<HourlyBucket>,
    val peakDbfs: Double?,
    val meanSnoreDbfs: Double?,
    /** All events (bounded), oldest first — the timeline data source. */
    val events: List<AcousticEventEntity>,
    /** eventId → clip reference for playback. */
    val clips: Map<Long, ClipRef>,
    val integrityNotes: List<String>,
    val baseline: List<ClinicalBaselineEntity>,
    val modelVersion: String?,
    /** Garmin device-reported metrics for the session's calendar day (may be empty). */
    val garmin: List<GarminMetric> = emptyList(),
    /** Self-logged lifestyle factors for the session's calendar day (may be empty). */
    val dailyFactors: List<FactorEntry> = emptyList(),
    /** Parsed capture statistics (null for sessions recorded before observability existed). */
    val capture: CaptureStats? = null,
    /** Derived from capture stats — why a night might look incomplete. */
    val healthWarnings: List<String> = emptyList(),
    /** Short user note added after the night (user context, never a measurement). */
    val morningNotes: String? = null,
    /**
     * The night grouped into episodes, loudest peak first (see [Episodes]) — the review
     * surface for "what were the loudest noises?" without scrolling 1,000+ fragments.
     */
    val loudestEpisodes: List<EventEpisode> = emptyList(),
)

object ReportAssembler {

    const val MAX_TIMELINE_EVENTS = 2_000

    fun assemble(
        session: SleepSessionEntity,
        events: List<AcousticEventEntity>,
        clips: List<AudioClipEntity>,
        baseline: List<ClinicalBaselineEntity>,
        garmin: List<GarminMetric> = emptyList(),
        factors: List<FactorEntry> = emptyList(),
        capture: CaptureStats? = null,
    ): MorningReport? {
        val endedAt = session.endedAt ?: return null
        val timeInBedSeconds = ((endedAt - session.startedAt).coerceAtLeast(0)) / 1000

        val snoreEvents = events.filter { it.type == EventType.SNORE }
        // Union, not sum — the same math as the stored measurements (SnoreMath): merged
        // overlapping event windows must count their audio once.
        val snoreSeconds = com.sleepanalysis.domain.scoring.SnoreMath
            .unionDurationMs(snoreEvents.map { it.startMs to it.endMs }) / 1000
        // Same denominator as the measurements: mic-live captured analysis time, not
        // tap-to-end time in bed (docs/DATA_MODEL.md §5.1). Falls back to time in bed only
        // when the session has no capture stats at all.
        val capturedSeconds = capture?.capturedAnalysisMs()?.div(1000)?.takeIf { it > 0 }
            ?: timeInBedSeconds
        val sharePercent = if (capturedSeconds > 0) snoreSeconds * 100.0 / capturedSeconds else 0.0

        val tierCounts = snoreEvents.groupingBy { it.intensityTier }.eachCount()
        val score = SnoreScore.compute(
            snoreSeconds = snoreSeconds,
            nightSeconds = capturedSeconds,
            tierCounts = tierCounts,
        )

        val integrityNotes = buildList {
            // First note: a resumed night was not continuously captured — the headline numbers
            // (time in bed, hourly buckets) span a night with a dead gap in the middle.
            if (session.resumedAfterInterruption) {
                add("Resumed after an interruption — overnight capture was not continuous.")
            }
            if (session.status != SessionStatus.COMPLETED) {
                add("Session status: ${session.status.lowercase()} — report covers only what was recorded.")
            }
            if (events.size >= MAX_TIMELINE_EVENTS) {
                add("Timeline truncated to the first $MAX_TIMELINE_EVENTS events.")
            }
        }

        val healthWarnings = capture.healthWarnings(endedAt, timeInBedSeconds)

        // Join clips to events via event.clipId — the authoritative link written at capture time
        // (audio_clip.eventId is back-filled, but old rows may predate that).
        val clipById = clips.associateBy { it.id }
        val clipRefsById: Map<Long, ClipRef> = events.mapNotNull { event ->
            val clip = event.clipId?.let { clipById[it] } ?: return@mapNotNull null
            event.id to ClipRef(
                eventId = event.id,
                filePath = clip.filePath,
                offsetMs = event.offsetMs,
                durationMs = clip.durationMs,
                intensityTier = event.intensityTier,
                eventType = event.type,
            )
        }.toMap()
        // Episode grouping uses the FULL event list (not the truncated timeline) so the
        // loudest-first section never hides an episode behind MAX_TIMELINE_EVENTS.
        val episodes = loudestEpisodes(groupEpisodes(events, clipRefsById))

        return MorningReport(
            sessionId = session.id,
            startedAt = session.startedAt,
            endedAt = endedAt,
            status = session.status,
            timeZone = session.timeZone,
            timeInBedSeconds = timeInBedSeconds,
            snoreSeconds = snoreSeconds,
            snoreSharePercent = sharePercent,
            snoreEventCount = snoreEvents.size,
            snoreScore = score,
            intensityBreakdown = tierCounts,
            eventCountsByType = events.groupingBy { it.type }.eachCount(),
            hourly = hourlyBuckets(session, snoreEvents),
            peakDbfs = snoreEvents.maxOfOrNull { it.peakDbfs },
            meanSnoreDbfs = snoreEvents.map { it.meanDbfs }.takeIf { it.isNotEmpty() }?.average(),
            events = events.take(MAX_TIMELINE_EVENTS),
            clips = events.take(MAX_TIMELINE_EVENTS)
                .mapNotNull { event -> clipRefsById[event.id]?.let { event.id to it } }
                .toMap(),
            integrityNotes = integrityNotes,
            baseline = baseline,
            modelVersion = snoreEvents.firstOrNull()?.modelVersion,
            garmin = garmin,
            dailyFactors = factors,
            capture = capture,
            healthWarnings = healthWarnings,
            morningNotes = session.morningNotes,
            loudestEpisodes = episodes,
        )
    }

    /**
     * Derives human-readable health warnings from capture statistics. Never mentions clinical
     * concepts. The gap check compares processed windows against the CAPTURE window —
     * capture_started_at when persisted (delay-aware), tap-to-stop time in bed otherwise.
     */
    private fun CaptureStats?.healthWarnings(endedAtMs: Long, timeInBedSeconds: Long): List<String> {
        val self = this ?: return listOf("Capture statistics unavailable for this session (recorded before health tracking or stats write failed).")
        return buildList {
            val processed = self.windowsProcessed
            val windowSeconds = self.captureStartMs
                ?.takeIf { endedAtMs > it }
                ?.let { (endedAtMs - it) / 1000 }
                ?: timeInBedSeconds
            when {
                // Recovered/interrupted sessions finalize without engine stats: unknown ≠ failed.
                processed == null -> { /* stats unknown — no capture-quality warnings */ }
                processed == 0L && windowSeconds > 30 -> {
                    add("No analysis windows were processed — capture likely failed for most of the session.")
                }
                else -> {
                    val expected = windowSeconds * 2 // 0.5 s hop
                    if (expected > 0 && processed < expected * 0.9) {
                        add("Capture gaps suspected: $processed of ~$expected expected analysis windows were processed.")
                    }
                }
            }
            (self.thermalThrottleEvents ?: 0).takeIf { it > 0 }?.let {
                val skippedHops = self.skippedHopSpans.sumOf { span -> span.hops }
                if (skippedHops > 0) {
                    add(
                        "Thermal throttling engaged $it time(s) — $skippedHops analysis hops " +
                            "skipped; per-hour detection coverage was reduced."
                    )
                } else {
                    add("Thermal throttling engaged $it time(s) — recording continued with slower analysis.")
                }
            }
        }
    }

    internal fun hourlyBuckets(
        session: SleepSessionEntity,
        snoreEvents: List<AcousticEventEntity>,
    ): List<HourlyBucket> {
        if (session.endedAt == null) return emptyList()
        val hourMs = 3_600_000L
        val duration = (session.endedAt - session.startedAt).coerceAtLeast(0)
        if (duration == 0L) return emptyList()

        // Merge events into hour buckets by their start offset.
        data class Acc(var seconds: Long = 0, var maxTier: String? = null)

        val bucketCount = ((duration + hourMs - 1) / hourMs).toInt()
        val buckets = Array(bucketCount) { Acc() }
        for (event in snoreEvents) {
            val idx = floor(event.offsetMs.toDouble() / hourMs).toInt().coerceIn(0, bucketCount - 1)
            buckets[idx].seconds += event.durationMs / 1000
            val current = buckets[idx].maxTier
            if (current == null || tierRank(event.intensityTier) > tierRank(current)) {
                buckets[idx].maxTier = event.intensityTier
            }
        }
        return buckets.mapIndexed { i, acc ->
            HourlyBucket(hourStartOffsetMs = i * hourMs, snoreSeconds = acc.seconds, maxTier = acc.maxTier)
        }
    }

    private fun tierRank(tier: String): Int = when (tier) {
        SnoreScore.IntensityTiers.EPIC -> 4
        SnoreScore.IntensityTiers.LOUD -> 3
        SnoreScore.IntensityTiers.LIGHT -> 2
        else -> 1
    }
}
