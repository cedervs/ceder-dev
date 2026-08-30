package com.cedervs.worlddiscovery.core.location

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellRepository
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies the mutual-exclusion guarantee this class exists for: foreground and background
 * tracking are never simultaneously active, because one class owns both transitions rather than
 * two independent lifecycle observers relying on ordering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppForegroundTrackingControllerTest {

    private lateinit var locationUpdatesProvider: NeverEmittingLocationUpdatesProvider
    private lateinit var foregroundSession: LocationTrackingSession
    private lateinit var consent: FakeControllerConsent
    private lateinit var backgroundRegistrar: FakeControllerRegistrar
    private lateinit var backgroundController: BackgroundLocationController
    private val fakeOwner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = error("not used by AppForegroundTrackingController")
    }

    @Before
    fun setUp() {
        locationUpdatesProvider = NeverEmittingLocationUpdatesProvider()
        consent = FakeControllerConsent()
        backgroundRegistrar = FakeControllerRegistrar()
    }

    private fun buildController(scope: CoroutineScope): AppForegroundTrackingController {
        val submitDiscoveryObservation = SubmitDiscoveryObservation(UnusedCellConverter(), UnusedCellRepository())
        foregroundSession = LocationTrackingSession(locationUpdatesProvider, submitDiscoveryObservation, scope)
        backgroundController = BackgroundLocationController(consent, backgroundRegistrar, scope)
        return AppForegroundTrackingController(foregroundSession, backgroundController)
    }

    @Test
    fun `onStart disarms background before starting foreground`() = runTest {
        val controller = buildController(this)

        controller.onStart(fakeOwner)
        advanceUntilIdle()

        assertEquals(1, backgroundRegistrar.unregisterCallCount)
        assertEquals(TrackingSessionState.Active, foregroundSession.state.value)

        // Cleanup: onStart leaves the foreground session's collector running forever (by
        // design — it only stops on the next onStop). Stop it directly rather than through
        // controller.onStop(), which would also call arm() and hang waiting on a consent value
        // this test never sends. Leaving a child coroutine alive past the test body is treated
        // as a leak by runTest.
        foregroundSession.stop()
        advanceUntilIdle()
    }

    @Test
    fun `onStop stops foreground before arming background`() = runTest {
        val controller = buildController(this)
        consent.emitNext(true)

        controller.onStop(fakeOwner)
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Idle, foregroundSession.state.value)
        assertEquals(1, backgroundRegistrar.registerCallCount)
    }

    @Test
    fun `onStop with consent disabled never registers background, foreground still stops`() = runTest {
        val controller = buildController(this)
        consent.emitNext(false)

        controller.onStop(fakeOwner)
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Idle, foregroundSession.state.value)
        assertEquals(0, backgroundRegistrar.registerCallCount)
    }

    @Test
    fun `a full foreground-background-foreground cycle never leaves both active`() = runTest {
        val controller = buildController(this)
        consent.emitNext(true)

        controller.onStart(fakeOwner)
        advanceUntilIdle()
        controller.onStop(fakeOwner)
        advanceUntilIdle()
        controller.onStart(fakeOwner)
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, foregroundSession.state.value)
        assertEquals(1, backgroundRegistrar.registerCallCount)
        assertEquals(2, backgroundRegistrar.unregisterCallCount)

        // Cleanup: the cycle ends on onStart, leaving the foreground collector running — see the
        // first test's comment for why this stops the session directly rather than through
        // controller.onStop() (whose arm() would hang: the single consent value sent above was
        // already consumed by the mid-cycle onStop()).
        foregroundSession.stop()
        advanceUntilIdle()
    }
}

private class NeverEmittingLocationUpdatesProvider : LocationUpdatesProvider {
    override fun observeLocationUpdates(): Flow<LocationAcquisitionResult> {
        val channel = Channel<LocationAcquisitionResult>()
        return channel.receiveAsFlow()
    }
}

private class UnusedCellConverter : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        error("not expected to be called in this test")
}

private class UnusedCellRepository : DiscoveredCellRepository {
    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        error("not expected to be called in this test")

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        error("not expected to be called in this test")
    }
}

private class FakeControllerConsent : BackgroundTrackingConsent {
    private val channel = Channel<Boolean>(Channel.UNLIMITED)

    fun emitNext(value: Boolean) {
        channel.trySend(value)
    }

    override val isEnabled: Flow<Boolean> = channel.receiveAsFlow()

    override suspend fun setEnabled(enabled: Boolean) = Unit
}

private class FakeControllerRegistrar : BackgroundLocationRegistrar {
    var registerCallCount = 0
        private set
    var unregisterCallCount = 0
        private set

    override fun register() {
        registerCallCount++
    }

    override fun unregister() {
        unregisterCallCount++
    }
}
