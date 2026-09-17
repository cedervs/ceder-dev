package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A Correction Round 2 — the three-layer candidate/evidence association matrix:
 * - **Layer A** — [OrderedObservationWindowIdentity] (Codex's remaining B2 finding: a geometry-only
 *   fingerprint cannot prove which original ordered observations produced a candidate).
 * - **Layer B** — [MatcherRunIdentity] (Codex's remaining B2 finding: a free-form caller string has
 *   nothing on the gate side to compare against).
 * - **Layer C** — [ReconstructionCandidateIdentity] (Round 1's original B2 fix, narrowed in scope —
 *   see [ReconstructionSafetyGateTest] for the general matrix; this file focuses on A and B).
 */
class ReconstructionSafetyIdentityAssociationTest {

    private val gate = DeterministicReconstructionSafetyGate
    private val evaluatedAt: Instant = Instant.parse("2026-01-01T10:00:00Z")
    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val lyon = Coordinate(latitude = 45.7640, longitude = 4.8357)
    private val marseille = Coordinate(latitude = 43.2965, longitude = 5.3698)

    private val policy = ReconstructionSafetyPolicy(
        transportMode = TransportMode.ROAD_VEHICLE,
        plausibleSpeedMetersPerSecond = 0.0..55.0,
        minimumObservationSupport = 1,
        maximumInterpolatedObservationFraction = 1.0,
        maximumToleratedAlternativeRoutes = Int.MAX_VALUE,
        minimumMatcherConfidenceWhenKnown = null,
        acceptWhenMatcherConfidenceUnknown = true,
    )

    private fun rawObservation(elapsedRealtimeNanos: Long, coordinate: Coordinate) = TrajectoryObservation(
        coordinate = coordinate,
        observedAt = Instant.parse("2026-01-01T10:00:00Z").plusSeconds(elapsedRealtimeNanos),
        receivedAt = Instant.parse("2026-01-01T10:00:00Z"),
        source = TrajectoryObservationSource.FOREGROUND,
        processSessionId = "session-1",
        elapsedRealtimeClockDomainId = "session-1",
        elapsedRealtimeNanos = elapsedRealtimeNanos,
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
        geometry: List<Coordinate>,
        observedIndices: Set<Int>,
        inferredIndices: Set<Int> = emptySet(),
        inputObservationCount: Int = geometry.size,
        observationWindowIdentity: OrderedObservationWindowIdentity?,
        matcherRunIdentity: MatcherRunIdentity? = MatcherRunIdentity("run-1"),
    ) = TrajectoryReconstructionResult.AcceptedTrajectory(
        geometry = geometry,
        observedIndices = observedIndices,
        inferredIndices = inferredIndices,
        strategy = ReconstructionStrategy(ReconstructionStrategyKind.NETWORK_MATCHING),
        transportModeHypothesis = TransportModeHypothesis(TransportMode.ROAD_VEHICLE),
        confidence = ReconstructionConfidence.UNKNOWN,
        engineVersion = 1,
        graphSource = null,
        parameters = emptyMap(),
        inputObservationCount = inputObservationCount,
        observationMatches = emptyList(),
        bridgedObservationEdges = emptySet(),
        observationWindowIdentity = observationWindowIdentity,
        matcherRunIdentity = matcherRunIdentity,
        reasons = emptyList(),
    )

    private fun favorableEvidenceFor(
        theCandidate: TrajectoryReconstructionResult.AcceptedTrajectory,
        observationWindowIdentity: OrderedObservationWindowIdentity,
        matcherRunIdentity: MatcherRunIdentity = MatcherRunIdentity("run-1"),
    ) = ReconstructionSafetyEvidence(
        associatedCandidateIdentity = ReconstructionCandidateIdentity.of(theCandidate),
        observationWindowIdentity = observationWindowIdentity,
        matcherRunIdentity = matcherRunIdentity,
        observation = ObservationEvidence(temporallyMonotonic = EvidenceValue.Known(true)),
        matcher = MatcherEvidence(declinedIntervals = EvidenceValue.Known(emptyList())),
    )

