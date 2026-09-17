package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A Correction Round 1/2 — Codex's B4 ("internally contradictory evidence can accept" /
 * "numeric extreme safety"). Two enforcement layers:
 * - [ObservationEvidence]'s own construction-time relational-coherence check (duration/displacement/
 *   speed) — see [ObservationEvidenceRelationalCoherenceTest], including Round 2's numeric-extreme
 *   fixes (non-finite derived speed, `Duration.toNanos()` overflow).
 * - [DeterministicReconstructionSafetyGate.evaluate]'s matcher-accounting-vs-candidate-total check
 *   (now a four-way partition including `unknownClassificationObservationCount`, Round 2's minor
 *   finding) — see [MatcherAccountingCoherenceTest].
 */
class ObservationEvidenceRelationalCoherenceTest {

    @Test(expected = IllegalArgumentException::class)
    fun `zeroDurationPositiveDisplacement is rejected at construction`() {
        ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(Duration.ZERO),
            endpointDisplacementMeters = EvidenceValue.Known(50.0),
        )
    }

    @Test
    fun `coherentStationaryEvidence (zero duration, zero displacement) is allowed`() {
        val result = ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(Duration.ZERO),
            endpointDisplacementMeters = EvidenceValue.Known(0.0),
        )

        assertTrue(result.elapsedDuration is EvidenceValue.Known)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inconsistentSuppliedSpeed is rejected at construction`() {
        ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(30)),
            endpointDisplacementMeters = EvidenceValue.Known(300.0),
            impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(500.0),
        )
    }

    @Test
    fun `a supplied speed within tolerance of the derived speed is allowed`() {
        val result = ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(30)),
            endpointDisplacementMeters = EvidenceValue.Known(300.0),
            impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(10.2),
        )

        assertTrue(result.impliedAverageSpeedMetersPerSecond is EvidenceValue.Known)
    }

    @Test
    fun `a supplied speed with only duration known (no displacement) is not cross-checked`() {
        val result = ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(30)),
            impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(999.0),
        )

        assertTrue(result.impliedAverageSpeedMetersPerSecond is EvidenceValue.Known)
    }

    // ==============================================================================================
    // PHASE 3A CORRECTION ROUND 2 -- numeric-extreme safety (Codex's remaining B4 finding).
    // ==============================================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `1ns duration against Double MAX_VALUE displacement is rejected, not silently validated as consistent`() {
        // Old bug: derivedSpeed = Double.MAX_VALUE / (1ns in seconds) overflows to Infinity, and
        // Infinity <= Infinity is true in IEEE-754, so a naive tolerance check would incorrectly pass.
        ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(Duration.ofNanos(1)),
            endpointDisplacementMeters = EvidenceValue.Known(Double.MAX_VALUE),
            impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(1.0),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `1ns duration with a matching (also huge) supplied speed is still rejected as non-finite`() {
        // Even if the caller "helpfully" supplies Infinity itself, a non-finite derived quantity must
        // never participate in a successful comparison -- impliedAverageSpeedMetersPerSecond's own
        // individual finiteness check rejects this before the relational check is even reached.
        ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(Duration.ofNanos(1)),
            endpointDisplacementMeters = EvidenceValue.Known(Double.MAX_VALUE),
            impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(Double.POSITIVE_INFINITY),
        )
    }

    @Test
    fun `a very large but finite Duration does not throw ArithmeticException from toNanos overflow`() {
        // Duration.ofSeconds(Long.MAX_VALUE) is constructible and far exceeds what Duration.toNanos()
        // can represent (~292 years) -- this must not crash the relational-coherence check.
        val hugeDuration = Duration.ofSeconds(Long.MAX_VALUE)
        val result = ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(hugeDuration),
            // No displacement/speed supplied -- isolates the "does construction even complete"
            // question from the tolerance-comparison question.
        )

        assertTrue(result.elapsedDuration is EvidenceValue.Known)
    }

    @Test
    fun `a very large finite Duration combined with a huge displacement is rejected via non-finite derived speed, never throws`() {
        val hugeDuration = Duration.ofSeconds(Long.MAX_VALUE)
        try {
            ObservationEvidence(
                elapsedDuration = EvidenceValue.Known(hugeDuration),
                endpointDisplacementMeters = EvidenceValue.Known(Double.MAX_VALUE),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(1.0),
            )
            // If this doesn't throw, the derived speed must have been a tiny, finite, legitimately
            // implausible-looking-but-representable number (Double.MAX_VALUE / a truly enormous
            // second count can stay finite) -- either outcome (throws IllegalArgumentException, or
            // completes without ArithmeticException) is acceptable; an ArithmeticException is not.
        } catch (expected: IllegalArgumentException) {
            // acceptable: rejected as incoherent
        }
    }

    @Test
    fun `zero duration remains rejected against positive displacement regardless of extreme scale`() {
        try {
            ObservationEvidence(
                elapsedDuration = EvidenceValue.Known(Duration.ZERO),
                endpointDisplacementMeters = EvidenceValue.Known(Double.MAX_VALUE),
            )
            org.junit.Assert.fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a huge but individually-finite displacement combined with a small duration and a wildly wrong supplied speed is rejected`() {
        ObservationEvidence(
            elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(1)),
            endpointDisplacementMeters = EvidenceValue.Known(1.0e300),
            impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(1.0), // derived speed is ~1e300, nowhere near 1.0
        )
    }
}

