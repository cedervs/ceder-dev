package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A Correction Round 2 — Codex's §11 MAJOR finding: "`internal factory` does NOT prevent
 * another production file in the same module from constructing `AcceptReconstruction.of(...)`".
 *
 * Verifies [DeterministicReconstructionSafetyGate.authorize] and
 * [DeterministicReconstructionSafetyGate.ReconstructionAuthorization] — the opaque,
 * unforgeable-even-same-module capability a future consumer should require instead of merely
 * `decision is AcceptReconstruction`.
 *
 * **What this file demonstrates about Kotlin visibility, honestly stated (see
 * [DeterministicReconstructionSafetyGate.ReconstructionAuthorization]'s own doc comment for the full
 * correction): its primary constructor is `private`, callable only from its own companion object
 * (ordinary Kotlin private-scope-sharing between a class and its companion) — an outer class does
 * **not** automatically get access to a nested class's private constructor in Kotlin, unlike Java;
 * this was verified against the real compiler while building this round, not assumed. So the actual
 * guarantee is the same `internal`, module-wide ceiling [ReconstructionSafetyDecision.AcceptReconstruction]'s
 * own `of` factory already has — this file *could*, in principle, call
 * `ReconstructionAuthorization.Companion.grantedByGateEvaluation` directly, since it is `internal`
 * and this test file is part of the same module. What this design still adds is a **separate,
 * deliberately awkwardly-named, non-`operator` type** that a consumer must specifically reach for —
 * this file always goes through [DeterministicReconstructionSafetyGate.authorize] below, by
 * deliberate test discipline, never by a compiler-enforced impossibility of doing otherwise.
 */
class AuthorizationBoundaryTest {

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

    private fun favorableCandidateAndEvidence(): Pair<TrajectoryReconstructionResult.AcceptedTrajectory, ReconstructionSafetyEvidence> {
        val window = listOf(rawObservation(1), rawObservation(2))
        val windowIdentity = OrderedObservationWindowIdentity.of(window)
        val runIdentity = MatcherRunIdentity("run-1")
        val candidate = TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, lyon),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.NETWORK_MATCHING),
            transportModeHypothesis = TransportModeHypothesis(TransportMode.ROAD_VEHICLE),
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 2,
            observationMatches = emptyList(),
            bridgedObservationEdges = emptySet(),
            observationWindowIdentity = windowIdentity,
            matcherRunIdentity = runIdentity,
            reasons = emptyList(),
        )
        val evidence = ReconstructionSafetyEvidence(
            associatedCandidateIdentity = ReconstructionCandidateIdentity.of(candidate),
            observationWindowIdentity = windowIdentity,
            matcherRunIdentity = runIdentity,
            observation = ObservationEvidence(
                elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(30)),
                endpointDisplacementMeters = EvidenceValue.Known(300.0),
                impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(10.0),
                temporallyMonotonic = EvidenceValue.Known(true),
            ),
            matcher = MatcherEvidence(
                matchedObservationCount = EvidenceValue.Known(2),
                interpolatedObservationCount = EvidenceValue.Known(0),
                unmatchedObservationCount = EvidenceValue.Known(0),
                unknownClassificationObservationCount = EvidenceValue.Known(0),
                declinedIntervals = EvidenceValue.Known(emptyList()),
            ),
        )
        return candidate to evidence
    }

    @Test
    fun `authorize returns a non-null authorization exactly when the decision is AcceptReconstruction`() {
        val (candidate, evidence) = favorableCandidateAndEvidence()

        val result = gate.authorize(candidate, evidence, policy, evaluatedAt)

        assertTrue(result.decision is ReconstructionSafetyDecision.AcceptReconstruction)
        assertNotNull(result.authorization)
        assertSame(result.decision, result.authorization!!.decision)
    }

    @Test
    fun `authorize returns a null authorization when the decision is NoReconstruction`() {
        val (candidate, evidence) = favorableCandidateAndEvidence()
        val rejectingEvidence = evidence.copy(
            observation = evidence.observation.copy(temporallyMonotonic = EvidenceValue.Known(false)),
        )

        val result = gate.authorize(candidate, rejectingEvidence, policy, evaluatedAt)

        assertTrue(result.decision is ReconstructionSafetyDecision.NoReconstruction)
        assertNull(result.authorization)
    }

    @Test
    fun `authorize returns a null authorization when the decision is RequireMoreEvidence`() {
        val (candidate, evidence) = favorableCandidateAndEvidence()
        val uncertainEvidence = evidence.copy(
            matcher = MatcherEvidence(
                matchedObservationCount = evidence.matcher.matchedObservationCount,
                interpolatedObservationCount = evidence.matcher.interpolatedObservationCount,
                unmatchedObservationCount = evidence.matcher.unmatchedObservationCount,
                unknownClassificationObservationCount = evidence.matcher.unknownClassificationObservationCount,
                declinedIntervals = evidence.matcher.declinedIntervals,
                nativeConfidence = ConfidenceValue.Unknown,
            ),
        )
        val confidenceRequiringPolicy = policy.copy(acceptWhenMatcherConfidenceUnknown = false)

        val result = gate.authorize(candidate, uncertainEvidence, confidenceRequiringPolicy, evaluatedAt)

        assertTrue(result.decision is ReconstructionSafetyDecision.RequireMoreEvidence)
        assertNull(result.authorization)
    }

    @Test
    fun `authorize's decision is identical to calling evaluate directly (same evaluation, no divergence)`() {
        val (candidate, evidence) = favorableCandidateAndEvidence()

        val viaEvaluate = gate.evaluate(candidate, evidence, policy, evaluatedAt)
        val viaAuthorize = gate.authorize(candidate, evidence, policy, evaluatedAt)

        assertEquals(viaEvaluate, viaAuthorize.decision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an AcceptReconstruction with disqualifying reasons still cannot be fabricated -- category invariant unaffected by this round`() {
        // Even the weaker, module-wide-internal `of` factory still enforces its own reason-category
        // invariant (Round 1) -- this round changes WHERE authorization comes from, not that invariant.
        ReconstructionSafetyDecision.AcceptReconstruction.of(
            listOf(SafetyGateReason(SafetyGateReasonCode.INVALID_TEMPORAL_STRUCTURE)),
        )
    }

    /**
     * The honest boundary this round actually achieves (see this file's own top doc comment and
     * [DeterministicReconstructionSafetyGate.ReconstructionAuthorization]'s doc comment for the full
     * correction): a caller in this same module — this test included — CAN construct a
     * category-valid [ReconstructionSafetyDecision.AcceptReconstruction] directly via `of`, and CAN
     * also reach `ReconstructionAuthorization.Companion.grantedByGateEvaluation` directly, since both
     * are merely `internal`. What actually differs in practice is that a real consumer's code must
     * specifically ask for a [DeterministicReconstructionSafetyGate.ReconstructionAuthorization] —
     * a distinct, deliberately unusual type -- rather than treating the far more ordinary-looking
     * `AcceptReconstruction` as if it were already sufficient proof. This test exercises that real
     * boundary directly, showing it is the same one every other module caller has: `internal`.
     */
    @Test
    fun `the companion factory is internal, not a compiler-enforced single-caller boundary -- same module can call it directly too`() {
        val fabricated = ReconstructionSafetyDecision.AcceptReconstruction.of(
            listOf(SafetyGateReason(SafetyGateReasonCode.SUFFICIENT_OBSERVATION_SUPPORT)),
        )

        val directlyGranted = DeterministicReconstructionSafetyGate.ReconstructionAuthorization.grantedByGateEvaluation(fabricated)

        assertSame(fabricated, directlyGranted.decision)
    }
}
