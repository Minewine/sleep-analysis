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
    /** Offset from session start — with the session start, the clip's absolute timestamp. */
    val offsetMs: Long,
    val durationMs: Long,
    val intensityTier: String,
    val eventType: String,
    /** The clipped event's analysis-path peak — ranking + summary display (night-report). */
    val peakDbfs: Double,
)

data class GarminMetric(
    val measurementType: String,
    val value: Double,
    val unit: String,
    val timestampMs: Long,
)

/**
 * Garmin's own view of this night, parsed report-time from the verbatim dailySleep raw payload
 * (docs/NIGHT_REPORT.md §Garmin). The window overlap makes the vendor/acoustic join honest:
 * the two devices may have measured substantially different windows, and the report must say
 * so instead of silently aligning them.
 */
data class GarminSleepSummary(
    /** ISO yyyy-MM-dd Garmin day (the wake date) the summary was read from. */
    val date: String,
    val sleepStartMs: Long,
    val sleepEndMs: Long,
    /** Garmin headline sleep score (0–100), null when the payload predates the parser fix. */
    val sleepScore: Double?,
    /** Overlap between the Garmin sleep window and the session's capture window. */
    val overlapWithCaptureMs: Long,
    val captureWindowStartMs: Long,
    val captureWindowEndMs: Long,
)

/** How completely this night is linked to Garmin data (drives a non-blocking banner). */
enum class GarminLinkStatus {
    /** Raw payload present — the Garmin card shows the sleep window + overlap. */
    LINKED,

    /** Day-window metrics imported, but the raw payload (and thus the window) is unavailable. */
    METRICS_ONLY,

    /** No Garmin data at all for this night. */
    MISSING,
}

/** Pure pick of the previous comparable night (COMPLETED/RECOVERED, strictly earlier). */
object PreviousNight {
    fun isEligible(session: SleepSessionEntity): Boolean =
        session.endedAt != null && (session.status == SessionStatus.COMPLETED ||
            session.status == SessionStatus.RECOVERED)

    /**
     * The latest eligible session strictly before [current]'s start; id tie-break keeps the
     * pick deterministic when two sessions share a start timestamp.
     */
    fun pick(sessions: List<SleepSessionEntity>, current: SleepSessionEntity): SleepSessionEntity? =
        sessions.asSequence()
            .filter { it.id != current.id && it.startedAt < current.startedAt }
            .filter(::isEligible)
            .sortedWith(compareByDescending<SleepSessionEntity> { it.startedAt }.thenByDescending { it.id })
            .firstOrNull()
}

/** The previous completed night, as its stored measurements recorded it — comparison input. */
data class PreviousNightSummary(
    val sessionId: Long,
    val startedAt: Long,
    val snoreSeconds: Long?,
    val sharePercent: Double?,
    val snoreScore: Int?,
    /** Score-formula version of the previous night's stored SNORE_SCORE row (see SnoreScore). */
    val scoreFormulaVersion: String?,
    /** Detector version of the previous night's snore events. */
    val detectorVersion: String?,
    /** True when BOTH nights' detector versions are known and equal — gate for time comparisons. */
    val sameDetectorVersion: Boolean,
    /** True when the previous score row used the current formula — gate for score comparison. */
    val sameScoreFormula: Boolean,
)

/** The night's longest single snore event (the "worst continuous spell" headline number). */
data class LongSnoreEvent(
    val startMs: Long,
    val durationMs: Long,
    val peakDbfs: Double,
)

/** How much of the night's snoring has retained audio (drives a non-blocking banner). */
data class ClipCoverage(
    /** Snoring spells (events) with a clip. */
    val snoreEventsWithClip: Int,
    val snoreEventCount: Int,
    /** Union snoring seconds with a clip / in total — overlap-aware (SnoreMath). */
    val coveredSeconds: Long,
    val totalSeconds: Long,
)

/** One vendor stage span (epoch ms) — the sleepLevels timeline of a linked session. */
data class StageSpan(val startMs: Long, val endMs: Long, val stage: String)

/** Which vendor stage a bout fell in, and how much of it that stage covered. */
data class BoutStage(val stage: String, val coveredFraction: Double)

