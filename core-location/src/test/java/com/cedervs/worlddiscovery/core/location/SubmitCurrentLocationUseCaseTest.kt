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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubmitCurrentLocationUseCaseTest {

    private lateinit var repository: FakeDiscoveredCellRepository
    private lateinit var locationProvider: FakeLocationProvider
    private lateinit var useCase: SubmitCurrentLocationUseCase

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

    private fun observation(coordinate: Coordinate, observedAt: Instant = Instant.EPOCH) = LocationObservation(
        coordinate = coordinate,
        observedAt = observedAt,
        accuracyMeters = null,
        speedMetersPerSecond = null,
        provider = null,
    )

    @Before
    fun setUp() {
        repository = FakeDiscoveredCellRepository()
        locationProvider = FakeLocationProvider(LocationAcquisitionResult.LocationUnavailable)
        val converter = FakeH3CellConverter(mapOf(paris to parisCell))
        val submitDiscoveryObservation = SubmitDiscoveryObservation(converter, repository)
        useCase = SubmitCurrentLocationUseCase(locationProvider, submitDiscoveryObservation)
    }

    @Test
    fun `a successful location is submitted to the discovery engine`() = runTest {
        locationProvider.result = LocationAcquisitionResult.Success(observation(paris))

        val outcome = useCase()

        assertEquals(LocationTestOutcome.Success, outcome)
        assertEquals(1, repository.all().size)
        assertEquals(TrustStatus.NON_CERTIFIED, repository.all().single().trustStatus)
    }

    @Test
    fun `pressing the same location twice does not create a duplicate discovered cell`() = runTest {
        locationProvider.result = LocationAcquisitionResult.Success(observation(paris))

        useCase()
        val secondOutcome = useCase()

        assertEquals(LocationTestOutcome.Success, secondOutcome)
        assertEquals(1, repository.all().size)
    }

    @Test
    fun `the observation's own observedAt reaches the discovery engine, not the processing time`() = runTest {
        val fixObservedAt = Instant.parse("2020-01-01T00:00:00Z")
        locationProvider.result = LocationAcquisitionResult.Success(observation(paris, fixObservedAt))

        useCase()

        val stored = repository.all().single()
        assertEquals(fixObservedAt, stored.firstDiscoveredAt)
        assertEquals(fixObservedAt, stored.lastObservedAt)
    }

    @Test
    fun `a diagnostic logger that throws never prevents submission to the discovery engine`() = runTest {
        val useCaseWithThrowingLogger = SubmitCurrentLocationUseCase(
            locationProvider,
            SubmitDiscoveryObservation(FakeH3CellConverter(mapOf(paris to parisCell)), repository),
            diagnosticLogger = UseCaseThrowingLocationDiagnosticLogger(),
        )
        locationProvider.result = LocationAcquisitionResult.Success(observation(paris))

        val outcome = useCaseWithThrowingLogger()

        assertEquals(LocationTestOutcome.Success, outcome)
        assertEquals(1, repository.all().size)
    }

    @Test
    fun `a CancellationException fabricated by the diagnostic logger never prevents submission`() = runTest {
        val useCaseWithCancellingLogger = SubmitCurrentLocationUseCase(
            locationProvider,
            SubmitDiscoveryObservation(FakeH3CellConverter(mapOf(paris to parisCell)), repository),
            diagnosticLogger = UseCaseCancellingLocationDiagnosticLogger(),
        )
        locationProvider.result = LocationAcquisitionResult.Success(observation(paris))

        val outcome = useCaseWithCancellingLogger()

        assertEquals(LocationTestOutcome.Success, outcome)
        assertEquals(1, repository.all().size)
    }

    @Test
    fun `a denied permission is surfaced without touching the repository`() = runTest {
        locationProvider.result = LocationAcquisitionResult.PermissionDenied

        val outcome = useCase()

        assertEquals(LocationTestOutcome.PermissionDenied, outcome)
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `disabled location services are surfaced without touching the repository`() = runTest {
        locationProvider.result = LocationAcquisitionResult.LocationServicesDisabled

        val outcome = useCase()

        assertEquals(LocationTestOutcome.LocationServicesDisabled, outcome)
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `an unavailable location is surfaced without touching the repository`() = runTest {
        locationProvider.result = LocationAcquisitionResult.LocationUnavailable

        val outcome = useCase()

        assertEquals(LocationTestOutcome.LocationUnavailable, outcome)
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `an invalid coordinate can never be represented as a successful acquisition`() {
        // Coordinate's own validation is the boundary that guarantees invalid data can never
        // reach persistence: a Success result physically cannot wrap it.
        assertThrows(IllegalArgumentException::class.java) {
            LocationAcquisitionResult.Success(observation(Coordinate(latitude = 200.0, longitude = 0.0)))
        }
    }

    @Test
    fun `an unexpected error from the location provider is reported and does not throw`() = runTest {
        locationProvider.shouldThrow = true

        val outcome = useCase()

        assertEquals(LocationTestOutcome.SubmissionError, outcome)
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `an unexpected error from the discovery engine is reported and does not throw`() = runTest {
        locationProvider.result = LocationAcquisitionResult.Success(observation(paris))
        repository.shouldThrowOnUpsert = true

        val outcome = useCase()

        assertEquals(LocationTestOutcome.SubmissionError, outcome)
    }
}

private class UseCaseThrowingLocationDiagnosticLogger : LocationDiagnosticLogger {
    override fun log(observation: LocationObservation) {
        error("simulated diagnostic logger failure")
    }
}

private class UseCaseCancellingLocationDiagnosticLogger : LocationDiagnosticLogger {
    override fun log(observation: LocationObservation) {
        throw CancellationException("fabricated by a misbehaving synchronous logger, not real cancellation")
    }
}

private class FakeLocationProvider(var result: LocationAcquisitionResult) : LocationProvider {
    var shouldThrow = false

    override suspend fun getCurrentLocation(): LocationAcquisitionResult {
        if (shouldThrow) error("simulated provider failure")
        return result
    }
}

private class FakeH3CellConverter(private val mapping: Map<Coordinate, CanonicalCell>) : H3CellConverter {
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
    var shouldThrowOnUpsert = false

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        storage[cell to trustStatus]

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        if (shouldThrowOnUpsert) error("simulated persistence failure")
        storage[discoveredCell.cell to discoveredCell.trustStatus] = discoveredCell
    }

    override fun observeAll(): Flow<List<DiscoveredCell>> = error("not expected to be called in this test")

    fun all(): List<DiscoveredCell> = storage.values.toList()
}
