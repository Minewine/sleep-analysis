package com.sleepanalysis.monitoring.detection

import com.sleepanalysis.data.db.entity.EventType

/**
 * Turns per-window detection booleans into discrete events: hysteresis (ENTER consecutive
 * active windows to open, EXIT consecutive quiet windows to close), minimum duration, and
 * merge of short gaps (docs/ARCHITECTURE.md §4, DetectorConfig).
 *
 * Pure logic on timestamps — unit tested. Timestamps are window END times in ms.
 *
 * Also aggregates the detector's decision inputs (SNR, low-band ratio, noise floor) per event
 * into [EventFeatures] so every stored event carries the numbers behind its classification.
 *
 * Relative level gate: the v0 detector's thresholds are relative to a rolling noise floor, so
 * in a silent room (floor ≈ mic self-noise, −92 dBFS) noise-floor rumble classifies as snore
 * and the low-band ratio saturates (~1.0, night 1 dogfood). A finalized SNORE event whose mean
 * level is below the gate — the event's own mean noise floor + [gateMarginDb], never below
 * [gateHardFloorDbfs] (DetectorConfig.gateThresholdDbfs) — is downgraded to OTHER_NOISE: it
 * stays in the database as an event but is never labeled snore and (via ClipPolicy) never
 * persists a clip. Relative to the floor, the gate self-calibrates across rooms/mic placements.
 *
 * ## Gap merging via deferred emission (docs/ARCHITECTURE.md §4 "merge gaps ≤ 2 s")
 *
 * Real snore trains arrive in breath groups separated by 1–2 s quiet gaps — shorter than the
 * 2 s exit hysteresis needs to be provably quiet, so the exit timeout shattered session 10 into
 * 1,306 events of which 69% were bare 2–3 s minimum-duration fragments. Merging therefore
 * happens on already-CLOSED drafts, with deferred emission so a returned draft never needs
 * amending (callers — AudioEngine's clip snapshot and DB row — see each event exactly once):
 *
 *  - A closed draft is NOT returned immediately; it is held as the deferred final for its
 *    FINAL (post-gate-downgrade) type. When the next same-type draft closes within
 *    [mergeGapMs] of the held final's end (draft.startMs − deferred.endMs ≤ mergeGapMs), the
 *    two fold into one held draft (peak = max, window-count-weighted means from the raw
 *    per-window sums, openWindows summed, start of the first preserved) instead of being
 *    emitted — so a whole train is delivered as one event.
 *  - Merge-before-discard: a closing draft shorter than [minDurationMs] folds into a
 *    within-gap same-type deferred final BEFORE the too-short discard applies; without a
 *    partner it is discarded exactly as before merging existed.
 *  - SNORE and OTHER_NOISE never merge with each other; a downgraded SNORE (final type
 *    OTHER_NOISE) merges with a prior OTHER_NOISE deferred final — the gate decision is the
 *    only thing that decides which bucket a draft merges into.
 *  - Emission timing: the held final is returned by [update] once the merge window has
 *    provably closed, and by [flush] at session end (a flush-closed draft still folds into a
 *    within-gap held final first).
 *
 *    "Provably closed" needs care: a successor draft only EXISTS at its close, which is at
 *    least (enterWindows − 1 + exitWindows) hops after its first pending window. With
 *    MERGE_GAP_MS (2 s) equal to EXIT_WINDOWS×HOP_MS (2 s), comparing the current timestamp
 *    against deferred.endMs + mergeGapMs alone would expire every held final one hop after
 *    its close — before any successor draft could exist — and no same-type merge would ever
 *    fire. The hold therefore lasts until BOTH machines are idle (no pending and no active
 *    windows — while either is busy a draft may still arrive and merge, checked at absorb
 *    time) AND the timestamp exceeds deferred.endMs + mergeGapMs + [windowMs] (the +windowMs
 *    accounts for event starts being backdated one window from the first pending stamp).
 *    With the defaults this emits ≤ ~3.5 s after the event's last active window — inside the
 *    live ring buffer's 8 s clip horizon (see AudioEngine.deliver).
 *
 *    The idle condition alone could pin a held final indefinitely behind an unrelated open
 *    event (a continuously-open snore spell suppressing the OTHER_NOISE bucket), so a hard
 *    hold cap applies independent of machine-busy: held finals are emitted once the timestamp
 *    exceeds deferred.endMs + [maxHoldMs] (7 s at defaults). A merging successor's draft
 *    closes at gap + successorSpan + exitSpan after the held final's end, so the cap covers
 *    every successor with span ≤ (exitWindows + 2) hops (3 s — the observed fragment span);
 *    longer downgrade-ambiguous successors are sacrificed by the cap rather than pinning the
 *    held final past the live ring buffer's 8 s clip horizon: emission happens ≤ endMs +
 *    maxHoldMs + one hop = 7.5 s after the tail, 0.5 s inside the ring (AudioEngine.deliver).
 *
 *    A later same-type draft that does not merge CAN arrive while its predecessor is still
 *    held — expiry uses the hard cap whenever EITHER machine is busy, so e.g. a single
 *    pending window in the other machine at the idle-bound stamp keeps the predecessor
 *    through the successor's own close. That case is handled, not sacrificed: the held
 *    final is emitted and the successor becomes the new deferred final (never dropped,
 *    never double-emitted).
 */