/** Snoring time (union) that fell inside one vendor stage span. */
data class StageSnoreMinutes(val stage: String, val snoreMinutes: Long, val stageMinutes: Long)

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
    /**
     * The score's explainable decomposition (share points + intensity points, formula
     * [SnoreScore.FORMULA_VERSION]) — recomputed identically to the finalize-time measurement,
     * so the report can show why the score is what it is. Zero breakdown for a silent night.
     */
    val scoreBreakdown: SnoreScore.Result = SnoreScore.Result(0, 0.0, 0.0, 0.0),
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
    /**
     * OTHER_NOISE events hidden from this report for low confidence (< the configured threshold).
     * They stay in the database (labeling dataset, exports) — only the display filters them.
     */
    val lowConfidenceNoiseExcluded: Int = 0,
    /** When mic-live capture began (after a start delay); null for pre-v8 sessions. */
    val captureWindowStartMs: Long? = null,
    /** True when the share denominator is captured analysis time (false = time-in-bed fallback). */
    val shareUsesCapturedTime: Boolean = false,
    /**
     * The night's snoring as bursts and bouts (see [groupBouts]) at the ≤10 s gap — the
     * headline "worst stretch" numbers. Null without snore events.
     */
    val boutStats: BoutStats? = null,
    /** The same aggregation at the tighter ≤5 s gap (short-recovery chains split out). */
    val boutStats5s: BoutStats? = null,
    /** Quiet stretches ≥ [QUIET_GAP_MIN_MS] inside the capture window, chronological. */
    val quietGaps: List<QuietGap> = emptyList(),
    /** Garmin's own sleep window for this night, when the raw payload is retained. */
    val garminSleep: GarminSleepSummary? = null,
    /** How completely the night is linked to Garmin data (banner input). */
    val garminStatus: GarminLinkStatus = GarminLinkStatus.MISSING,
    /** The night's longest single snore event (null without snore events). */
    val longestSnore: LongSnoreEvent? = null,
    /**
     * Top retained clips, highest peak first then longest — the "hear the worst moments"
     * entry points. Subset of [clips] (same ClipRef instances).
     */
    val topClips: List<ClipRef> = emptyList(),
    /** How much of the night's snoring has audio behind it (banner input). */
    val clipCoverage: ClipCoverage? = null,
    /** The previous completed night's stored numbers, for the TL;DR comparison (version-gated). */
    val previousNight: PreviousNightSummary? = null,
    /** Vendor stage the longest snore bout fell in (null without a stage timeline). */
    val longestBoutStage: BoutStage? = null,
    /**
     * Snoring minutes per vendor stage (timeline present only), loudest-volume stage first —
     * the stage-level view of the night that the stage totals alone cannot give.
     */
    val snoreMinutesByStage: List<StageSnoreMinutes> = emptyList(),
    /**
     * The clip to play for the night's worst bout: the highest-peak clip-backed burst inside
     * the longest ≤10 s bout. Null when the bout has no clip-backed bursts (or no bouts).
     */
    val worstBoutClip: ClipRef? = null,
    /** Garmin stage totals in minutes, stage → minutes (empty without vendor stage rows). */
    val garminStageMinutes: Map<String, Long> = emptyMap(),
    /** Garmin's own sleep score promoted from the vendor rows (null when not imported). */
    val garminSleepScore: Double? = null,
)

object ReportAssembler {

    const val MAX_TIMELINE_EVENTS = 2_000

    /** How many clips the "hear the worst moments" section offers. */
    const val MAX_TOP_CLIPS = 8

    /**
     * OTHER_NOISE events below this confidence are display-filtered (report, counts, timeline,
     * episodes) — they sit at or under the ambient bed and are mostly mic-noise junk
     * (night Nov 5→6: 26 of 81 noise events). Kept in the DB for labeling/exports; SNORE
     * events are never filtered (score inputs must stay stable). The threshold is
     * user-configurable (UserPreferences.noiseDisplayMinConfidence — which reuses this
     * value as its default); 0 disables filtering.
     */
    const val DEFAULT_MIN_DISPLAYED_NOISE_CONFIDENCE = 0.5

