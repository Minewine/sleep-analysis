package com.sleepanalysis.monitoring.eval

import com.sleepanalysis.monitoring.audio.WavReader
import com.sleepanalysis.monitoring.detection.DetectorConfig
import com.sleepanalysis.monitoring.detection.SnoreDetector
import com.sleepanalysis.monitoring.replay.ReplayEngine
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scores a labeled clip set (the fixture eval harness, docs/NEXT_STEPS.md P2) against the
 * current [SnoreDetector]: every clip is replayed offline through [ReplayEngine] — the same
 * window math the live loop runs — and the per-window snore calls are reduced to one
 * clip-level decision ("snore-positive": the snore-window ratio reaches
 * [clipPositiveThreshold]) so per-class precision/recall can be computed against the user
 * labels stored on events (docs/DATA_MODEL.md §3.5). docs/ARCHITECTURE.md §9: thresholds are
 * asserted on release candidates before any detector change ships.
 *
 * Labels are one-vs-rest: for each label L, clips the user labeled L are the positive class
 * and every other LABELED clip is negative — a clip labeled SNORE is positive ground truth
 * for the SNORE row, a clip labeled COUGH counts against every row it isn't. Unlabeled clips
 * are still replayed and scored (useful for eyeballing) but excluded from metrics.
 *
 * Pure JVM — no Android imports, unit tested with synthesized clips.
 */
class FixtureEvaluator {

    data class FixtureEntry(
        val file: File,
        val eventType: String,
        /** SNORE / COUGH / SPEECH / MOVEMENT / OTHER_NOT_SNORE, or null when unlabeled. */
        val userLabel: String?,
    )

    data class ClipScore(
        val file: File,
        val userLabel: String?,
        val eventType: String,
        val windowCount: Int,
        val snoreWindows: Int,
        val snoreRatio: Double,
        val snorePositive: Boolean,
        /** Events the detector finalized on this clip (after hysteresis/min-duration/merging). */
        val eventCount: Int,
        /** Mean event duration in ms; 0 when the clip produced no events. */
        val meanEventDurationMs: Long,
    )

    /** One-vs-rest confusion counts for [label]; precision/recall are null when undefined. */
    data class ClassMetrics(
        val label: String,
        val clips: Int,
        val truePositive: Int,
        val falsePositive: Int,
        val trueNegative: Int,
        val falseNegative: Int,
    ) {
        fun precision(): Double? =
            if (truePositive + falsePositive == 0) null else truePositive.toDouble() / (truePositive + falsePositive)

        fun recall(): Double? =
            if (truePositive + falseNegative == 0) null else truePositive.toDouble() / (truePositive + falseNegative)
    }

    data class EvalReport(
        val detectorVersion: String,
        val clipPositiveThreshold: Double,
        val scores: List<ClipScore>,
        val metrics: List<ClassMetrics>,
        val unlabeledClips: Int,
        /**
         * Existing clips that were not scored: decode failures + rate mismatches. Kept as the
         * aggregate for backward compatibility; the breakdown is in [decodeFailures] and
         * [rateMismatches]. Entries whose file is missing entirely are counted separately in
         * [missingFiles] (they were never candidates for scoring).
         */
        val skippedClips: Int = 0,
        /** Manifest entries whose fixture file is absent — nothing was decoded or scored. */
        val missingFiles: Int = 0,
        /** Existing clips that failed to decode (corrupt/truncated WAV). */
        val decodeFailures: Int = 0,
        /** Decodable clips skipped because their sample rate is not [DetectorConfig.SAMPLE_RATE]. */
        val rateMismatches: Int = 0,
    )

