package com.sleepanalysis.data.garmin

import com.sleepanalysis.domain.policy.MeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests with representative Garmin payload shapes. Tolerance is the point: unknown
 * shapes yield empty lists (and the raw JSON is stored), known shapes map to vendor metrics.
 */
class GarminJsonParsersTest {

    private val dayStart = 1_730_454_000_000L // 2026-01-12 local midnight-ish

    @Test
    fun `dailySleep with sleepTimeSeconds and nested score parses`() {
        val json = """
            {"dailySleepDTO":{"sleepTimeSeconds":26460,"sleepStartTimestampGMT":${dayStart + 7_200_000}},
             "sleepScore":{"value":74,"qualifier":"GOOD"}}
        """.trimIndent()
        val metrics = GarminJsonParsers.parseDailySleep(json, dayStart)
        assertEquals(2, metrics.size)
        assertEquals(MeasurementType.VENDOR_SLEEP_DURATION, metrics[0].type)
        assertEquals(26460.0, metrics[0].value, 0.0001)
        assertEquals(MeasurementType.VENDOR_SLEEP_SCORE, metrics[1].type)
        assertEquals(74.0, metrics[1].value, 0.0001)
    }

    @Test
    fun `dailySleep minutes fallback converts to seconds`() {
        val json = """{"sleepSummary":{"sleepTimeMinutes":441}}"""
        val metrics = GarminJsonParsers.parseDailySleep(json, dayStart)
        assertEquals(1, metrics.size)
        assertEquals(441.0 * 60, metrics[0].value, 0.0001)
    }

    @Test
    fun `hrv summary maps lastNightAvg to rmssd`() {
        val json = """{"hrvSummary":{"weeklyAvg":52.3,"lastNightAvg":45,"lastNight5MinHigh":63},"hrvValues":[]}"""
        val metrics = GarminJsonParsers.parseHrv(json, dayStart)
        assertEquals(1, metrics.size)
        assertEquals(MeasurementType.VENDOR_HRV_RMSSD, metrics[0].type)
        assertEquals(45.0, metrics[0].value, 0.0001)
    }

    @Test
    fun `full sleep payload maps duration score stages physiology spo2 and need`() {
        // Field names verified against a live dailySleep response (2026-11-02).
        val json = """
            {"sleepTimeSeconds":26460,"sleepStartTimestampGMT":${dayStart + 7_200_000},
             "sleepEndTimestampGMT":${dayStart + 27_600_000},
             "deepSleepSeconds":4200,"lightSleepSeconds":13260,"remSleepSeconds":6000,
             "awakeSleepSeconds":1800,"awakeCount":3,"napTimeSeconds":3600,
             "avgHeartRate":58.4,"averageRespirationValue":14.2,"avgSleepStress":21.5,
             "averageSpO2Value":95.3,"lowestSpO2Value":88.0,"numberOfEventsBelowThreshold":7,
             "sleepScore":{"value":74,"qualifier":"GOOD"},
             "sleepNeed":{"baseline":460,"actual":460}}
        """.trimIndent()
        val byType = GarminJsonParsers.parseDailySleep(json, dayStart).associateBy { it.type }
        assertEquals(74.0, byType.getValue(MeasurementType.VENDOR_SLEEP_SCORE).value, 0.0001)
        assertEquals(4200.0, byType.getValue(MeasurementType.VENDOR_DEEP_SLEEP).value, 0.0001)
        assertEquals(13260.0, byType.getValue(MeasurementType.VENDOR_LIGHT_SLEEP).value, 0.0001)
        assertEquals(6000.0, byType.getValue(MeasurementType.VENDOR_REM_SLEEP).value, 0.0001)
        assertEquals(1800.0, byType.getValue(MeasurementType.VENDOR_AWAKE_SLEEP).value, 0.0001)
        assertEquals(3.0, byType.getValue(MeasurementType.VENDOR_SLEEP_AWAKENINGS).value, 0.0001)
        assertEquals(3600.0, byType.getValue(MeasurementType.VENDOR_NAP_TIME).value, 0.0001)
        assertEquals(58.4, byType.getValue(MeasurementType.VENDOR_AVG_HEART_RATE_SLEEP).value, 0.0001)
        assertEquals(14.2, byType.getValue(MeasurementType.VENDOR_RESPIRATION_AVG_SLEEP).value, 0.0001)
        assertEquals(21.5, byType.getValue(MeasurementType.VENDOR_SLEEP_STRESS_AVG).value, 0.0001)
        assertEquals(95.3, byType.getValue(MeasurementType.VENDOR_SPO2_AVG_SLEEP).value, 0.0001)
        assertEquals(88.0, byType.getValue(MeasurementType.VENDOR_SPO2_LOWEST_SLEEP).value, 0.0001)
        assertEquals(460.0, byType.getValue(MeasurementType.VENDOR_SLEEP_NEED).value, 0.0001)
        assertEquals(dayStart + 7_200_000, byType.getValue(MeasurementType.VENDOR_SLEEP_DURATION).vendorTimestampMs)
    }

