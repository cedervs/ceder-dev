package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryReconstructorTest {

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)

    /** A placeholder content fingerprint for tests that only need a well-formed
     * [ObservationIdentityEntry], not any specific field values. */
    private val dummyContentFingerprint = ObservationContentFingerprint(
        latitude = paris.latitude,
        longitude = paris.longitude,
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

    private fun observation(elapsedRealtimeNanos: Long) = TrajectoryObservation(
        coordinate = paris,
        observedAt = Instant.parse("2026-01-01T10:00:00Z"),
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

    // ==============================================================================================
    // NoOpTrajectoryReconstructor -- the only implementation wired anywhere in Phase 1.
    // ==============================================================================================

    @Test
    fun `NoOpTrajectoryReconstructor always returns NoReconstruction`() {
        val window = ObservationWindow(listOf(observation(1L), observation(2L)))
        val context = TrajectoryReconstructionContext(engineVersion = 1)

        val result = NoOpTrajectoryReconstructor().reconstruct(window, context)

        assertTrue(result is TrajectoryReconstructionResult.NoReconstruction)
    }

    @Test
    fun `NoOpTrajectoryReconstructor explains itself with CONFIGURATION_DISABLED`() {
        val window = ObservationWindow(listOf(observation(1L)))
        val context = TrajectoryReconstructionContext(engineVersion = 1)

        val result = NoOpTrajectoryReconstructor().reconstruct(window, context)

        assertEquals(
            listOf(ReconstructionReasonCode.CONFIGURATION_DISABLED),
            result.reasons.map { it.code },
        )
    }

    @Test
    fun `NoOpTrajectoryReconstructor never emits a cadence recommendation`() {
        val window = ObservationWindow(listOf(observation(1L)))
        val result = NoOpTrajectoryReconstructor().reconstruct(window, TrajectoryReconstructionContext(engineVersion = 1))

        assertEquals(null, result.cadenceRecommendation)
    }

    // ==============================================================================================
    // ObservationWindow -- non-empty invariant.
    // ==============================================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `an empty ObservationWindow is rejected`() {
        ObservationWindow(emptyList())
    }

    @Test
    fun `a single-observation ObservationWindow is allowed`() {
        val window = ObservationWindow(listOf(observation(1L)))

        assertEquals(1, window.observations.size)
    }

    // ==============================================================================================
    // AcceptedTrajectory -- must carry a real geometry, never a degenerate single-point "trajectory",
    // and every invariant below is enforced at construction (Codex review hardening round).
    // ==============================================================================================

    private fun accepted(
        geometry: List<Coordinate> = listOf(paris, paris),
        observedIndices: Set<Int> = setOf(0, 1),
        inferredIndices: Set<Int> = emptySet(),
        engineVersion: Int = 1,
        inputObservationCount: Int = 2,
        observationMatches: List<ObservationNetworkMatch> = emptyList(),
    ) = TrajectoryReconstructionResult.AcceptedTrajectory(
        geometry = geometry,
        observedIndices = observedIndices,
        inferredIndices = inferredIndices,
        strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
        transportModeHypothesis = null,
        confidence = ReconstructionConfidence.UNKNOWN,
        engineVersion = engineVersion,
        graphSource = null,
        parameters = emptyMap(),
        inputObservationCount = inputObservationCount,
        observationMatches = observationMatches,
        bridgedObservationEdges = emptySet(),
        reasons = emptyList(),
    )

    @Test(expected = IllegalArgumentException::class)
    fun `an AcceptedTrajectory with fewer than 2 geometry points is rejected`() {
        accepted(geometry = listOf(paris), observedIndices = setOf(0), inputObservationCount = 1)
    }

    @Test
    fun `an AcceptedTrajectory with 2 geometry points is allowed`() {
        val result = accepted()

        assertEquals(2, result.geometry.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative observedIndices entry is rejected`() {
        accepted(observedIndices = setOf(-1, 1), inferredIndices = setOf(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an observedIndices entry beyond geometry size is rejected`() {
        accepted(observedIndices = setOf(0, 5), inferredIndices = emptySet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an inferredIndices entry beyond geometry size is rejected`() {
        accepted(observedIndices = setOf(0), inferredIndices = setOf(5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlap between observedIndices and inferredIndices is rejected`() {
        accepted(observedIndices = setOf(0, 1), inferredIndices = setOf(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a geometry point with no provenance at all is rejected`() {
        // 3 points, but only index 0 and 1 are accounted for -- index 2 has neither an observed
        // nor an inferred provenance, which must not be silently allowed.
        accepted(
            geometry = listOf(paris, paris, paris),
            observedIndices = setOf(0),
            inferredIndices = setOf(1),
            inputObservationCount = 1,
        )
    }

    @Test
    fun `every geometry point covered by the union of observed and inferred indices is allowed`() {
        val result = accepted(
            geometry = listOf(paris, paris, paris),
            observedIndices = setOf(0, 2),
            inferredIndices = setOf(1),
            inputObservationCount = 2,
        )

        assertEquals(setOf(0, 2), result.observedIndices)
        assertEquals(setOf(1), result.inferredIndices)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `engineVersion 0 is rejected`() {
        accepted(engineVersion = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative engineVersion is rejected`() {
        accepted(engineVersion = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inputObservationCount 0 is rejected`() {
        accepted(inputObservationCount = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative inputObservationCount is rejected`() {
        accepted(inputObservationCount = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an observationMatches entry indexing beyond inputObservationCount is rejected`() {
        accepted(
            inputObservationCount = 2,
            observationMatches = listOf(ObservationNetworkMatch(observationIndex = 2, candidateDescription = "edge-1")),
        )
    }

    @Test
    fun `an observationMatches entry within inputObservationCount is allowed`() {
        val result = accepted(
            inputObservationCount = 2,
            observationMatches = listOf(ObservationNetworkMatch(observationIndex = 1, candidateDescription = "edge-1")),
        )

        assertEquals(1, result.observationMatches.single().observationIndex)
    }

    @Test
    fun `mutating the caller's list after construction does not affect the built AcceptedTrajectory`() {
        val mutableGeometry = mutableListOf(paris, paris)
        val mutableObserved = mutableSetOf(0, 1)
        val result = TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = mutableGeometry,
            observedIndices = mutableObserved,
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
            transportModeHypothesis = null,
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 2,
            observationMatches = emptyList(),
            bridgedObservationEdges = emptySet(),
            reasons = emptyList(),
        )

        mutableGeometry.add(paris)
        mutableObserved.add(2)

        assertEquals(2, result.geometry.size)
        assertEquals(setOf(0, 1), result.observedIndices)
    }

    @Test
    fun `mutating the caller's reasons list after construction does not affect the built AcceptedTrajectory`() {
        val mutableReasons = mutableListOf(ReconstructionReason(ReconstructionReasonCode.OTHER))
        val result = TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, paris),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
            transportModeHypothesis = null,
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 2,
            observationMatches = emptyList(),
            bridgedObservationEdges = emptySet(),
            reasons = mutableReasons,
        )

        mutableReasons.add(ReconstructionReason(ReconstructionReasonCode.GAP_TOO_LONG))

        assertEquals(1, result.reasons.size)
    }

    // ==============================================================================================
    // PHASE 3A CORRECTION ROUND 2 -- bridgedObservationEdges / observationWindowIdentity invariants.
    // ==============================================================================================

    @Test
    fun `bridgedObservationEdges within the valid observation-window edge range is allowed`() {
        // inputObservationCount=5 -> valid observation-window edge indices are 0..3.
        val result = TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, paris),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
            transportModeHypothesis = null,
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 5,
            observationMatches = emptyList(),
            bridgedObservationEdges = setOf(0, 3),
            reasons = emptyList(),
        )

        assertEquals(setOf(0, 3), result.bridgedObservationEdges)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a bridgedObservationEdges entry beyond the last valid edge is rejected`() {
        TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, paris),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
            transportModeHypothesis = null,
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 5, // valid edges 0..3
            observationMatches = emptyList(),
            bridgedObservationEdges = setOf(4), // out of bounds
            reasons = emptyList(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative bridgedObservationEdges entry is rejected`() {
        TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, paris),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
            transportModeHypothesis = null,
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 5,
            observationMatches = emptyList(),
            bridgedObservationEdges = setOf(-1),
            reasons = emptyList(),
        )
    }

    @Test
    fun `bridgedObservationEdges must be empty when inputObservationCount is 1 (no edges exist)`() {
        val result = TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, paris),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
            transportModeHypothesis = null,
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 1,
            observationMatches = emptyList(),
            bridgedObservationEdges = emptySet(),
            reasons = emptyList(),
        )

        assertEquals(emptySet<Int>(), result.bridgedObservationEdges)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `any bridgedObservationEdges entry is rejected when inputObservationCount is 1`() {
        TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, paris),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
            transportModeHypothesis = null,
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 1,
            observationMatches = emptyList(),
            bridgedObservationEdges = setOf(0),
            reasons = emptyList(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an observationWindowIdentity whose own observationCount disagrees with inputObservationCount is rejected`() {
        TrajectoryReconstructionResult.AcceptedTrajectory(
            geometry = listOf(paris, paris),
            observedIndices = setOf(0, 1),
            inferredIndices = emptySet(),
            strategy = ReconstructionStrategy(ReconstructionStrategyKind.GEOMETRIC_CONTINUITY),
            transportModeHypothesis = null,
            confidence = ReconstructionConfidence.UNKNOWN,
            engineVersion = 1,
            graphSource = null,
            parameters = emptyMap(),
            inputObservationCount = 5,
            observationMatches = emptyList(),
            bridgedObservationEdges = emptySet(),
            observationWindowIdentity = OrderedObservationWindowIdentity(
                observationCount = 3,
                orderedObservationIdentities = listOf(
                    ObservationIdentityEntry(dedupKey = "a", contentFingerprint = dummyContentFingerprint),
                    ObservationIdentityEntry(dedupKey = "b", contentFingerprint = dummyContentFingerprint),
                    ObservationIdentityEntry(dedupKey = "c", contentFingerprint = dummyContentFingerprint),
                ),
                sequenceChecksum = 0L,
            ),
            reasons = emptyList(),
        )
    }

    @Test
    fun `observationWindowIdentity and matcherRunIdentity default to null`() {
        val result = accepted()

        assertEquals(null, result.observationWindowIdentity)
        assertEquals(null, result.matcherRunIdentity)
    }
}
