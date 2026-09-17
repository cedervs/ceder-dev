package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A Correction Round 2 test matrix for [DeterministicReconstructionSafetyGate]. Rewritten
 * against the three-layer identity model (Layer A: [OrderedObservationWindowIdentity], Layer B:
 * [MatcherRunIdentity], Layer C: [ReconstructionCandidateIdentity]) and the edge-based
 * matcher-declined-continuity model ([EdgeRange]). See [ReconstructionSafetyIdentityAssociationTest]
 * for the deeper Layer A/B/C adversarial matrix, [EdgeContinuityTest] for the edge-boundary matrix,
 * [ReconstructionEvidenceCoherenceTest] for the numeric-extreme/accounting matrix, and
 * [AuthorizationBoundaryTest] for the [DeterministicReconstructionSafetyGate.authorize] boundary.
 *
 * Every numeric policy value below is a [TEST_PLACEHOLDER_POLICY] only.
 */
class ReconstructionSafetyGateTest {

    private val gate = DeterministicReconstructionSafetyGate
    private val evaluatedAt: Instant = Instant.parse("2026-01-01T10:00:00Z")
    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val lyon = Coordinate(latitude = 45.7640, longitude = 4.8357)

    private val TEST_PLACEHOLDER_POLICY = ReconstructionSafetyPolicy(
        transportMode = TransportMode.ROAD_VEHICLE,
        plausibleSpeedMetersPerSecond = 0.0..55.0,
        minimumObservationSupport = 5,
        maximumInterpolatedObservationFraction = 0.5,
        maximumToleratedAlternativeRoutes = 2,
        minimumMatcherConfidenceWhenKnown = 0.6,
        acceptWhenMatcherConfidenceUnknown = false,
    )