    fun assemble(
        session: SleepSessionEntity,
        events: List<AcousticEventEntity>,
        clips: List<AudioClipEntity>,
        baseline: List<ClinicalBaselineEntity>,
        garmin: List<GarminMetric> = emptyList(),
        factors: List<FactorEntry> = emptyList(),
        capture: CaptureStats? = null,
        garminSleep: GarminSleepSummary? = null,
        previousNight: PreviousNightSummary? = null,
        garminStages: List<StageSpan> = emptyList(),
        noiseMinConfidence: Double = DEFAULT_MIN_DISPLAYED_NOISE_CONFIDENCE,
        /** Stored quiet gaps (schema v10) — replaces the report-time computation when present. */
        quietGapsOverride: List<QuietGap>? = null,
    ): MorningReport? {
        val endedAt = session.endedAt ?: return null
        val timeInBedSeconds = ((endedAt - session.startedAt).coerceAtLeast(0)) / 1000

        // Display filter first: everything downstream (counts, share, hourly, episodes,
        // timeline) sees the same event set, so the report stays internally consistent.
        // A threshold of 0 disables the filter.
        val visibleEvents = if (noiseMinConfidence > 0.0) {
            events.filterNot {
                it.type == EventType.OTHER_NOISE && it.confidence < noiseMinConfidence
            }
        } else {
            events
        }
        val excludedNoise = events.size - visibleEvents.size

        val snoreEvents = visibleEvents.filter { it.type == EventType.SNORE }
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
        // Score v2 weights intensity by per-tier snoring TIME (the per-tier unions partition
        // snoreSeconds) — identical math to the finalize-time measurement, so the report's
        // score always reproduces the stored one (docs/NIGHT_REPORT.md §Score).
        val tierSeconds: Map<String, Long> = snoreEvents.groupBy { it.intensityTier }
            .mapValues { (_, tierEvents) ->
                com.sleepanalysis.domain.scoring.SnoreMath
                    .unionDurationMs(tierEvents.map { it.startMs to it.endMs })
            }
        val score = SnoreScore.compute(
            snoreSeconds = snoreSeconds,
            nightSeconds = capturedSeconds,
            tierSeconds = tierSeconds,
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
            if (excludedNoise > 0) {
                add(
                    "$excludedNoise low-confidence noise event(s) hidden (confidence < " +
                        "$noiseMinConfidence) — kept in the database for labeling and exports.",
                )
            }
            if (visibleEvents.size >= MAX_TIMELINE_EVENTS) {
                add("Timeline truncated to the first $MAX_TIMELINE_EVENTS events.")
            }
        }

        val healthWarnings = capture.healthWarnings(endedAt, timeInBedSeconds)
        val capturedSecondsAvailable = capture?.capturedAnalysisMs()?.takeIf { it > 0 } != null
        val captureWindowStartMs = capture?.captureStartMs

        // Join clips to events via event.clipId — the authoritative link written at capture time
        // (audio_clip.eventId is back-filled, but old rows may predate that). Built from the
        // VISIBLE events so every display surface (episodes, top clips, playback map) sees the
        // same filtered set the integrity note promises (quiet gaps intentionally do not).
        val clipById = clips.associateBy { it.id }
        val clipRefsById: Map<Long, ClipRef> = visibleEvents.mapNotNull { event ->
            val clip = event.clipId?.let { clipById[it] } ?: return@mapNotNull null
            event.id to ClipRef(
                eventId = event.id,
                filePath = clip.filePath,
                offsetMs = event.offsetMs,
                durationMs = clip.durationMs,
                intensityTier = event.intensityTier,
                eventType = event.type,
                peakDbfs = event.peakDbfs,
            )
        }.toMap()
        // Episode grouping uses the FULL visible event list (not the truncated timeline) so the
        // loudest-first section never hides an episode behind MAX_TIMELINE_EVENTS.
        val episodes = loudestEpisodes(groupEpisodes(visibleEvents, clipRefsById))
        val bouts = groupBouts(snoreEvents)
        val bouts5s = groupBouts(snoreEvents, gapMs = 5_000L)
        // Quiet gaps mean "nothing was DETECTED" — computed on the unfiltered event list so a
        // span containing detected (display-hidden) low-confidence noise is never presented as
        // silence. Measured inside the CAPTURE window — never tap-to-stop, or a start delay
        // would present itself as one giant quiet gap (docs/NIGHT_REPORT.md).
        val gaps = quietGapsOverride ?: classifyQuietGaps(
            quietGaps(
                events,
                windowStartMs = captureWindowStartMs ?: session.startedAt,
                windowEndMs = endedAt,
            ),
            capture?.ambientBuckets.orEmpty(),
        )

        // "Hear the worst moments": the longest single spell and the top retained clips
        // (highest peak first, then longest — the loudness reserve makes the loudest events
        // the most likely to carry audio).
        val longestSnore = snoreEvents.maxByOrNull { it.durationMs }?.let {
            LongSnoreEvent(it.startMs, it.durationMs, it.peakDbfs)
        }
        val topClips = clipRefsById.values
            .sortedWith(
                compareByDescending<ClipRef> { it.peakDbfs }
                    .thenByDescending { it.durationMs }
                    .thenBy { it.eventId },
            )
            .take(MAX_TOP_CLIPS)
        val clipBacked = snoreEvents.filter { it.clipId != null }
        val clipCoverage = if (snoreEvents.isEmpty()) null else ClipCoverage(
            snoreEventsWithClip = clipBacked.size,
            snoreEventCount = snoreEvents.size,
            coveredSeconds = com.sleepanalysis.domain.scoring.SnoreMath
                .unionDurationMs(clipBacked.map { it.startMs to it.endMs }) / 1000,
            totalSeconds = snoreSeconds,
        )
        val garminStatus = when {
            garminSleep != null -> GarminLinkStatus.LINKED
            garmin.isNotEmpty() -> GarminLinkStatus.METRICS_ONLY
            else -> GarminLinkStatus.MISSING
        }

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
            snoreScore = score.score,
            scoreBreakdown = score,
            intensityBreakdown = tierCounts,
            eventCountsByType = visibleEvents.groupingBy { it.type }.eachCount(),
            hourly = hourlyBuckets(session, snoreEvents),
            peakDbfs = snoreEvents.maxOfOrNull { it.peakDbfs },
            meanSnoreDbfs = snoreEvents.map { it.meanDbfs }.takeIf { it.isNotEmpty() }?.average(),
            events = visibleEvents.take(MAX_TIMELINE_EVENTS),
            clips = visibleEvents.take(MAX_TIMELINE_EVENTS)
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
            lowConfidenceNoiseExcluded = excludedNoise,
            captureWindowStartMs = captureWindowStartMs,
            shareUsesCapturedTime = capturedSecondsAvailable,
            boutStats = boutStats(bouts),
            boutStats5s = boutStats(bouts5s),
            quietGaps = gaps,
            garminSleep = garminSleep,
            garminStatus = garminStatus,
            longestSnore = longestSnore,
            topClips = topClips,
            clipCoverage = clipCoverage,
            previousNight = previousNight,
            longestBoutStage = bouts.maxByOrNull { it.endMs - it.startMs }
                ?.let { longest -> stageForSpan(longest.startMs, longest.endMs, garminStages) },
            snoreMinutesByStage = snoreMinutesPerStage(snoreEvents, garminStages),
            worstBoutClip = worstBoutClip(bouts, clipRefsById),
            garminStageMinutes = garminStageMinutes(garmin),
            garminSleepScore = garminSleepScore(garmin),
        )
    }

    /**
     * The vendor stage covering the largest fraction of [spanStartMs, spanEndMs] — the
     * "which stage did the worst stretch fall in" answer. Overlap-aware across stage
     * boundaries (a bout straddling REM→LIGHT reports the majority stage); no timeline or
     * no overlap → null, never a guess.
     */
    fun stageForSpan(
        spanStartMs: Long,
        spanEndMs: Long,
        stages: List<StageSpan>,
    ): BoutStage? {
        if (spanEndMs <= spanStartMs || stages.isEmpty()) return null
        val spanMs = (spanEndMs - spanStartMs).coerceAtLeast(1)
        val overlaps = stages.groupingBy { it.stage }
            .fold(0L) { acc, span ->
                acc + (minOf(spanEndMs, span.endMs) - maxOf(spanStartMs, span.startMs)).coerceAtLeast(0)
            }
        val best = overlaps.maxByOrNull { it.value } ?: return null
        if (best.value <= 0) return null
        return BoutStage(best.key, best.value.toDouble() / spanMs)
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

    /**
     * Overlap between the Garmin sleep window and the session's capture window, clamped at 0
     * (a disjoint window — the "wrong night's data" signal — overlaps by exactly 0).
     */
    /**
     * Union snoring minutes inside each stage span of [stages] (bursts overlap-counted once
     * via SnoreMath; a burst straddling two stages contributes to both proportionally to its
     * overlap, so the per-stage sums can slightly exceed the night's union when timelines
     * switch mid-burst). Stages with no snoring are omitted; sorted by minutes, loudest first.
     * [StageSnoreMinutes.stageMinutes] carries each stage's total span length for context.
     */
    fun snoreMinutesPerStage(
        snoreEvents: List<AcousticEventEntity>,
        stages: List<StageSpan>,
    ): List<StageSnoreMinutes> {
        if (stages.isEmpty() || snoreEvents.isEmpty()) return emptyList()
        val perStage = stages.groupBy { it.stage }.map { (stage, spans) ->
            val clipped = buildList {
                for (span in spans) {
                    for (e in snoreEvents) {
                        val s = maxOf(e.startMs, span.startMs)
                        val e2 = minOf(e.endMs, span.endMs)
                        if (e2 > s) add(s to e2)
                    }
                }
            }
            val stageMs = spans.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
            Triple(stage, com.sleepanalysis.domain.scoring.SnoreMath.unionDurationMs(clipped), stageMs)
        }
        return perStage
            .map { StageSnoreMinutes(it.first, it.second / 60_000, it.third / 60_000) }
            .filter { it.snoreMinutes > 0 } // sub-minute stages dropped after rounding
            .sortedByDescending { it.snoreMinutes }
    }

    /**
     * Garmin stage totals (minutes) from the device-reported vendor rows — the compact
     * "Deep 97m · REM 64m · …" line next to the snoring minutes. Pure; unknown stage keys
     * are ignored.
     */
    fun garminStageMinutes(garmin: List<GarminMetric>): Map<String, Long> {
        val stageKeys = mapOf(
            "VENDOR_DEEP_SLEEP" to "Deep",
            "VENDOR_LIGHT_SLEEP" to "Light",
            "VENDOR_REM_SLEEP" to "REM",
            "VENDOR_AWAKE_SLEEP" to "Awake",
        )
        return garmin
            .filter { stageKeys.containsKey(it.measurementType) }
            .associate { stageKeys.getValue(it.measurementType) to (it.value / 60).toLong() }
    }

    /** The vendor headline sleep score from the metric rows; null when not imported. */
    fun garminSleepScore(garmin: List<GarminMetric>): Double? =
        garmin.firstOrNull { it.measurementType == "VENDOR_SLEEP_SCORE" }?.value

    /**
     * The clip to play for the night's worst (longest ≤10 s) bout: the highest-peak
     * clip-backed burst inside its span — "hear the worst bout" in one tap. Null when the
     * bout has no clip-backed bursts.
     */
    fun worstBoutClip(
        bouts: List<SnoreBout>,
        clipsById: Map<Long, ClipRef>,
    ): ClipRef? {
        val longest = bouts.maxByOrNull { it.endMs - it.startMs } ?: return null
        return longest.bursts
            .sortedByDescending { it.peakDbfs }
            .firstNotNullOfOrNull { clipsById[it.id] }
    }

    fun garminOverlapMs(
        captureStartMs: Long,
        captureEndMs: Long,
        sleepStartMs: Long,
        sleepEndMs: Long,
    ): Long =
        (minOf(captureEndMs, sleepEndMs) - maxOf(captureStartMs, sleepStartMs)).coerceAtLeast(0)

    private fun tierRank(tier: String): Int = when (tier) {
        SnoreScore.IntensityTiers.EPIC -> 4
        SnoreScore.IntensityTiers.LOUD -> 3
        SnoreScore.IntensityTiers.LIGHT -> 2
        else -> 1
    }
}