    @Test
    fun `desaturation event count is never imported (M12-M17)`() {
        val json = """{"numberOfEventsBelowThreshold":7,"averageSpO2Value":95.0}"""
        val types = GarminJsonParsers.parseDailySleep(json, dayStart).map { it.type }
        assertTrue(types.contains(MeasurementType.VENDOR_SPO2_AVG_SLEEP))
        assertTrue(types.none { it.name.contains("DESATURATION") || it == MeasurementType.SPO2_DESATURATION_ESTIMATE })
    }

    @Test
    fun `all-null sleep payload yields only the sleep need baseline`() {
        // The observed 2026-11-01 response: full DTO with every data field null.
        val json = """{"sleepTimeSeconds":null,"deepSleepSeconds":null,"sleepScore":null,
                       "sleepNeed":{"baseline":460,"actual":460}}"""
        val metrics = GarminJsonParsers.parseDailySleep(json, dayStart)
        assertEquals(1, metrics.size)
        assertEquals(MeasurementType.VENDOR_SLEEP_NEED, metrics.single().type)
        assertEquals(460.0, metrics.single().value, 0.0001)
    }

    @Test
    fun `sleep score factors object maps known names and skips unknown`() {
        val json = """
            {"sleepScore":{"value":74,
              "factorValues":{"sleepDuration":80,"sleepQuality":70,"mysteryFactor":50}}}
        """.trimIndent()
        val byType = GarminJsonParsers.parseDailySleep(json, dayStart).associateBy { it.type }
        assertEquals(74.0, byType.getValue(MeasurementType.VENDOR_SLEEP_SCORE).value, 0.0001)
        assertEquals(80.0, byType.getValue(MeasurementType.VENDOR_SLEEP_FACTOR_DURATION).value, 0.0001)
        assertEquals(70.0, byType.getValue(MeasurementType.VENDOR_SLEEP_FACTOR_QUALITY).value, 0.0001)
        // Unknown factor key skipped; no metric carries its value.
        assertTrue(byType.values.none { it.value == 50.0 })
    }

    @Test
    fun `sleep score factors array shape maps by name`() {
        val json = """
            {"sleepScore":{"value":74,
              "factors":[{"name":"timing","value":88},{"name":"stress","value":64}]}}
        """.trimIndent()
        val byType = GarminJsonParsers.parseDailySleep(json, dayStart).associateBy { it.type }
        assertEquals(88.0, byType.getValue(MeasurementType.VENDOR_SLEEP_FACTOR_TIMING).value, 0.0001)
        assertEquals(64.0, byType.getValue(MeasurementType.VENDOR_SLEEP_FACTOR_STRESS).value, 0.0001)
    }

    @Test
    fun `stress daily parses average from array and object shapes`() {
        val array = """[{"calendarDate":"2026-11-01","stressAverage":35,"stressMax":80}]"""
        val fromArray = GarminJsonParsers.parseStress(array, dayStart)
        assertEquals(1, fromArray.size)
        assertEquals(MeasurementType.VENDOR_STRESS_DAILY_AVG, fromArray.single().type)
        assertEquals(35.0, fromArray.single().value, 0.0001)

        val obj = """{"averageStressLevel":42,"restless":5}"""
        assertEquals(42.0, GarminJsonParsers.parseStress(obj, dayStart).single().value, 0.0001)
    }

    @Test
    fun `stress average outside 0-100 is rejected`() {
        assertTrue(GarminJsonParsers.parseStress("""{"stressAverage":140}""", dayStart).isEmpty())
        assertTrue(GarminJsonParsers.parseStress("""{"stressAverage":null}""", dayStart).isEmpty())
    }

    @Test
    fun `body battery report array takes the last charged value`() {
        val json = """
            [{"charged":10,"drained":30,"timestamp":$dayStart},
             {"charged":45,"drained":5,"timestamp":${dayStart + 3_600_000}}]
        """.trimIndent()
        val metrics = GarminJsonParsers.parseBodyBattery(json, dayStart)
        assertEquals(1, metrics.size)
        assertEquals(45.0, metrics[0].value, 0.0001)
    }

