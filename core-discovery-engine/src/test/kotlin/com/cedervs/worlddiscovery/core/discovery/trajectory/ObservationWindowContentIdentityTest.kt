package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A Correction Round 3 — the final Codex-identified blocker: on [buildObservationDedupKey]'s
 * [FixIdentityGuarantee.STRONG] path, two observations sharing the same `elapsedRealtimeClockDomainId`
 * + `elapsedRealtimeNanos` but differing in content (e.g. coordinate) produced the *same* dedup key,
 * and Round 2's [OrderedObservationWindowIdentity] stored only that dedup key — so two genuinely
 * different observations could produce the same window identity. This file reproduces that exact
 * scenario (§7's mandatory regression) and exercises the full per-field mutation / reordering /
 * stability / gate-regression matrix the round requires.
 */
class ObservationWindowContentIdentityTest {

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val lyon = Coordinate(latitude = 45.7640, longitude = 4.8357)

    /** A "base" observation with every safety-relevant field populated (non-null), so per-field
     * mutation tests below can flip exactly one field at a time and observe the effect. Uses the
     * STRONG dedup path throughout (`elapsedRealtimeNanos` non-zero). */
    private fun baseObservation(
        coordinate: Coordinate = paris,
        observedAt: Instant = Instant.parse("2026-01-01T10:00:00Z"),
        elapsedRealtimeClockDomainId: String = "domain-X",
        elapsedRealtimeNanos: Long = 123L,
        accuracyMeters: Float? = 5.0f,
        speedMetersPerSecond: Float? = 2.0f,
        speedAccuracyMetersPerSecond: Float? = 0.5f,
        bearingDegrees: Float? = 90.0f,
        bearingAccuracyDegrees: Float? = 10.0f,
        provider: String? = "fused",
        isMockLocation: Boolean = false,
    ) = TrajectoryObservation(
        coordinate = coordinate,
        observedAt = observedAt,
        receivedAt = Instant.parse("2026-01-01T10:00:05Z"),
        source = TrajectoryObservationSource.FOREGROUND,
        processSessionId = "session-1",
        elapsedRealtimeClockDomainId = elapsedRealtimeClockDomainId,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
        bearingDegrees = bearingDegrees,
        bearingAccuracyDegrees = bearingAccuracyDegrees,
        provider = provider,
        isMockLocation = isMockLocation,
        batchId = null,
        indexInBatch = null,
    )

    // ==============================================================================================
    // §7 -- the exact mandatory blocker-reproduction regression.
    // ==============================================================================================

    @Test
    fun `same STRONG dedup key with a different coordinate still produces a different window identity`() {
        val observationA = baseObservation(
            coordinate = paris,
            elapsedRealtimeClockDomainId = "domain-X",
            elapsedRealtimeNanos = 123L,
        )
        val observationB = baseObservation(
            coordinate = lyon, // the ONLY difference
            elapsedRealtimeClockDomainId = "domain-X",
            elapsedRealtimeNanos = 123L,
        )

        val dedupKeyA = buildObservationDedupKey(
            elapsedRealtimeClockDomainId = observationA.elapsedRealtimeClockDomainId,
            elapsedRealtimeNanos = observationA.elapsedRealtimeNanos,
            providerTimeEpochMillis = observationA.observedAt.toEpochMilli(),
            coordinate = observationA.coordinate,
            accuracyMeters = observationA.accuracyMeters,
        )
        val dedupKeyB = buildObservationDedupKey(
            elapsedRealtimeClockDomainId = observationB.elapsedRealtimeClockDomainId,
            elapsedRealtimeNanos = observationB.elapsedRealtimeNanos,
            providerTimeEpochMillis = observationB.observedAt.toEpochMilli(),
            coordinate = observationB.coordinate,
            accuracyMeters = observationB.accuracyMeters,
        )

        // Sanity: both really do take the STRONG path, and the dedup key alone really is identical --
        // this is the exact defect surface, confirmed to still exist in buildObservationDedupKey
        // itself (which this round deliberately does NOT change).
        assertEquals(FixIdentityGuarantee.STRONG, fixIdentityGuarantee(observationA.elapsedRealtimeNanos))
        assertEquals(FixIdentityGuarantee.STRONG, fixIdentityGuarantee(observationB.elapsedRealtimeNanos))
        assertEquals(dedupKeyA, dedupKeyB)

        // The actual fix: window identity must still differ.
        val windowIdentityA = OrderedObservationWindowIdentity.of(listOf(observationA))
        val windowIdentityB = OrderedObservationWindowIdentity.of(listOf(observationB))

        assertNotEquals(windowIdentityA, windowIdentityB)
    }

    // ==============================================================================================
    // §8 -- per-field mutation tests. Each safety-relevant field, changed alone, changes identity.
    // ==============================================================================================

    @Test
    fun `changing latitude changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(coordinate = paris)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(coordinate = Coordinate(paris.latitude + 0.01, paris.longitude))))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing longitude changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(coordinate = paris)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(coordinate = Coordinate(paris.latitude, paris.longitude + 0.01))))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing the wall-clock observedAt timestamp changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(observedAt = Instant.parse("2026-01-01T10:00:00Z"))))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(observedAt = Instant.parse("2026-01-01T10:00:01Z"))))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing observedAt by sub-second nanosecond precision alone changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(observedAt = Instant.parse("2026-01-01T10:00:00.000000001Z"))))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(observedAt = Instant.parse("2026-01-01T10:00:00.000000002Z"))))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing elapsedRealtimeClockDomainId changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(elapsedRealtimeClockDomainId = "domain-X")))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(elapsedRealtimeClockDomainId = "domain-Y")))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing elapsedRealtimeNanos changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(elapsedRealtimeNanos = 123L)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(elapsedRealtimeNanos = 456L)))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing accuracyMeters changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(accuracyMeters = 5.0f)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(accuracyMeters = 6.0f)))

        assertNotEquals(a, b)
    }

    @Test
    fun `accuracyMeters null vs a genuine zero reading are distinguished`() {
        val nullValue = OrderedObservationWindowIdentity.of(listOf(baseObservation(accuracyMeters = null)))
        val zeroValue = OrderedObservationWindowIdentity.of(listOf(baseObservation(accuracyMeters = 0.0f)))

        assertNotEquals(nullValue, zeroValue)
    }

    @Test
    fun `changing speedMetersPerSecond changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(speedMetersPerSecond = 2.0f)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(speedMetersPerSecond = 3.0f)))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing speedAccuracyMetersPerSecond changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(speedAccuracyMetersPerSecond = 0.5f)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(speedAccuracyMetersPerSecond = 0.6f)))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing bearingDegrees changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(bearingDegrees = 90.0f)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(bearingDegrees = 91.0f)))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing bearingAccuracyDegrees changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(bearingAccuracyDegrees = 10.0f)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(bearingAccuracyDegrees = 11.0f)))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing the mock-location flag changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(isMockLocation = false)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(isMockLocation = true)))

        assertNotEquals(a, b)
    }

    @Test
    fun `changing provider changes window identity`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation(provider = "fused")))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation(provider = "gps")))

        assertNotEquals(a, b)
    }

    // ---- Intentionally excluded fields: demonstrate the exclusion explicitly. ----

    @Test
    fun `changing receivedAt alone does NOT change window identity (delivery metadata, intentionally excluded)`() {
        val a = OrderedObservationWindowIdentity.of(
            listOf(baseObservation().copy(receivedAt = Instant.parse("2026-01-01T10:00:05Z"))),
        )
        val b = OrderedObservationWindowIdentity.of(
            listOf(baseObservation().copy(receivedAt = Instant.parse("2026-01-01T10:05:00Z"))),
        )

        assertEquals(a, b)
    }

    @Test
    fun `changing source alone does NOT change window identity (delivery metadata, intentionally excluded)`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation().copy(source = TrajectoryObservationSource.FOREGROUND)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation().copy(source = TrajectoryObservationSource.BACKGROUND)))

        assertEquals(a, b)
    }

    @Test
    fun `changing processSessionId alone does NOT change window identity (delivery metadata, intentionally excluded)`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation().copy(processSessionId = "session-1")))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation().copy(processSessionId = "session-2")))

        assertEquals(a, b)
    }

    @Test
    fun `changing batchId or indexInBatch alone does NOT change window identity (delivery metadata, intentionally excluded)`() {
        val a = OrderedObservationWindowIdentity.of(listOf(baseObservation().copy(batchId = "batch-1", indexInBatch = 0)))
        val b = OrderedObservationWindowIdentity.of(listOf(baseObservation().copy(batchId = "batch-2", indexInBatch = 3)))

        assertEquals(a, b)
    }

    // ==============================================================================================
    // §9 -- window order sensitivity, preserved.
    // ==============================================================================================

    @Test
    fun `reordering the same observations changes window identity`() {
        val a = baseObservation(coordinate = paris, elapsedRealtimeNanos = 1L)
        val b = baseObservation(coordinate = lyon, elapsedRealtimeNanos = 2L)

        val forward = OrderedObservationWindowIdentity.of(listOf(a, b))
        val backward = OrderedObservationWindowIdentity.of(listOf(b, a))

        assertNotEquals(forward, backward)
    }

    // ==============================================================================================
    // §10 -- same semantic content, independently constructed, produces the same identity.
    // ==============================================================================================

    @Test
    fun `two independently constructed but semantically identical observation sequences produce the same window identity`() {
        val sequenceOne = listOf(
            baseObservation(coordinate = paris, elapsedRealtimeNanos = 1L),
            baseObservation(coordinate = lyon, elapsedRealtimeNanos = 2L),
        )
        val sequenceTwo = listOf(
            baseObservation(coordinate = Coordinate(paris.latitude, paris.longitude), elapsedRealtimeNanos = 1L),
            baseObservation(coordinate = Coordinate(lyon.latitude, lyon.longitude), elapsedRealtimeNanos = 2L),
        )

        assertEquals(
            OrderedObservationWindowIdentity.of(sequenceOne),
            OrderedObservationWindowIdentity.of(sequenceTwo),
        )
    }

    @Test
    fun `checksum alone is never the discriminator -- equal identities also have equal per-observation entries`() {
        val sequence = listOf(baseObservation(coordinate = paris, elapsedRealtimeNanos = 1L))
        val identityOne = OrderedObservationWindowIdentity.of(sequence)
        val identityTwo = OrderedObservationWindowIdentity.of(sequence)

        assertEquals(identityOne.sequenceChecksum, identityTwo.sequenceChecksum)
        assertEquals(identityOne.orderedObservationIdentities, identityTwo.orderedObservationIdentities)
    }

    // ==============================================================================================
    // §11 -- gate-level regression: Layer A independently catches the exact blocker scenario.
    // ==============================================================================================

    @Test
    fun `gate rejects evidence from a content-differing window sharing the same STRONG dedup identity`() {
        val gate = DeterministicReconstructionSafetyGate
        val evaluatedAt = Instant.parse("2026-01-01T10:00:00Z")
        val policy = ReconstructionSafetyPolicy(
            transportMode = TransportMode.ROAD_VEHICLE,
            plausibleSpeedMetersPerSecond = 0.0..55.0,
            minimumObservationSupport = 1,
            maximumInterpolatedObservationFraction = 1.0,
            maximumToleratedAlternativeRoutes = Int.MAX_VALUE,
            minimumMatcherConfidenceWhenKnown = null,
            acceptWhenMatcherConfidenceUnknown = true,
        )

        // Window A and window B share the exact same STRONG dedup identity (domain-X, elapsed=123)
        // but differ in coordinate content.
        val windowAObservation = baseObservation(coordinate = paris, elapsedRealtimeClockDomainId = "domain-X", elapsedRealtimeNanos = 123L)
        val windowBObservation = baseObservation(coordinate = lyon, elapsedRealtimeClockDomainId = "domain-X", elapsedRealtimeNanos = 123L)
        val windowAIdentity = OrderedObservationWindowIdentity.of(listOf(windowAObservation))
        val windowBIdentity = OrderedObservationWindowIdentity.of(listOf(windowBObservation))
        assertNotEquals(windowAIdentity, windowBIdentity) // sanity, per the fix above

        val runIdentity = MatcherRunIdentity("run-1")
        // The candidate under evaluation claims to come from window B.
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
            inputObservationCount = 1,
            observationMatches = emptyList(),
            bridgedObservationEdges = emptySet(),
            observationWindowIdentity = windowBIdentity,
            matcherRunIdentity = runIdentity,
            reasons = emptyList(),
        )
        // But the evidence actually describes window A (same geometry identity target -- the
        // adversarial point is isolating Layer A alone, so associatedCandidateIdentity legitimately
        // matches the candidate; only observationWindowIdentity is wrong).
        val evidence = ReconstructionSafetyEvidence(
            associatedCandidateIdentity = ReconstructionCandidateIdentity.of(candidate),
            observationWindowIdentity = windowAIdentity,
            matcherRunIdentity = runIdentity,
            observation = ObservationEvidence(temporallyMonotonic = EvidenceValue.Known(true)),
            matcher = MatcherEvidence(declinedIntervals = EvidenceValue.Known(emptyList())),
        )

        val decision = gate.evaluate(candidate, evidence, policy, evaluatedAt)

        assertTrue(decision is ReconstructionSafetyDecision.NoReconstruction)
        assertTrue(decision.reasons.any { it.code == SafetyGateReasonCode.OBSERVATION_WINDOW_MISMATCH })
    }
}
