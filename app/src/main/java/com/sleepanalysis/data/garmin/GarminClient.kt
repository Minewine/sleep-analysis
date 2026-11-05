package com.sleepanalysis.data.garmin

import com.sleepanalysis.data.secure.GarminKeyStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Minimal HTTP client for the Garmin Personal API (docs/GARMIN_INTEGRATION.md).
 *
 * Network posture (docs/PRIVACY_AND_REGULATORY.md §1, amended for Phase 3): the app talks to
 * exactly one host — personal.connectapi.garmin.com — and only when the user taps Import.
 * Uses java.net.HttpURLConnection (no third-party network dependency, easy to audit).
 * Never logs the API key, token, or response bodies.
 */
@Singleton
class GarminClient @Inject constructor(
    private val keyStore: GarminKeyStore,
) {

    data class TokenInfo(val token: String?, val expiresAtMs: Long?)

    data class EndpointResult(
        val endpoint: String,
        val httpStatus: Int,
        /** Response body when HTTP 2xx and non-blank, else null. Never logged. */
        val json: String?,
    )

    data class GarminDayResult(val endpoints: List<EndpointResult>) {
        fun ok(endpoint: String): EndpointResult? =
            endpoints.firstOrNull { it.endpoint == endpoint && it.httpStatus in 200..299 && it.json != null }
    }

    class GarminException(message: String, cause: Throwable? = null) : Exception(message, cause)

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs = 0L

    /** Fetches all five endpoints for [date]. Each endpoint fails independently. */
    suspend fun fetchDay(date: String): GarminDayResult = withContext(Dispatchers.IO) {
        val apiKey = keyStore.load()
            ?: throw GarminException("No Garmin API key stored — add one in Settings first.")
        var token = bearerToken(apiKey)

        // (requestPath, endpointLabel) — the label is the storage/status key (GarminImportManager
        // EP_* constants); range endpoints carry dates in path or query but share one label.
        val endpoints: List<Pair<String, String>> = listOf(
            "/sleep-service/sleep/dailySleep?date=$date" to "/sleep-service/sleep/dailySleep",
            "/hrv-service/hrv/$date" to "/hrv-service/hrv",
            // Range endpoints (verified against the Personal API surface, GARMIN_INTEGRATION.md §4):
            // these take startDate/endDate, NOT date — a ?date= param is ignored/rejected.
            "/wellness-service/wellness/bodyBattery/reports/daily?startDate=$date&endDate=$date"
                to "/wellness-service/wellness/bodyBattery/reports/daily",
            "/usersummary-service/v2/dailysummaries?startDate=$date&endDate=$date"
                to "/usersummary-service/v2/dailysummaries",
            "/usersummary-service/stats/stress/daily/$date/$date"
                to "/usersummary-service/stats/stress/daily",
        )
        val results = endpoints.map { (path, label) ->
            var result = httpGet(path, label, token)
            if (result.httpStatus == 401) {
                // Token likely expired — exchange once and retry this endpoint. A FAILED
                // RE-EXCHANGE marks only this endpoint failed and must not abort the
                // import: the other endpoints may still succeed (each fails independently,
                // docs/GARMIN_INTEGRATION.md §6).
                cachedToken = null
                result = try {
                    token = bearerToken(apiKey)
                    httpGet(path, label, token)
                } catch (_: GarminException) {
                    // Negative status = no HTTP verdict this run (network failure or a
                    // token re-exchange that never produced a token).
                    EndpointResult(label, STATUS_NO_HTTP, null)
                }
            }
            result
        }
        GarminDayResult(results)
    }

    /** Exchanges the personal API key for a short-lived bearer token (cached until expiry). */
    private suspend fun bearerToken(apiKey: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiresAtMs }?.let { return@withContext it }

        val connection = try {
            // Accept */*: the token endpoint returns a bare JWT, not JSON. Requesting
            // application/json here yields HTTP 406 once the key passes auth (night-2 dogfood
            // bug) — invalid keys 401 before content negotiation, which is why only real-key
            // imports ever saw it.
            openConnection("$BASE$TOKEN_PATH", method = "POST", accept = "*/*")
        } catch (e: IOException) {
            throw GarminException("Network unreachable: ${e.message}", e)
        }
        connection.setRequestProperty("Authorization", "ApiKey $apiKey")
        val (status, body, _) = readResponse(connection)
        if (status != 200) {
            throw GarminException(
                when (status) {
                    401, 403 -> "Garmin rejected the API key (HTTP $status). Check it in Garmin Connect."
                    else -> "Garmin token exchange failed (HTTP $status)."
                }
            )
        }
        val info = GarminJsonParsers.parseToken(body ?: "", now)
        val token = info.token
            ?: throw GarminException("Garmin token response did not contain a token (raw kept for diagnostics).")
        // Unknown TTL → conservative 5-minute cache; honored longer when Garmin reports expiry.
        tokenExpiresAtMs = info.expiresAtMs ?: (now + DEFAULT_TOKEN_TTL_MS)
        cachedToken = token
        token
    }

    /** One raw HTTP exchange: status, body, and the server's Retry-After hint if it sent one. */
    private data class RawResponse(val status: Int, val body: String?, val retryAfterHeader: String?)

    /**
     * Fetches one endpoint with bounded retries: transient I/O failures and rate-limit
     * verdicts (429/503) are retried up to [GarminRetryPolicy.MAX_ATTEMPTS], backing off
     * per [GarminRetryPolicy] — honoring Retry-After, never waiting longer than its cap.
     * After the budget is spent the endpoint fails independently (negative status when no
     * HTTP verdict was reached) — one flaky endpoint never aborts the import.
     */
    private suspend fun httpGet(path: String, endpoint: String, token: String): EndpointResult {
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                getOnce(path, endpoint, token)
            } catch (_: IOException) {
                null
            }
            if (response != null && !GarminRetryPolicy.shouldRetry(response.status)) {
                // Any 2xx counts as success; an empty 204-style body is an absent result,
                // which the tolerant parsers skip exactly like a missing key.
                return EndpointResult(
                    endpoint,
                    response.status,
                    response.body?.takeIf { response.status in 200..299 && it.isNotBlank() },
                )
            }
            if (attempt >= GarminRetryPolicy.MAX_ATTEMPTS) {
                return EndpointResult(endpoint, response?.status ?: STATUS_NO_HTTP, null)
            }
            delay(GarminRetryPolicy.backoffMs(attempt, response?.retryAfterHeader))
        }
    }

    private fun getOnce(path: String, endpoint: String, token: String): RawResponse {
        val connection = openConnection("$BASE$path", method = "GET")
        connection.setRequestProperty("Authorization", "Bearer $token")
        val (status, body, retryAfter) = readResponse(connection)
        return RawResponse(status, body, retryAfter)
    }

    private fun openConnection(url: String, method: String, accept: String = "application/json"): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SleepAnalysis/0.1 (personal; on-device sleep research)")
        }
        return connection
    }

    /** Reads body or error stream plus the Retry-After header; the key/token are never logged. */
    private fun readResponse(connection: HttpURLConnection): Triple<Int, String?, String?> = try {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }
        val retryAfter = connection.getHeaderField("Retry-After")
        Triple(status, body, retryAfter)
    } catch (e: IOException) {
        throw e
    } finally {
        connection.disconnect()
    }

    fun invalidateToken() {
        cachedToken = null
        tokenExpiresAtMs = 0
    }

    private companion object {
        const val BASE = "https://personal.connectapi.garmin.com"
        const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000

        /** Endpoint status meaning "no HTTP verdict this run" (network failure / failed token re-exchange). */
        const val STATUS_NO_HTTP = -1
        const val DEFAULT_TOKEN_TTL_MS = 5 * 60_000L
    }
}

