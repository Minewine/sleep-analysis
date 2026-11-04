package com.sleepanalysis.monitoring.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event shaping tests: hysteresis, minimum duration, snore precedence, flush, and feature
 * accumulation. Window timestamps are END times, hops of 500 ms starting at 1000 ms.
 */
class EventDetectorTest {

    private val detector = EventDetector()

    // Fixed synthetic window values: dbfs -15 over floor -40 => SNR 25 dB.
    private fun features(ts: Long, dbfs: Double = -15.0, ratio: Double = 0.8, floor: Double = -40.0) =
        AudioFeatures(windowDbfs = dbfs, noiseFloorDbfs = floor, lowBandRatio = ratio, timestampMs = ts)

    private fun result(snore: Boolean, loud: Boolean = true, confidence: Double = if (snore) 0.8 else 0.0) =
        DetectionResult(snore = snore, loudAcousticEvent = loud, confidence = confidence)

    private fun feed(
        detector: EventDetector,
        pattern: List<Pair<Boolean, Boolean>>, // (snore, loud) per window
        startTsMs: Long = 1000,
        hopMs: Long = 500,
    ): List<EventDetector.EventDraft> {
        val out = mutableListOf<EventDetector.EventDraft>()
        pattern.forEachIndexed { i, (snore, loud) ->
            out += detector.update(
                timestampMs = startTsMs + i * hopMs,
                features = features(startTsMs + i * hopMs),
                result = result(snore, loud),
            )
        }
        return out
    }

    @Test
    fun `snore spell opens after enter windows and closes after exit windows`() {
        // Gap merging defers the closed draft (see EventDetector KDoc); flush emits it here.
        val events = feed(detector, pattern = snore(4) + quiet(4)) + detector.flush()
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("SNORE", e.type)
        assertEquals(0L, e.startMs)
        assertEquals(2500L, e.endMs)
        assertEquals(-15.0, e.peakDbfs, 0.0001)
        assertEquals(0.8, e.confidence, 0.0001)
    }

    @Test
    fun `event carries aggregated detector features`() {
        val events = feed(detector, pattern = snore(4) + quiet(4)) + detector.flush()
        val f = events.single().features
        assertEquals(2, f.enterWindows) // default config
        assertEquals(4, f.exitWindows)
        assertEquals(2, f.openWindows)
        assertEquals(4, f.closeWindows)
        assertEquals(25.0, f.snrPeakDb, 0.0001)
        assertEquals(25.0, f.snrMeanDb, 0.0001)
        assertEquals(0.8, f.lowBandRatioPeak, 0.0001)
        assertEquals(0.8, f.lowBandRatioMean, 0.0001)
        assertEquals(-40.0, f.noiseFloorDbfsMean, 0.0001)
        // JSON round-trips with the documented keys.
        val parsed = EventFeatures.fromJson(f.toJson())!!
        assertEquals(f, parsed)
    }

    @Test
    fun `features parse tolerantly for legacy rows without window counts`() {
        val legacy = EventFeatures.fromJson(
            """{"enter_windows":2,"snr_peak_db":25.0,"snr_mean_db":25.0,""" +
                """"low_band_ratio_peak":0.8,"low_band_ratio_mean":0.8,"noise_floor_dbfs":-40.0}"""
        )!!
        assertEquals(2, legacy.enterWindows)
        assertEquals(4, legacy.exitWindows) // configured value when the key is absent
        assertEquals(2, legacy.openWindows)
        assertEquals(-1, legacy.closeWindows) // observed count unknown on legacy rows
    }

    @Test
    fun `features use peak and mean when the signal varies`() {
        var flip = false
        val drafts = mutableListOf<EventDetector.EventDraft>()
        // 4 snoring windows alternating SNR 20 / 30 (via window level) at fixed floor.
        for (i in 0 until 4) {
            flip = !flip
            val dbfs = if (flip) -20.0 else -10.0
            drafts += detector.update(
                1000L + i * 500,
                features(1000L + i * 500, dbfs = dbfs),
                result(snore = true),
            )
        }
        drafts += detector.flush() // event is still open; it finalizes here
        val f = drafts.first().features
        assertEquals(30.0, f.snrPeakDb, 0.0001)   // -10 over -40
        // First window only primes (enterWindows=2): accumulated windows carry 30/20/30.
        assertEquals(26.6667, f.snrMeanDb, 0.001)
    }

