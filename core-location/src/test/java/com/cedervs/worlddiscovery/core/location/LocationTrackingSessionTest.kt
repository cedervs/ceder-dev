package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellRepository
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationTrackingSessionTest {

    private lateinit var repository: FakeSessionDiscoveredCellRepository
    private lateinit var provider: FakeLocationUpdatesProvider
    private lateinit var submitDiscoveryObservation: SubmitDiscoveryObservation

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
    private val lyon = Coordinate(latitude = 45.7640, longitude = 4.8357)
    private val lyonCell = CanonicalCell(h3Index = "8c1f2a4362d1fff", resolution = 12)

    @Before
    fun setUp() {
        repository = FakeSessionDiscoveredCellRepository()
        provider = FakeLocationUpdatesProvider()
        val converter = FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell))
        submitDiscoveryObservation = SubmitDiscoveryObservation(converter, repository)
    }

    @Test
    fun `starting with permission granted moves to Active and submits a successful observation`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(paris))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `two observations in different cells both reach the repository`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(paris))
        provider.emit(LocationAcquisitionResult.Success(lyon))
        advanceUntilIdle()

        assertEquals(2, repository.all().size)
        session.stop()
    }

    @Test
    fun `repeated observations in the same cell each reach submission but merge into one row`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(paris))
        provider.emit(LocationAcquisitionResult.Success(paris))
        provider.emit(LocationAcquisitionResult.Success(paris))
        advanceUntilIdle()

        assertEquals(3, repository.upsertCallCount)
        assertEquals(1, repository.all().size)
        session.stop()
    }

    @Test
    fun `permission denied at start moves to PermissionDenied and never submits`() = runTest {
        provider.emit(LocationAcquisitionResult.PermissionDenied)
        provider.close()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        advanceUntilIdle()

        assertEquals(TrackingSessionState.PermissionDenied, session.state.value)
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `disabled location services at start does not stop the session and later resumes on its own`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.LocationServicesDisabled)
        advanceUntilIdle()
        assertEquals(TrackingSessionState.LocationServicesDisabled, session.state.value)

        provider.emit(LocationAcquisitionResult.Success(paris))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `stop cancels collection and later emissions are never submitted`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(paris))
        advanceUntilIdle()
        session.stop()

        provider.emit(LocationAcquisitionResult.Success(lyon))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Idle, session.state.value)
        assertEquals(1, repository.upsertCallCount)
    }

    @Test
    fun `calling start twice while already active does not subscribe a second time`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        session.start()
        advanceUntilIdle()

        assertEquals(1, provider.subscribeCount)
        session.stop()
    }

    @Test
    fun `permission denied mid-session stops further submissions`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(paris))
        provider.emit(LocationAcquisitionResult.PermissionDenied)
        provider.close()
        advanceUntilIdle()

        assertEquals(TrackingSessionState.PermissionDenied, session.state.value)
        assertEquals(1, repository.upsertCallCount)
    }

    @Test
    fun `a transient error mid-session does not stop the session`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Error("transient"))
        advanceUntilIdle()
        assertEquals(TrackingSessionState.Active, session.state.value)

        provider.emit(LocationAcquisitionResult.Success(paris))
        advanceUntilIdle()

        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `a cancellation from the discovery engine is not swallowed as a generic error`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)
        repository.throwCancellationOnNextUpsert = true

        session.start()
        provider.emit(LocationAcquisitionResult.Success(paris))
        advanceUntilIdle()

        // The job cancels itself as a result of the (mis-signalled) CancellationException instead
        // of silently continuing — a later emission is never processed.
        provider.emit(LocationAcquisitionResult.Success(lyon))
        advanceUntilIdle()

        assertTrue(repository.all().isEmpty())
        session.stop()
    }

    @Test
    fun `start after a permission-denied termination resumes collection once permission is granted`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        // First attempt (e.g. the automatic session's initial ON_START, before the user has
        // granted permission): terminates as PermissionDenied, same as today.
        provider.emit(LocationAcquisitionResult.PermissionDenied)
        provider.close()
        session.start()
        advanceUntilIdle()
        assertEquals(TrackingSessionState.PermissionDenied, session.state.value)
        assertEquals(1, provider.subscribeCount)

        // Permission is granted later, in the same foreground period — this is exactly what
        // AppContainer.retryLocationTrackingAfterPermissionGranted() does: call start() again.
        // The job from the first attempt already completed (the provider closed its flow), so
        // this is a genuine new attempt, not blocked by the idempotent no-op guard.
        provider.startNewChannel()
        session.start()
        provider.emit(LocationAcquisitionResult.Success(paris))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(2, provider.subscribeCount)
        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `retrying start while already active does not create a second collector`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(paris))
        advanceUntilIdle()
        assertEquals(TrackingSessionState.Active, session.state.value)

        // Simulates a spurious retry-after-permission-grant call arriving even though the
        // automatic session was already tracking successfully — must stay a no-op.
        session.start()
        advanceUntilIdle()

        assertEquals(1, provider.subscribeCount)
        session.stop()
    }
}

private class FakeLocationUpdatesProvider : LocationUpdatesProvider {
    private var channel = Channel<LocationAcquisitionResult>(Channel.UNLIMITED)
    var subscribeCount = 0
        private set

    fun emit(result: LocationAcquisitionResult) {
        channel.trySend(result)
    }

    fun close() {
        channel.close()
    }

    /** Simulates permission/services state being checked fresh on a brand new subscription — the
     * real [FusedLocationUpdatesProvider] re-runs its checks every time it's collected. Only the
     * permission-grant-recovery test needs this; every other test uses a single subscription. */
    fun startNewChannel() {
        channel = Channel(Channel.UNLIMITED)
    }

    override fun observeLocationUpdates(): Flow<LocationAcquisitionResult> {
        subscribeCount++
        return channel.receiveAsFlow()
    }
}

private class FakeSessionH3CellConverter(private val mapping: Map<Coordinate, CanonicalCell>) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        mapping[coordinate] ?: error("No fake mapping configured for $coordinate")
}

private class FakeSessionDiscoveredCellRepository : DiscoveredCellRepository {
    private val storage = mutableMapOf<Pair<CanonicalCell, TrustStatus>, DiscoveredCell>()
    var upsertCallCount = 0
        private set
    var throwCancellationOnNextUpsert = false

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        storage[cell to trustStatus]

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        upsertCallCount++
        if (throwCancellationOnNextUpsert) {
            throwCancellationOnNextUpsert = false
            throw CancellationException("simulated")
        }
        storage[discoveredCell.cell to discoveredCell.trustStatus] = discoveredCell
    }

    fun all(): List<DiscoveredCell> = storage.values.toList()
}
