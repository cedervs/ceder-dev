package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A round's §18 — abstract, non-private regression fixtures encoding Phase 2B's own accepted
 * semantic findings, so a future change to [DeterministicReconstructionSafetyGate] cannot silently
 * forget a lesson the physical benchmarking rounds already paid for. **No coordinates, street names,
 * place names, or any other identifying data from the real Phase 2B captures appear here.**
 *
 * **PHASE 3A CORRECTION ROUND 1 (Codex's M3 "benchmark regression fixture misattributes the OSRM
 * lesson").** The original round's fixture modeled Scenario 1's accepted OSRM 27s `WRONG_ROAD`
 * finding as a matcher-split/unsupported-bridge scenario. That is factually wrong — the real finding
 * was a **single continuous matching with no split at all**. The split/unsupported-bridge lesson
 * instead belongs to **2B-5**'s controlled-gap study. See each fixture's own doc comment.
 *
 * **PHASE 3A CORRECTION ROUND 2** — updated for the three-layer identity model (window/run/geometry)
 * and the edge-based declined-continuity model ([EdgeRange], observation-window edge space).
 */
class ReconstructionSafetyBenchmarkRegressionFixturesTest {

    private val gate = DeterministicReconstructionSafetyGate
    private val evaluatedAt: Instant = Instant.parse("2026-01-01T10:00:00Z")
    private val somewhere = Coordinate(latitude = 45.0, longitude = 1.0)
    private val somewhereElse = Coordinate(latitude = 45.01, longitude = 1.01)

    private val policy = ReconstructionSafetyPolicy(
        transportMode = TransportMode.ROAD_VEHICLE,
        plausibleSpeedMetersPerSecond = 0.0..40.0,
        minimumObservationSupport = 5,
        maximumInterpolatedObservationFraction = 0.4,
        maximumToleratedAlternativeRoutes = 2,
        minimumMatcherConfidenceWhenKnown = 0.6,
        acceptWhenMatcherConfidenceUnknown = false,
    )

    private fun rawObservation(n: Long) = TrajectoryObservation(
        coordinate = somewhere,
        observedAt = Instant.parse("2026-01-01T09:00:00Z").plusSeconds(n),
        receivedAt = Instant.parse("2026-01-01T09:00:00Z"),
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

    private fun candidate(
        inferredIndices: Set<Int> = emptySet(),
        inputObservationCount: Int = 8,
        bridgedObservationEdges: Set<Int> = emptySet(),
    ): TrajectoryReconstructionResult.AcceptedTrajectory {
        val window = (1L..inputObservationCount).map { rawObservation(it) }
        return TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = if (inferredIndices.isEmpty()) listOf(somewhere, somewhereElse) else listOf(somewhere, somewhere, somewhereElse),
            observedIndices = if (inferredIndices.isEmpty()) setOf(0, 1) else setOf(0, 2),
            inferredIndices = inferredIndices,
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.NETWORK_MATCHING),
            transportModeHypothesis = TransportModeHypothesis(TransportMode.ROAD_VEHICLE),
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = inputObservationCount,
            observationMatches = emptyList(),
            bridgedObservationEdges = bridgedObservationEdges,
            observationWindowIdentity = OrderedObservationWindowIdentity.of(window),
            matcherRunIdentity = MatcherRunIdentity("fixture-run"),
            reasons = emptyList(),
        )
    }

    private fun evidenceFor(
        theCandidate: TrajectoryReconstructionResult.AcceptedTrajectory,
        observation: ObservationEvidence = ObservationEvidence(),
        networkRoute: NetworkRouteEvidence = NetworkRouteEvidence(),
        matcher: MatcherEvidence = MatcherEvidence(),
    ) = ReconstructionSafetyEvidence(
        associatedCandidateIdentity = ReconstructionCandidateIdentity.of(theCandidate),
        observationWindowIdentity = theCandidate.observationWindowIdentity!!,
        matcherRunIdentity = theCandidate.matcherRunIdentity!!,
        observation = observation,
        networkRoute = networkRoute,
        matcher = matcher,
    )

    /**
     * Traces to **Phase 2B-2C** — corrected attribution (Codex's M3): OSRM's real 27s finding was a
     * single continuous matching with **no split**, confidently `WRONG_ROAD`. This fixture is
     * deliberately an **honest limitation**, not a passing safety demonstration: given only favorable
     * evidence in every category this gate reasons over, the gate correctly accepts. A confidently-
     * wrong single continuous match with no other unfavorable signal is out of this generic gate's
     * reach with today's evidence categories — detecting it in the real benchmark round required a
     * denser ground-truth reference trace, an evidence-*generation* concern upstream of this gate.
     */
    @Test
    fun singleContinuousWrongMatch_isTheGatesHonestLimitation() {
        val theCandidate = candidate(inferredIndices = emptySet(), inputObservationCount = 8)
        val evidence = evidenceFor(
            theCandidate,
            observation = ObservationEvidence(
                elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(27)),
                endpointDisplacementMeters = EvidenceValue.Known(37.8),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(1.4),
                temporallyMonotonic = EvidenceValue.Known(true),
            ),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(8),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(emptyList()), // no split -- exactly 2B-2C's real signature
                nativeConfidence = ConfidenceValue.Known(0.9), // matcher was confident -- and wrong
            ),
        )

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
    }

    /** Companion to [singleContinuousWrongMatch_isTheGatesHonestLimitation]: when realistic degraded
     * confidence IS reflected in the evidence, the gate correctly defers instead of accepting. */
    @Test
    fun singleContinuousWrongMatch_withRealisticDegradedConfidence_deferInstead() {
        val theCandidate = candidate(inferredIndices = emptySet(), inputObservationCount = 8)
        val evidence = evidenceFor(
            theCandidate,
            observation = ObservationEvidence(
                elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(27)),
                endpointDisplacementMeters = EvidenceValue.Known(37.8),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(1.4),
                temporallyMonotonic = EvidenceValue.Known(true),
            ),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(8),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(emptyList()),
                nativeConfidence = ConfidenceValue.Known(0.3),
            ),
        )

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_CONFIDENCE_WEAK })
    }

    /** Traces to **Phase 2B-3B**: Valhalla's AMBIGUOUS classification at sparse cadences in the
     * parallel-urban-corridors scenario -- multiple plausible corridors, not a wrong claim. */
    @Test
    fun urbanParallelCorridorAmbiguity_requiresMoreEvidence() {
        val theCandidate = candidate()
        val evidence = evidenceFor(
            theCandidate,
            observation = ObservationEvidence(
                temporallyMonotonic = EvidenceValue.Known(true),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(1.4),
            ),
            networkRoute = NetworkRouteEvidence(
                networkComplexity = EvidenceValue.Known(NetworkComplexity.HIGH),
                plausibleAlternativeRouteCount = EvidenceValue.Known(4),
            ),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(8),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(emptyList()),
            ),
        )

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.HIGH_NETWORK_AMBIGUITY })
    }

    /** Traces to **Phase 2B-4**: the vehicle scenario's AMBIGUOUS classification at 27s/45s,
     * attributed to a real trip endpoint sitting off the routable network. */
    @Test
    fun vehicleOffNetworkEndpoint_requiresMoreEvidence() {
        val theCandidate = candidate(inputObservationCount = 8)
        val evidence = evidenceFor(
            theCandidate,
            observation = ObservationEvidence(
                temporallyMonotonic = EvidenceValue.Known(true),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(18.0),
            ),
            networkRoute = NetworkRouteEvidence(endpointPlausible = EvidenceValue.Known(false)),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(8),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(emptyList()),
            ),
        )

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.ENDPOINT_IMPLAUSIBLE })
    }

    /** Traces to **Phase 2B-5**: Valhalla stayed SUPPORTED_CORRIDOR on SIMPLE_ROAD/JUNCTION_OR_FORK
     * gaps up to 180s -- evidence against a universal duration threshold. Duration is collected but
     * never read by any gate rule. */
    @Test
    fun longGapOnSimpleCorridor_isStillAcceptedOnItsOwnEvidence() {
        val theCandidate = candidate(inputObservationCount = 8)
        val evidence = evidenceFor(
            theCandidate,
            observation = ObservationEvidence(
                elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(180)),
                endpointDisplacementMeters = EvidenceValue.Known(3_240.0),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(18.0),
                temporallyMonotonic = EvidenceValue.Known(true),
            ),
            networkRoute = NetworkRouteEvidence(
                networkComplexity = EvidenceValue.Known(NetworkComplexity.LOW),
                plausibleAlternativeRouteCount = EvidenceValue.Known(0),
                endpointPlausible = EvidenceValue.Known(true),
            ),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(8),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(emptyList()),
                nativeConfidence = ConfidenceValue.Known(0.9),
            ),
        )

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
    }

    /** Traces to **Phase 2B-5**: Valhalla was AMBIGUOUS on URBAN_COMPLEX gaps even at only 30s --
     * network context, not duration, drove the outcome. */
    @Test
    fun shortGapInHighlyAmbiguousNetwork_stillRequiresMoreEvidence() {
        val theCandidate = candidate(inputObservationCount = 8)
        val evidence = evidenceFor(
            theCandidate,
            observation = ObservationEvidence(
                elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(30)),
                endpointDisplacementMeters = EvidenceValue.Known(540.0),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(18.0),
                temporallyMonotonic = EvidenceValue.Known(true),
            ),
            networkRoute = NetworkRouteEvidence(
                networkComplexity = EvidenceValue.Known(NetworkComplexity.HIGH),
                plausibleAlternativeRouteCount = EvidenceValue.Known(5),
            ),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(8),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(emptyList()),
                nativeConfidence = ConfidenceValue.Known(0.5),
            ),
        )

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
    }

    /** Correctly attributed to **Phase 2B-5**'s central finding: OSRM split at every one of 14
     * controlled gaps and this was its maximally conservative, *safe* default. The candidate presents
     * no bridged edge across the gap the matcher declined, exactly like 2B-5's real OSRM behavior. */
    @Test
    fun matcherSplitAcrossMissingObservations_isUncertainty() {
        val theCandidate = candidate(inferredIndices = emptySet(), inputObservationCount = 8, bridgedObservationEdges = emptySet())
        val evidence = evidenceFor(
            theCandidate,
            observation = ObservationEvidence(
                temporallyMonotonic = EvidenceValue.Known(true),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(18.0),
            ),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(8),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(
                    listOf(MatcherDeclinedInterval(EdgeRange(0, 1), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)),
                ),
            ),
        )

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        // No hard rejection: no bridge was invented across the declined edges.
        assertTrue(decision !is ReconstructionSafetyDecision.NoReconstruction)
    }

    /** The corrected split/unsupported-bridge lesson, still traced to **Phase 2B-5**'s own
     * anti-cheating methodology: a candidate that bridges exactly the observation-window edge the
     * matcher declined. */
    @Test
    fun matcherSplitWithInventedBridge_isRejected() {
        val theCandidate = candidate(inferredIndices = setOf(1), inputObservationCount = 8, bridgedObservationEdges = setOf(1))
        val evidence = evidenceFor(
            theCandidate,
            observation = ObservationEvidence(
                temporallyMonotonic = EvidenceValue.Known(true),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(18.0),
            ),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(8),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(
                    listOf(MatcherDeclinedInterval(EdgeRange(1, 1), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)),
                ),
            ),
        )

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }
}
