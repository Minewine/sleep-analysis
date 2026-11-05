package com.sleepanalysis.data.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Success semantics of the client result (docs/GARMIN_INTEGRATION.md §6): any 2xx with a body
 * counts as success; non-2xx and absent bodies yield no result, matching the parsers' tolerance.
 */
class GarminDayResultTest {

    @Test
    fun `ok matches any 2xx endpoint with a body`() {
        val result = GarminClient.GarminDayResult(
            listOf(
                GarminClient.EndpointResult("/sleep", 200, "{}"),
                GarminClient.EndpointResult("/hrv", 204, "{}"),
            ),
        )
        assertEquals("/sleep", result.ok("/sleep")?.endpoint)
        assertEquals("/hrv", result.ok("/hrv")?.endpoint)
    }

    @Test
    fun `ok rejects non-2xx statuses and absent bodies`() {
        val result = GarminClient.GarminDayResult(
            listOf(
                GarminClient.EndpointResult("/e401", 401, """{"error":"unauthorized"}"""),
                GarminClient.EndpointResult("/e500", 500, "boom"),
                GarminClient.EndpointResult("/enet", -1, null), // IOException after the retry
                GarminClient.EndpointResult("/eempty", 204, null), // empty body → absent result
            ),
        )
        assertNull(result.ok("/e401"))
        assertNull(result.ok("/e500"))
        assertNull(result.ok("/enet"))
        assertNull(result.ok("/eempty"))
    }
}
