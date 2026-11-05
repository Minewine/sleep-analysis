package com.sleepanalysis.data.garmin

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminRetryPolicyTest {

    private val now = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneOffset.UTC)
        .toInstant().toEpochMilli()

    @Test
    fun `only rate-limit verdicts are retryable`() {
        assertTrue(GarminRetryPolicy.shouldRetry(429))
        assertTrue(GarminRetryPolicy.shouldRetry(503))
        assertFalse(GarminRetryPolicy.shouldRetry(401))
        assertFalse(GarminRetryPolicy.shouldRetry(500))
        assertFalse(GarminRetryPolicy.shouldRetry(200))
    }

    @Test
    fun `numeric retry-after is honored`() {
        assertEquals(7_000L, GarminRetryPolicy.backoffMs(1, "7", now))
        assertEquals(0L, GarminRetryPolicy.backoffMs(1, "0", now))
    }

    @Test
    fun `retry-after is capped`() {
        assertEquals(GarminRetryPolicy.MAX_BACKOFF_MS, GarminRetryPolicy.backoffMs(1, "3600", now))
    }

    @Test
    fun `http-date retry-after is honored and capped`() {
        val inFiveSeconds = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(now + 5_000), ZoneOffset.UTC,
        ).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(5_000L, GarminRetryPolicy.backoffMs(1, inFiveSeconds, now))
    }

    @Test
    fun `past http-date falls back to the exponential default`() {
        val yesterday = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(now - 86_400_000), ZoneOffset.UTC,
        ).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(GarminRetryPolicy.DEFAULT_BACKOFF_MS, GarminRetryPolicy.backoffMs(1, yesterday, now))
    }

    @Test
    fun `no header grows exponentially and is capped`() {
        assertEquals(2_000L, GarminRetryPolicy.backoffMs(1, null, now))
        assertEquals(4_000L, GarminRetryPolicy.backoffMs(2, null, now))
        assertEquals(GarminRetryPolicy.MAX_BACKOFF_MS, GarminRetryPolicy.backoffMs(6, null, now))
    }

    @Test
    fun `garbage header falls back to the exponential default`() {
        assertEquals(GarminRetryPolicy.DEFAULT_BACKOFF_MS, GarminRetryPolicy.backoffMs(1, "soon-ish", now))
    }

    @Test
    fun `retry-after parser returns null for absent and negative`() {
        assertNull(GarminRetryPolicy.retryAfterMs(null, now))
        assertNull(GarminRetryPolicy.retryAfterMs("", now))
        assertNull(GarminRetryPolicy.retryAfterMs("-3", now))
    }
}
