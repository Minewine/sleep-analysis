package com.sleepanalysis.monitoring.audio

import com.sleepanalysis.domain.report.SkippedHopSpan

/**
 * Records which analysis hops were skipped by thermal throttling (docs/DATA_MODEL.md §5.1):
 * consecutive skipped hops merge into spans, so a night of every-2nd-hop throttling stays a
 * handful of [SkippedHopSpan] entries instead of tens of thousands of timestamps. Analysis
 * reconstructs the exact skipped hops from a span: within it, every odd hop of the capture
 * grid was unanalyzed. Epoch-based so spans of a resumed engine (START_STICKY restart) merge
 * and sort trivially in the stats carry.
 *
 * Single-writer contract: [skipped]/[reset] are capture-thread-only; [spans] snapshots under
 * [lock] so stats() can read from the service heartbeat.
 */
class HopSkipLedger(
    private val mergeGapMs: Long = MERGE_GAP_MS,
    private val maxSpans: Int = MAX_SPANS,
) {
    private val lock = Any()
    private val spans = ArrayList<SkippedHopSpan>()

    fun reset() {
        synchronized(lock) { spans.clear() }
    }

    /** Records one skipped analysis hop whose window ends at [hopEndEpochMs]. */
    fun skipped(hopEndEpochMs: Long) {
        synchronized(lock) {
            val last = spans.lastOrNull()
            when {
                last != null && hopEndEpochMs - last.endEpochMs <= mergeGapMs ->
                    spans[spans.size - 1] = last.copy(
                        endEpochMs = maxOf(last.endEpochMs, hopEndEpochMs),
                        hops = last.hops + 1,
                    )
                spans.size < maxSpans -> spans.add(SkippedHopSpan(hopEndEpochMs, hopEndEpochMs, 1))
                // Over the cap: drop the hop rather than grow unbounded. The cap is far above
                // any real throttle episode count; the skipped window counters stay exact.
            }
        }
    }

    fun spans(): List<SkippedHopSpan> = synchronized(lock) { spans.toList() }

    companion object {
        /** Skipped hops arrive ~1 s apart while throttled; quiet gaps beyond this start a new span. */
        const val MERGE_GAP_MS = 3_000L
        const val MAX_SPANS = 1_000
    }
}
