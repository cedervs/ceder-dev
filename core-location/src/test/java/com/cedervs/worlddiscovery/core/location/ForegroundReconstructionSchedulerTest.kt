package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.H3GridTraversal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundReconstructionSchedulerTest {

    private val parisCoordinate = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val lyonCoordinate = Coordinate(latitude = 45.7640, longitude = 4.8357)
    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
    private val lyonCell = CanonicalCell(h3Index = "8c1f2a4362d1fff", resolution = 12)

    private fun observation(coordinate: Coordinate, observedAt: Instant) = LocationObservation(
        coordinate = coordinate,
        observedAt = observedAt,
        accuracyMeters = null,
        speedMetersPerSecond = null,
        provider = null,
    )

    @Test
    fun `the default policy is deny-all — no candidate is ever produced, and H3 is never touched`() {
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = UnusedH3CellConverter(),
            gridTraversal = UnusedH3GridTraversal(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        // Reaching this assertion at all is proof H3CellConverter/H3GridTraversal were never
        // called — both fakes error() if invoked.
        assertEquals(ReconstructionCandidate.NotEligible, result)
    }

    @Test
    fun `an explicit deny-all policy behaves identically to the default`() {
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = UnusedH3CellConverter(),
            gridTraversal = UnusedH3GridTraversal(),
            eligibilityPolicy = DenyAllReconstructionEligibilityPolicy(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        assertEquals(ReconstructionCandidate.NotEligible, result)
    }

    @Test
    fun `origin equal to destination produces a candidate with no intermediate cells`() {
        val converter = FakeSchedulerH3CellConverter(mapOf(parisCoordinate to parisCell))
        val traversal = FakeSchedulerH3GridTraversal(mapOf((parisCell to parisCell) to listOf(parisCell)))
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = converter,
            gridTraversal = traversal,
            eligibilityPolicy = AllowAllReconstructionEligibilityPolicy(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        assertEquals(ReconstructionCandidate.Candidate(intermediateCells = emptyList()), result)
    }

    @Test
    fun `adjacent origin and destination produce a candidate with no intermediate cells`() {
        val converter = FakeSchedulerH3CellConverter(mapOf(parisCoordinate to parisCell, lyonCoordinate to lyonCell))
        val traversal = FakeSchedulerH3GridTraversal(mapOf((parisCell to lyonCell) to listOf(parisCell, lyonCell)))
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = converter,
            gridTraversal = traversal,
            eligibilityPolicy = AllowAllReconstructionEligibilityPolicy(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        // The path itself is [parisCell, lyonCell] (both endpoints, per H3GridTraversal's
        // contract) — nothing strictly between them, so intermediateCells is empty.
        assertEquals(ReconstructionCandidate.Candidate(intermediateCells = emptyList()), result)
    }

    @Test
    fun `a path with one cell strictly between origin and destination surfaces exactly that cell`() {
        val midCell = CanonicalCell(h3Index = "8c1fb469b6969ff", resolution = 12)
        val converter = FakeSchedulerH3CellConverter(mapOf(parisCoordinate to parisCell, lyonCoordinate to lyonCell))
        val traversal = FakeSchedulerH3GridTraversal(
            mapOf((parisCell to lyonCell) to listOf(parisCell, midCell, lyonCell)),
        )
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = converter,
            gridTraversal = traversal,
            eligibilityPolicy = AllowAllReconstructionEligibilityPolicy(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        assertEquals(ReconstructionCandidate.Candidate(intermediateCells = listOf(midCell)), result)
    }

    @Test
    fun `a path with several cells strictly between origin and destination surfaces exactly those cells, in order`() {
        val mid1 = CanonicalCell(h3Index = "8c1fb469b6969ff", resolution = 12)
        val mid2 = CanonicalCell(h3Index = "8c1fb4662555dff", resolution = 12)
        val mid3 = CanonicalCell(h3Index = "8c08000000035ff", resolution = 12)
        val converter = FakeSchedulerH3CellConverter(mapOf(parisCoordinate to parisCell, lyonCoordinate to lyonCell))
        val traversal = FakeSchedulerH3GridTraversal(
            mapOf((parisCell to lyonCell) to listOf(parisCell, mid1, mid2, mid3, lyonCell)),
        )
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = converter,
            gridTraversal = traversal,
            eligibilityPolicy = AllowAllReconstructionEligibilityPolicy(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        assertEquals(ReconstructionCandidate.Candidate(intermediateCells = listOf(mid1, mid2, mid3)), result)
    }

    @Test
    fun `intermediateCells never contains the origin or destination cell`() {
        val mid = CanonicalCell(h3Index = "8c1fb469b6969ff", resolution = 12)
        val converter = FakeSchedulerH3CellConverter(mapOf(parisCoordinate to parisCell, lyonCoordinate to lyonCell))
        val traversal = FakeSchedulerH3GridTraversal(
            mapOf((parisCell to lyonCell) to listOf(parisCell, mid, lyonCell)),
        )
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = converter,
            gridTraversal = traversal,
            eligibilityPolicy = AllowAllReconstructionEligibilityPolicy(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        ) as ReconstructionCandidate.Candidate

        assertTrue(parisCell !in result.intermediateCells)
        assertTrue(lyonCell !in result.intermediateCells)
    }

    @Test
    fun `an eligible transition whose H3 path cannot be computed produces NoPath, not an exception`() {
        val converter = FakeSchedulerH3CellConverter(mapOf(parisCoordinate to parisCell, lyonCoordinate to lyonCell))
        val traversal = FakeSchedulerH3GridTraversal(emptyMap())
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = converter,
            gridTraversal = traversal,
            eligibilityPolicy = AllowAllReconstructionEligibilityPolicy(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        assertEquals(ReconstructionCandidate.NoPath, result)
    }

    @Test
    fun `a diagnostic logger that throws never prevents evaluateTransition from returning a result`() {
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = UnusedH3CellConverter(),
            gridTraversal = UnusedH3GridTraversal(),
            diagnosticLogger = ThrowingTransitionDiagnosticLogger(),
        )

        val result = scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        assertEquals(ReconstructionCandidate.NotEligible, result)
    }

    @Test
    fun `the logged diagnostics carry only aggregated facts, never a coordinate, H3 id, or path`() {
        val recording = RecordingTransitionDiagnosticLogger()
        val converter = FakeSchedulerH3CellConverter(mapOf(parisCoordinate to parisCell, lyonCoordinate to lyonCell))
        val traversal = FakeSchedulerH3GridTraversal(mapOf((parisCell to lyonCell) to listOf(parisCell, lyonCell)))
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = converter,
            gridTraversal = traversal,
            eligibilityPolicy = AllowAllReconstructionEligibilityPolicy(),
            diagnosticLogger = recording,
        )

        scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        // ReconstructionTransitionDiagnostics's fields are structurally aggregate-only (deltas,
        // booleans, a count) — there is no field a coordinate/H3 id/full path could even be
        // placed in. This asserts the values themselves are the aggregated facts expected.
        val logged = requireNotNull(recording.logged)
        assertTrue(logged.eligible)
        assertEquals(5_000L, logged.deltaMillis)
        assertEquals(2, logged.pathCellCount)
    }

    @Test
    fun `a not-eligible transition is still logged, with a null path cell count`() {
        val recording = RecordingTransitionDiagnosticLogger()
        val scheduler = ForegroundReconstructionScheduler(
            cellConverter = UnusedH3CellConverter(),
            gridTraversal = UnusedH3GridTraversal(),
            diagnosticLogger = recording,
        )

        scheduler.evaluateTransition(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        val logged = requireNotNull(recording.logged)
        assertEquals(false, logged.eligible)
        assertEquals(null, logged.pathCellCount)
    }
}

private class UnusedH3CellConverter : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        error("not expected to be called in this test")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        error("not expected to be called in this test")

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        error("not expected to be called in this test")

    override fun isValidCell(cell: CanonicalCell): Boolean =
        error("not expected to be called in this test")
}

private class UnusedH3GridTraversal : H3GridTraversal {
    override fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>? =
        error("not expected to be called in this test")
}

private class AllowAllReconstructionEligibilityPolicy : ReconstructionEligibilityPolicy {
    override fun isEligible(from: LocationObservation, to: LocationObservation): Boolean = true
}

private class FakeSchedulerH3CellConverter(
    private val mapping: Map<Coordinate, CanonicalCell>,
) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        mapping[coordinate] ?: error("No fake mapping configured for $coordinate")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        error("not expected to be called in this test")

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        error("not expected to be called in this test")

    override fun isValidCell(cell: CanonicalCell): Boolean =
        error("not expected to be called in this test")
}

private class FakeSchedulerH3GridTraversal(
    private val paths: Map<Pair<CanonicalCell, CanonicalCell>, List<CanonicalCell>>,
) : H3GridTraversal {
    override fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>? =
        paths[origin to destination]
}

private class ThrowingTransitionDiagnosticLogger : TransitionDiagnosticLogger {
    override fun log(transition: ReconstructionTransitionDiagnostics) {
        error("simulated diagnostic logger failure")
    }
}

private class RecordingTransitionDiagnosticLogger : TransitionDiagnosticLogger {
    var logged: ReconstructionTransitionDiagnostics? = null
        private set

    override fun log(transition: ReconstructionTransitionDiagnostics) {
        logged = transition
    }
}
