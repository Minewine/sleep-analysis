package com.sleepanalysis.domain.report

/**
 * The compact headline payload stored on the `report` snapshot row (schema v12,
 * docs/DATA_MODEL.md §3.7, target shape docs/DATA_MODEL.md §5.2). Deliberately HEADLINE-ONLY:
 * the full report stays a live [ReportAssembler] computation — the snapshot exists so later
 * analysis can (a) see the versions ([ReportSnapshotEntity.detectorVersion]/[ReportSnapshotEntity.scoreVersion])
 * that produced a night without re-deriving them from events, and (b) survive the events'
 * 90-day retention with the night's own numbers. Every field the row carries is one the
 * night's measurements already carry under [com.sleepanalysis.domain.policy.ClaimPolicy].
 *
 * Hand-built like [com.sleepanalysis.domain.validity.NightValidity.notes] (one format for the
 * finalize write and any future rewrite so neither drifts); parsed tolerantly — missing or
 * malformed fields read back as null ([parse]).
 */
object ReportSnapshot {

    /** Payload version carried INSIDE the JSON (docs/DATA_MODEL.md §7) — bump on shape change. */
    const val PAYLOAD_VERSION = 1

    /** One night's headline metrics — exactly what [payload] serializes and [parse] reads back. */
    data class Headline(
        val validityGrade: String? = null,
        val snoreScore: Int? = null,
        /** Stored SNORE_SHARE_OF_NIGHT percent (null: no captured-analysis denominator). */
        val snoreSharePercent: Double? = null,
        val snoreSeconds: Long? = null,
        val longestBoutMs: Long? = null,
        /** Garmin's own sleep window for the night — present only when linked (§5.3). */
        val garminSleepStartMs: Long? = null,
        val garminSleepEndMs: Long? = null,
        val garminSleepScore: Int? = null,
        val garminOverlapWithCaptureMs: Long? = null,
    )

    fun payload(headline: Headline): String = buildString {
        append("{\"schema_version\":").append(PAYLOAD_VERSION)
        headline.validityGrade?.let { append(",\"validity_grade\":").append(quoted(it)) }
        headline.snoreScore?.let { append(",\"snore_score\":").append(it) }
        headline.snoreSharePercent?.let { append(",\"snore_share_percent\":").append(fmt(it)) }
        headline.snoreSeconds?.let { append(",\"snore_seconds\":").append(it) }
        headline.longestBoutMs?.let { append(",\"longest_bout_ms\":").append(it) }
        headline.garminSleepStartMs?.let { append(",\"garmin_sleep_start_ms\":").append(it) }
        headline.garminSleepEndMs?.let { append(",\"garmin_sleep_end_ms\":").append(it) }
        headline.garminSleepScore?.let { append(",\"garmin_sleep_score\":").append(it) }
        headline.garminOverlapWithCaptureMs?.let { append(",\"garmin_overlap_ms\":").append(it) }
        append('}')
    }

    /** Tolerant read-back (exports, future gates); null when the payload is unusable or not ours. */
    fun parse(json: String?): Headline? = runCatching {
        if (json.isNullOrBlank()) return@runCatching null
        val o = org.json.JSONObject(json)
        if (o.optInt("schema_version") != PAYLOAD_VERSION) return@runCatching null
        Headline(
            validityGrade = o.optString("validity_grade").takeUnless { it.isEmpty() },
            snoreScore = optInt(o, "snore_score"),
            snoreSharePercent = optDouble(o, "snore_share_percent"),
            snoreSeconds = optLong(o, "snore_seconds"),
            longestBoutMs = optLong(o, "longest_bout_ms"),
            garminSleepStartMs = optLong(o, "garmin_sleep_start_ms"),
            garminSleepEndMs = optLong(o, "garmin_sleep_end_ms"),
            garminSleepScore = optInt(o, "garmin_sleep_score"),
            garminOverlapWithCaptureMs = optLong(o, "garmin_overlap_ms"),
        )
    }.getOrNull()

    // Present-but-non-numeric values (corrupt payloads) are treated as MISSING — never as 0.
    private fun optLong(o: org.json.JSONObject, key: String): Long? = when (val v = if (o.isNull(key)) null else o.opt(key)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun optInt(o: org.json.JSONObject, key: String): Int? = when (val v = if (o.isNull(key)) null else o.opt(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun optDouble(o: org.json.JSONObject, key: String): Double? =
        when (val v = if (o.isNull(key)) null else o.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()?.takeUnless { it.isNaN() }
            else -> null
        }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun quoted(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(c)
        }
        append('"')
    }
}