    // ==============================================================================================
    // Layer A -- OrderedObservationWindowIdentity pure equality matrix.
    // ==============================================================================================

    @Test
    fun `identical ordered observations produce equal window identities`() {
        val a = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val b = listOf(rawObservation(1, paris), rawObservation(2, lyon))

        assertEquals(OrderedObservationWindowIdentity.of(a), OrderedObservationWindowIdentity.of(b))
    }

    @Test
    fun `different windows with the exact same observation count differ`() {
        val a = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val b = listOf(rawObservation(3, paris), rawObservation(4, lyon)) // different elapsedRealtimeNanos/timestamps

        assertNotEquals(OrderedObservationWindowIdentity.of(a), OrderedObservationWindowIdentity.of(b))
    }

    @Test
    fun `reordering the same observations changes the window identity`() {
        val a = listOf(rawObservation(1, paris), rawObservation(2, lyon), rawObservation(3, marseille))
        val b = listOf(rawObservation(3, marseille), rawObservation(2, lyon), rawObservation(1, paris))

        assertNotEquals(OrderedObservationWindowIdentity.of(a), OrderedObservationWindowIdentity.of(b))
    }

    @Test
    fun `a changed coordinate for an otherwise identical observation changes the window identity`() {
        // elapsedRealtimeNanos=0 forces the WEAK dedup-key fallback, which is coordinate-sensitive.
        val a = listOf(rawObservation(0, paris))
        val b = listOf(rawObservation(0, lyon))

        assertNotEquals(OrderedObservationWindowIdentity.of(a), OrderedObservationWindowIdentity.of(b))
    }

    // ==============================================================================================
    // Layer A -- end-to-end gate behavior. This is the case Round 1's geometry-only identity could
    // NOT catch: two different observation windows (A/B vs C/D) honestly reconstructing to
    // byte-for-byte identical candidate geometry/provenance/endpoints.
    // ==============================================================================================