    private fun observation(elapsedRealtimeNanos: Long) = TrajectoryObservation(
        coordinate = paris,
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

    private val defaultObservations = (1L..200L).map { observation(it) }
    private val defaultRunIdentity = MatcherRunIdentity("run-1")

    private fun windowIdentityFor(count: Int) = OrderedObservationWindowIdentity.of(defaultObservations.take(count))

    private fun candidate(
        geometry: List<Coordinate> = listOf(paris, lyon),
        observedIndices: Set<Int> = setOf(0, 1),
        inferredIndices: Set<Int> = emptySet(),
        inputObservationCount: Int = 10,
        transportModeHypothesis: TransportModeHypothesis? = TransportModeHypothesis(TransportMode.ROAD_VEHICLE),
        bridgedObservationEdges: Set<Int> = emptySet(),
        observationWindowIdentity: OrderedObservationWindowIdentity? = windowIdentityFor(inputObservationCount),
        matcherRunIdentity: MatcherRunIdentity? = defaultRunIdentity,
    ) = TrajectoryReconstructionResult.AcceptedTrajectory(
        geometry = geometry,
        observedIndices = observedIndices,
        inferredIndices = inferredIndices,
        strategy = ReconstructionStrategy(ReconstructionStrategyKind.NETWORK_MATCHING),
        transportModeHypothesis = transportModeHypothesis,
        confidence = ReconstructionConfidence.UNKNOWN,
        engineVersion = 1,
        graphSource = null,
        parameters = emptyMap(),
        inputObservationCount = inputObservationCount,
        observationMatches = emptyList(),
        bridgedObservationEdges = bridgedObservationEdges,
        observationWindowIdentity = observationWindowIdentity,
        matcherRunIdentity = matcherRunIdentity,
        reasons = emptyList(),
    )

    /** Fully favorable, fully-known evidence for [theCandidate]. Duration/displacement/speed are kept
     * mutually consistent (30s, 300m -> 10 m/s). Window/run identity default to the candidate's own
     * (non-null by default), so identity checks pass unless a test deliberately diverges them. */
    private fun favorableEvidence(
        theCandidate: TrajectoryReconstructionResult.AcceptedTrajectory,
        observationWindowIdentity: OrderedObservationWindowIdentity = theCandidate.observationWindowIdentity
            ?: windowIdentityFor(theCandidate.inputObservationCount),
        matcherRunIdentity: MatcherRunIdentity = theCandidate.matcherRunIdentity ?: defaultRunIdentity,
        elapsedDuration: EvidenceValue<Duration> = EvidenceValue.Known(Duration.ofSeconds(30)),
        endpointDisplacementMeters: EvidenceValue<Double> = EvidenceValue.Known(300.0),
        impliedAverageSpeedMetersPerSecond: EvidenceValue<Double> = EvidenceValue.Known(10.0),
        temporallyMonotonic: EvidenceValue<Boolean> = EvidenceValue.Known(true),
        meanAccuracyMeters: EvidenceValue<Double> = EvidenceValue.Unknown,
        plausibleAlternativeRouteCount: EvidenceValue<Int> = EvidenceValue.Known(1),
        endpointPlausible: EvidenceValue<Boolean> = EvidenceValue.Known(true),
        networkComplexity: EvidenceValue<NetworkComplexity> = EvidenceValue.Unknown,
        matchedObservationCount: EvidenceValue<Int> = EvidenceValue.Known(9),
        interpolatedObservationCount: EvidenceValue<Int> = EvidenceValue.Known(1),
        unmatchedObservationCount: EvidenceValue<Int> = EvidenceValue.Known(0),
        unknownClassificationObservationCount: EvidenceValue<Int> = EvidenceValue.Known(0),
        declinedIntervals: EvidenceValue<List<MatcherDeclinedInterval>> = EvidenceValue.Known(emptyList()),
        nativeConfidence: ConfidenceValue = ConfidenceValue.Known(0.8),
        impliedSpeedConsistent: EvidenceValue<Boolean> = EvidenceValue.Known(true),
        headingConsistent: EvidenceValue<Boolean> = EvidenceValue.Known(true),
        observationToRouteConsistent: EvidenceValue<Boolean> = EvidenceValue.Known(true),
        crossMatcherAgreement: CrossMatcherAgreementEvidence? = null,
        associatedCandidateIdentity: ReconstructionCandidateIdentity = ReconstructionCandidateIdentity.of(theCandidate),
    ) = ReconstructionSafetyEvidence(
        associatedCandidateIdentity = associatedCandidateIdentity,
        observationWindowIdentity = observationWindowIdentity,
        matcherRunIdentity = matcherRunIdentity,
        observation = ObservationEvidence(
            elapsedDuration = elapsedDuration,
            endpointDisplacementMeters = endpointDisplacementMeters,
            impliedAverageSpeedMetersPerSecond = impliedAverageSpeedMetersPerSecond,
            meanAccuracyMeters = meanAccuracyMeters,
            temporallyMonotonic = temporallyMonotonic,
        ),
        networkRoute = NetworkRouteEvidence(
            networkComplexity = networkComplexity,
            plausibleAlternativeRouteCount = plausibleAlternativeRouteCount,
            endpointPlausible = endpointPlausible,
        ),
        matcher = MatcherEvidence(
            matchedObservationCount = matchedObservationCount,
            interpolatedObservationCount = interpolatedObservationCount,
            unmatchedObservationCount = unmatchedObservationCount,
            unknownClassificationObservationCount = unknownClassificationObservationCount,
            declinedIntervals = declinedIntervals,
            nativeConfidence = nativeConfidence,
        ),
        consistency = ConsistencyEvidence(
            impliedSpeedConsistent = impliedSpeedConsistent,
            headingConsistent = headingConsistent,
            observationToRouteConsistent = observationToRouteConsistent,
        ),
        crossMatcherAgreement = crossMatcherAgreement,
    )

    /** All-[EvidenceValue.Unknown] optional/measured evidence, still correctly associated with
     * [theCandidate] via all three identity layers (mandatory bookkeeping, never legitimately
     * "unknown" on the evidence side -- only the candidate's own optional window/run identity fields
     * can be null). */
    private fun mostlyUnknownEvidence(theCandidate: TrajectoryReconstructionResult.AcceptedTrajectory) =
        ReconstructionSafetyEvidence(
            associatedCandidateIdentity = ReconstructionCandidateIdentity.of(theCandidate),
            observationWindowIdentity = theCandidate.observationWindowIdentity ?: windowIdentityFor(theCandidate.inputObservationCount),
            matcherRunIdentity = theCandidate.matcherRunIdentity ?: defaultRunIdentity,
        )

    // ==============================================================================================
    // A. Clear supported case -> ACCEPT_RECONSTRUCTION.
    // ==============================================================================================

    @Test
    fun `A - a clearly supported candidate is accepted`() {
        val c = candidate()
        val decision = gate.evaluate(c, favorableEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
        assertTrue(decision.reasons.isNotEmpty())
        assertTrue(decision.reasons.all { it.code.category == SafetyGateReasonCategory.SUPPORT })
    }

    // ==============================================================================================
    // B1. ACCEPT must be fail-closed (preserved from Round 1, re-verified against the new identity
    // model).
    // ==============================================================================================

    @Test
    fun `B1 - almostEverythingUnknown_cannotAccept`() {
        val c = candidate(transportModeHypothesis = null)
        val decision = gate.evaluate(c, mostlyUnknownEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        val codes = decision.reasons.map { it.code }
        assertTrue(codes.contains(SafetyGateReasonCode.TEMPORAL_STRUCTURE_UNKNOWN))
        assertTrue(codes.contains(SafetyGateReasonCode.KINEMATICS_UNKNOWN))
        assertTrue(codes.contains(SafetyGateReasonCode.STRUCTURAL_RELATIONSHIP_UNKNOWN))
        assertTrue(codes.contains(SafetyGateReasonCode.MATCHER_ACCOUNTING_UNKNOWN))
        assertTrue(codes.contains(SafetyGateReasonCode.TRANSPORT_COMPATIBILITY_UNKNOWN))
    }

    @Test
    fun `B1 - absenceOfKnownFailure_isNotEnoughForAccept`() {
        val c = candidate(inputObservationCount = 100)
        val decision = gate.evaluate(c, mostlyUnknownEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.AcceptReconstruction)
        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
    }

    @Test
    fun `B1 - positiveEvidenceRequiredForAccept`() {
        val c = candidate()
        val decision = gate.evaluate(c, favorableEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
        val codes = decision.reasons.map { it.code }
        assertTrue(codes.contains(SafetyGateReasonCode.OBSERVATION_WINDOW_IDENTITY_CONFIRMED))
        assertTrue(codes.contains(SafetyGateReasonCode.MATCHER_RUN_IDENTITY_CONFIRMED))
        assertTrue(codes.contains(SafetyGateReasonCode.EVIDENCE_CANDIDATE_ASSOCIATION_CONFIRMED))
        assertTrue(codes.contains(SafetyGateReasonCode.TEMPORAL_STRUCTURE_VALID))
        assertTrue(codes.contains(SafetyGateReasonCode.KINEMATICS_PLAUSIBLE))
        assertTrue(codes.contains(SafetyGateReasonCode.NO_INVENTED_BRIDGE))
        assertTrue(codes.contains(SafetyGateReasonCode.ROUTE_STRUCTURALLY_CONTINUOUS))
        assertTrue(codes.contains(SafetyGateReasonCode.MATCHER_ACCOUNTING_COHERENT))
        assertTrue(codes.contains(SafetyGateReasonCode.SUFFICIENT_OBSERVATION_SUPPORT))
        assertTrue(codes.contains(SafetyGateReasonCode.TRANSPORT_MODE_COMPATIBLE))
    }

    // ==============================================================================================
    // B, C. Hard failures (temporal / kinematics), preserved from Round 1.
    // ==============================================================================================

    @Test
    fun `B - non-monotonic temporal structure is rejected`() {
        val c = candidate()
        val evidence = favorableEvidence(c, temporallyMonotonic = EvidenceValue.Known(false))

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.INVALID_TEMPORAL_STRUCTURE })
    }

    @Test
    fun `C - implausible implied speed is rejected`() {
        val c = candidate()
        val evidence = favorableEvidence(
            c,
            endpointDisplacementMeters = EvidenceValue.Known(29_970.0),
            impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(999.0),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.PHYSICALLY_IMPLAUSIBLE_KINEMATICS })
    }

    // ==============================================================================================
    // B3 (edge-based, Round 2). Split with an invented bridge vs. split without one.
    // ==============================================================================================

    @Test
    fun `B3 - a declined edge bridged by the candidate is rejected as an unsupported bridge`() {
        val c = candidate(bridgedObservationEdges = setOf(3))
        val evidence = favorableEvidence(
            c,
            declinedIntervals = EvidenceValue.Known(
                listOf(MatcherDeclinedInterval(EdgeRange(3, 3), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)),
            ),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.UNSUPPORTED_BRIDGE })
    }