    @Test
    fun `body battery summary style uses most recent value`() {
        val json = """{"bodyBatteryMostRecentValue":38,"bodyBatteryHighestValue":70}"""
        val metrics = GarminJsonParsers.parseBodyBattery(json, dayStart)
        assertEquals(38.0, metrics.single().value, 0.0001)
    }

    @Test
    fun `daily summary yields resting heart rate`() {
        val json = """
            {"restingHeartRate":52,"bodyBatteryMostRecentValue":41,
             "calendarDate":"2026-01-12","steps":4211}
        """.trimIndent()
        val metrics = GarminJsonParsers.parseDailySummary(json, dayStart)
        assertEquals(2, metrics.size)
        assertEquals(MeasurementType.VENDOR_RESTING_HEART_RATE, metrics[0].type)
        assertEquals(52.0, metrics[0].value, 0.0001)
        assertEquals(MeasurementType.VENDOR_BODY_BATTERY, metrics[1].type)
    }

    @Test
    fun `malformed or unknown payloads yield no metrics`() {
        assertTrue(GarminJsonParsers.parseDailySleep("not json {", dayStart).isEmpty())
        assertTrue(GarminJsonParsers.parseHrv("""{"unrelated":1}""", dayStart).isEmpty())
        assertTrue(GarminJsonParsers.parseBodyBattery("""{"foo":[1,2]}""", dayStart).isEmpty())
    }

    @Test
    fun `token response parses token and expiry in seconds`() {
        val now = 1_000_000_000_000L
        val info = GarminJsonParsers.parseToken("""{"token":"abc.def","expiresIn":600}""", now)
        assertEquals("abc.def", info.token)
        assertNull(info.expiresAtMs) // expiresIn is not a key we treat as absolute expiry

        val absolute = GarminJsonParsers.parseToken("""{"token":"x","expiresAt":1000000600}""", now)
        assertEquals(now + 600_000, absolute.expiresAtMs)
    }

    // Bare-JWT token responses: what Garmin's personal-api token endpoint actually returns
    // (header.payload.signature, not JSON). Asking the endpoint for application/json yields 406.

    /** Encodes a JSON string as unpadded base64url, the JWT segment encoding. */
    private fun b64url(s: String) =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `bare JWT token response parses token and exp claim`() {
        val jwt = "${b64url("""{"alg":"HS256"}""")}.${b64url("""{"exp":1000000600,"sub":"garmin"}""")}.c2ln"
        val info = GarminJsonParsers.parseToken(jwt, nowMs = 1_000_000_000_000L)
        assertEquals(jwt, info.token)
        assertEquals(1_000_000_600_000L, info.expiresAtMs)
    }

    @Test
    fun `bare JWT without exp claim parses with null expiry`() {
        val jwt = "${b64url("""{"alg":"HS256"}""")}.${b64url("""{"sub":"garmin"}""")}.c2ln"
        val info = GarminJsonParsers.parseToken(jwt, nowMs = 1_000_000_000_000L)
        assertEquals(jwt, info.token)
        assertNull(info.expiresAtMs) // caller falls back to the conservative cache TTL
    }

    @Test
    fun `JWT-shaped body with non-JSON payload still yields the token`() {
        // Segments match the base64url shape but the payload is not a JSON object.
        val jwt = "${b64url("""{"alg":"HS256"}""")}.${b64url("not json")}.c2ln"
        val info = GarminJsonParsers.parseToken(jwt, nowMs = 1_000_000_000_000L)
        assertEquals(jwt, info.token)
        assertNull(info.expiresAtMs)
    }

    @Test
    fun `token response that is neither JSON nor JWT yields no token`() {
        val info = GarminJsonParsers.parseToken("Gateway timeout - retry", nowMs = 0L)
        assertNull(info.token)
        assertNull(info.expiresAtMs)
    }

    @Test
    fun `bounded search does not traverse absurdly deep structures`() {
        val deep = buildString {
            append("{")
            repeat(30) { append("\"a\":{") }
            append("\"sleepTimeSeconds\":123")
            repeat(30) { append("}") }
            append("}")
        }
        // Depth 30 exceeds MAX_DEPTH — tolerated as absent rather than crashing.
        assertTrue(GarminJsonParsers.parseDailySleep(deep, dayStart).isEmpty())
    }
}
