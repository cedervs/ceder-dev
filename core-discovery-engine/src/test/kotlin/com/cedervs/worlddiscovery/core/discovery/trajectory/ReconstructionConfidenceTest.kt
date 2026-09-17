package com.cedervs.worlddiscovery.core.discovery.trajectory

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconstructionConfidenceTest {

    @Test
    fun `every component beyond overall defaults to Unknown`() {
        val confidence = ReconstructionConfidence(overall = ConfidenceValue.Known(0.8))

        assertEquals(ConfidenceValue.Unknown, confidence.gpsQuality)
        assertEquals(ConfidenceValue.Unknown, confidence.temporalCoherence)
        assertEquals(ConfidenceValue.Unknown, confidence.gapDurationAndDistance)
        assertEquals(ConfidenceValue.Unknown, confidence.kinematicPlausibility)
        assertEquals(ConfidenceValue.Unknown, confidence.observationToCandidateDistance)
        assertEquals(ConfidenceValue.Unknown, confidence.topologicalContinuity)
        assertEquals(ConfidenceValue.Unknown, confidence.pathAmbiguity)
        assertEquals(ConfidenceValue.Unknown, confidence.networkCoverage)
        assertEquals(ConfidenceValue.Unknown, confidence.transportModeConfidence)
        assertEquals(ConfidenceValue.Unknown, confidence.bearingCoherence)
        assertEquals(ConfidenceValue.Unknown, confidence.speedCoherence)
        assertEquals(ConfidenceValue.Unknown, confidence.modeChangeLikelihood)
        assertEquals(ConfidenceValue.Unknown, confidence.graphFreshness)
    }

    @Test
    fun `UNKNOWN constant has every component including overall as Unknown`() {
        assertEquals(ConfidenceValue.Unknown, ReconstructionConfidence.UNKNOWN.overall)
    }

    @Test
    fun `ConfidenceValue Known accepts the full 0 to 1 range inclusive`() {
        ConfidenceValue.Known(0.0)
        ConfidenceValue.Known(1.0)
        ConfidenceValue.Known(0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ConfidenceValue Known rejects a value below 0`() {
        ConfidenceValue.Known(-0.01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ConfidenceValue Known rejects a value above 1`() {
        ConfidenceValue.Known(1.01)
    }

    // ==============================================================================================
    // Edge cases (Codex review round) -- NaN/Infinity are already rejected by the existing
    // `value in 0.0..1.0` bounds check (a range containment check is false for NaN, and both
    // infinities fall outside [0.0, 1.0]); these tests exist to make that guarantee explicit and
    // regression-proof rather than an incidental side effect of the range check.
    // ==============================================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `ConfidenceValue Known rejects NaN`() {
        ConfidenceValue.Known(Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ConfidenceValue Known rejects positive Infinity`() {
        ConfidenceValue.Known(Double.POSITIVE_INFINITY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ConfidenceValue Known rejects negative Infinity`() {
        ConfidenceValue.Known(Double.NEGATIVE_INFINITY)
    }
}
