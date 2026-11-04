package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.monitoring.detection.DetectorConfig

/**
 * Decides whether a finalized event may persist an audio clip. Pure logic — unit tested.
 *
 * Gates and reserves (night 1–3 dogfood fixes; gate 1 self-calibrating since night 2):
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
 *     bypass both budgets (WRITE_LOUD_RESERVE) while a small nightly reserve lasts — the level
 *     gate (1) still applies, so momentary peaks inside rumble earn nothing. Reserve writes
 *     also fill the hourly bucket and nightly total, keeping the accounting truthful.
 *  3. Per-hour budget: at most [maxPerHour] clips in any absolute epoch hour, so a noisy
 *     bedtime/settling burst cannot starve the actual sleep window (night 1 wrote all 50
 *     clips in the first 42 minutes). Buckets are UTC-aligned, so a session straddling the
 *     top of an hour can spend two budgets minutes apart — acceptable for budget fairness.
 *  4. Nightly backstop: [maxTotal] per session overall. [initialTotal] counts clips already
 *     persisted by earlier engine runs of the same session (START_STICKY restart).
 *
 * Budget state lives per engine run; a restart re-seeds the nightly total but not the hourly
 * one — acceptable because the level gate (1) already suppresses the noise-floor junk that
 * made hourly exhaustion matter.
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
    private val settlingMinutes: Long = DetectorConfig.SETTLING_MINUTES,
    private val settlingExtraDb: Double = DetectorConfig.SETTLING_EXTRA_DB,
    initialTotal: Int = 0,
) {

    /** Why an event's clip was allowed or dropped (see class KDoc). */
    enum class Decision { WRITE, WRITE_LOUD_RESERVE, SKIP_LEVEL, SKIP_BUDGET }

    /** Total clips allowed so far, including the inherited count. @Volatile: read by stats(). */
    @Volatile
    var totalWritten: Int = initialTotal
        private set

    /** Clips granted through the loudness reserve so far. Capture-thread-only. */
    var loudWritten: Int = 0
        private set

    private val perHour = HashMap<Long, Int>()

    /**
     * The gate decision for an event with mean level [meanDbfs] and peak [peakDbfs] over a
     * rolling floor of [noiseFloorDbfs], starting at [eventStartEpochMs].
     * [captureStartEpochMs] anchors the settling window (0 = unknown, settling disabled).
     */
    fun decide(
        meanDbfs: Double,
        noiseFloorDbfs: Double,
        peakDbfs: Double,
        eventStartEpochMs: Long,
        captureStartEpochMs: Long,
    ): Decision {
        val settling = captureStartEpochMs > 0 &&
            eventStartEpochMs < captureStartEpochMs + settlingMinutes * 60_000L
        val margin = gateMarginDb + if (settling) settlingExtraDb else 0.0
        val threshold = DetectorConfig.gateThresholdDbfs(noiseFloorDbfs, margin, gateHardFloorDbfs)
        return when {
            meanDbfs < threshold -> Decision.SKIP_LEVEL
            peakDbfs >= loudClipPeakDbfs && loudWritten < loudReservePerNight ->
                Decision.WRITE_LOUD_RESERVE
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
    ): Boolean {
        val decision = decide(meanDbfs, noiseFloorDbfs, peakDbfs, eventStartEpochMs, captureStartEpochMs)
        return decision == Decision.WRITE || decision == Decision.WRITE_LOUD_RESERVE
    }

    /**
     * Records that a clip was persisted for an event starting at [eventStartEpochMs].
     * [viaLoudReserve] must mirror the [Decision.WRITE_LOUD_RESERVE] grant — it books the
     * write against the nightly reserve (and the hourly bucket/total either way).
     */
    fun onWritten(peakDbfs: Double, eventStartEpochMs: Long, viaLoudReserve: Boolean = false) {
        totalWritten++
        if (viaLoudReserve) loudWritten++
        val bucket = hourBucket(eventStartEpochMs)
        perHour[bucket] = (perHour[bucket] ?: 0) + 1
    }

    private fun hourBucket(epochMs: Long): Long = epochMs / HOUR_MS

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}
