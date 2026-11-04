package com.sleepanalysis.data.garmin

import com.sleepanalysis.domain.policy.MeasurementType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Tolerant parsers for Garmin Personal API responses (docs/GARMIN_INTEGRATION.md §5).
 *
 * Garmin's response shapes vary between firmware/app versions and Personal-API revisions, so
 * parsing is deliberately defensive: a shallow bounded search for known keys, null on absence,
 * and the raw JSON is always stored verbatim alongside the normalized metrics so mappings can
 * be corrected later without re-fetching.
 *
 * Pure JVM (org.json) — unit tested with representative payload shapes.
 */
object GarminJsonParsers {

    data class ParsedMetric(
        val type: MeasurementType,
        val value: Double,
        /** Vendor-provided timestamp if present, else null (we fall back to the day window). */
        val vendorTimestampMs: Long? = null,
    )

    private const val MAX_DEPTH = 5

    /**
     * /sleep-service/sleep/dailySleep → duration, score, score factors, stage seconds, overnight
     * physiology and SpO₂ levels. Field names verified against a live response (2026-11-02, all
     * nulls present so keys are exact). `numberOfEventsBelowThreshold` is deliberately NOT
     * imported — vendor desaturation counts are banned as a display claim (M12/M17).
     */
    fun parseDailySleep(json: String, dayStartMs: Long): List<ParsedMetric> {
        val root = parseRoot(json) ?: return emptyList()
        val metrics = mutableListOf<ParsedMetric>()
        val sleepStart = findLong(root, "sleepStartTimestampGMT")
        findDouble(root, "sleepTimeSeconds")?.let {
            metrics += ParsedMetric(MeasurementType.VENDOR_SLEEP_DURATION, it, sleepStart)
        }
        val score: Double? = (findObject(root, "sleepScore")?.opt("value") as? Number)?.toDouble()
            ?: findDouble(root, "sleepScoreValue")
        score?.let { metrics += ParsedMetric(MeasurementType.VENDOR_SLEEP_SCORE, it) }
        // Score factor breakdown: Garmin exposes sub-scores (0–100) under the sleepScore object.
        // Exact key naming is verified on the first real night; unknown factor keys are skipped
        // (raw payload preserved) rather than guessed at.
        findObject(root, "sleepScore")?.let { metrics += parseSleepScoreFactors(it) }
        // M13: vendor stage seconds — device-reported context, never compared to PSG.
        stageMetric(root, "deepSleepSeconds", MeasurementType.VENDOR_DEEP_SLEEP)?.let { metrics += it }
        stageMetric(root, "lightSleepSeconds", MeasurementType.VENDOR_LIGHT_SLEEP)?.let { metrics += it }
        stageMetric(root, "remSleepSeconds", MeasurementType.VENDOR_REM_SLEEP)?.let { metrics += it }
        stageMetric(root, "awakeSleepSeconds", MeasurementType.VENDOR_AWAKE_SLEEP)?.let { metrics += it }
        // Overnight physiology (M10) and stress composite.
        findDouble(root, "avgHeartRate")?.let {
            if (it > 0) metrics += ParsedMetric(MeasurementType.VENDOR_AVG_HEART_RATE_SLEEP, it)
        }
        findDouble(root, "averageRespirationValue")?.let {
            if (it > 0) metrics += ParsedMetric(MeasurementType.VENDOR_RESPIRATION_AVG_SLEEP, it)
        }
        findDouble(root, "avgSleepStress")?.let {
            metrics += ParsedMetric(MeasurementType.VENDOR_SLEEP_STRESS_AVG, it)
        }
        findDouble(root, "awakeCount")?.let {
            metrics += ParsedMetric(MeasurementType.VENDOR_SLEEP_AWAKENINGS, it)
        }
        findDouble(root, "napTimeSeconds")?.let {
            if (it > 0) metrics += ParsedMetric(MeasurementType.VENDOR_NAP_TIME, it)
        }
        // M12: SpO₂ levels only — never the below-threshold event count.
        findDouble(root, "averageSpO2Value")?.let {
            metrics += ParsedMetric(MeasurementType.VENDOR_SPO2_AVG_SLEEP, it)
        }
        findDouble(root, "lowestSpO2Value")?.let {
            metrics += ParsedMetric(MeasurementType.VENDOR_SPO2_LOWEST_SLEEP, it)
        }
        // Garmin's personalized sleep need (minutes) — context for the morning report.
        findObject(root, "sleepNeed")?.let { need ->
            findDouble(need, "baseline")?.let {
                metrics += ParsedMetric(MeasurementType.VENDOR_SLEEP_NEED, it)
            }
        }
        if (metrics.isEmpty()) {
            // Some shapes report duration in minutes under a different key.
            findDouble(root, "sleepTimeMinutes")?.let {
                metrics += ParsedMetric(MeasurementType.VENDOR_SLEEP_DURATION, it * 60, null)
            }
        }
        return metrics.filter { it.value >= 0 && isFinite(it.value) && withinDay(it, dayStartMs) }
    }

