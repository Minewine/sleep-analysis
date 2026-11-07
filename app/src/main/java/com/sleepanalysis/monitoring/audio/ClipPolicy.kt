package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.data.db.entity.EventType
import com.sleepanalysis.monitoring.detection.DetectorConfig

/**
 * Decides whether a finalized event may persist an audio clip. Pure logic — unit tested.
 *
 * Gates and reserves (night 1–4 dogfood fixes; gate 1 self-calibrating since night 2):
 *  1. Relative level gate: events quieter than the rolling noise floor + [gateMarginDb] sit at
 *     or under the ambient bed (unaudible, unverifiable by clip playback) and never get a clip.
 *     [DetectorConfig.MIN_GATE_DBFS] caps how low the threshold falls, so mic-self-noise junk
 *     in a dead-quiet room is still gated. During the first [settlingMinutes] after capture
 *     begins the margin is stricter by [settlingExtraDb] — bedtime noise is the night's loudest
 *     ambient period, and the start delay only removes most of it. The window anchors to the
 *     engine's capture start, so it also re-arms for the first 30 min after a START_STICKY
 *     resume — deliberate: the device was just handled and is running hot.
 *  2. Loudness reserve: a first-come-first-served budget starves exactly the events worth
 *     hearing (night 3's loudest snore, peak −16.6 dBFS, missed its clip because the hour's 8
 *     were already spent). Events whose PEAK clears [DetectorConfig.LOUD_CLIP_PEAK_DBFS]
 *     bypass every other gate (WRITE_LOUD_RESERVE) while a small nightly reserve lasts — the
 *     level gate (1) still applies, so momentary peaks inside rumble earn nothing. Reserve
 *     writes also fill the hourly bucket and nightly total, keeping the accounting truthful.
 *  3. Bout cooldown (night 4: 1,048 snore bursts produced only 60 clips — one 04:14–04:25 bout
 *     ate ~15 of the 50-clip budget on indistinguishable 2.5 s bursts and coverage fell to 4%
 *     of snore time): after a clip is written for an event type, same-type events whose START
 *     falls within [boutCooldownMs] of that write's end are skipped (SKIP_COOLDOWN — its own
 *     decision value so the session log can distinguish cooldown suppression from budget
 *     exhaustion) — unless
 *     they are an upgrade, peaking at least [upgradeDb] over the written clip's peak, which
 *     writes (within the nightly/hourly caps) and re-anchors the cooldown with its own peak.
 *     A bout therefore costs ~1 clip per cooldown window plus one clip per intensity step:
 *     the night's worst moments are guaranteed playable audio and the budget spreads across
 *     the night instead of being eaten by one train. Reserve-eligible events (2) bypass the
 *     cooldown and re-anchor it too. State is two values per type — streaming-friendly, no
 *     whole-night buffering.
 *  4. Per-hour budget: at most [maxPerHour] clips in any absolute epoch hour, so a noisy
 *     bedtime/settling burst cannot starve the actual sleep window (night 1 wrote all 50
 *     clips in the first 42 minutes). Buckets are UTC-aligned, so a session straddling the
 *     top of an hour can spend two budgets minutes apart — acceptable for budget fairness.
 *  5. Nightly backstop: [maxTotal] per session overall. [initialTotal] counts clips already
 *     persisted by earlier engine runs of the same session (START_STICKY restart).
 *
 * Budget state lives per engine run (the policy is constructed per engine and never reset);
 * a restart re-seeds the nightly total but not the hourly one — acceptable because the level
 * gate (1) already suppresses the noise-floor junk that made hourly exhaustion matter. A
 * restart also clears the cooldown anchors (3), so the first post-restart burst of an ongoing
 * bout writes again and re-anchors — at worst one extra clip per interruption.
 *
 * Single-writer contract: [decide]/[onWritten] must be called from the capture thread only;
 * [totalWritten] is @Volatile so stats() can read it from the service heartbeat.
 */
