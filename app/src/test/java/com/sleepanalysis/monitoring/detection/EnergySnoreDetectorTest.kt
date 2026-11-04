package com.sleepanalysis.monitoring.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergySnoreDetectorTest {

    private val detector = EnergySnoreDetector()

    private fun features(
        dbfs: Double,
        lowBandRatio: Double,
        floor: Double = -40.0,
        timestampMs: Long = 0L,
        spectralFlatness: Double? = null,
        harmonicRatio: Double? = null,
    ) = AudioFeatures(
        windowDbfs = dbfs,
        noiseFloorDbfs = floor,
        lowBandRatio = lowBandRatio,
        timestampMs = timestampMs,
        spectralFlatness = spectralFlatness,
        harmonicRatio = harmonicRatio,
    )

    /** Spectral-passing snore-like window: tonal, periodic, strong SNR. */
    private fun snoreLike(dbfs: Double, timestampMs: Long = 0L) =
        features(
            dbfs = dbfs,
            lowBandRatio = 0.8,
            timestampMs = timestampMs,
            spectralFlatness = 0.2,
            harmonicRatio = 0.7,
        )

    @Test
    fun `loud low-band window above snr threshold is classified as snore`() {
        val result = detector.classify(features(dbfs = -15.0, lowBandRatio = 0.8))
        assertTrue(result.snore)
        assertTrue(result.loudAcousticEvent)
        assertTrue(result.confidence in 0.0..1.0)
    }

    @Test
    fun `loud broadband window is loud event but not snore`() {
        val result = detector.classify(features(dbfs = -15.0, lowBandRatio = 0.2))
        assertFalse(result.snore)
        assertTrue(result.loudAcousticEvent)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `quiet window is neither snore nor loud`() {
        val result = detector.classify(features(dbfs = -39.0, lowBandRatio = 0.8))
        assertFalse(result.snore)
        assertFalse(result.loudAcousticEvent)
    }

    @Test
    fun `window near noise floor is not loud`() {
        val result = detector.classify(features(dbfs = -34.0, lowBandRatio = 0.8, floor = -40.0))
        assertFalse(result.snore)
        assertFalse(result.loudAcousticEvent)
    }

    @Test
    fun `confidence increases with snr margin`() {
        val low = detector.classify(features(dbfs = -30.0, lowBandRatio = 0.9)) // snr 10
        val high = detector.classify(features(dbfs = -20.0, lowBandRatio = 0.9)) // snr 20
        assertTrue(high.confidence > low.confidence)
    }

    @Test
    fun `null spectral features behave exactly as v0 - marginal snr window accepted without history`() {
        // snr 16 (above the loud gate at floor+12, below STRONG_SNR_DB) would need rhythm
        // history under v1; the null-feature path must stay byte-for-byte v0 (older sessions,
        // stub producers).
        val result = detector.classify(features(dbfs = -24.0, lowBandRatio = 0.9))
        assertTrue(result.snore)
        assertEquals(0.5 + 0.3 * ((16.0 - 8.0) / 12.0) + 0.2 * ((0.9 - 0.55) / 0.45), result.confidence, 1e-9)
    }

    @Test
    fun `flat broadband window is rejected even with good snr and band ratio`() {
        val result = detector.classify(
            features(dbfs = -15.0, lowBandRatio = 0.8, spectralFlatness = 0.9, harmonicRatio = 0.1),
        )
        assertFalse(result.snore)
        assertTrue(result.loudAcousticEvent)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `inharmonic window with low flatness is rejected by the harmonicity gate`() {
        val result = detector.classify(
            features(dbfs = -15.0, lowBandRatio = 0.8, spectralFlatness = 0.2, harmonicRatio = 0.1),
        )
        assertFalse(result.snore)
    }

    @Test
    fun `harmonic snore-like window passes the spectral gates`() {
        val result = detector.classify(snoreLike(dbfs = -15.0))
        assertTrue(result.snore)
        assertTrue(result.loudAcousticEvent)
        assertTrue(result.confidence in 0.0..1.0)
    }

    @Test
    fun `strong snr window is accepted without rhythm history`() {
        val result = detector.classify(snoreLike(dbfs = -15.0, timestampMs = 0L)) // snr 25
        assertTrue(result.snore)
    }

    @Test
    fun `marginal snr window is rejected without rhythm history`() {
        // snr 12: above SNORE_MIN_SNR_DB, below STRONG_SNR_DB, no prior snore window.
        val result = detector.classify(snoreLike(dbfs = -28.0, timestampMs = 0L))
        assertFalse(result.snore)
    }

    @Test
    fun `marginal window is accepted right after a snore window within the rhythm window`() {
        assertTrue(detector.classify(snoreLike(dbfs = -15.0, timestampMs = 0L)).snore)
        val result = detector.classify(snoreLike(dbfs = -28.0, timestampMs = 1_000L))
        assertTrue(result.snore)
    }

    @Test
    fun `marginal window is rejected when the previous snore window is older than the rhythm window`() {
        assertTrue(detector.classify(snoreLike(dbfs = -15.0, timestampMs = 0L)).snore)
        val result = detector.classify(snoreLike(dbfs = -28.0, timestampMs = 8_001L))
        assertFalse(result.snore)
    }

    @Test
    fun `window failing the spectral gates does not seed the rhythm memory`() {
        // A loud broadband spike must not legitimize the next marginal window.
        assertFalse(
            detector.classify(
                features(dbfs = -15.0, lowBandRatio = 0.8, timestampMs = 0L, spectralFlatness = 0.9, harmonicRatio = 0.1),
            ).snore,
        )
        val result = detector.classify(snoreLike(dbfs = -28.0, timestampMs = 1_000L))
        assertFalse(result.snore)
    }

    @Test
    fun `reset clears the rhythm memory`() {
        assertTrue(detector.classify(snoreLike(dbfs = -15.0, timestampMs = 0L)).snore)
        detector.reset()
        val result = detector.classify(snoreLike(dbfs = -28.0, timestampMs = 1_000L))
        assertFalse(result.snore)
    }

    @Test
    fun `rhythm disabled via config accepts a marginal window without history`() {
        val rhythmOff = EnergySnoreDetector(
            EnergySnoreDetector.DetectorConfigValues(rhythmEnabled = false),
        )
        val result = rhythmOff.classify(snoreLike(dbfs = -28.0, timestampMs = 0L))
        assertTrue(result.snore)
    }

    @Test
    fun `spectral gating disabled via config restores v0 behavior for spectral-bearing features`() {
        val gatingOff = EnergySnoreDetector(
            EnergySnoreDetector.DetectorConfigValues(spectralGatingEnabled = false),
        )
        val result = gatingOff.classify(
            features(dbfs = -15.0, lowBandRatio = 0.8, spectralFlatness = 0.9, harmonicRatio = 0.1),
        )
        assertTrue(result.snore)
    }

    @Test
    fun `spectral gating disabled scores passing windows on the exact v0 confidence scale`() {
        // The A/B arm must not silently inherit the blended v1 weights: same spectral-bearing
        // features, gating off -> 0.5 + 0.3*snrMargin + 0.2*bandMargin (no flatness term).
        val gatingOff = EnergySnoreDetector(
            EnergySnoreDetector.DetectorConfigValues(spectralGatingEnabled = false),
        )
        val result = gatingOff.classify(
            features(dbfs = -15.0, lowBandRatio = 0.8, timestampMs = 0L, spectralFlatness = 0.2, harmonicRatio = 0.7),
        )
        assertTrue(result.snore)
        assertEquals(
            0.5 + 0.3 * (((25.0 - 8.0) / 12.0).coerceIn(0.0, 1.0)) +
                0.2 * (((0.8 - 0.55) / 0.45).coerceIn(0.0, 1.0)),
            result.confidence,
            1e-9,
        )
    }

    @Test
    fun `blended confidence matches the documented v1 formula exactly`() {
        // snr 20, band 0.8, flatness 0.2: 0.5 + 0.25*snrMargin + 0.15*bandMargin + 0.10*flatnessMargin.
        val result = detector.classify(snoreLike(dbfs = -20.0, timestampMs = 0L))
        assertTrue(result.snore)
        assertEquals(
            0.5 + 0.25 * ((20.0 - 8.0) / 12.0) +
                0.15 * ((0.8 - 0.55) / 0.45) +
                0.10 * ((0.55 - 0.2) / 0.55),
            result.confidence,
            1e-9,
        )
    }

    @Test
    fun `confidence blends the flatness margin when spectral features are present`() {
        val flat = detector.classify(snoreLike(dbfs = -20.0, timestampMs = 0L)) // flatness 0.2
        val peaked = detector.classify(
            features(
                dbfs = -20.0,
                lowBandRatio = 0.8,
                timestampMs = 1_000L,
                spectralFlatness = 0.05,
                harmonicRatio = 0.7,
            ),
        )
        // Same snr/band; the lower flatness must earn strictly more confidence.
        assertTrue(peaked.confidence > flat.confidence)
    }

    @Test
    fun `reset keeps the detector functional`() {
        detector.reset()
        val result = detector.classify(features(dbfs = -15.0, lowBandRatio = 0.8))
        assertTrue(result.snore)
    }
}