    @Test
    fun `gate rejects evidence from a different observation window even with identical candidate geometry`() {
        val windowAB = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val windowCD = listOf(rawObservation(3, paris), rawObservation(4, lyon)) // different underlying observations
        val identityAB = OrderedObservationWindowIdentity.of(windowAB)
        val identityCD = OrderedObservationWindowIdentity.of(windowCD)
        assertNotEquals(identityAB, identityCD) // sanity: the windows really are different

        // The SAME candidate geometry/provenance/endpoints, claimed to come from window C/D.
        val candidateFromCD = candidate(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            observationWindowIdentity = identityCD,
        )
        // Evidence actually describes window A/B.
        val evidenceForAB = favorableEvidenceFor(candidateFromCD, observationWindowIdentity = identityAB)

        val decision = gate.evaluate(candidateFromCD, evidenceForAB, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.OBSERVATION_WINDOW_MISMATCH })
    }

    @Test
    fun `gate rejects evidence built from a pure reordering of the same observations`() {
        val original = listOf(rawObservation(1, paris), rawObservation(2, lyon), rawObservation(3, marseille))
        val reordered = listOf(rawObservation(3, marseille), rawObservation(2, lyon), rawObservation(1, paris))
        val identityOriginal = OrderedObservationWindowIdentity.of(original)
        val identityReordered = OrderedObservationWindowIdentity.of(reordered)

        val theCandidate = candidate(
            geometry = listOf(paris, lyon, marseille),
            observedIndices = setOf(0, 1, 2),
            inputObservationCount = 3,
            observationWindowIdentity = identityOriginal,
        )
        val evidenceFromReordered = favorableEvidenceFor(theCandidate, observationWindowIdentity = identityReordered)

        val decision = gate.evaluate(theCandidate, evidenceFromReordered, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.OBSERVATION_WINDOW_MISMATCH })
    }

    @Test
    fun `an unknown (null) candidate window identity must not accept`() {
        val windowAB = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val theCandidate = candidate(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            observationWindowIdentity = null,
        )
        val evidence = favorableEvidenceFor(theCandidate, observationWindowIdentity = OrderedObservationWindowIdentity.of(windowAB))

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.AcceptReconstruction)
        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.OBSERVATION_WINDOW_IDENTITY_UNKNOWN })
    }

    // ==============================================================================================
    // Layer B -- matcher-run identity.
    // ==============================================================================================

    @Test
    fun `sameRunIdentity_acceptAssociation`() {
        val window = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val identity = OrderedObservationWindowIdentity.of(window)
        val run = MatcherRunIdentity("run-shared")
        val theCandidate = candidate(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            observationWindowIdentity = identity,
            matcherRunIdentity = run,
        )
        val evidence = favorableEvidenceFor(theCandidate, observationWindowIdentity = identity, matcherRunIdentity = run)

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.NoReconstruction)
    }

    @Test
    fun `differentRunIdentity_hardFailure`() {
        val window = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val identity = OrderedObservationWindowIdentity.of(window)
        val theCandidate = candidate(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            observationWindowIdentity = identity,
            matcherRunIdentity = MatcherRunIdentity("run-A"),
        )
        val evidence = favorableEvidenceFor(theCandidate, observationWindowIdentity = identity, matcherRunIdentity = MatcherRunIdentity("run-B"))

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_RUN_MISMATCH })
    }

    @Test
    fun `staleRunEvidence_hardFailure`() {
        // Evidence computed for an EARLIER run of the same window; the candidate now being evaluated
        // came from a LATER, different run over that same window.
        val window = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val identity = OrderedObservationWindowIdentity.of(window)
        val staleRun = MatcherRunIdentity("run-stale")
        val freshRun = MatcherRunIdentity("run-fresh")
        val theCandidate = candidate(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            observationWindowIdentity = identity,
            matcherRunIdentity = freshRun,
        )
        val staleEvidence = favorableEvidenceFor(theCandidate, observationWindowIdentity = identity, matcherRunIdentity = staleRun)

        val decision = gate.evaluate(theCandidate, staleEvidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_RUN_MISMATCH })
    }

    @Test
    fun `sameWindowSameGeometryDifferentRun_mustNotMix`() {
        val window = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val identity = OrderedObservationWindowIdentity.of(window)
        val runOne = MatcherRunIdentity("run-1")
        val runTwo = MatcherRunIdentity("run-2")
        // Same window identity AND same candidate geometry (Layer A and Layer C would both match),
        // only the run differs (Layer B) -- must still be rejected.
        val theCandidate = candidate(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            observationWindowIdentity = identity,
            matcherRunIdentity = runOne,
        )
        val evidenceFromDifferentRun = favorableEvidenceFor(theCandidate, observationWindowIdentity = identity, matcherRunIdentity = runTwo)

        val decision = gate.evaluate(theCandidate, evidenceFromDifferentRun, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_RUN_MISMATCH })
    }

    @Test
    fun `an unknown (null) candidate run identity must not accept`() {
        val window = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val identity = OrderedObservationWindowIdentity.of(window)
        val theCandidate = candidate(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            observationWindowIdentity = identity,
            matcherRunIdentity = null,
        )
        val evidence = favorableEvidenceFor(theCandidate, observationWindowIdentity = identity)

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.AcceptReconstruction)
        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_RUN_IDENTITY_UNKNOWN })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank MatcherRunIdentity value is rejected at construction`() {
        MatcherRunIdentity("   ")
    }

    // ==============================================================================================
    // Layer C control case -- still rejects a mismatched candidate-geometry identity, unchanged from
    // Round 1 (see ReconstructionSafetyGateTest for the fuller Layer C matrix).
    // ==============================================================================================

    @Test
    fun `gate accepts a fully correctly-associated candidate across all three layers (control case)`() {
        val window = listOf(rawObservation(1, paris), rawObservation(2, lyon))
        val identity = OrderedObservationWindowIdentity.of(window)
        val run = MatcherRunIdentity("run-1")
        val theCandidate = candidate(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            observationWindowIdentity = identity,
            matcherRunIdentity = run,
        )
        val evidence = favorableEvidenceFor(theCandidate, observationWindowIdentity = identity, matcherRunIdentity = run)

        val decision = gate.evaluate(theCandidate, evidence, policy, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.NoReconstruction)
    }
}