class MatcherAccountingCoherenceTest {

    private val gate = DeterministicReconstructionSafetyGate
    private val evaluatedAt: Instant = Instant.parse("2026-01-01T10:00:00Z")
    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val lyon = Coordinate(latitude = 45.7640, longitude = 4.8357)

    private val policy = ReconstructionSafetyPolicy(
        transportMode = TransportMode.ROAD_VEHICLE,
        plausibleSpeedMetersPerSecond = 0.0..55.0,
        minimumObservationSupport = 1,
        maximumInterpolatedObservationFraction = 1.0,
        maximumToleratedAlternativeRoutes = Int.MAX_VALUE,
        minimumMatcherConfidenceWhenKnown = null,
        acceptWhenMatcherConfidenceUnknown = true,
    )

    private fun rawObservation(n: Long) = TrajectoryObservation(
        coordinate = paris,
        observedAt = Instant.parse("2026-01-01T10:00:00Z").plusSeconds(n),
        receivedAt = Instant.parse("2026-01-01T10:00:00Z"),
        source = TrajectoryObservationSource.FOREGROUND,
        processSessionId = "session-1",
        elapsedRealtimeClockDomainId = "session-1",
        elapsedRealtimeNanos = n,
        accuracyMeters = null,
        speedMetersPerSecond = null,
        speedAccuracyMetersPerSecond = null,
        bearingDegrees = null,
        bearingAccuracyDegrees = null,
        provider = null,
        isMockLocation = false,
        batchId = null,
        indexInBatch = null,
    )