/**
 * Bounded retry/backoff policy for Garmin endpoints — pure, unit-tested without network.
 * Garmin rate-limits with 429/503 and a Retry-After header (delta-seconds or RFC 1123
 * HTTP-date). The header is honored but never beyond [MAX_BACKOFF_MS], an endpoint is never
 * attempted more than [MAX_ATTEMPTS] times, and without a header the delay grows
 * exponentially from [DEFAULT_BACKOFF_MS].
 */
object GarminRetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val DEFAULT_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 15_000L

    fun shouldRetry(status: Int): Boolean = status == 429 || status == 503

    /**
     * Delay before retry attempt [attempt] (1-based): Retry-After when the server sent one
     * (bounded), else exponential 2 s → 4 s → capped. Never negative.
     */
    fun backoffMs(attempt: Int, retryAfterHeader: String?, nowMs: Long = System.currentTimeMillis()): Long {
        retryAfterMs(retryAfterHeader, nowMs)?.let { return it.coerceIn(0, MAX_BACKOFF_MS) }
        val exponential = DEFAULT_BACKOFF_MS shl (attempt - 1).coerceIn(0, 4)
        return exponential.coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Retry-After as a delay in ms: a non-negative delta-seconds value or a future RFC 1123
     * HTTP-date; null when the header is absent, unparsable, or already in the past.
     */
    fun retryAfterMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        if (header.isNullOrBlank()) return null
        header.trim().toLongOrNull()?.let { seconds -> return (seconds * 1000L).takeIf { it >= 0 } }
        return try {
            val at = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            (at - nowMs).takeIf { it > 0 }
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
