package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellRepository
import com.cedervs.worlddiscovery.core.discovery.DiscoveryDiagnosticSink
import com.cedervs.worlddiscovery.core.discovery.DiscoveryObservationDiagnostics
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

    // ==============================================================================================
    // TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE -- calibrationDiagnosticSink wiring. See
    // CalibrationDiagnosticFileWriter.kt's doc comment for the removal point.
    // ==============================================================================================

    @Test
    fun `every location in the batch is recorded with the same batchId and correct batchSize and indexInBatch`() = runTest {
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val sink = RecordingBatchCalibrationDiagnosticSink()
        val submitWithSink = SubmitBackgroundLocationObservations(
            SubmitDiscoveryObservation(converter, repository),
            calibrationDiagnosticSink = sink,
        )

        submitWithSink(listOf(observation(paris, earlier), observation(lyon, later)))

        val delivered = sink.delivered()
        assertEquals(2, delivered.size)
        assertEquals(delivered[0].batchId, delivered[1].batchId)
        assertTrue(delivered.all { it.batchSize == 2 })
        assertTrue(delivered.all { it.source == CalibrationLocationSource.BACKGROUND_PENDING_INTENT })
        assertEquals(listOf(0, 1), delivered.map { it.indexInBatch })
    }

    @Test
    fun `a throwing calibration sink never prevents submission to the discovery engine`() = runTest {
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val submitWithThrowingSink = SubmitBackgroundLocationObservations(
            SubmitDiscoveryObservation(converter, repository),
            calibrationDiagnosticSink = ThrowingBatchCalibrationDiagnosticSink(),
        )

        submitWithThrowingSink(listOf(observation(paris, earlier), observation(lyon, later)))

        assertEquals(2, repository.upsertCallCount)
        assertEquals(2, repository.all().size)
    }

    // ==============================================================================================
    // Codex review round -- explicit delivery correlation: each observation in a batch must reach
    // SubmitDiscoveryObservation with a deliveryId of "$batchId:$index", so its DISCOVERY_RESULT
    // can be explicitly paired with its own LOCATION_DELIVERED rather than relying on file order.
    // ==============================================================================================

    @Test
    fun `each observation in a batch is submitted with a deliveryId of batchId colon index`() = runTest {
        val converter = FakeBatchH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        val recordingCalibrationSink = RecordingBatchCalibrationDiagnosticSink()
        val recordingDiscoverySink = RecordingDeliveryCorrelationDiscoverySink()
        val submitWithSinks = SubmitBackgroundLocationObservations(
            SubmitDiscoveryObservation(converter, repository, recordingDiscoverySink),
            calibrationDiagnosticSink = recordingCalibrationSink,
        )

        submitWithSinks(listOf(observation(paris, earlier), observation(lyon, later)))

        val delivered = recordingCalibrationSink.delivered()
        val discoveryResults = recordingDiscoverySink.recorded
        assertEquals(2, discoveryResults.size)
        // Every DISCOVERY_RESULT's deliveryId must match some LOCATION_DELIVERED's own
        // batchId/indexInBatch pair -- proving the two can be paired explicitly, not just by
        // adjacency in the file.
        for (result in discoveryResults) {
            val matchingDelivery = delivered.singleOrNull { "${it.batchId}:${it.indexInBatch}" == result.deliveryId }
            assertTrue("no LOCATION_DELIVERED matched DISCOVERY_RESULT deliveryId=${result.deliveryId}", matchingDelivery != null)
        }
        assertEquals(delivered[0].batchId, delivered[1].batchId) // still one shared batchId
        assertEquals(setOf("0", "1"), discoveryResults.map { it.deliveryId!!.substringAfterLast(":") }.toSet())
    }
}

private class RecordingDeliveryCorrelationDiscoverySink : DiscoveryDiagnosticSink {
    val recorded = mutableListOf<DiscoveryObservationDiagnostics>()

    override fun record(diagnostics: DiscoveryObservationDiagnostics) {
        recorded.add(diagnostics)
    }
}

private class RecordingBatchCalibrationDiagnosticSink : CalibrationDiagnosticSink {
    private val recorded = mutableListOf<CalibrationDiagnosticEvent.LocationDelivered>()

    override fun record(event: CalibrationDiagnosticEvent) {
        if (event is CalibrationDiagnosticEvent.LocationDelivered) recorded.add(event)
    }

    override fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long): Boolean {
        record(event)
        return true
    }

    fun delivered(): List<CalibrationDiagnosticEvent.LocationDelivered> = recorded
}

private class ThrowingBatchCalibrationDiagnosticSink : CalibrationDiagnosticSink {
    override fun record(event: CalibrationDiagnosticEvent) {
        error("simulated calibration diagnostic sink failure")
    }

    override fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long): Boolean {
        error("simulated calibration diagnostic sink failure")
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