    @Test
    fun `event shorter than minimum duration is discarded`() {
        val events = feed(detector, pattern = snore(2) + quiet(4))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `single snore window never opens an event`() {
        val events = feed(detector, pattern = snore(1) + quiet(6))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `enterWindows=1 debug mode opens after one window`() {
        val dbg = EventDetector(enterWindows = 1)
        val events = feed(dbg, pattern = snore(3) + quiet(4)) + dbg.flush()
        // Opens at the first window (start = 0), spans three active windows (end 2000) —
        // with enterWindows=2 the same pattern would be discarded (1.5 s < 2 s minimum).
        assertEquals(1, events.size)
        assertEquals(1, events.single().features.enterWindows)
        assertEquals(0L, events.single().startMs)
        assertEquals(2000L, events.single().endMs)
    }

    @Test
    fun `snoring takes precedence over loud-noise shaping`() {
        val events = feed(detector, pattern = snore(6, loud = true) + loudOnly(6) + quiet(4)) +
            detector.flush()
        assertEquals(2, events.size)
        assertEquals("SNORE", events[0].type)
        assertEquals("OTHER_NOISE", events[1].type)
    }

    @Test
    fun `loud but broadband window produces OTHER_NOISE event`() {
        val events = feed(detector, pattern = loudOnly(4) + quiet(4)) + detector.flush()
        assertEquals(1, events.size)
        assertEquals("OTHER_NOISE", events.single().type)
    }

    @Test
    fun `flush closes a still-open event`() {
        feed(detector, pattern = snore(6))
        val flushed = detector.flush()
        assertEquals(1, flushed.size)
        assertEquals("SNORE", flushed[0].type)
        // Observed close count differs from the configured exit hysteresis on the flush path —
        // exactly the distinction the stored window counts exist to capture.
        assertEquals(0, flushed[0].features.closeWindows)
        // Subsequent flush is a no-op.
        assertNull(detector.flush().firstOrNull())
    }

    @Test
    fun `detector can be reused after reset`() {
        feed(detector, pattern = snore(4) + quiet(4))
        detector.flush()
        detector.reset()
        val events = feed(detector, pattern = snore(4) + quiet(4)) + detector.flush()
        assertEquals(1, events.size)
    }

    // --- Relative level gate (DetectorConfig.gateThresholdDbfs) ----------------------

    /** Feeds snore-flagged windows at per-window dBFS levels over [floor], then closes with quiet windows. */
    private fun feedLevels(
        detector: EventDetector,
        levels: List<Double>,
        floor: Double = -40.0,
    ): List<EventDetector.EventDraft> {
        val out = mutableListOf<EventDetector.EventDraft>()
        levels.forEachIndexed { i, dbfs ->
            out += detector.update(
                timestampMs = 1000L + i * 500,
                features = features(1000L + i * 500, dbfs = dbfs, floor = floor),
                result = result(snore = true),
            )
        }
        var i = levels.size
        while (out.isEmpty() && i < levels.size + 8) {
            out += detector.update(
                timestampMs = 1000L + i * 500,
                features = features(1000L + i * 500, dbfs = -90.0, floor = floor),
                // Silence: neither snore nor loud — a loud flag here would open a competing
                // OTHER_NOISE event and hold the deferred snore final past the loop horizon.
                result = result(snore = false, loud = false),
            )
            i++
        }
        return out
    }

    @Test
    fun `snore event below the level gate is downgraded to OTHER_NOISE`() {
        // Floor −40 → threshold −30: a −60 dBFS event is 20 dB under the ambient bed — rumble.
        val events = feedLevels(detector, levels = List(4) { -60.0 })
        assertEquals(1, events.size)
        assertEquals("OTHER_NOISE", events.single().type)
        assertEquals(-60.0, events.single().meanDbfs, 0.0001)
    }

    @Test
    fun `audible snore event keeps the SNORE label`() {
        val events = feedLevels(detector, levels = List(4) { -30.0 })
        assertEquals(1, events.size)
        assertEquals("SNORE", events.single().type)
    }

    @Test
    fun `level gate applies to the event mean, not the loudest window`() {
        // Peak −30 (LOUD band) but a long −80 tail drags the mean to −72.86: not snore.
        // (Window 0 only primes the enter hysteresis; accumulated windows are w1..w7.)
        val events = feedLevels(detector, levels = List(2) { -30.0 } + List(6) { -80.0 })
        assertEquals(1, events.size)
        assertEquals("OTHER_NOISE", events.single().type)
        assertEquals(-510.0 / 7, events.single().meanDbfs, 0.0001)
        assertEquals(-30.0, events.single().peakDbfs, 0.0001)
    }

    @Test
    fun `gate margin is configurable`() {
        // Floor −40: the default margin (10 → threshold −30) downgrades a −33 dBFS event;
        // a 5 dB margin (threshold −35) keeps it — the knob for quieter mic placements.
        val events = feedLevels(detector, levels = List(4) { -33.0 })
        assertEquals("OTHER_NOISE", events.single().type)
        val lenient = EventDetector(gateMarginDb = 5.0)
        val lenientEvents = feedLevels(lenient, levels = List(4) { -33.0 })
        assertEquals("SNORE", lenientEvents.single().type)
    }

    @Test
    fun `event exactly at the level gate keeps the SNORE label`() {
        // Gate is inclusive: meanDbfs >= floor + margin stays snore.
        val events = feedLevels(detector, levels = List(4) { -30.0 })
        assertEquals(1, events.size)
        assertEquals("SNORE", events.single().type)
        assertEquals(-30.0, events.single().meanDbfs, 0.0001)
    }

    @Test
    fun `sub-gate event finalized by flush is also downgraded`() {
        // Session-end path: the same close() runs, so a still-open quiet snore is downgraded too.
        repeat(4) { i ->
            detector.update(
                1000L + i * 500,
                features(1000L + i * 500, dbfs = -60.0),
                result(snore = true),
            )
        }
        val flushed = detector.flush()
        assertEquals(1, flushed.size)
        assertEquals("OTHER_NOISE", flushed.single().type)
    }

    @Test
    fun `hard absolute floor gates mic-self-noise rumble in a silent room`() {
        // Floor ≈ mic self-noise: floor + margin (−82) sits below audibility, so the hard
        // absolute floor (−60) applies — near-silence is downgraded despite a 12 dB SNR.
        val events = feedLevels(detector, levels = List(4) { -80.0 }, floor = -92.0)
        assertEquals(1, events.size)
        assertEquals("OTHER_NOISE", events.single().type)
    }

    @Test
    fun `quiet room - audible quiet snore below the old absolute cutoff keeps SNORE`() {
        // −58 dBFS in a −70 dBFS room is 12 dB over the floor — clearly audible — but the old
        // absolute −55 gate discarded it. The relative gate (max(−60, −60) = −60) keeps it.
        val events = feedLevels(detector, levels = List(4) { -58.0 }, floor = -70.0)
        assertEquals(1, events.size)
        assertEquals("SNORE", events.single().type)
    }

    @Test
    fun `louder room - the gate recalibrates upward`() {
        // −50 dBFS in a −45 dBFS bedroom is barely over the ambient bed — junk the old
        // absolute −55 gate would have labeled SNORE. The relative gate (−35) downgrades it.
        val events = feedLevels(detector, levels = List(4) { -50.0 }, floor = -45.0)
        assertEquals(1, events.size)
        assertEquals("OTHER_NOISE", events.single().type)
    }

    // --- Gap merging via deferred emission (EventDetector class KDoc) -----------------

    @Test
    fun `same-type events separated by exactly mergeGapMs merge into one`() {
        // Event A: 0..2500 (closes at 4500). Event B starts at 4500 (first pending window
        // 5500) — gap exactly MERGE_GAP_MS — and closes at 9000, within the deferred hold.
        val events = feed(detector, pattern = snore(4) + quiet(5) + snore(4) + quiet(4)) +
            detector.flush()
        assertEquals(1, events.size)
        val merged = events.single()
        assertEquals("SNORE", merged.type)
        assertEquals(0L, merged.startMs)   // start of the first event preserved
        assertEquals(7000L, merged.endMs)  // spans both events
    }

    @Test
    fun `same-type events separated by more than mergeGapMs stay separate`() {
        // Event B starts at 5000 (first pending window 6000): gap 2500 > MERGE_GAP_MS. The
        // held final is emitted at ts 6000, the moment both machines are idle past its bound.
        val events = feed(detector, pattern = snore(4) + quiet(6) + snore(4) + quiet(4)) +
            detector.flush()
        assertEquals(2, events.size)
        assertEquals("SNORE", events[0].type)
        assertEquals(0L, events[0].startMs)
        assertEquals(2500L, events[0].endMs)
        assertEquals("SNORE", events[1].type)
        assertEquals(5000L, events[1].startMs)
        assertEquals(7500L, events[1].endMs)
    }

    @Test
    fun `deferred emission - closing step returns empty and the draft appears after the gap`() {
        val whileClosing = feed(detector, pattern = snore(4) + quiet(4))
        assertTrue(whileClosing.isEmpty()) // the closed draft is held, not returned
        val later = feed(detector, pattern = quiet(6), startTsMs = 5500)
        assertEquals(1, later.size) // timestamps advanced past the merge window: emitted now
        assertEquals(0L, later.single().startMs)
        assertEquals(2500L, later.single().endMs)
    }

    @Test
    fun `deferred final emits exactly one hop past its expiry bound`() {
        // Event A: 0..2500, closed at 4500 and held. Idle expiry bound =
        // endMs + MERGE_GAP_MS + WINDOW_MS = 5500: the ts-5500 window sits exactly on the
        // bound (strictly greater required — no emission); ts 6000 must emit.
        var ts = 1000L
        repeat(4) {
            detector.update(ts, features(ts), result(snore = true))
            ts += 500
        }
        repeat(6) { // quiet: closes at 4500 (held), then 5000 and 5500 stay inside the bound
            assertTrue(detector.update(ts, features(ts), result(snore = false, loud = false)).isEmpty())
            ts += 500
        }
        val emitted = detector.update(ts, features(ts), result(snore = false, loud = false))
        assertEquals(1, emitted.size)
        assertEquals(0L, emitted.single().startMs)
        assertEquals(2500L, emitted.single().endMs)
    }

    @Test
    fun `held final is not pinned past the hard hold cap by a continuous snore spell`() {
        // OTHER_NOISE final 0..2500 held at 4500; then 30 s of gap-free snoring keeps the
        // snore machine busy, suppressing idle expiry for the OTHER_NOISE bucket. The hard
        // hold cap (endMs + MERGE_GAP_MS + 10 hops = 9500) must emit it mid-spell (first
        // tick past the cap: ts 10000) regardless of the busy machine — and exactly once.
        val events = feed(detector, pattern = loudOnly(4) + quiet(4) + snore(30, loud = false))
        val held = events.filter { it.type == "OTHER_NOISE" }
        assertEquals(1, held.size)
        assertEquals(0L, held.single().startMs)
        assertEquals(2500L, held.single().endMs)
    }

    @Test
    fun `mixed types within the gap do not merge`() {
        // OTHER_NOISE starts at 3500 — 1000 ms after the SNORE final ends, well within
        // MERGE_GAP_MS — but SNORE and OTHER_NOISE never merge with each other.
        val events = feed(detector, pattern = snore(4) + loudOnly(6) + quiet(4)) + detector.flush()
        assertEquals(2, events.size)
        assertEquals("SNORE", events[0].type)
        assertEquals(0L, events[0].startMs)
        assertEquals(2500L, events[0].endMs)
        assertEquals("OTHER_NOISE", events[1].type)
        assertEquals(3500L, events[1].startMs)
    }

    @Test
    fun `downgraded snore merges with a prior other-noise deferred final`() {
        // OTHER_NOISE event 0..2500 held as the deferred final. A later snore spell whose
        // mean sits below the relative level gate (−60 over floor −40 → threshold −30) is
        // downgraded to OTHER_NOISE — same FINAL type — so it folds into the held final.
        feed(detector, pattern = loudOnly(4) + quiet(4))
        var ts = 5500L
        repeat(4) {
            detector.update(ts, features(ts, dbfs = -60.0), result(snore = true))
            ts += 500
        }
        repeat(4) {
            detector.update(ts, features(ts, dbfs = -90.0), result(snore = false))
            ts += 500
        }
        val events = detector.flush()
        assertEquals(1, events.size)
        assertEquals("OTHER_NOISE", events.single().type)
        assertEquals(0L, events.single().startMs) // merged onto the prior OTHER_NOISE final
        assertEquals(7000L, events.single().endMs)
    }

    @Test
    fun `short draft below minimum duration folds into a within-gap deferred final`() {
        // Merge-before-discard: the second spell is 1.5 s alone (below MIN_DURATION_MS) yet
        // within the gap of the held final, so it survives inside the merge instead of being
        // dropped as it would be without a partner.
        feed(detector, pattern = snore(4) + quiet(4)) // SNORE 0..2500 deferred
        var ts = 5500L
        repeat(2) { // opens at the second window (start 4500), active 4500..6000
            detector.update(ts, features(ts), result(snore = true))
            ts += 500
        }
        repeat(4) {
            detector.update(ts, features(ts), result(snore = false))
            ts += 500
        }
        val events = detector.flush()
        assertEquals(1, events.size)
        assertEquals(0L, events.single().startMs)
        assertEquals(6000L, events.single().endMs)
        assertEquals(4, events.single().features.openWindows) // 2 + 2 summed
    }

    @Test
    fun `flush emits remaining deferred finals and a second flush is a no-op`() {
        feed(detector, pattern = snore(4) + quiet(4))
        val flushed = detector.flush()
        assertEquals(1, flushed.size)
        assertEquals(0L, flushed.single().startMs)
        assertEquals(2500L, flushed.single().endMs)
        assertTrue(detector.flush().isEmpty())
    }

    @Test
    fun `flush-closed draft merges with a pending deferred final within the gap`() {
        // The second event is still open when the feed ends; flush closes it and folds it
        // into the held first final (gap exactly MERGE_GAP_MS: B starts at 4500).
        feed(detector, pattern = snore(4) + quiet(5) + snore(4))
        val events = detector.flush()
        assertEquals(1, events.size)
        assertEquals(0L, events.single().startMs)
        assertEquals(7000L, events.single().endMs)
    }

    @Test
    fun `merged draft aggregates peak as max and means window-weighted`() {
        // First event's windows at −30 dBFS, second's at −10: peak must be the louder
        // second event's; the mean is the window-weighted average over all six windows.
        var ts = 1000L
        repeat(4) {
            detector.update(ts, features(ts, dbfs = -30.0), result(snore = true))
            ts += 500
        }
        repeat(4) { // quiet closes the first event at ts 4500 (held as deferred final)
            detector.update(ts, features(ts, dbfs = -90.0), result(snore = false))
            ts += 500
        }
        repeat(4) { // second event 4000..6500 — within the gap, folds into the held final
            detector.update(ts, features(ts, dbfs = -10.0), result(snore = true))
            ts += 500
        }
        repeat(4) {
            detector.update(ts, features(ts, dbfs = -90.0), result(snore = false))
            ts += 500
        }
        val events = detector.flush()
        assertEquals(1, events.size)
        val merged = events.single()
        assertEquals(-10.0, merged.peakDbfs, 0.0001) // max, not first
        // 3 windows at −30 plus 3 at −10 → mean −20 (true window-weighted sum).
        assertEquals(-20.0, merged.meanDbfs, 0.0001)
        assertEquals(0L, merged.startMs)
        assertEquals(6500L, merged.endMs)
        assertEquals(4, merged.features.openWindows)
        assertEquals(0.8, merged.confidence, 0.0001)
    }

    // --- Non-mergeable successor while a final is still held (deferOrFold) -------------

    @Test
    fun `non-mergeable successor arriving while its predecessor is still held is not dropped`() {
        // Event A: SNORE 0..2500, closed at 4500 and held. Five quiet hops leave both machines
        // idle, but a single loud-eligible window at 5500 leaves the loud machine pending — so
        // expiry at 6000 sees a busy machine and applies the 7 s hard cap instead of the 3 s
        // idle bound, keeping A held. The successor burst (SNORE 5000..7500, gap 2.5 s > 2 s)
        // then keeps the snore machine busy through its own close at 9500: deferOrFold observes
        // A still held with a non-mergeable gap. The successor must be stored as the new
        // deferred final, not silently dropped.
        var ts = 1000L
        val drafts = mutableListOf<EventDetector.EventDraft>()
        fun step(snore: Boolean, loud: Boolean) {
            drafts += detector.update(ts, features(ts), result(snore, loud))
            ts += 500
        }
        repeat(4) { step(snore = true, loud = true) }    // A opens: 1000..2500
        repeat(5) { step(snore = false, loud = false) }  // quiet 3000..5000: A closes at 4500 (held)
        step(snore = false, loud = true)                 // 5500: pins loudMachine.pending = 1
        repeat(4) { step(snore = true, loud = false) }   // successor 6000..7500 (opens at 6500)
        repeat(4) { step(snore = false, loud = false) }  // 8000..9500: closes at 9500

        // Nothing was emitted while A was held; its emission happens at the successor's close.
        assertEquals(1, drafts.size)
        val held = drafts.single()
        assertEquals("SNORE", held.type)
        assertEquals(0L, held.startMs)
        assertEquals(2500L, held.endMs)

        // The successor becomes the new deferred final and is emitted one idle bound later.
        repeat(3) { step(snore = false, loud = false) }  // 10000, 10500, 11000
        assertEquals(2, drafts.size)
        val successor = drafts.last()
        assertEquals("SNORE", successor.type)
        assertEquals(5000L, successor.startMs)
        assertEquals(7500L, successor.endMs)

        // Exactly two events total, each emitted exactly once.
        assertTrue(detector.flush().isEmpty())
    }

    // --- Stride-aware hard hold cap (thermal throttling) --------------------------------

    @Test
    fun `hard hold cap shrinks under a 1 s stamp stride so the clip keeps event audio`() {
        // Thermal throttling halves the analysis rate: consecutive update stamps advance
        // 1000 ms. With an unshrunk cap the emission overshoots by that stride: endMs +
        // 7000 + 1000 = the 8 s clip ring horizon exactly — a snapshot with zero event
        // audio (AudioEngine.deliver). The stride-aware cap emits one stamp past
        // endMs + 6500 instead, keeping the event's tail inside the ring.
        val throttled = EventDetector()
        var ts = 1000L
        val drafts = mutableListOf<EventDetector.EventDraft>()
        fun step(snore: Boolean, loud: Boolean) {
            drafts += throttled.update(ts, features(ts), result(snore, loud))
            ts += 1000
        }
        repeat(4) { step(snore = false, loud = true) }  // OTHER_NOISE opens: 1000..4000
        repeat(7) { step(snore = true, loud = false) }  // 5000..11000: snore spell force-closes the
        // loud event at 5000 (held), then keeps the snore machine busy so only the cap can emit it.
        assertEquals(1, drafts.size)
        val held = drafts.single()
        assertEquals("OTHER_NOISE", held.type)
        assertEquals(0L, held.startMs)
        assertEquals(4000L, held.endMs)
        // The snore spell is still open; flush finalizes it, untouched by the cap change.
        val flushed = throttled.flush()
        assertEquals(1, flushed.size)
        assertEquals("SNORE", flushed.single().type)
    }

    @Test
    fun `hard hold cap emission timing is unchanged at the normal 500 ms stride`() {
        // Same shape as the throttled variant at hopMs stride: the cap slack is zero, so the
        // emission stamp is exactly the historical first stamp past endMs + maxHoldMs
        // (2500 + 7000 = 9500, strictly greater → 10000 on the 500 ms grid).
        var ts = 1000L
        val drafts = mutableListOf<EventDetector.EventDraft>()
        fun step(snore: Boolean, loud: Boolean) {
            drafts += detector.update(ts, features(ts), result(snore, loud))
            ts += 500
        }
        repeat(4) { step(snore = false, loud = true) }   // OTHER_NOISE 0..2500 opens
        repeat(14) { step(snore = true, loud = false) }  // 3000..9500: force-closed and held
        assertTrue(drafts.isEmpty())
        step(snore = true, loud = false)                 // 10000: first stamp past the cap
        assertEquals(1, drafts.size)
        val held = drafts.single()
        assertEquals("OTHER_NOISE", held.type)
        assertEquals(0L, held.startMs)
        assertEquals(2500L, held.endMs)
    }

    private fun snore(n: Int, loud: Boolean = true) = List(n) { true to loud }
    private fun quiet(n: Int) = List(n) { false to false }
    private fun loudOnly(n: Int) = List(n) { false to true }
}
