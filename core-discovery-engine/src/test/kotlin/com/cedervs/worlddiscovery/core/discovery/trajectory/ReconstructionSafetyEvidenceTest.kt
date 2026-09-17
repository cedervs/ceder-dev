package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceValueTest {

    @Test
    fun `knownOrNull returns the value for Known`() {
        assertEquals(5, EvidenceValue.Known(5).knownOrNull())
    }

    @Test
    fun `knownOrNull returns null for Unknown`() {
        assertNull(EvidenceValue.Unknown.knownOrNull())
    }
}

class ObservationEvidenceTest {

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known elapsedDuration is rejected`() {
        ObservationEvidence(elapsedDuration = EvidenceValue.Known(Duration.ofSeconds(-1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known endpointDisplacementMeters is rejected`() {
        ObservationEvidence(endpointDisplacementMeters = EvidenceValue.Known(-1.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-finite known impliedAverageSpeedMetersPerSecond is rejected`() {
        ObservationEvidence(impliedAverageSpeedMetersPerSecond = EvidenceValue.Known(Double.NaN))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known meanAccuracyMeters is rejected`() {
        ObservationEvidence(meanAccuracyMeters = EvidenceValue.Known(-5.0))
    }

    @Test
    fun `an all-Unknown ObservationEvidence is allowed`() {
        val result = ObservationEvidence()

        assertEquals(EvidenceValue.Unknown, result.elapsedDuration)
    }

    // Relational-coherence invariants (Codex's B4) are covered by ObservationEvidenceRelationalCoherenceTest
    // in ReconstructionEvidenceCoherenceTest.kt.
}

class NetworkRouteEvidenceTest {

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known plausibleAlternativeRouteCount is rejected`() {
        NetworkRouteEvidence(plausibleAlternativeRouteCount = EvidenceValue.Known(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known pathContinuityMagnitude is rejected`() {
        NetworkRouteEvidence(pathContinuityMagnitude = EvidenceValue.Known(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known reconstructedRouteLengthMeters is rejected`() {
        NetworkRouteEvidence(reconstructedRouteLengthMeters = EvidenceValue.Known(-1.0))
    }
}

class MatcherEvidenceTest {

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known matchedObservationCount is rejected`() {
        MatcherEvidence(matchedObservationCount = EvidenceValue.Known(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known interpolatedObservationCount is rejected`() {
        MatcherEvidence(interpolatedObservationCount = EvidenceValue.Known(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known unmatchedObservationCount is rejected`() {
        MatcherEvidence(unmatchedObservationCount = EvidenceValue.Known(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative known unknownClassificationObservationCount is rejected`() {
        MatcherEvidence(unknownClassificationObservationCount = EvidenceValue.Known(-1))
    }

    @Test
    fun `mutating the caller's declinedIntervals list after construction does not affect it`() {
        val mutableIntervals = mutableListOf(
            MatcherDeclinedInterval(EdgeRange(0, 0), MatcherSplitOutcome.SPLIT_INTO_SEGMENTS),
        )
        val result = MatcherEvidence(declinedIntervals = EvidenceValue.Known(mutableIntervals))

        mutableIntervals.clear()

        assertEquals(1, (result.declinedIntervals as EvidenceValue.Known).value.size)
    }

    @Test
    fun `an empty declinedIntervals list is a valid, distinct known value from Unknown`() {
        val result = MatcherEvidence(declinedIntervals = EvidenceValue.Known(emptyList()))

        assertTrue(result.declinedIntervals is EvidenceValue.Known)
        assertEquals(emptyList<MatcherDeclinedInterval>(), (result.declinedIntervals as EvidenceValue.Known).value)
    }
}

class EdgeRangeTest {

    @Test(expected = IllegalArgumentException::class)
    fun `a negative startIndex is rejected`() {
        EdgeRange(startIndex = -1, endIndex = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an endIndex before startIndex (a reversed range) is rejected`() {
        EdgeRange(startIndex = 5, endIndex = 4)
    }

    @Test
    fun `a single-edge range is allowed`() {
        val result = EdgeRange(startIndex = 3, endIndex = 3)

        assertEquals(3..3, result.indices)
    }
}

class CrossMatcherAgreementEvidenceTest {

    @Test(expected = IllegalArgumentException::class)
    fun `evaluatedEngineCount below 1 is rejected`() {
        CrossMatcherAgreementEvidence(evaluatedEngineCount = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an agreeingEngineCount above evaluatedEngineCount is rejected`() {
        CrossMatcherAgreementEvidence(evaluatedEngineCount = 2, agreeingEngineCount = EvidenceValue.Known(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative agreeingEngineCount is rejected`() {
        CrossMatcherAgreementEvidence(evaluatedEngineCount = 2, agreeingEngineCount = EvidenceValue.Known(-1))
    }

    @Test
    fun `agreeingEngineCount equal to evaluatedEngineCount is allowed`() {
        val result = CrossMatcherAgreementEvidence(evaluatedEngineCount = 2, agreeingEngineCount = EvidenceValue.Known(2))

        assertEquals(EvidenceValue.Known(2), result.agreeingEngineCount)
    }
}

class ReconstructionSafetyEvidenceConstructionTest {

    private val identity = ReconstructionCandidateIdentity(
        inputObservationCount = 2,
        geometrySize = 2,
        firstCoordinate = com.cedervs.worlddiscovery.core.discovery.Coordinate(0.0, 0.0),
        lastCoordinate = com.cedervs.worlddiscovery.core.discovery.Coordinate(1.0, 1.0),
        observedIndices = setOf(0, 1),
        inferredIndices = emptySet(),
        geometryChecksum = 42L,
    )
    private val dummyContentFingerprint = ObservationContentFingerprint(
        latitude = 0.0,
        longitude = 0.0,
        observedAtEpochSecond = 0L,
        observedAtNano = 0,
        elapsedRealtimeClockDomainId = "session-1",
        elapsedRealtimeNanos = 0L,
        accuracyMetersBits = null,
        speedMetersPerSecondBits = null,
        speedAccuracyMetersPerSecondBits = null,
        bearingDegreesBits = null,
        bearingAccuracyDegreesBits = null,
        provider = null,
        isMockLocation = false,
    )
    private val windowIdentity = OrderedObservationWindowIdentity(
        observationCount = 2,
        orderedObservationIdentities = listOf(
            ObservationIdentityEntry(dedupKey = "a", contentFingerprint = dummyContentFingerprint),
            ObservationIdentityEntry(dedupKey = "b", contentFingerprint = dummyContentFingerprint),
        ),
        sequenceChecksum = 7L,
    )

    @Test
    fun `a fully specified evidence bundle is constructed with its own field values`() {
        val result = ReconstructionSafetyEvidence(
            associatedCandidateIdentity = identity,
            observationWindowIdentity = windowIdentity,
            matcherRunIdentity = MatcherRunIdentity("run-1"),
        )

        assertEquals(identity, result.associatedCandidateIdentity)
        assertEquals(windowIdentity, result.observationWindowIdentity)
        assertEquals(MatcherRunIdentity("run-1"), result.matcherRunIdentity)
    }
}

class MatcherRunIdentityTest {

    @Test(expected = IllegalArgumentException::class)
    fun `a blank value is rejected`() {
        MatcherRunIdentity("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a whitespace-only value is rejected`() {
        MatcherRunIdentity("   ")
    }

    @Test
    fun `a non-blank value is allowed`() {
        assertEquals("run-1", MatcherRunIdentity("run-1").value)
    }

    @Test
    fun `UuidMatcherRunIdentityGenerator produces distinct identities`() {
        val a = UuidMatcherRunIdentityGenerator.generate()
        val b = UuidMatcherRunIdentityGenerator.generate()

        assertTrue(a != b)
    }
}

class OrderedObservationWindowIdentityTest {

    private val dummyContentFingerprint = ObservationContentFingerprint(
        latitude = 0.0,
        longitude = 0.0,
        observedAtEpochSecond = 0L,
        observedAtNano = 0,
        elapsedRealtimeClockDomainId = "session-1",
        elapsedRealtimeNanos = 0L,
        accuracyMetersBits = null,
        speedMetersPerSecondBits = null,
        speedAccuracyMetersPerSecondBits = null,
        bearingDegreesBits = null,
        bearingAccuracyDegreesBits = null,
        provider = null,
        isMockLocation = false,
    )

    @Test(expected = IllegalArgumentException::class)
    fun `a negative observationCount is rejected`() {
        OrderedObservationWindowIdentity(observationCount = -1, orderedObservationIdentities = emptyList(), sequenceChecksum = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an orderedObservationIdentities size mismatched with observationCount is rejected`() {
        OrderedObservationWindowIdentity(
            observationCount = 2,
            orderedObservationIdentities = listOf(ObservationIdentityEntry("a", dummyContentFingerprint)),
            sequenceChecksum = 0L,
        )
    }

    @Test
    fun `an empty window identity (zero observations) is allowed`() {
        val result = OrderedObservationWindowIdentity(observationCount = 0, orderedObservationIdentities = emptyList(), sequenceChecksum = 0L)

        assertEquals(0, result.observationCount)
    }
}