    fun evaluate(
        entries: List<FixtureEntry>,
        detector: SnoreDetector,
        clipPositiveThreshold: Double = 0.5,
        initialFloorDbfs: Double? = null,
    ): EvalReport {
        val replay = ReplayEngine(detector, ReplayEngine.Config(initialFloorDbfs = initialFloorDbfs))
        val scores = mutableListOf<ClipScore>()
        var unlabeled = 0
        var missingFiles = 0
        var decodeFailures = 0
        var rateMismatches = 0
        for (entry in entries) {
            if (!entry.file.exists()) {
                missingFiles++ // fixture pulled without its clip; nothing to score
                continue
            }
            val wav = try {
                WavReader.readPcm16Mono(entry.file)
            } catch (_: IllegalArgumentException) {
                decodeFailures++
                continue
            }
            if (wav.sampleRate != DetectorConfig.SAMPLE_RATE) {
                rateMismatches++ // only clips the replay can reproduce the live math for are scored
                continue
            }
            // A clip must be scored with the detector in its session-start state, exactly as
            // the live pipeline sees a fresh recording — state must not leak across clips.
            detector.reset()
            val result = replay.replay(wav.samples)
            val snoreWindows = result.windows.count { it.snore }
            val ratio = if (result.windows.isEmpty()) 0.0 else snoreWindows.toDouble() / result.windows.size
            // Event-shaping diagnostics: how fragmented the detector's output is on this clip.
            val events = result.events
            val meanEventDurationMs =
                if (events.isEmpty()) 0L else events.sumOf { it.endMs - it.startMs } / events.size
            scores.add(
                ClipScore(
                    file = entry.file,
                    userLabel = entry.userLabel,
                    eventType = entry.eventType,
                    windowCount = result.windows.size,
                    snoreWindows = snoreWindows,
                    snoreRatio = ratio,
                    snorePositive = ratio >= clipPositiveThreshold,
                    eventCount = events.size,
                    meanEventDurationMs = meanEventDurationMs,
                )
            )
            if (entry.userLabel == null) unlabeled++
        }

        val labeled = scores.filter { it.userLabel != null }
        val metrics = labeled.map { it.userLabel!! }.distinct().sorted().map { label ->
            val positives = labeled.filter { it.userLabel == label }
            val negatives = labeled.filter { it.userLabel != label }
            ClassMetrics(
                label = label,
                clips = positives.size,
                truePositive = positives.count { it.snorePositive },
                falsePositive = negatives.count { it.snorePositive },
                trueNegative = negatives.count { !it.snorePositive },
                falseNegative = positives.count { !it.snorePositive },
            )
        }
        return EvalReport(
            detectorVersion = DETECTOR_VERSION,
            clipPositiveThreshold = clipPositiveThreshold,
            scores = scores,
            metrics = metrics,
            unlabeledClips = unlabeled,
            skippedClips = decodeFailures + rateMismatches,
            missingFiles = missingFiles,
            decodeFailures = decodeFailures,
            rateMismatches = rateMismatches,
        )
    }

    /** JSON form of [report] — kept separate from [writeJson] so it can be round-trip tested. */
    fun toJson(report: EvalReport): JSONObject = JSONObject().apply {
        put("detector_version", report.detectorVersion)
        put("clip_positive_threshold", report.clipPositiveThreshold)
        put("unlabeled_clips", report.unlabeledClips)
        put("skipped_clips", report.skippedClips)
        put("missing_files", report.missingFiles)
        put("decode_failures", report.decodeFailures)
        put("rate_mismatches", report.rateMismatches)
        put(
            "scores",
            JSONArray().apply {
                for (s in report.scores) {
                    put(
                        JSONObject().apply {
                            put("clip", s.file.name)
                            put("event_type", s.eventType)
                            put("user_label", s.userLabel ?: JSONObject.NULL)
                            put("window_count", s.windowCount)
                            put("snore_windows", s.snoreWindows)
                            put("snore_ratio", s.snoreRatio)
                            put("snore_positive", s.snorePositive)
                            put("event_count", s.eventCount)
                            put("mean_event_duration_ms", s.meanEventDurationMs)
                        }
                    )
                }
            },
        )
        put(
            "metrics",
            JSONArray().apply {
                for (m in report.metrics) {
                    put(
                        JSONObject().apply {
                            put("label", m.label)
                            put("clips", m.clips)
                            put("tp", m.truePositive)
                            put("fp", m.falsePositive)
                            put("tn", m.trueNegative)
                            put("fn", m.falseNegative)
                            put("precision", m.precision() ?: JSONObject.NULL)
                            put("recall", m.recall() ?: JSONObject.NULL)
                        }
                    )
                }
            },
        )
    }

    fun writeJson(report: EvalReport, file: File) {
        file.parentFile?.mkdirs()
        // writeText, not PrintWriter(File, Charset): that ctor is API 33+ (min is 30).
        file.writeText(toJson(report).toString(2), Charsets.UTF_8)
    }

    /** Compact per-label table for stdout (the `evalFixtures` task / manual runs). */
    fun summaryText(report: EvalReport): String {
        val lines = mutableListOf(
            "detector=${report.detectorVersion} threshold=${report.clipPositiveThreshold} " +
                "clips=${report.scores.size} unlabeled=${report.unlabeledClips} skipped=${report.skippedClips} " +
                "missing=${report.missingFiles} decode_failed=${report.decodeFailures} " +
                "rate_mismatch=${report.rateMismatches}",
            String.format("%-18s %6s %4s %4s %4s %4s %8s %8s", "label", "clips", "tp", "fp", "tn", "fn", "prec", "rec"),
        )
        for (m in report.metrics) {
            val prec = m.precision()?.let { String.format("%.2f", it) } ?: "-"
            val rec = m.recall()?.let { String.format("%.2f", it) } ?: "-"
            lines.add(
                String.format(
                    "%-18s %6d %4d %4d %4d %4d %8s %8s",
                    m.label, m.clips, m.truePositive, m.falsePositive, m.trueNegative, m.falseNegative, prec, rec,
                )
            )
        }
        return lines.joinToString("\n")
    }

    companion object {
        /**
         * Detector identity recorded on the report. Mirrors
         * `SessionRepository.MODEL_VERSION` (data layer — not importable from pure-JVM eval
         * code); update both together when the detector model id changes.
         */
        const val DETECTOR_VERSION = "energy-heuristic-v1-spectral"
    }
}