    @Test
    fun `B3 - a declined edge with no candidate bridge can still be accepted`() {
        val c = candidate(bridgedObservationEdges = emptySet())
        val evidence = favorableEvidence(
            c,
            declinedIntervals = EvidenceValue.Known(
                listOf(MatcherDeclinedInterval(EdgeRange(0, 0), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)),
            ),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
        val codes = decision.reasons.map { it.code }
        assertTrue(codes.contains(SafetyGateReasonCode.NO_INVENTED_BRIDGE))
        assertTrue(codes.none { it == SafetyGateReasonCode.ROUTE_STRUCTURALLY_CONTINUOUS })
    }

    @Test
    fun `B3 - unknown declined-edge association must not accept`() {
        val c = candidate()
        val evidence = favorableEvidence(c, declinedIntervals = EvidenceValue.Unknown)

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.STRUCTURAL_RELATIONSHIP_UNKNOWN })
    }

    // ==============================================================================================
    // B5. Transport-mode compatibility, preserved from Round 1.
    // ==============================================================================================

    @Test
    fun `B5 - matchingTransportMode is accepted`() {
        val c = candidate(transportModeHypothesis = TransportModeHypothesis(TransportMode.ROAD_VEHICLE))
        val decision = gate.evaluate(c, favorableEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.TRANSPORT_MODE_COMPATIBLE })
    }

    @Test
    fun `B5 - walkVsVehicleMismatch is rejected`() {
        val c = candidate(transportModeHypothesis = TransportModeHypothesis(TransportMode.WALK))
        val decision = gate.evaluate(c, favorableEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.TRANSPORT_MODE_MISMATCH })
    }

    @Test
    fun `B5 - unsupportedOrUnknownModeCannotAccept`() {
        val c = candidate(transportModeHypothesis = null)
        val decision = gate.evaluate(c, favorableEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision !is ReconstructionSafetyDecision.AcceptReconstruction)
        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.TRANSPORT_COMPATIBILITY_UNKNOWN })
    }

    // ==============================================================================================
    // E, F, G, I, J, K, L, M -- optional evidence / ambiguity rules, preserved from Round 1.
    // ==============================================================================================

    @Test
    fun `E - high ambiguity and sparse observations require more evidence`() {
        val c = candidate(inputObservationCount = 2)
        val evidence = favorableEvidence(
            c,
            matchedObservationCount = EvidenceValue.Known(2),
            interpolatedObservationCount = EvidenceValue.Known(0),
            unmatchedObservationCount = EvidenceValue.Known(0),
            unknownClassificationObservationCount = EvidenceValue.Known(0),
            plausibleAlternativeRouteCount = EvidenceValue.Known(5),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        val codes = decision.reasons.map { it.code }
        assertTrue(codes.contains(SafetyGateReasonCode.SPARSE_OBSERVATIONS))
        assertTrue(codes.contains(SafetyGateReasonCode.HIGH_NETWORK_AMBIGUITY))
    }

    @Test
    fun `F - weak matcher confidence alone requires more evidence, never a hard rejection`() {
        val c = candidate()
        val evidence = favorableEvidence(c, nativeConfidence = ConfidenceValue.Known(0.1))

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_CONFIDENCE_WEAK })
    }

    @Test
    fun `G - unavailable matcher confidence is a distinct explicit reason, never coerced to weak-zero`() {
        val c = candidate()
        val evidence = favorableEvidence(c, nativeConfidence = ConfidenceValue.Unknown)

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_CONFIDENCE_UNAVAILABLE })
        assertTrue(decision.reasons.none { it.code == SafetyGateReasonCode.MATCHER_CONFIDENCE_WEAK })
    }

    @Test
    fun `G2 - a policy that explicitly accepts unknown matcher confidence can still accept`() {
        val c = candidate()
        val permissivePolicy = TEST_PLACEHOLDER_POLICY.copy(acceptWhenMatcherConfidenceUnknown = true)
        val evidence = favorableEvidence(c, nativeConfidence = ConfidenceValue.Unknown)

        val decision = gate.evaluate(c, evidence, permissivePolicy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
    }

    @Test
    fun `I - heavy interpolation requires more evidence, never a hard rejection`() {
        val c = candidate()
        val evidence = favorableEvidence(
            c,
            matchedObservationCount = EvidenceValue.Known(3),
            interpolatedObservationCount = EvidenceValue.Known(7),
            unmatchedObservationCount = EvidenceValue.Known(0),
            unknownClassificationObservationCount = EvidenceValue.Known(0),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.INTERPOLATION_HEAVY })
    }

    @Test
    fun `J - cross-matcher disagreement adds uncertainty without picking a winner`() {
        val c = candidate()
        val evidence = favorableEvidence(
            c,
            crossMatcherAgreement = CrossMatcherAgreementEvidence(
                evaluatedEngineCount = 3,
                agreeingEngineCount = EvidenceValue.Known(1),
            ),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.MATCHER_DISAGREEMENT })
    }

    @Test
    fun `K - a HIGH network complexity label alone does not block a well-supported route`() {
        val c = candidate()
        val evidence = favorableEvidence(c, networkComplexity = EvidenceValue.Known(NetworkComplexity.HIGH))

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
    }

    @Test
    fun `L - a long elapsed duration alone does not block acceptance`() {
        val c = candidate()
        val evidence = favorableEvidence(
            c,
            elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(1800)),
            endpointDisplacementMeters = EvidenceValue.Known(18_000.0),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
    }

    @Test
    fun `M - a short elapsed duration does not override high network ambiguity`() {
        val c = candidate()
        val evidence = favorableEvidence(
            c,
            elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(5)),
            endpointDisplacementMeters = EvidenceValue.Known(50.0),
            plausibleAlternativeRouteCount = EvidenceValue.Known(10),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.HIGH_NETWORK_AMBIGUITY })
    }

    // ==============================================================================================
    // N, O, P -- provenance/determinism/immutability, preserved from Round 1.
    // ==============================================================================================

    @Test
    fun `N - evaluate never touches observed provenance -- it has no dependency capable of it`() {
        val c = candidate(bridgedObservationEdges = setOf(3))
        val beforeGeometry = c.geometry
        val beforeObserved = c.observedIndices
        val evidence = favorableEvidence(
            c,
            declinedIntervals = EvidenceValue.Known(
                listOf(MatcherDeclinedInterval(EdgeRange(3, 3), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS)),
            ),
        )

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertEquals(beforeGeometry, c.geometry)
        assertEquals(beforeObserved, c.observedIndices)
    }

    @Test
    fun `O - identical inputs always produce an identical decision`() {
        val c = candidate()
        val evidence = favorableEvidence(c)
        val first = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)
        val second = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertEquals(first, second)
        assertEquals(first.reasons, second.reasons)
    }

    @Test
    fun `deterministic reason ordering with multiple simultaneous hard-failure findings`() {
        val c = candidate(transportModeHypothesis = TransportModeHypothesis(TransportMode.WALK))
        val evidence = favorableEvidence(
            c,
            temporallyMonotonic = EvidenceValue.Known(false),
            endpointDisplacementMeters = EvidenceValue.Known(29_970.0),
            impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(999.0),
        )

        val first = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)
        val second = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(first is ReconstructionSafetyDecision.NoReconstruction)
        assertEquals(first.reasons, second.reasons)
        assertTrue(first.reasons.size >= 3)
    }

    // ==============================================================================================
    // CASE A / CASE B / CASE C -- explicit recheck (Correction Round 2 §15).
    // ==============================================================================================

    @Test
    fun `CASE A - almost all evidence unknown, still RequireMoreEvidence, never Accept`() {
        val c = candidate(transportModeHypothesis = null)
        val decision = gate.evaluate(c, mostlyUnknownEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertTrue(decision !is ReconstructionSafetyDecision.AcceptReconstruction)
    }

    @Test
    fun `CASE B - known strong positive evidence, no hard failure, no unresolved mandatory uncertainty, AcceptReconstruction`() {
        val c = candidate()
        val decision = gate.evaluate(c, favorableEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
    }

    @Test
    fun `CASE C - strong support plus one hard structural failure, still NoReconstruction`() {
        val c = candidate()
        val evidence = favorableEvidence(c, temporallyMonotonic = EvidenceValue.Known(false))

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.INVALID_TEMPORAL_STRUCTURE })
    }

    // ==============================================================================================
    // Extra: cadence recommendation stamping, sealed-type distinction.
    // ==============================================================================================

    @Test
    fun `RequireMoreEvidence carries a cadence recommendation stamped with the supplied evaluatedAt`() {
        val c = candidate()
        val evidence = favorableEvidence(c, plausibleAlternativeRouteCount = EvidenceValue.Known(10))

        val decision = gate.evaluate(c, evidence, TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        val requireMoreEvidence = decision as ReconstructionSafetyDecision.RequireMoreEvidence
        assertNotNull(requireMoreEvidence.cadenceRecommendation)
        assertEquals(evaluatedAt, requireMoreEvidence.cadenceRecommendation!!.recommendedAt)
    }

    @Test
    fun `AcceptReconstruction is a sealed decision distinct from RequireMoreEvidence`() {
        val c = candidate()
        val decision = gate.evaluate(c, favorableEvidence(c), TEST_PLACEHOLDER_POLICY, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.AcceptReconstruction)
        assertNull((decision as? ReconstructionSafetyDecision.RequireMoreEvidence))
    }
}