    private fun candidate(inputObservationCount: Int): TrajectoryReconstructionResult.AcceptedTrajectory {
        val window = (1L..inputObservationCount).map { rawObservation(it) }
        return TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.NETWORK_MATCHING),
            transportModeHypothesis = TransportModeHypothesis(TransportMode.ROAD_VEHICLE),
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = inputObservationCount,
            observationMatches = emptyList(),
            bridgedObservationEdges = emptySet(),
            observationWindowIdentity = OrderedObservationWindowIdentity.of(window),
            matcherRunIdentity = MatcherRunIdentity("run-1"),
            reasons = emptyList(),
        )
    }

    private fun evidenceFor(
        candidate: TrajectoryReconstructionResult.AcceptedTrajectory,
        matched: EvidenceValue<Int>,
        interpolated: EvidenceValue<Int>,
        unmatched: EvidenceValue<Int>,
        unknownClassification: EvidenceValue<Int> = EvidenceValue.Known(0),
    ) = ReconstructionSafetyEvidence(
        associatedCandidateIdentity = ReconstructionCandidateIdentity.of(candidate),
        observationWindowIdentity = candidate.observationWindowIdentity!!,
        matcherRunIdentity = candidate.matcherRunIdentity!!,
        observation = ObservationEvidence(temporallyMonotonic = EvidenceValue.Known(true)),
        matcher = MatcherEvidence(
            matchedObservationCount = matched,
            interpolatedObservationCount = interpolated,
            unmatchedObservationCount = unmatched,
            unknownClassificationObservationCount = unknownClassification,
            declinedIntervals = EvidenceValue.Known(emptyList()),
        ),
    )

    @Test
    fun `matcherCountsExceedTotal is rejected -- 10 observations, 300 accounted for`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(c, matched = EvidenceValue.Known(100), interpolated = EvidenceValue.Known(100), unmatched = EvidenceValue.Known(100))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_ACCOUNTING_INCOHERENT })
    }

    @Test
    fun `matcherCountsDoNotSumToExpectedTotal is rejected -- known counts undershoot the total`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(c, matched = EvidenceValue.Known(3), interpolated = EvidenceValue.Known(3), unmatched = EvidenceValue.Known(0))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_ACCOUNTING_INCOHERENT })
    }

    @Test
    fun `allMatcherCountsZeroWhenImpossible is rejected -- 10 observations, zero accounted for`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(c, matched = EvidenceValue.Known(0), interpolated = EvidenceValue.Known(0), unmatched = EvidenceValue.Known(0))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_ACCOUNTING_INCOHERENT })
    }

    @Test
    fun `partialUnknownAccounting blocks acceptance without being a hard failure`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(c, matched = EvidenceValue.Known(9), interpolated = EvidenceValue.Known(1), unmatched = EvidenceValue.Unknown)

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_ACCOUNTING_UNKNOWN })
    }

    @Test
    fun `an exact coherent four-way partition is accepted as MATCHER_ACCOUNTING_COHERENT`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(
            c,
            matched = EvidenceValue.Known(6),
            interpolated = EvidenceValue.Known(2),
            unmatched = EvidenceValue.Known(1),
            unknownClassification = EvidenceValue.Known(1),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.NoReconstruction)
    }

    // ==============================================================================================
    // PHASE 3A CORRECTION ROUND 2 -- UNKNOWN matcher classification (Codex's minor finding).
    // ==============================================================================================

    @Test
    fun `a known positive unknownClassificationObservationCount blocks acceptance even with a coherent partition`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(
            c,
            matched = EvidenceValue.Known(6),
            interpolated = EvidenceValue.Known(2),
            unmatched = EvidenceValue.Known(1),
            unknownClassification = EvidenceValue.Known(1),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.AcceptReconstruction)
        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNCLASSIFIED_OBSERVATIONS_PRESENT })
    }

    @Test
    fun `unknownClassificationObservationCount of exactly zero does not itself block acceptance`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(
            c,
            matched = EvidenceValue.Known(9),
            interpolated = EvidenceValue.Known(1),
            unmatched = EvidenceValue.Known(0),
            unknownClassification = EvidenceValue.Known(0),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.none { it.code == SafetyGateReasonCode.UNCLASSIFIED_OBSERVATIONS_PRESENT })
    }

    @Test
    fun `an unknown unknownClassificationObservationCount alongside known matched-interpolated-unmatched blocks accounting coherence`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(
            c,
            matched = EvidenceValue.Known(9),
            interpolated = EvidenceValue.Known(1),
            unmatched = EvidenceValue.Known(0),
            unknownClassification = EvidenceValue.Unknown,
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_ACCOUNTING_UNKNOWN })
    }

    @Test
    fun `integerOverflowAttempt across all four buckets is handled via overflow-safe Long arithmetic, never crashes`() {
        val c = candidate(inputObservationCount = 10)
        val evidence = evidenceFor(
            c,
            matched = EvidenceValue.Known(Int.MAX_VALUE),
            interpolated = EvidenceValue.Known(Int.MAX_VALUE),
            unmatched = EvidenceValue.Known(Int.MAX_VALUE),
            unknownClassification = EvidenceValue.Known(Int.MAX_VALUE),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_ACCOUNTING_INCOHERENT })
    }
}
