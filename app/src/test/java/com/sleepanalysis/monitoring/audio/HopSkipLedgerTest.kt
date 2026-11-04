package com.sleepanalysis.monitoring.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skipped-hop ledger tests: spans merge while thermal throttling holds (skipped hops ~1 s
 * apart), split after a quiet gap, and survive as an analyzable record of which hops were
 * unanalyzed (docs/DATA_MODEL.md §5.1).
 */
class HopSkipLedgerTest {

    @Test
    fun `consecutive skipped hops merge into one span`() {
        val ledger = HopSkipLedger()
        // Every 2nd hop while throttled: one skipped hop per second.
        ledger.skipped(1_000)
        ledger.skipped(2_000)
        ledger.skipped(3_000)
        val spans = ledger.spans()
        assertEquals(1, spans.size)
        assertEquals(1_000, spans.single().startEpochMs)
        assertEquals(3_000, spans.single().endEpochMs)
        assertEquals(3, spans.single().hops)
    }

    @Test
    fun `a quiet gap beyond the merge tolerance starts a new span`() {
        val ledger = HopSkipLedger()
        ledger.skipped(1_000)
        ledger.skipped(2_000)
        // > 3 s without skips: throttling effectively ended and restarted.
        ledger.skipped(10_000)
        val spans = ledger.spans()
        assertEquals(2, spans.size)
        assertEquals(2, spans[0].hops)
        assertEquals(1, spans[1].hops)
        assertEquals(10_000, spans[1].startEpochMs)
    }

    @Test
    fun `merge tolerance boundary still merges`() {
        val ledger = HopSkipLedger()
        ledger.skipped(1_000)
        ledger.skipped(1_000 + HopSkipLedger.MERGE_GAP_MS)
        assertEquals(1, ledger.spans().size)
    }

    @Test
    fun `out-of-order timestamps still extend the last span`() {
        val ledger = HopSkipLedger()
        ledger.skipped(5_000)
        ledger.skipped(4_000) // defensive: capture thread is single, but stay sane
        val spans = ledger.spans()
        assertEquals(1, spans.size)
        assertEquals(5_000, spans.single().endEpochMs)
        assertEquals(2, spans.single().hops)
    }

    @Test
    fun `spans count is capped to bound memory and stats size`() {
        val ledger = HopSkipLedger(maxSpans = 5)
        // 10 far-apart single skips → only the first 5 are kept.
        for (i in 0 until 10) ledger.skipped(i * 60_000L)
        assertEquals(5, ledger.spans().size)
    }

    @Test
    fun `reset clears the ledger for a fresh engine run`() {
        val ledger = HopSkipLedger()
        ledger.skipped(1_000)
        ledger.reset()
        assertTrue(ledger.spans().isEmpty())
    }

    @Test
    fun `spans returns a defensive copy`() {
        val ledger = HopSkipLedger()
        ledger.skipped(1_000)
        val snapshot = ledger.spans()
        ledger.skipped(2_000) // merges into the live span
        assertEquals(1, snapshot.size)
        assertEquals(1, snapshot.single().hops)
        assertEquals(2, ledger.spans().single().hops)
    }
}
