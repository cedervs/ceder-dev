package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A Correction Round 2 — Codex's remaining B3 finding ("model edges, not only vertices").
 * The full edge-boundary/validation matrix for [EdgeRange]/[MatcherDeclinedInterval] against
 * [TrajectoryReconstructionResult.AcceptedTrajectory.bridgedObservationEdges], per the round's own
 * §7/§14 required test list.
 */
class EdgeContinuityTest {

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

    /** A candidate with [inputObservationCount] original observations, a same-sized flattened
     * geometry (every geometry vertex labelled `observed` -- see [flattenedObservedVertices_cannotHideDeclinedEdge]
     * for why this is exactly the adversarial shape this model must still catch), and
     * [bridgedObservationEdges] as given. */
    private fun candidate(
        inputObservationCount: Int,
        bridgedObservationEdges: Set<Int>,
    ): TrajectoryReconstructionResult.AcceptedTrajectory {
        val window = (1L..inputObservationCount).map { rawObservation(it) }
        val geometry = List(inputObservationCount) { paris }.let { if (it.size < 2) listOf(paris, lyon) else it }
        return TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = geometry,
            observedIndices = geometry.indices.toSet(), // EVERY vertex labelled observed, deliberately
            inferredIndices = emptySet(),
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
            matcherRunIdentity = MatcherRunIdentity("run-1"),
            reasons = emptyList(),
        )
    }

    private fun evidenceWithDeclined(
        theCandidate: TrajectoryReconstructionResult.AcceptedTrajectory,
        declined: List<MatcherDeclinedInterval>,
    ) = ReconstructionSafetyEvidence(
        associatedCandidateIdentity = ReconstructionCandidateIdentity.of(theCandidate),
        observationWindowIdentity = theCandidate.observationWindowIdentity!!,
        matcherRunIdentity = theCandidate.matcherRunIdentity!!,
        observation = ObservationEvidence(temporallyMonotonic = EvidenceValue.Known(true)),
        matcher = MatcherEvidence(declinedIntervals = EvidenceValue.Known(declined)),
    )

    // ==============================================================================================
    // Boundary cases.
    // ==============================================================================================

    @Test
    fun edge0_boundary() {
        // inputObservationCount=5 -> valid edges 0..3. Edge 0 is the very first edge.
        val c = candidate(inputObservationCount = 5, bridgedObservationEdges = setOf(0))
        val evidence = evidenceWithDeclined(c, listOf(MatcherDeclinedInterval(EdgeRange(0, 0), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    @Test
    fun lastEdge_boundary() {
        // inputObservationCount=5 -> valid edges 0..3. Edge 3 is the very last edge.
        val c = candidate(inputObservationCount = 5, bridgedObservationEdges = setOf(3))
        val evidence = evidenceWithDeclined(c, listOf(MatcherDeclinedInterval(EdgeRange(3, 3), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    @Test
    fun singleEdgeCandidate() {
        // inputObservationCount=2 -> exactly one valid edge, index 0.
        val c = candidate(inputObservationCount = 2, bridgedObservationEdges = setOf(0))
        val evidence = evidenceWithDeclined(c, listOf(MatcherDeclinedInterval(EdgeRange(0, 0), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    @Test
    fun declinedEdge_outOfBounds() {
        // inputObservationCount=5 -> valid edges 0..3; edge 4 does not exist.
        val c = candidate(inputObservationCount = 5, bridgedObservationEdges = emptySet())
        val evidence = evidenceWithDeclined(c, listOf(MatcherDeclinedInterval(EdgeRange(4, 4), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.DECLINED_EDGE_OUT_OF_BOUNDS })
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedDeclinedRange() {
        EdgeRange(startIndex = 5, endIndex = 2)
    }

    @Test
    fun overlappingRanges() {
        // Two overlapping declined ranges, one of which really does overlap a bridged edge -- the
        // gate must still correctly detect it (no crash, no double-counting confusion).
        val c = candidate(inputObservationCount = 8, bridgedObservationEdges = setOf(3))
        val evidence = evidenceWithDeclined(
            c,
            listOf(
                MatcherDeclinedInterval(EdgeRange(1, 4), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
                MatcherDeclinedInterval(EdgeRange(2, 5), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS), // overlaps the first
            ),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    @Test
    fun adjacentRanges() {
        // Two adjacent (touching, non-overlapping) declined ranges; bridged edge falls exactly on the
        // boundary between them.
        val c = candidate(inputObservationCount = 8, bridgedObservationEdges = setOf(3))
        val evidence = evidenceWithDeclined(
            c,
            listOf(
                MatcherDeclinedInterval(EdgeRange(0, 2), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
                MatcherDeclinedInterval(EdgeRange(3, 5), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS), // adjacent to the first
            ),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    @Test
    fun duplicateRanges_areHarmlessAndStillDetected() {
        val c = candidate(inputObservationCount = 8, bridgedObservationEdges = setOf(2))
        val evidence = evidenceWithDeclined(
            c,
            listOf(
                MatcherDeclinedInterval(EdgeRange(2, 2), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
                MatcherDeclinedInterval(EdgeRange(2, 2), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS), // exact duplicate
            ),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    // ==============================================================================================
    // The core B3 finding: a declined edge between two OBSERVED vertices, no inferred vertex needed.
    // ==============================================================================================

    @Test
    fun declinedEdge_withNoInferredVertex_stillRejectsBridge() {
        // Geometry has NO inferred vertices at all (every position is "observed"), yet the candidate
        // still claims (via bridgedObservationEdges) that edge 2 was bridged/inferred at the
        // observation-window level -- exactly the case Round 1's vertex-only check could not catch.
        val c = candidate(inputObservationCount = 8, bridgedObservationEdges = setOf(2))
        assertTrue(c.inferredIndices.isEmpty()) // sanity: genuinely no inferred vertex exists

        val evidence = evidenceWithDeclined(c, listOf(MatcherDeclinedInterval(EdgeRange(2, 2), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    @Test
    fun flattenedObservedVertices_cannotHideDeclinedEdge() {
        // Same shape as above, restated explicitly: ALL geometry vertices are labelled observed
        // (see candidate()'s own construction -- observedIndices always covers every index), so a
        // vertex-provenance-only check would see nothing wrong anywhere. The edge-space check still
        // catches it because it never reads observedIndices/inferredIndices at all.
        val c = candidate(inputObservationCount = 6, bridgedObservationEdges = setOf(0, 1, 2, 3, 4))
        assertTrue(c.observedIndices == c.geometry.indices.toSet())

        val evidence = evidenceWithDeclined(c, listOf(MatcherDeclinedInterval(EdgeRange(3, 3), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)))

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    @Test
    fun multipleSegments_oneDeclinedEdge() {
        // Several declined intervals representing multiple independent segments; only one of them
        // (edge 5) is actually bridged by the candidate -- the gate must identify that specific one.
        val c = candidate(inputObservationCount = 10, bridgedObservationEdges = setOf(5))
        val evidence = evidenceWithDeclined(
            c,
            listOf(
                MatcherDeclinedInterval(EdgeRange(0, 0), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
                MatcherDeclinedInterval(EdgeRange(2, 2), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
                MatcherDeclinedInterval(EdgeRange(5, 5), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS), // the bridged one
                MatcherDeclinedInterval(EdgeRange(8, 8), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
            ),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        val reason = decision.reasons.single { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE }
        val detail = requireNotNull(reason.detail)
        assertTrue(detail.contains("startIndex=5, endIndex=5"))
        assertTrue(!detail.contains("startIndex=0, endIndex=0"))
        assertTrue(!detail.contains("startIndex=2, endIndex=2"))
        assertTrue(!detail.contains("startIndex=8, endIndex=8"))
    }

    @Test
    fun multipleSegments_noneDeclinedEdgeBridged_isSafe() {
        val c = candidate(inputObservationCount = 10, bridgedObservationEdges = emptySet())
        val evidence = evidenceWithDeclined(
            c,
            listOf(
                MatcherDeclinedInterval(EdgeRange(0, 0), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
                MatcherDeclinedInterval(EdgeRange(5, 5), MatcherSplitOutcome.WHOLE_CANDIDATE_REJECTED),
                MatcherDeclinedInterval(EdgeRange(8, 8), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
            ),
        )

        val decision = gate.evaluate(c, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.NoReconstruction)
    }
}