class ClipPolicy(
    private val gateMarginDb: Double = DetectorConfig.GATE_MARGIN_DB,
    private val gateHardFloorDbfs: Double = DetectorConfig.MIN_GATE_DBFS,
    private val maxPerHour: Int = DetectorConfig.MAX_CLIPS_PER_HOUR,
    private val maxTotal: Int = DetectorConfig.MAX_CLIPS_PER_NIGHT,
    private val loudClipPeakDbfs: Double = DetectorConfig.LOUD_CLIP_PEAK_DBFS,
    private val loudReservePerNight: Int = DetectorConfig.LOUD_CLIP_RESERVE_PER_NIGHT,
    private val boutCooldownMs: Long = DetectorConfig.BOUT_COOLDOWN_MS,
    private val upgradeDb: Double = DetectorConfig.CLIP_UPGRADE_DB,
    private val settlingMinutes: Long = DetectorConfig.SETTLING_MINUTES,
    private val settlingExtraDb: Double = DetectorConfig.SETTLING_EXTRA_DB,
    initialTotal: Int = 0,
) {

    /** Why an event's clip was allowed or dropped (see class KDoc). */
    enum class Decision { WRITE, WRITE_LOUD_RESERVE, SKIP_LEVEL, SKIP_BUDGET, SKIP_COOLDOWN }

    /** Total clips allowed so far, including the inherited count. @Volatile: read by stats(). */
    @Volatile
    var totalWritten: Int = initialTotal
        private set

    /** Clips granted through the loudness reserve so far. Capture-thread-only. */
    var loudWritten: Int = 0
        private set

    private val perHour = HashMap<Long, Int>()

    /** Bout-cooldown anchors per event type: the last written clip's end (epoch ms) and peak. */
    private val lastWriteEndMsByType = HashMap<String, Long>()
    private val lastWritePeakDbfsByType = HashMap<String, Double>()

    /**
     * The gate decision for an event of [type] with mean level [meanDbfs] and peak [peakDbfs]
     * over a rolling floor of [noiseFloorDbfs], starting at [eventStartEpochMs].
     * [captureStartEpochMs] anchors the settling window (0 = unknown, settling disabled).
     * [type] keys the bout cooldown (gate 3): anchors are per event type, so a COUGH train
     * neither extends nor is suppressed by a SNORE train's cooldown. Defaults to
     * [EventType.SNORE] — the type the detector emits by far the most.
     */
    fun decide(
        meanDbfs: Double,
        noiseFloorDbfs: Double,
        peakDbfs: Double,
        eventStartEpochMs: Long,
        captureStartEpochMs: Long,
        type: String = EventType.SNORE,
    ): Decision {
        val settling = captureStartEpochMs > 0 &&
            eventStartEpochMs < captureStartEpochMs + settlingMinutes * 60_000L
        val margin = gateMarginDb + if (settling) settlingExtraDb else 0.0
        val threshold = DetectorConfig.gateThresholdDbfs(noiseFloorDbfs, margin, gateHardFloorDbfs)
        val lastWriteEndMs = lastWriteEndMsByType[type]
        val lastWritePeakDbfs = lastWritePeakDbfsByType[type]
        val inCooldown = lastWriteEndMs != null &&
            eventStartEpochMs < lastWriteEndMs + boutCooldownMs
        val upgrade = lastWritePeakDbfs != null && peakDbfs >= lastWritePeakDbfs + upgradeDb
        return when {
            meanDbfs < threshold -> Decision.SKIP_LEVEL
            peakDbfs >= loudClipPeakDbfs && loudWritten < loudReservePerNight ->
                Decision.WRITE_LOUD_RESERVE
            inCooldown && !upgrade -> Decision.SKIP_COOLDOWN
            totalWritten >= maxTotal -> Decision.SKIP_BUDGET
            (perHour[hourBucket(eventStartEpochMs)] ?: 0) >= maxPerHour -> Decision.SKIP_BUDGET
            else -> Decision.WRITE
        }
    }

    /** Convenience form of [decide] for callers that only need a yes/no. */
    fun shouldWrite(
        meanDbfs: Double,
        noiseFloorDbfs: Double,
        peakDbfs: Double,
        eventStartEpochMs: Long,
        captureStartEpochMs: Long,
        type: String = EventType.SNORE,
    ): Boolean {
        val decision =
            decide(meanDbfs, noiseFloorDbfs, peakDbfs, eventStartEpochMs, captureStartEpochMs, type)
        return decision == Decision.WRITE || decision == Decision.WRITE_LOUD_RESERVE
    }

    /**
     * Records that a clip was persisted for an event of [type] starting at [eventStartEpochMs]
     * and ending at [eventEndEpochMs] (defaults to the start — pass the real end so the bout
     * cooldown counts from the clip's actual tail). [viaLoudReserve] must mirror the
     * [Decision.WRITE_LOUD_RESERVE] grant — it books the write against the nightly reserve
     * (and the hourly bucket/total either way). Every write also becomes the type's
     * bout-cooldown anchor (end + peak, gate 3): same-type events starting within
     * [boutCooldownMs] of [eventEndEpochMs] skip unless they upgrade over [peakDbfs].
     */
    fun onWritten(
        peakDbfs: Double,
        eventStartEpochMs: Long,
        viaLoudReserve: Boolean = false,
        type: String = EventType.SNORE,
        eventEndEpochMs: Long = eventStartEpochMs,
    ) {
        totalWritten++
        if (viaLoudReserve) loudWritten++
        val bucket = hourBucket(eventStartEpochMs)
        perHour[bucket] = (perHour[bucket] ?: 0) + 1
        lastWriteEndMsByType[type] = eventEndEpochMs
        lastWritePeakDbfsByType[type] = peakDbfs
    }

    private fun hourBucket(epochMs: Long): Long = epochMs / HOUR_MS

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}
