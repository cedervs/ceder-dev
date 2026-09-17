package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubmitDiscoveryObservationTest {

    private lateinit var converter: FakeH3CellConverter
    private lateinit var repository: FakeDiscoveredCellRepository
    private lateinit var submitObservation: SubmitDiscoveryObservation

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

    @Before
    fun setUp() {
        converter = FakeH3CellConverter(mapOf(paris to parisCell))
        repository = FakeDiscoveredCellRepository()
        submitObservation = SubmitDiscoveryObservation(converter, repository)
    }

    @Test
    fun `submitting the same coordinate twice does not create two discovered cells`() = runTest {
        val timestamp = Instant.parse("2026-01-01T10:00:00Z")

        submitObservation(paris, timestamp, Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        submitObservation(paris, timestamp.plusSeconds(60), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)

        assertEquals(1, repository.all().size)
    }

    @Test
    fun `two observations mapped to the same H3 cell merge into one record`() = runTest {
        val nearbyCoordinate = Coordinate(latitude = 48.85661, longitude = 2.35221)
        converter.mapping = converter.mapping + (nearbyCoordinate to parisCell)

        submitObservation(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        submitObservation(
            nearbyCoordinate,
            Instant.parse("2026-01-01T10:05:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(1, repository.all().size)
    }

    @Test
    fun `firstDiscoveredAt remains the earliest submission timestamp`() = runTest {
        submitObservation(paris, Instant.parse("2026-01-05T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        val result = submitObservation(
            paris,
            Instant.parse("2026-01-01T10:00:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), result.firstDiscoveredAt)
    }

    @Test
    fun `lastObservedAt advances to the latest submission timestamp`() = runTest {
        submitObservation(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        val result = submitObservation(
            paris,
            Instant.parse("2026-01-05T10:00:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), result.lastObservedAt)
    }

    @Test
    fun `trust status is preserved across merges and Certified stays independent from Non-certified`() = runTest {
        submitObservation(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        submitObservation(paris, Instant.parse("2026-01-02T10:00:00Z"), Provenance.OBSERVED, TrustStatus.CERTIFIED)

        assertEquals(2, repository.all().size)
        val statuses = repository.all().map { it.trustStatus }.toSet()
        assertEquals(setOf(TrustStatus.NON_CERTIFIED, TrustStatus.CERTIFIED), statuses)
    }

    @Test
    fun `engine version and H3 resolution are recorded on the persisted cell`() = runTest {
        val result = submitObservation(
            paris,
            Instant.parse("2026-01-01T10:00:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(DiscoveryEngineVersion.CURRENT, result.engineVersion)
        assertEquals(DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION, result.h3Resolution)
    }

    @Test
    fun `repository and use case operate fully in memory without any backend dependency`() = runTest {
        submitObservation(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)

        assertTrue(repository.all().isNotEmpty())
    }

    // ==============================================================================================
    // TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE -- DiscoveryDiagnosticSink wiring. See
    // DiscoveryDiagnosticSink.kt's doc comment for the removal point.
    // ==============================================================================================

    @Test
    fun `a genuinely new cell is recorded as NEW_CELL`() = runTest {
        val recordingSink = RecordingDiscoveryDiagnosticSink()
        val submitWithSink = SubmitDiscoveryObservation(converter, repository, recordingSink)

        submitWithSink(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)

        assertEquals(1, recordingSink.recorded.size)
        assertEquals(DiscoveryObservationOutcome.NEW_CELL, recordingSink.recorded.single().outcome)
        assertEquals(parisCell.h3Index, recordingSink.recorded.single().h3Cell)
        assertEquals(null, recordingSink.recorded.single().rejectionReason)
    }

    @Test
    fun `a second submission into the same cell is recorded as MERGED_EXISTING, not NEW_CELL`() = runTest {
        val recordingSink = RecordingDiscoveryDiagnosticSink()
        val submitWithSink = SubmitDiscoveryObservation(converter, repository, recordingSink)

        submitWithSink(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        submitWithSink(paris, Instant.parse("2026-01-01T10:05:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)

        assertEquals(2, recordingSink.recorded.size)
        assertEquals(DiscoveryObservationOutcome.NEW_CELL, recordingSink.recorded[0].outcome)
        assertEquals(DiscoveryObservationOutcome.MERGED_EXISTING, recordingSink.recorded[1].outcome)
    }

    @Test
    fun `a repository failure is recorded as REJECTED with only the exception class name, and still rethrows`() = runTest {
        val throwingRepository = ThrowingOnUpsertRepository()
        val recordingSink = RecordingDiscoveryDiagnosticSink()
        val submitWithSink = SubmitDiscoveryObservation(converter, throwingRepository, recordingSink)

        try {
            submitWithSink(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
            org.junit.Assert.fail("expected the simulated persistence failure to propagate")
        } catch (e: IllegalStateException) {
            // expected -- see ThrowingOnUpsertRepository
        }

        assertEquals(1, recordingSink.recorded.size)
        val diagnostics = recordingSink.recorded.single()
        assertEquals(DiscoveryObservationOutcome.REJECTED, diagnostics.outcome)
        assertEquals("IllegalStateException", diagnostics.rejectionReason)
    }

    @Test
    fun `a diagnostic sink that throws never prevents submission from succeeding`() = runTest {
        val submitWithThrowingSink = SubmitDiscoveryObservation(converter, repository, ThrowingDiscoveryDiagnosticSink())

        val result = submitWithThrowingSink(
            paris,
            Instant.parse("2026-01-01T10:00:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(1, repository.all().size)
        assertEquals(parisCell, result.cell)
    }

    @Test
    fun `a CancellationException fabricated by the diagnostic sink never surfaces as a rejection`() = runTest {
        val submitWithCancellingSink = SubmitDiscoveryObservation(converter, repository, CancellingDiscoveryDiagnosticSink())

        // Must not throw -- recordSafely swallows the fabricated CancellationException, and the
        // genuine submission below it already completed successfully.
        submitWithCancellingSink(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)

        assertEquals(1, repository.all().size)
    }

    // ==============================================================================================
    // Codex review round -- REJECTED_PRE_H3: a conversion failure (before any H3 cell exists) must
    // itself be diagnosable, not silently indistinguishable from "never reached this code."
    // ==============================================================================================

    @Test
    fun `an H3 conversion failure is recorded as REJECTED_PRE_H3 with a null h3Cell, and still rethrows`() = runTest {
        val recordingSink = RecordingDiscoveryDiagnosticSink()
        val submitWithSink = SubmitDiscoveryObservation(converter, repository, recordingSink)
        val unmappedCoordinate = Coordinate(latitude = 10.0, longitude = 10.0) // not in converter.mapping

        try {
            submitWithSink(unmappedCoordinate, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
            org.junit.Assert.fail("expected the simulated conversion failure to propagate")
        } catch (e: IllegalStateException) {
            // expected -- see FakeH3CellConverter
        }

        assertEquals(1, recordingSink.recorded.size)
        val diagnostics = recordingSink.recorded.single()
        assertEquals(DiscoveryObservationOutcome.REJECTED_PRE_H3, diagnostics.outcome)
        assertEquals(null, diagnostics.h3Cell)
        assertEquals("IllegalStateException", diagnostics.rejectionReason)
        assertEquals(0, repository.all().size)
    }

    @Test
    fun `a genuine CancellationException from H3 conversion propagates and is never recorded as REJECTED_PRE_H3`() = runTest {
        val cancellingConverter = CancellingH3CellConverter()
        val recordingSink = RecordingDiscoveryDiagnosticSink()
        val submitWithSink = SubmitDiscoveryObservation(cancellingConverter, repository, recordingSink)

        try {
            submitWithSink(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
            org.junit.Assert.fail("expected the CancellationException to propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected -- genuine cancellation must propagate untouched, never diagnosed as a
            // domain rejection.
        }

        assertTrue("a genuine CancellationException must never be recorded as a diagnostic outcome", recordingSink.recorded.isEmpty())
    }

    // ==============================================================================================
    // Codex review round -- explicit delivery correlation: diagnosticDeliveryId, when supplied,
    // must appear unchanged on every DiscoveryObservationDiagnostics this call produces.
    // ==============================================================================================

    @Test
    fun `diagnosticDeliveryId is threaded unchanged into the recorded diagnostics on success`() = runTest {
        val recordingSink = RecordingDiscoveryDiagnosticSink()
        val submitWithSink = SubmitDiscoveryObservation(converter, repository, recordingSink)

        submitWithSink(
            paris,
            Instant.parse("2026-01-01T10:00:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
            diagnosticDeliveryId = "batch-42:3",
        )

        assertEquals("batch-42:3", recordingSink.recorded.single().deliveryId)
    }

    @Test
    fun `diagnosticDeliveryId defaults to null when the caller does not supply one`() = runTest {
        val recordingSink = RecordingDiscoveryDiagnosticSink()
        val submitWithSink = SubmitDiscoveryObservation(converter, repository, recordingSink)

        submitWithSink(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)

        assertEquals(null, recordingSink.recorded.single().deliveryId)
    }
}

private class RecordingDiscoveryDiagnosticSink : DiscoveryDiagnosticSink {
    val recorded = mutableListOf<DiscoveryObservationDiagnostics>()

    override fun record(diagnostics: DiscoveryObservationDiagnostics) {
        recorded.add(diagnostics)
    }
}

private class ThrowingDiscoveryDiagnosticSink : DiscoveryDiagnosticSink {
    override fun record(diagnostics: DiscoveryObservationDiagnostics) {
        error("simulated diagnostic sink failure")
    }
}

private class CancellingDiscoveryDiagnosticSink : DiscoveryDiagnosticSink {
    override fun record(diagnostics: DiscoveryObservationDiagnostics) {
        throw kotlinx.coroutines.CancellationException("fabricated by a misbehaving synchronous sink, not real cancellation")
    }
}

private class ThrowingOnUpsertRepository : DiscoveredCellRepository {
    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? = null

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        error("simulated persistence failure")
    }

    override fun observeAll(): Flow<List<DiscoveredCell>> = error("not expected to be called in this test")
}

private class CancellingH3CellConverter : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        throw kotlinx.coroutines.CancellationException("simulated genuine job cancellation during conversion")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        error("not expected to be called in this test")

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        error("not expected to be called in this test")

    override fun isValidCell(cell: CanonicalCell): Boolean =
        error("not expected to be called in this test")
}

private class FakeH3CellConverter(var mapping: Map<Coordinate, CanonicalCell>) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        mapping[coordinate] ?: error("No fake mapping configured for $coordinate")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        error("not expected to be called in this test")

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        error("not expected to be called in this test")

    override fun isValidCell(cell: CanonicalCell): Boolean =
        error("not expected to be called in this test")
}

private class FakeDiscoveredCellRepository : DiscoveredCellRepository {
    private val storage = mutableMapOf<Pair<CanonicalCell, TrustStatus>, DiscoveredCell>()

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        storage[cell to trustStatus]

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        storage[discoveredCell.cell to discoveredCell.trustStatus] = discoveredCell
    }

    override fun observeAll(): Flow<List<DiscoveredCell>> = error("not expected to be called in this test")

    fun all(): List<DiscoveredCell> = storage.values.toList()
}
