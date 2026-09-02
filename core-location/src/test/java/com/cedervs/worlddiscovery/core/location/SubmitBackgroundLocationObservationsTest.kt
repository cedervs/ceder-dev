package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellRepository
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubmitBackgroundLocationObservationsTest {

    private lateinit var repository: FakeBatchDiscoveredCellRepository
    private lateinit var submitBackgroundLocationObservations: SubmitBackgroundLocationObservations

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
    private val lyon = Coordinate(latitude = 45.7640, longitude = 4.8357)
    private val lyonCell = CanonicalCell(h3Index = "8c1f2a4362d1fff", resolution = 12)

    private val earlier = Instant.parse("2026-08-29T10:00:00Z")
    private val later = Instant.parse("2026-08-29T10:20:00Z")

    private fun observation(coordinate: Coordinate, observedAt: Instant) = LocationObservation(
        coordinate = coordinate,
        observedAt = observedAt,
        accuracyMeters = null,
        speedMetersPerSecond = null,
        provider = null,
    )

    @Before
    fun setUp() {
        repository = FakeBatchDiscoveredCellRepository()
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val submitDiscoveryObservation = SubmitDiscoveryObservation(converter, repository)
        submitBackgroundLocationObservations = SubmitBackgroundLocationObservations(submitDiscoveryObservation)
    }

    @Test
    fun `every location in a batch is submitted, not just the last`() = runTest {
        submitBackgroundLocationObservations(
            listOf(
                observation(paris, earlier),
                observation(lyon, later),
            ),
        )

        assertEquals(2, repository.upsertCallCount)
        assertEquals(2, repository.all().size)
    }

    @Test
    fun `each location keeps its own timestamp rather than a shared now`() = runTest {
        // Two fixes in the same cell, submitted in chronological order — firstDiscoveredAt and
        // lastObservedAt must reflect the two distinct fix timestamps, not Instant.now().
        submitBackgroundLocationObservations(
            listOf(
                observation(paris, earlier),
                observation(paris, later),
            ),
        )

        val cell = repository.all().single()
        assertEquals(earlier, cell.firstDiscoveredAt)
        assertEquals(later, cell.lastObservedAt)
    }

    @Test
    fun `an out-of-order batch still ends up with the correct earliest and latest timestamps`() = runTest {
        // This class does not itself sort — extractBackgroundLocationObservations does — but the
        // underlying DiscoveredCellMerger is order-independent regardless of submission order.
        submitBackgroundLocationObservations(
            listOf(
                observation(paris, later),
                observation(paris, earlier),
            ),
        )

        val cell = repository.all().single()
        assertEquals(earlier, cell.firstDiscoveredAt)
        assertEquals(later, cell.lastObservedAt)
    }

    @Test
    fun `a diagnostic logger that throws never prevents submission to the discovery engine`() = runTest {
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val submitWithThrowingLogger = SubmitBackgroundLocationObservations(
            SubmitDiscoveryObservation(converter, repository),
            diagnosticLogger = BatchThrowingLocationDiagnosticLogger(),
        )

        submitWithThrowingLogger(
            listOf(
                observation(paris, earlier),
                observation(lyon, later),
            ),
        )

        assertEquals(2, repository.upsertCallCount)
        assertEquals(2, repository.all().size)
    }

    @Test
    fun `a CancellationException fabricated by the diagnostic logger never prevents submission for the rest of the batch`() = runTest {
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val submitWithCancellingLogger = SubmitBackgroundLocationObservations(
            SubmitDiscoveryObservation(converter, repository),
            diagnosticLogger = BatchCancellingLocationDiagnosticLogger(),
        )

        submitWithCancellingLogger(
            listOf(
                observation(paris, earlier),
                observation(lyon, later),
            ),
        )

        assertEquals(2, repository.upsertCallCount)
        assertEquals(2, repository.all().size)
    }

    @Test
    fun `an empty batch touches the repository not at all`() = runTest {
        submitBackgroundLocationObservations(emptyList())

        assertTrue(repository.all().isEmpty())
        assertEquals(0, repository.upsertCallCount)
    }

    @Test
    fun `a failed submission is skipped without aborting the rest of the batch`() = runTest {
        repository.throwOnUpsertForCell = parisCell

        submitBackgroundLocationObservations(
            listOf(
                observation(paris, earlier),
                observation(lyon, later),
            ),
        )

        assertEquals(1, repository.all().size)
        assertEquals(lyonCell, repository.all().single().cell)
    }

    @Test
    fun `the background diagnostic logger receives the exact delivered batch once`() = runTest {
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val recordingLogger = RecordingBackgroundLocationDiagnosticLogger()
        val submitWithDiagnostics = SubmitBackgroundLocationObservations(
            SubmitDiscoveryObservation(converter, repository),
            backgroundDiagnosticLogger = recordingLogger,
        )
        val batch = listOf(observation(paris, earlier), observation(lyon, later))

        submitWithDiagnostics(batch)

        assertEquals(1, recordingLogger.deliveries.size)
        assertEquals(batch, recordingLogger.deliveries.single())
    }

    @Test
    fun `a throwing background diagnostic logger never prevents submission to the discovery engine`() = runTest {
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val submitWithThrowingBackgroundLogger = SubmitBackgroundLocationObservations(
            SubmitDiscoveryObservation(converter, repository),
            backgroundDiagnosticLogger = ThrowingBackgroundLocationDiagnosticLogger(),
        )

        submitWithThrowingBackgroundLogger(
            listOf(
                observation(paris, earlier),
                observation(lyon, later),
            ),
        )

        assertEquals(2, repository.upsertCallCount)
        assertEquals(2, repository.all().size)
    }

    @Test
    fun `a CancellationException fabricated by the background diagnostic logger never prevents submission`() = runTest {
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val submitWithCancellingBackgroundLogger = SubmitBackgroundLocationObservations(
            SubmitDiscoveryObservation(converter, repository),
            backgroundDiagnosticLogger = CancellingBackgroundLocationDiagnosticLogger(),
        )

        submitWithCancellingBackgroundLogger(
            listOf(
                observation(paris, earlier),
                observation(lyon, later),
            ),
        )

        assertEquals(2, repository.upsertCallCount)
        assertEquals(2, repository.all().size)
    }
}