    /** Stage seconds → metric; nulls and sub-second garbage absent. */
    private fun stageMetric(root: Any?, key: String, type: MeasurementType): ParsedMetric? {
        val v = findDouble(root, key) ?: return null
        return ParsedMetric(type, v)
    }

    /**
     * Sleep-score factor sub-scores. Accepted shapes: an object of factor name → 0–100 number
     * under `factorValues`/`factors`/`subScores`, or an array of `{name/type/factor, value}`.
     * Factor names map by substring (duration/quality/stress/timing); unknown names are skipped.
     */
    private fun parseSleepScoreFactors(scoreObj: JSONObject): List<ParsedMetric> {
        val container: Any? = scoreObj.opt("factorValues")
            ?: scoreObj.opt("factors")
            ?: scoreObj.opt("subScores")
            ?: return emptyList()
        val out = mutableListOf<ParsedMetric>()
        fun addFactor(key: String, value: Double) {
            val k = key.lowercase()
            val type = when {
                "duration" in k -> MeasurementType.VENDOR_SLEEP_FACTOR_DURATION
                "quality" in k || "sound" in k -> MeasurementType.VENDOR_SLEEP_FACTOR_QUALITY
                "stress" in k -> MeasurementType.VENDOR_SLEEP_FACTOR_STRESS
                "timing" in k -> MeasurementType.VENDOR_SLEEP_FACTOR_TIMING
                else -> return
            }
            out += ParsedMetric(type, value)
        }
        when (container) {
            is JSONObject -> container.keys().forEach { k ->
                (container.opt(k) as? Number)?.let { addFactor(k, it.toDouble()) }
            }
            is JSONArray -> for (i in 0 until container.length()) {
                val item = container.opt(i)
                if (item is JSONObject) {
                    val name = listOf("name", "type", "factor").firstNotNullOfOrNull { item.opt(it) as? String }
                    val value = (item.opt("value") as? Number)?.toDouble()
                    if (name != null && value != null) addFactor(name, value)
                }
            }
        }
        return out.filter { isFinite(it.value) && it.value in 0.0..100.0 }
    }

    /**
     * /usersummary-service/stats/stress/daily/{from}/{to} → daily average stress (0–100).
     * Shape tolerance: array-of-days or single-day object; `stressAverage` (Personal API) or
     * `averageStressLevel` (Connect web shape).
     */
    fun parseStress(json: String, dayStartMs: Long): List<ParsedMetric> {
        val root = parseRoot(json) ?: return emptyList()
        val avg = findDouble(root, "stressAverage", "averageStressLevel", "avgStressLevel") ?: return emptyList()
        return listOf(ParsedMetric(MeasurementType.VENDOR_STRESS_DAILY_AVG, avg))
            .filter { isFinite(it.value) && it.value in 0.0..100.0 }
    }

    /** /hrv-service/hrv/{date} → nightly rMSSD average. */
    fun parseHrv(json: String, dayStartMs: Long): List<ParsedMetric> {
        val root = parseRoot(json) ?: return emptyList()
        val rMSSD = findObject(root, "hrvSummary")?.opt("lastNightAvg") as? Number
            ?: findDouble(root, "lastNightAvg")
            ?: return emptyList()
        return listOf(ParsedMetric(MeasurementType.VENDOR_HRV_RMSSD, rMSSD.toDouble()))
            .filter { isFinite(it.value) && it.value > 0 }
    }

    /** /wellness-service/wellness/bodyBattery/reports/daily → latest/charged body battery value. */
    fun parseBodyBattery(json: String, dayStartMs: Long): List<ParsedMetric> {
        val root = parseRoot(json) ?: return emptyList()
        // Preferred: explicit "most recent" field on summary-style payloads.
        findDouble(root, "bodyBatteryMostRecentValue")?.let {
            return listOf(ParsedMetric(MeasurementType.VENDOR_BODY_BATTERY, it)).filter { m -> isFinite(m.value) }
        }
        // Report-style payloads: arrays of charge/drain events — take the last charged value.
        val lastCharged = lastArrayNumber(root, "charged") ?: lastArrayNumber(root, "value")
        return lastCharged?.let {
            listOf(ParsedMetric(MeasurementType.VENDOR_BODY_BATTERY, it)).filter { m -> isFinite(m.value) }
        } ?: emptyList()
    }

    /** /usersummary-service/v2/dailysummaries → resting HR (+ body battery fallback). */
    fun parseDailySummary(json: String, dayStartMs: Long): List<ParsedMetric> {
        val root = parseRoot(json) ?: return emptyList()
        val metrics = mutableListOf<ParsedMetric>()
        findDouble(root, "restingHeartRate")?.let {
            if (isFinite(it) && it > 0) metrics += ParsedMetric(MeasurementType.VENDOR_RESTING_HEART_RATE, it)
        }
        findDouble(root, "bodyBatteryMostRecentValue")?.let {
            if (isFinite(it)) metrics += ParsedMetric(MeasurementType.VENDOR_BODY_BATTERY, it)
        }
        return metrics
    }