class EventDetector(
    private val enterWindows: Int = DetectorConfig.ENTER_WINDOWS,
    private val exitWindows: Int = DetectorConfig.EXIT_WINDOWS,
    private val minDurationMs: Long = DetectorConfig.MIN_DURATION_MS,
    private val mergeGapMs: Long = DetectorConfig.MERGE_GAP_MS,
    private val windowMs: Long = DetectorConfig.WINDOW_MS,
    private val hopMs: Long = DetectorConfig.HOP_MS,
    private val gateMarginDb: Double = DetectorConfig.GATE_MARGIN_DB,
    private val gateHardFloorDbfs: Double = DetectorConfig.MIN_GATE_DBFS,
) {

    /** A finalized acoustic event, not yet persisted. */
    data class EventDraft(
        val type: String,
        val startMs: Long,
        val endMs: Long,
        val peakDbfs: Double,
        val meanDbfs: Double,
        val intensityTier: String,
        val confidence: Double,
        /** The v0 detector's decision inputs for this event. */
        val features: EventFeatures,
    )

    fun reset() {
        snoreMachine = Machine(EventType.SNORE)
        loudMachine = Machine(EventType.OTHER_NOISE)
        deferred.clear()
        lastUpdateTsMs = null
    }

    /**
     * Feed one window. Snoring takes precedence: while a snore event is active, loud-event
     * shaping is suppressed so the same audio cannot produce overlapping events.
     *
     * Returned drafts are finalized events — including deferred finals whose merge window has
     * provably closed (see the class KDoc). A closing draft itself is held, not returned.
     */
    fun update(timestampMs: Long, features: AudioFeatures, result: DetectionResult): List<EventDraft> {
        val snr = features.windowDbfs - features.noiseFloorDbfs
        val finalized = mutableListOf<EventDraft>()
        // Observed stamp stride feeds the hold-cap slack (thermal throttling doubles it —
        // see [maxHoldMs]); the first window has no predecessor and assumes the normal hop.
        val strideMs = lastUpdateTsMs?.let { (timestampMs - it).coerceAtLeast(0L) } ?: hopMs
        lastUpdateTsMs = timestampMs
        expireDeferred(timestampMs, strideMs, finalized)
        if (result.snore && loudMachine.active) {
            loudMachine.close()?.let { draft ->
                deferOrFold(draft, requireNotNull(loudMachine.closedSums))?.let { finalized.add(it) }
            }
        }
        snoreMachine.step(timestampMs, result.snore, features, snr, result.confidence)
            ?.let { draft -> deferOrFold(draft, requireNotNull(snoreMachine.closedSums))?.let { finalized.add(it) } }
        val loudEligible = result.loudAcousticEvent && !result.snore && !snoreMachine.active
        loudMachine.step(timestampMs, loudEligible, features, snr, result.confidence)
            ?.let { draft -> deferOrFold(draft, requireNotNull(loudMachine.closedSums))?.let { finalized.add(it) } }
        return finalized
    }

    /**
     * Closes any events still open (session end) and emits everything: flush-closed drafts
     * fold into within-gap same-type deferred finals first, then every remaining deferred
     * final is emitted, chronologically by start.
     */
    fun flush(): List<EventDraft> {
        val out = mutableListOf<EventDraft>()
        snoreMachine.close()?.let { draft ->
            finalizeForFlush(draft, requireNotNull(snoreMachine.closedSums))?.let(out::add)
        }
        loudMachine.close()?.let { draft ->
            finalizeForFlush(draft, requireNotNull(loudMachine.closedSums))?.let(out::add)
        }
        deferred.values.sortedBy { it.draft.startMs }.forEach { out.add(it.draft) }
        deferred.clear()
        return out
    }

    /** A deferred final plus the raw per-window sums backing it for window-weighted re-aggregation. */
    private class Deferred(val draft: EventDraft, val sums: WindowSums)

    /**
     * Raw per-window accumulators of one closed event. Kept alongside the draft so a merge can
     * re-aggregate means over the true window counts instead of averaging two means (an
     * approximation that would overweight short fragments).
     */
    private class WindowSums(
        val windows: Int,
        val peakDbfs: Double,
        val sumDbfs: Double,
        val sumConfidence: Double,
        val snrPeakDb: Double,
        val snrSumDb: Double,
        val lowBandRatioPeak: Double,
        val lowBandRatioSum: Double,
        val noiseFloorDbfsSum: Double,
        val openWindows: Int,
    )

    /** Deferred finals by post-gate-downgrade type — at most one held final per type. */
    private val deferred = LinkedHashMap<String, Deferred>()

    /** Stamp of the previous [update] call — its delta is the observed stamp stride. */
    private var lastUpdateTsMs: Long? = null

    /**
     * Hard hold cap, independent of machine-busy: `mergeGapMs + (2·exitWindows + 2) × hopMs`
     * = 7 s at defaults. A merging successor's draft closes at `gap + successorSpan +
     * exitSpan` after the held final's end, so with gap ≤ [mergeGapMs] and successor span ≤
     * (exitWindows + 2) hops (3 s — the observed session-10 fragment span) the fold lands
     * at ≤ 7 s and survives the cap; longer successors are sacrificed rather than pinning
     * the held final behind a downgrade-ambiguous open event. Delivery stays 0.5 s inside
     * the live clip ring: emission at ≤ endMs + maxHoldMs + one hop = 7.5 s after the tail
     * (ring horizon 8 s — see AudioEngine.deliver; delivery is synchronous on the capture
     * thread, so the bound is exact).
     *
     * The "+ one hop" overshoot is the stride between consecutive [update] stamps. While the
     * engine thermally throttles, analyzed stamps advance 2×hopMs, so [expireDeferred] shrinks
     * the cap by the observed stride above [hopMs] — emission then still lands at ≤ endMs +
     * maxHoldMs + hopMs and the clip keeps the event's tail. At the normal hopMs stride the
     * slack is zero and the bound is exactly the historical maxHoldMs.
     */
    private val maxHoldMs = mergeGapMs + (2 * exitWindows + 2) * hopMs

    /**
     * Absorbs a just-closed draft: folds it into a within-gap same-type deferred final, or
     * holds it as the new deferred final. Returns a draft to emit NOW, or null when nothing
     * is due for emission yet.
     *
     * Merge-before-discard: a sub-[minDurationMs] draft survives only through folding into a
     * within-gap partner; with no partner it is discarded (the pre-merge behavior).
     *
     * Non-mergeable successor path: reachable under the current bounds — [expireDeferred]
     * applies the hard [maxHoldMs] cap (not the idle bound) whenever EITHER machine is busy,
     * so a successor closing after mergeGapMs can arrive with its predecessor still held
     * (e.g. one pending window in the other machine at the idle-bound stamp, or the
     * successor's own machine mid-hysteresis). The held final is removed and emitted here —
     * the map entry must never outlive its emission — and the successor is NOT dropped: it
     * becomes the new deferred final exactly as in the empty-map case, so its own emission
     * (by expiry or flush) still happens.
     */
    private fun deferOrFold(draft: EventDraft, sums: WindowSums): EventDraft? {
        val existing = deferred[draft.type]
        if (existing != null && draft.startMs - existing.draft.endMs <= mergeGapMs) {
            deferred[draft.type] = fold(existing, draft, sums)
            return null
        }
        if (existing != null) {
            deferred.remove(draft.type) // emitted below; never also by expiry (double emission)
            if (draft.endMs - draft.startMs >= minDurationMs) {
                deferred[draft.type] = Deferred(draft, sums)
            }
            return existing.draft
        }
        if (draft.endMs - draft.startMs >= minDurationMs) {
            deferred[draft.type] = Deferred(draft, sums)
        }
        return null
    }

    /** Flush path: fold a flush-closed draft into a within-gap held final and emit the merge now. */
    private fun finalizeForFlush(draft: EventDraft, sums: WindowSums): EventDraft? {
        val existing = deferred[draft.type]
        return when {
            existing != null && draft.startMs - existing.draft.endMs <= mergeGapMs -> {
                deferred.remove(draft.type)
                fold(existing, draft, sums).draft
            }
            draft.endMs - draft.startMs >= minDurationMs -> draft
            else -> null // too short and no merge partner: discarded as before merging existed
        }
    }

    /**
     * Emits held finals whose merge window has provably closed. While a machine is busy a
     * draft may still arrive and merge (checked at absorb time), so the idle bound
     * (endMs + mergeGapMs + windowMs) is only applied when both machines are idle; the hard
     * [maxHoldMs] cap applies regardless, so an unrelated continuously-open event can never
     * pin a held final past the clip ring horizon (see the class KDoc for both bounds).
     *
     * The cap is stride-aware: emission overshoots the bound by at most one stamp stride, so
     * a stride above [hopMs] (thermal throttling halves the analysis rate) shrinks the cap by
     * the difference ([strideSlackMs]) and emission stays ≤ endMs + maxHoldMs + hopMs — 0.5 s
     * inside the 8 s clip ring. At the normal stride the slack is 0 and the bound is exactly
     * the historical maxHoldMs.
     */
    private fun expireDeferred(timestampMs: Long, strideMs: Long, out: MutableList<EventDraft>) {
        val bound =
            if (snoreMachine.busy || loudMachine.busy) maxHoldMs - strideSlackMs(strideMs) else mergeGapMs + windowMs
        val expirable = deferred.entries.filter { timestampMs > it.value.draft.endMs + bound }
        expirable.sortedBy { it.value.draft.startMs }.forEach {
            out.add(it.value.draft)
            deferred.remove(it.key)
        }
    }

    /** Cap slack for an observed stamp stride; zero at or below [hopMs], growing 1:1 above it. */
    private fun strideSlackMs(strideMs: Long): Long = (strideMs - hopMs).coerceAtLeast(0L)

    /**
     * Folds [incoming] into the held final: start of the first preserved, end = max, peak =
     * max, means and confidence window-weighted over the true per-window sums, EventFeatures
     * aggregated (openWindows summed, feature peaks maxed, closeWindows taken from the most
     * recent close — the observed quiet count that actually ended the merged event). The
     * intensity tier is recomputed from the merged peak.
     */
    private fun fold(target: Deferred, incoming: EventDraft, incomingSums: WindowSums): Deferred {
        val a = target.draft
        val sA = target.sums
        val sB = incomingSums
        val windows = sA.windows + sB.windows
        val peakDbfs = maxOf(a.peakDbfs, incoming.peakDbfs)
        val sumDbfs = sA.sumDbfs + sB.sumDbfs
        val sumConfidence = sA.sumConfidence + sB.sumConfidence
        val snrPeakDb = maxOf(sA.snrPeakDb, sB.snrPeakDb)
        val snrSumDb = sA.snrSumDb + sB.snrSumDb
        val ratioPeak = maxOf(sA.lowBandRatioPeak, sB.lowBandRatioPeak)
        val ratioSum = sA.lowBandRatioSum + sB.lowBandRatioSum
        val floorSum = sA.noiseFloorDbfsSum + sB.noiseFloorDbfsSum
        val fA = a.features
        val fB = incoming.features
        val openWindows = fA.openWindows + fB.openWindows
        val merged = EventDraft(
            type = incoming.type,
            startMs = a.startMs,
            endMs = maxOf(a.endMs, incoming.endMs),
            peakDbfs = peakDbfs,
            meanDbfs = if (windows > 0) sumDbfs / windows else a.meanDbfs, // windows ≥ 1 whenever active
            intensityTier = DetectorConfig.intensityTier(peakDbfs),
            confidence = if (windows > 0) sumConfidence / windows else a.confidence,
            features = EventFeatures(
                enterWindows = fB.enterWindows,
                exitWindows = fB.exitWindows,
                openWindows = openWindows,
                closeWindows = fB.closeWindows,
                snrPeakDb = snrPeakDb,
                snrMeanDb = if (windows > 0) snrSumDb / windows else fA.snrMeanDb,
                lowBandRatioPeak = ratioPeak,
                lowBandRatioMean = if (windows > 0) ratioSum / windows else fA.lowBandRatioMean,
                noiseFloorDbfsMean = if (windows > 0) floorSum / windows else fA.noiseFloorDbfsMean,
            ),
        )
        return Deferred(
            merged,
            WindowSums(windows, peakDbfs, sumDbfs, sumConfidence, snrPeakDb, snrSumDb, ratioPeak, ratioSum, floorSum, openWindows),
        )
    }

    private inner class Machine(val type: String) {
        var pending = 0
        var quietWindows = 0
        var active = false
        var firstPendingTs = 0L
        var lastActiveTs = 0L
        var startMs = 0L
        var peak = Double.NEGATIVE_INFINITY
        var sumDbfs = 0.0
        var sumConfidence = 0.0
        var windows = 0
        /** Pending count when this event opened (= enterWindows; recorded for retuning analysis). */
        var openWindows = 0

        // Feature accumulation over the event's active windows.
        var snrPeak = Double.NEGATIVE_INFINITY
        var snrSum = 0.0
        var ratioPeak = 0.0
        var ratioSum = 0.0
        var floorSum = 0.0

        /**
         * Raw sums of the event closed by the most recent [close] call — read (via the
         * detector) right after a non-null close to merge the draft into a deferred final.
         */
        var closedSums: WindowSums? = null

        /** True while a draft may still be produced (event open or hysteresis building). */
        val busy: Boolean get() = pending > 0 || active

        fun step(
            ts: Long,
            activeFlag: Boolean,
            features: AudioFeatures,
            snr: Double,
            confidence: Double,
        ): EventDraft? {
            if (activeFlag) {
                quietWindows = 0
                if (!active) {
                    if (pending == 0) firstPendingTs = ts
                    pending++
                    if (pending >= enterWindows) {
                        active = true
                        openWindows = pending
                        startMs = firstPendingTs - windowMs
                        peak = Double.NEGATIVE_INFINITY
                        sumDbfs = 0.0
                        sumConfidence = 0.0
                        windows = 0
                        snrPeak = Double.NEGATIVE_INFINITY
                        snrSum = 0.0
                        ratioPeak = 0.0
                        ratioSum = 0.0
                        floorSum = 0.0
                    }
                }
                if (active) {
                    lastActiveTs = ts
                    peak = maxOf(peak, features.windowDbfs)
                    sumDbfs += features.windowDbfs
                    sumConfidence += confidence
                    windows++
                    snrPeak = maxOf(snrPeak, snr)
                    snrSum += snr
                    ratioPeak = maxOf(ratioPeak, features.lowBandRatio)
                    ratioSum += features.lowBandRatio
                    floorSum += features.noiseFloorDbfs
                }
                return null
            }

            pending = 0
            if (active) {
                quietWindows++
                if (quietWindows >= exitWindows) return close()
            }
            return null
        }

        /**
         * Closes the open event and returns its draft (never the empty min-duration filter —
         * that decision moved to the merge-aware absorb paths, where a too-short draft can
         * still fold into a within-gap deferred final before being discarded).
         */
        fun close(): EventDraft? {
            if (!active) return null
            active = false
            val endMs = lastActiveTs
            val meanDbfs = if (windows > 0) sumDbfs / windows else -100.0 // unreachable fallback; keep below the gate
            // Below the relative gate this is noise-floor rumble, not snoring — the SNORE
            // label only means something for clearly audible events (see class KDoc).
            val floorMean = if (windows > 0) floorSum / windows else 0.0
            val gateThreshold =
                DetectorConfig.gateThresholdDbfs(floorMean, gateMarginDb, gateHardFloorDbfs)
            val effectiveType =
                if (type == EventType.SNORE && meanDbfs < gateThreshold) EventType.OTHER_NOISE else type
            closedSums = WindowSums(
                windows = windows,
                peakDbfs = peak,
                sumDbfs = sumDbfs,
                sumConfidence = sumConfidence,
                snrPeakDb = snrPeak,
                snrSumDb = snrSum,
                lowBandRatioPeak = ratioPeak,
                lowBandRatioSum = ratioSum,
                noiseFloorDbfsSum = floorSum,
                openWindows = openWindows,
            )
            return EventDraft(
                type = effectiveType,
                startMs = startMs,
                endMs = endMs,
                peakDbfs = peak,
                meanDbfs = meanDbfs,
                intensityTier = DetectorConfig.intensityTier(peak),
                confidence = if (windows > 0) sumConfidence / windows else 0.0,
                features = EventFeatures(
                    enterWindows = enterWindows,
                    exitWindows = exitWindows,
                    openWindows = openWindows,
                    closeWindows = quietWindows,
                    snrPeakDb = snrPeak,
                    snrMeanDb = if (windows > 0) snrSum / windows else 0.0,
                    lowBandRatioPeak = ratioPeak,
                    lowBandRatioMean = if (windows > 0) ratioSum / windows else 0.0,
                    noiseFloorDbfsMean = floorMean,
                ),
            )
        }
    }

    private var snoreMachine = Machine(EventType.SNORE)
    private var loudMachine = Machine(EventType.OTHER_NOISE)
}