private class RecordingBackgroundLocationDiagnosticLogger : BackgroundLocationDiagnosticLogger {
    val deliveries = mutableListOf<List<LocationObservation>>()

    override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) = Unit

    override fun logDelivery(observations: List<LocationObservation>) {
        deliveries.add(observations)
    }
}

private class ThrowingBackgroundLocationDiagnosticLogger : BackgroundLocationDiagnosticLogger {
    override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) = Unit

    override fun logDelivery(observations: List<LocationObservation>) {
        error("simulated background diagnostic logger failure")
    }
}

private class CancellingBackgroundLocationDiagnosticLogger : BackgroundLocationDiagnosticLogger {
    override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) = Unit

    override fun logDelivery(observations: List<LocationObservation>) {
        throw CancellationException("fabricated by a misbehaving synchronous logger, not real cancellation")
    }
}

private class BatchThrowingLocationDiagnosticLogger : LocationDiagnosticLogger {
    override fun log(observation: LocationObservation) {
        error("simulated diagnostic logger failure")
    }
}

private class BatchCancellingLocationDiagnosticLogger : LocationDiagnosticLogger {
    override fun log(observation: LocationObservation) {
        throw CancellationException("fabricated by a misbehaving synchronous logger, not real cancellation")
    }
}

private class FakeBatchH3CellConverter(private val mapping: Map<Coordinate, CanonicalCell>) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        mapping[coordinate] ?: error("No fake mapping configured for $coordinate")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        error("not expected to be called in this test")

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        error("not expected to be called in this test")

    override fun isValidCell(cell: CanonicalCell): Boolean =
        error("not expected to be called in this test")
}

private class FakeBatchDiscoveredCellRepository : DiscoveredCellRepository {
    private val storage = mutableMapOf<Pair<CanonicalCell, TrustStatus>, DiscoveredCell>()
    var upsertCallCount = 0
        private set
    var throwOnUpsertForCell: CanonicalCell? = null

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        storage[cell to trustStatus]

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        upsertCallCount++
        if (discoveredCell.cell == throwOnUpsertForCell) {
            error("simulated persistence failure")
        }
        storage[discoveredCell.cell to discoveredCell.trustStatus] = discoveredCell
    }

    override fun observeAll(): Flow<List<DiscoveredCell>> = error("not expected to be called in this test")

    fun all(): List<DiscoveredCell> = storage.values.toList()
}