    /** Token-exchange response → bearer token (+ expiry when Garmin provides one). */
    /**
     * Token response parsing. Garmin's personal-api token endpoint returns a **bare JWT string**
     * (`header.payload.signature`, not JSON) — that is the primary shape. Kept tolerant for a
     * JSON envelope (`token` → `access_token` → `data.token`) in case Garmin revises it.
     * Expiry: the JWT `exp` claim (epoch seconds) when present, else null (caller falls back to
     * a conservative cache TTL).
     */
    fun parseToken(body: String, nowMs: Long): GarminClient.TokenInfo {
        val root = parseRoot(body) as? JSONObject
        if (root != null) {
            val token = (root.opt("token") as? String)
                ?: (root.opt("access_token") as? String)
                ?: (findObject(root, "data")?.opt("token") as? String)
            if (token != null) {
                val expiresAtMs = when (val e = findDouble(root, "expiresAt") ?: findDouble(root, "expiresAtMillis")) {
                    null -> findDouble(root, "expiresInSeconds")?.let { nowMs + it.toLong() * 1000 }
                    else -> {
                        // Heuristic: values > 1e12 are epoch millis, smaller values epoch seconds.
                        val raw = e.toLong()
                        if (raw > 1_000_000_000_000L) raw else raw * 1000
                    }
                }
                return GarminClient.TokenInfo(token = token, expiresAtMs = expiresAtMs)
            }
        }
        val jwt = parseJwt(body.trim())
        return if (jwt != null) {
            GarminClient.TokenInfo(token = jwt.first, expiresAtMs = jwt.second)
        } else {
            GarminClient.TokenInfo(token = null, expiresAtMs = null)
        }
    }

    private val JWT_SHAPE = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    /** (token, expiresAtMs) from a bare JWT; expiry null when the `exp` claim is absent or unreadable. */
    private fun parseJwt(body: String): Pair<String, Long?>? {
        if (!JWT_SHAPE.matches(body)) return null
        val payloadJson = runCatching {
            String(Base64.getUrlDecoder().decode(body.split('.')[1]), Charsets.UTF_8)
        }.getOrNull() ?: return body to null
        val exp = runCatching { JSONObject(payloadJson).optDouble("exp") }
            .getOrNull()?.takeIf { !it.isNaN() }?.toLong()
        return body to exp?.let { it * 1000L }
    }

    // ---- tolerant search helpers ----

    private fun parseRoot(json: String): Any? = try {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
    } catch (_: Exception) {
        null
    }

    /** Breadth-first, bounded-depth search for the first numeric value under any of [keys]. */
    private fun findDouble(node: Any?, vararg keys: String): Double? {
        val queue = ArrayDeque<Pair<Any?, Int>>()
        queue.add(node to 0)
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            when (current) {
                is JSONObject -> {
                    for (key in keys) {
                        val v = current.opt(key)
                        if (v is Number && isFinite(v.toDouble())) return v.toDouble()
                    }
                    if (depth < MAX_DEPTH) current.keys().forEach { queue.add(current.opt(it) to depth + 1) }
                }
                is JSONArray -> {
                    if (depth < MAX_DEPTH) for (i in 0 until current.length()) queue.add(current.opt(i) to depth + 1)
                }
            }
        }
        return null
    }

    private fun findLong(node: Any?, vararg keys: String): Long? =
        findDouble(node, *keys)?.toLong()

    private fun findObject(node: Any?, key: String): JSONObject? {
        val queue = ArrayDeque<Pair<Any?, Int>>()
        queue.add(node to 0)
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            when (current) {
                is JSONObject -> {
                    (current.opt(key) as? JSONObject)?.let { return it }
                    if (depth < MAX_DEPTH) current.keys().forEach { queue.add(current.opt(it) to depth + 1) }
                }
                is JSONArray -> {
                    if (depth < MAX_DEPTH) for (i in 0 until current.length()) queue.add(current.opt(i) to depth + 1)
                }
            }
        }
        return null
    }

    private fun lastArrayNumber(node: Any?, key: String): Double? {
        var found: Double? = null
        val queue = ArrayDeque<Pair<Any?, Int>>()
        queue.add(node to 0)
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            when (current) {
                is JSONObject -> if (depth < MAX_DEPTH) current.keys().forEach { queue.add(current.opt(it) to depth + 1) }
                is JSONArray -> {
                    for (i in 0 until current.length()) {
                        val item = current.opt(i)
                        if (item is JSONObject) {
                            (item.opt(key) as? Number)?.let { found = it.toDouble() }
                        }
                        if (depth < MAX_DEPTH) queue.add(item to depth + 1)
                    }
                }
            }
        }
        return found
    }

    /** Vendor timestamps outside the requested day are suspicious — prefer the day window. */
    private fun withinDay(metric: ParsedMetric, dayStartMs: Long): Boolean =
        metric.vendorTimestampMs == null || (metric.vendorTimestampMs >= dayStartMs - 12 * 3_600_000L)

    private fun isFinite(v: Double): Boolean = !v.isNaN() && !v.isInfinite()
}
