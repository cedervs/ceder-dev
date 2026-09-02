package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.H3GridTraversal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTransitionDiagnosticsTest {

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
    fun `record computes the path independently of any eligibility policy — there is none to consult`() {
        val recording = DiagnosticsRecordingTransitionDiagnosticLogger()
        val converter = FakeDiagnosticsH3CellConverter(mapOf(parisCoordinate to parisCell, lyonCoordinate to lyonCell))
        val traversal = FakeDiagnosticsH3GridTraversal(mapOf((parisCell to lyonCell) to listOf(parisCell, lyonCell)))
        val diagnostics = ForegroundTransitionDiagnostics(converter, traversal, recording)

        diagnostics.record(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        val logged = requireNotNull(recording.logged)
        assertEquals(2, logged.pathCellCount)
        assertEquals(true, logged.pathComputed)
        // No policy exists on this class at all — eligible is always false, purely because there
        // was nothing to evaluate, not because a policy denied it.
        assertEquals(false, logged.eligible)
    }

    @Test
    fun `record never throws and never produces a candidate when H3 cannot compute a path`() {
        val recording = DiagnosticsRecordingTransitionDiagnosticLogger()
        val converter = FakeDiagnosticsH3CellConverter(mapOf(parisCoordinate to parisCell, lyonCoordinate to lyonCell))
        val traversal = FakeDiagnosticsH3GridTraversal(emptyMap())
        val diagnostics = ForegroundTransitionDiagnostics(converter, traversal, recording)

        diagnostics.record(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        val logged = requireNotNull(recording.logged)
        assertEquals(false, logged.pathComputed)
        assertNull(logged.pathCellCount)
    }

    @Test
    fun `record converts both coordinates through the injected H3CellConverter, never doing it itself`() {
        var convertedCount = 0
        val converter = object : H3CellConverter {
            override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell {
                convertedCount++
                return if (coordinate == parisCoordinate) parisCell else lyonCell
            }

            override fun cellBoundary(cell: CanonicalCell): List<Coordinate> = error("not expected to be called in this test")
            override fun cellCenter(cell: CanonicalCell): Coordinate = error("not expected to be called in this test")
            override fun isValidCell(cell: CanonicalCell): Boolean = error("not expected to be called in this test")
        }
        val traversal = FakeDiagnosticsH3GridTraversal(mapOf((parisCell to lyonCell) to listOf(parisCell, lyonCell)))
        val diagnostics = ForegroundTransitionDiagnostics(converter, traversal, NoOpTransitionDiagnosticLogger())

        diagnostics.record(
            observation(parisCoordinate, Instant.parse("2026-01-01T10:00:00Z")),
            observation(lyonCoordinate, Instant.parse("2026-01-01T10:00:05Z")),
        )

        assertEquals(2, convertedCount)
    }
}

private class FakeDiagnosticsH3CellConverter(
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

private class FakeDiagnosticsH3GridTraversal(
    private val paths: Map<Pair<CanonicalCell, CanonicalCell>, List<CanonicalCell>>,
) : H3GridTraversal {
    override fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>? =
        paths[origin to destination]
}

private class DiagnosticsRecordingTransitionDiagnosticLogger : TransitionDiagnosticLogger {
    var logged: ReconstructionTransitionDiagnostics? = null
        private set

    override fun log(transition: ReconstructionTransitionDiagnostics) {
        logged = transition
    }
}
