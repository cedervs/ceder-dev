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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun observation(
        coordinate: Coordinate,
        observedAt: Instant = Instant.EPOCH,
        accuracyMeters: Float? = null,
        speedMetersPerSecond: Float? = null,
    ) = LocationObservation(
        coordinate = coordinate,
        observedAt = observedAt,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        provider = null,
    )

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
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `the observation's own observedAt reaches the discovery engine, not the processing time`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)
        val fixObservedAt = Instant.parse("2020-01-01T00:00:00Z")

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, fixObservedAt)))
        advanceUntilIdle()

        val stored = repository.all().single()
        assertEquals(fixObservedAt, stored.firstDiscoveredAt)
        assertEquals(fixObservedAt, stored.lastObservedAt)
        session.stop()
    }

    @Test
    fun `a diagnostic logger that throws never prevents submission to the discovery engine`() = runTest {
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            diagnosticLogger = SessionThrowingLocationDiagnosticLogger(),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `functional submission happens before either diagnostic runs, for every observation`() = runTest {
        val orderRecordingLogger = object : LocationDiagnosticLogger {
            var upsertCallCountWhenLogged: Int? = null
            override fun log(observation: LocationObservation) {
                upsertCallCountWhenLogged = repository.upsertCallCount
            }
        }
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            diagnosticLogger = orderRecordingLogger,
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        // By the time the diagnostic logger observed the fix, the repository upsert had already
        // happened — submission is never delayed until after the diagnostic.
        assertEquals(1, orderRecordingLogger.upsertCallCountWhenLogged)
        session.stop()
    }

    @Test
    fun `a CancellationException fabricated by the per-fix diagnostic logger never stops the collector`() = runTest {
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            diagnosticLogger = SessionCancellingLocationDiagnosticLogger(),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:25Z"))))
        advanceUntilIdle()

        // The observation that triggered the fabricated CancellationException is still submitted,
        // and every later observation still is too — the collector never stops. Contrast with
        // `a cancellation from the discovery engine is not swallowed as a generic error` below,
        // where a *real* CancellationException (from the suspend submission call) does stop it.
        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(3, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `the first observation of a session produces no transition diagnostic — there is no previous one yet`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                recording,
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertTrue(recording.logged.isEmpty())
        session.stop()
    }

    @Test
    fun `the second observation produces exactly one diagnosed transition`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                recording,
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        advanceUntilIdle()

        assertEquals(1, recording.logged.size)
        assertEquals(10_000L, recording.logged.single().deltaMillis)
        session.stop()
    }

    @Test
    fun `a third observation is only paired with the second, never with the first`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                recording,
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:25Z"))))
        advanceUntilIdle()

        assertEquals(2, recording.logged.size)
        // 1st->2nd: 10s apart; 2nd->3rd: 15s apart. Never 1st->3rd (25s).
        assertEquals(10_000L, recording.logged[0].deltaMillis)
        assertEquals(15_000L, recording.logged[1].deltaMillis)
        session.stop()
    }

    @Test
    fun `distance, accuracy and speed are carried through into the diagnosed transition`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                recording,
            ),
        )

        session.start()
        provider.emit(
            LocationAcquisitionResult.Success(
                observation(paris, Instant.parse("2026-01-01T10:00:00Z"), accuracyMeters = 5.0f, speedMetersPerSecond = 1.5f),
            ),
        )
        provider.emit(
            LocationAcquisitionResult.Success(
                observation(lyon, Instant.parse("2026-01-01T10:00:10Z"), accuracyMeters = null, speedMetersPerSecond = null),
            ),
        )
        advanceUntilIdle()

        val transition = recording.logged.single()
        assertTrue("expected a real Paris-Lyon distance, got ${transition.distanceMeters}", transition.distanceMeters!! > 380_000.0)
        assertEquals(5.0f, transition.fromAccuracyMeters)
        assertEquals(null, transition.toAccuracyMeters)
        assertEquals(1.5f, transition.fromSpeedMetersPerSecond)
        assertEquals(null, transition.toSpeedMetersPerSecond)
        session.stop()
    }

    @Test
    fun `an available H3 path is reflected in the diagnosed transition`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(mapOf((parisCell to lyonCell) to listOf(parisCell, lyonCell))),
                recording,
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        advanceUntilIdle()

        val transition = recording.logged.single()
        assertEquals(true, transition.pathComputed)
        assertEquals(2, transition.pathCellCount)
        session.stop()
    }

    @Test
    fun `an unavailable H3 path is reflected as pathComputed=false, without throwing`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(), // no configured paths -> always null
                recording,
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        advanceUntilIdle()

        val transition = recording.logged.single()
        assertEquals(false, transition.pathComputed)
        assertEquals(null, transition.pathCellCount)
        assertEquals(2, repository.all().size)
        session.stop()
    }

    @Test
    fun `a transition-diagnostic logger failure never prevents normal tracking from continuing, including later observations`() = runTest {
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                SessionThrowingTransitionDiagnosticLogger(),
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:25Z"))))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(3, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `a diagnostic H3 converter that throws never prevents submission or later observations`() = runTest {
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                SessionThrowingH3CellConverter(),
                FakeSessionH3GridTraversal(),
                NoOpTransitionDiagnosticLogger(),
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:25Z"))))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(3, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `a diagnostic H3 grid traversal that throws never prevents submission or later observations`() = runTest {
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                SessionThrowingH3GridTraversal(),
                NoOpTransitionDiagnosticLogger(),
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:25Z"))))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(3, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `a CancellationException fabricated by a synchronous diagnostic component never stops the collector`() = runTest {
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                SessionCancellingTransitionDiagnosticLogger(),
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:25Z"))))
        advanceUntilIdle()

        // Contrast with `a cancellation from the discovery engine is not swallowed as a generic
        // error` below: that CancellationException comes from a real suspend call and must stop
        // the collector; this one is fabricated inside a non-suspending diagnostic component and
        // must not.
        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(3, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `transition diagnostics never submit anything beyond the two real OBSERVED fixes`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(mapOf((parisCell to lyonCell) to listOf(parisCell, lyonCell))),
                recording,
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T10:00:10Z"))))
        advanceUntilIdle()

        // Exactly the two real fixes reached the repository, both OBSERVED — nothing RECONSTRUCTED,
        // nothing extra from the diagnostic pairing.
        assertEquals(2, repository.all().size)
        assertTrue(repository.all().all { it.provenance == com.cedervs.worlddiscovery.core.discovery.Provenance.OBSERVED })
        session.stop()
    }

    @Test
    fun `stopping and restarting the same session pairs fresh, not against the pre-stop fix`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                recording,
            ),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        advanceUntilIdle()
        session.stop()

        provider.startNewChannel()
        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T12:00:00Z"))))
        advanceUntilIdle()

        // Only one fix arrived since the restart — no previous observation to pair it with.
        assertTrue(recording.logged.isEmpty())
        session.stop()
    }

    @Test
    fun `a newly constructed session never reuses a previous session instance's last observation`() = runTest {
        val recording = SessionRecordingTransitionDiagnosticLogger()
        val firstSession = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                recording,
            ),
        )
        firstSession.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris, Instant.parse("2026-01-01T10:00:00Z"))))
        advanceUntilIdle()
        firstSession.stop()

        val secondProvider = FakeLocationUpdatesProvider()
        val secondSession = LocationTrackingSession(
            secondProvider,
            submitDiscoveryObservation,
            this,
            transitionDiagnostics = ForegroundTransitionDiagnostics(
                FakeSessionH3CellConverter(mapOf(paris to parisCell, lyon to lyonCell)),
                FakeSessionH3GridTraversal(),
                recording,
            ),
        )
        secondSession.start()
        secondProvider.emit(LocationAcquisitionResult.Success(observation(lyon, Instant.parse("2026-01-01T12:00:00Z"))))
        advanceUntilIdle()

        // The second session's own first observation must not be paired with anything from the
        // first, separate session instance.
        assertTrue(recording.logged.isEmpty())
        secondSession.stop()
    }

    @Test
    fun `two observations in different cells both reach the repository`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()

        assertEquals(2, repository.all().size)
        session.stop()
    }

    @Test
    fun `repeated observations in the same cell each reach submission but merge into one row`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
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

        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `stop cancels collection and later emissions are never submitted`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        session.stop()

        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
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
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
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

        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    @Test
    fun `a cancellation from the discovery engine is not swallowed as a generic error`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)
        repository.throwCancellationOnNextUpsert = true

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        // The job cancels itself as a result of the (mis-signalled) CancellationException instead
        // of silently continuing — a later emission is never processed.
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()

        assertTrue(repository.all().isEmpty())
        // The collector's own termination is unexpected (not a stop() call) — the generation-aware
        // finally in start() must still clear the live-position marker rather than leaving a fix
        // that's no longer actively being tracked displayed as if it were live.
        assertNull(session.currentObservation.value)
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
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
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
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        assertEquals(TrackingSessionState.Active, session.state.value)

        // Simulates a spurious retry-after-permission-grant call arriving even though the
        // automatic session was already tracking successfully — must stay a no-op.
        session.start()
        advanceUntilIdle()

        assertEquals(1, provider.subscribeCount)
        session.stop()
    }

    // ---- Live current-position UI state (currentObservation) ----

    @Test
    fun `currentObservation starts null`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        assertNull(session.currentObservation.value)
    }

    @Test
    fun `a Success publishes currentObservation`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertEquals(paris, session.currentObservation.value?.coordinate)
        session.stop()
    }

    @Test
    fun `a submission failure does not suppress the live-position marker`() = runTest {
        repository.throwOnNextUpsert = true
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertEquals(paris, session.currentObservation.value?.coordinate)
        assertEquals(TrackingSessionState.Active, session.state.value)
        session.stop()
    }

    @Test
    fun `a per-fix diagnostic-logger failure does not suppress the live-position marker`() = runTest {
        val session = LocationTrackingSession(
            provider,
            submitDiscoveryObservation,
            this,
            diagnosticLogger = SessionThrowingLocationDiagnosticLogger(),
        )

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertEquals(paris, session.currentObservation.value?.coordinate)
        session.stop()
    }

    @Test
    fun `PermissionDenied clears the live-position marker`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        provider.emit(LocationAcquisitionResult.PermissionDenied)
        provider.close()
        advanceUntilIdle()

        assertNull(session.currentObservation.value)
    }

    @Test
    fun `LocationServicesDisabled clears the live-position marker`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        provider.emit(LocationAcquisitionResult.LocationServicesDisabled)
        advanceUntilIdle()

        assertNull(session.currentObservation.value)
        session.stop()
    }

    @Test
    fun `LocationUnavailable does not affect discovery submission or session state`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        // Discovery submission and session status are untouched by this transient, UI-only signal
        // — see the staleness-window tests below for the marker's own (delayed) behavior.
        assertEquals(TrackingSessionState.Active, session.state.value)
        assertEquals(1, repository.upsertCallCount)
        session.stop()
    }

    // ---- Staleness grace window (fixed 21s deadline from the last Success receipt) ----

    @Test
    fun `LocationUnavailable within the staleness window leaves the marker visible`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        // Immediately after LocationUnavailable, still visible — not an immediate clear.
        assertEquals(paris, session.currentObservation.value?.coordinate)

        // Short of the 21s deadline from Success's receipt.
        advanceTimeBy(10_000L)
        assertEquals(paris, session.currentObservation.value?.coordinate)
        session.stop()
    }

    @Test
    fun `LocationUnavailable clears the marker once the fixed 21s deadline from the last Success passes`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        advanceTimeBy(21_000L)
        advanceUntilIdle()

        assertNull(session.currentObservation.value)
        session.stop()
    }

    @Test
    fun `repeated LocationUnavailable events do not reschedule or extend the deadline`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        // Two more LocationUnavailable events, spaced 5s apart — neither may push the deadline
        // further out than 21s from the ORIGINAL Success.
        advanceTimeBy(5_000L)
        clock.currentMillis += 5_000L
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        advanceTimeBy(5_000L)
        clock.currentMillis += 5_000L
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        // 10s elapsed so far; advancing the remaining 11s reaches the ORIGINAL 21s deadline from
        // the first Success. If a repeated event had rescheduled/extended the timer, the marker
        // would still be visible here.
        advanceTimeBy(11_000L)
        advanceUntilIdle()

        assertNull(
            "a repeated LocationUnavailable must not have pushed the deadline past the original 21s",
            session.currentObservation.value,
        )
        session.stop()
    }

    @Test
    fun `a fresh Success shortly before the deadline replaces the pending stale-clear and remains visible past the original deadline`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        advanceTimeBy(20_000L) // just before A's 21s deadline
        clock.currentMillis += 20_000L
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()

        // Past what would have been A's original deadline (21s from A) — B must still be visible.
        advanceTimeBy(5_000L)
        advanceUntilIdle()

        assertEquals(lyon, session.currentObservation.value?.coordinate)
        session.stop()
    }

    @Test
    fun `a new Success establishes its own freshness basis for any later LocationUnavailable`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        advanceTimeBy(20_000L)
        clock.currentMillis += 20_000L
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()

        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        // 21s from B (not from A, "20s ago" by this point) — must still be visible only 15s after B.
        advanceTimeBy(15_000L)
        assertEquals(lyon, session.currentObservation.value?.coordinate)

        // Now cross B's own 21s deadline.
        advanceTimeBy(6_000L)
        advanceUntilIdle()
        assertNull(session.currentObservation.value)
        session.stop()
    }

    @Test
    fun `a LocationUnavailable arriving after the deadline has already passed clears immediately`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        // Simulate 30s of real elapsed time (via the fake clock) passing with no LocationUnavailable
        // processed in between — e.g. the app was suspended/delayed — so no timer was ever running.
        clock.currentMillis += 30_000L
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        assertNull(session.currentObservation.value)
        session.stop()
    }

    /**
     * Regression test for the same-generation immediate-expiry race: an already-expired
     * `LocationUnavailable` clear and a fresh same-generation `Success` must never be able to
     * interleave such that the fresh `Success` gets wiped out by the stale decision.
     *
     * [scheduleStaleClearIfNeeded]'s immediate-expiry branch and [publishCurrentObservation] are
     * both fully synchronous (no suspension point) and are only ever invoked from this session's
     * single sequential `collect { }` loop (see [LocationTrackingSession.collectForGeneration]) —
     * by Flow's collection contract, that loop processes one emission fully to completion before
     * even looking at the next, so two same-generation events can never genuinely interleave via
     * that path regardless of lock scope. The only other actors that ever touch `positionLock` are
     * `start`/`stop` (which bump the generation, so are not "same generation" by construction) and
     * the scheduled `pendingClearJob`'s own delayed wake-up — and that wake-up is mutually
     * exclusive with this immediate-expiry branch: the branch only runs when `pendingClearJob ==
     * null`. So this exact interleaving cannot be forced live through the public API; a fabricated
     * synchronization hook big enough to force it would inject a suspension point this code
     * genuinely does not have, and would therefore test something that cannot happen in production
     * rather than the real fix. Given that, this test instead pins down the *observable contract*
     * the fix guarantees end-to-end: after an immediate-expiry clear for a stale Success A, a
     * same-generation Success B that follows is published normally and is never later clobbered by
     * any state the clear could have left dangling (a stray [pendingClearJob] or a stale
     * [lastSuccessReceiptMillis], neither of which the old split check-then-act-later shape reset
     * atomically with the clear it guarded). It would not have failed against the previous
     * implementation on its own — see the RETURN summary for why no dynamic test can — but it does
     * fail if the atomic clear ever regresses to leave dangling ownership state behind.
     */
    @Test
    fun `a same-generation Success right after an already-expired immediate clear is never later clobbered`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        // Success A is now expired -- the very next LocationUnavailable takes the immediate-expiry
        // path (no timer is ever scheduled for it).
        clock.currentMillis += 30_000L
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        assertNull(session.currentObservation.value)

        // A fresh, same-generation Success arrives immediately after. The atomic clear must not
        // have left any dangling ownership state (a stray pendingClearJob, a stale
        // lastSuccessReceiptMillis) capable of interfering with it.
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()
        assertEquals(lyon, session.currentObservation.value?.coordinate)

        // Advance well past the old 21s window relative to A to confirm nothing dangling from A's
        // clear ever fires later and disturbs B.
        advanceTimeBy(30_000L)
        advanceUntilIdle()
        assertEquals(lyon, session.currentObservation.value?.coordinate)

        session.stop()
    }

    @Test
    fun `PermissionDenied clears immediately even with a stale-clear timer pending`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        provider.emit(LocationAcquisitionResult.PermissionDenied)
        provider.close()
        advanceUntilIdle()

        assertNull(session.currentObservation.value)
    }

    @Test
    fun `LocationServicesDisabled clears immediately even with a stale-clear timer pending`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        provider.emit(LocationAcquisitionResult.LocationServicesDisabled)
        advanceUntilIdle()

        assertNull(session.currentObservation.value)
        session.stop()
    }

    @Test
    fun `stop clears immediately and invalidates any pending stale-clear timer`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        session.stop()
        assertNull(session.currentObservation.value)

        // Even past what would have been the original 21s deadline, nothing resurrects it — the
        // timer was invalidated by stop(), not merely raced against.
        advanceTimeBy(21_000L)
        advanceUntilIdle()
        assertNull(session.currentObservation.value)
    }

    @Test
    fun `an old session's pending stale-clear timer cannot affect a restarted session`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        // A stale-clear timer is now pending for generation 1, deadline 21s out.

        session.stop()
        provider.startNewChannel()
        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()
        assertEquals(lyon, session.currentObservation.value?.coordinate)

        // Advance past what would have been generation 1's original 21s deadline.
        advanceTimeBy(21_000L)
        advanceUntilIdle()

        assertEquals(lyon, session.currentObservation.value?.coordinate)
        session.stop()
    }

    @Test
    fun `unexpected collector termination clears immediately even with a stale-clear timer pending`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        repository.throwCancellationOnNextUpsert = true
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()

        // The genuine CancellationException from submission propagates and terminates the
        // collector; its finally clears the marker, superseding lyon's own just-published value.
        assertNull(session.currentObservation.value)
    }

    @Test
    fun `cancelling a pending stale-clear timer never stops the collector from processing later events`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        // Pending stale-clear timer now scheduled; the fresh Success below must cancel it without
        // affecting the collector itself — a single subscription, still receiving later events.
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        assertEquals(1, provider.subscribeCount)
        assertEquals(paris, session.currentObservation.value?.coordinate)
        session.stop()
    }

    @Test
    fun `cancelling a pending stale-clear timer never prevents later discovery submission`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()
        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()

        assertEquals(2, repository.upsertCallCount)
        assertEquals(2, repository.all().size)
        session.stop()
    }

    @Test
    fun `many repeated LocationUnavailable events still produce exactly one eventual clear at the original deadline`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()

        repeat(5) {
            provider.emit(LocationAcquisitionResult.LocationUnavailable)
            runCurrent()
        }

        assertEquals(paris, session.currentObservation.value?.coordinate)

        advanceTimeBy(21_000L)
        advanceUntilIdle()

        assertNull(session.currentObservation.value)
        session.stop()
    }

    @Test
    fun `a wildly old observedAt never alters the monotonic 21s freshness deadline`() = runTest {
        val clock = FakeMonotonicClock()
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this, monotonicClock = clock)

        session.start()
        // Location.time (observedAt) can be old, zero, or otherwise unreliable — must have zero
        // effect on the monotonic-clock-based freshness deadline. If observedAt were mistakenly
        // used, this fix (from 1970) would already look infinitely stale and clear immediately.
        provider.emit(LocationAcquisitionResult.Success(observation(paris, observedAt = Instant.EPOCH)))
        advanceUntilIdle()
        provider.emit(LocationAcquisitionResult.LocationUnavailable)
        runCurrent()

        advanceTimeBy(20_000L)
        assertEquals(paris, session.currentObservation.value?.coordinate)

        advanceTimeBy(2_000L)
        advanceUntilIdle()
        assertNull(session.currentObservation.value)
        session.stop()
    }

    @Test
    fun `stop clears the live-position marker`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        session.stop()

        assertNull(session.currentObservation.value)
    }

    @Test
    fun `a paused old collector's eventual finally cannot resurrect the marker after stop already cleared it`() = runTest {
        // Publication happens before the (paused) submission call, so by the time this collector
        // is paused, its own currentObservation write has already landed — this test exercises
        // what happens to that paused collector's LATER phases (the generation-aware finally, once
        // cancellation eventually unwinds it), not a not-yet-published write.
        val pause = CompletableDeferred<Unit>()
        repository.pauseNextUpsert = pause
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        session.stop()
        advanceUntilIdle()
        assertNull(session.currentObservation.value)

        // Release the paused collector — its cancellation-triggered unwind and generation-guarded
        // finally must still be a no-op; nothing resurrects the marker.
        pause.complete(Unit)
        advanceUntilIdle()

        assertNull(session.currentObservation.value)
    }

    @Test
    fun `restarting after stop stays null until the new session's own Success, and the old session cannot overwrite it`() = runTest {
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, this)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        session.stop()
        assertNull(session.currentObservation.value)

        provider.startNewChannel()
        session.start()
        assertNull("must stay null until the NEW session's own Success", session.currentObservation.value)

        provider.emit(LocationAcquisitionResult.Success(observation(lyon)))
        advanceUntilIdle()

        // The new session's own fix is published, and the old (by now cancelled) session's
        // eventual, generation-guarded cleanup cannot overwrite it — same mechanism as the paused
        // case above, exercised here across a genuine stop/restart instead.
        assertEquals(lyon, session.currentObservation.value?.coordinate)
        session.stop()
    }

    @Test
    fun `an upstream flow failure clears the live-position marker as the collector terminates unexpectedly`() = runTest {
        // AppContainer's real trackingScope uses a SupervisorJob specifically so a genuine,
        // uncaught failure in this session's own collector can't cancel anything else sharing that
        // scope — mirrored here (rather than passing the bare TestScope) so this test exercises
        // the same isolation production relies on, instead of the plain exception propagating out
        // through runTest itself. The handler here only swallows the *expected* simulated failure
        // for this test's own purposes — production has no equivalent handler today (a separate,
        // pre-existing gap this increment doesn't add scope to close; see the final report).
        val handler = CoroutineExceptionHandler { _, _ -> }
        val supervisedScope = CoroutineScope(coroutineContext + SupervisorJob() + handler)
        val session = LocationTrackingSession(provider, submitDiscoveryObservation, supervisedScope)

        session.start()
        provider.emit(LocationAcquisitionResult.Success(observation(paris)))
        advanceUntilIdle()
        assertEquals(paris, session.currentObservation.value?.coordinate)

        provider.closeWithError(RuntimeException("simulated upstream flow failure"))
        advanceUntilIdle()

        // Not a stop() call — the collector's own generation-aware finally is what clears this.
        assertNull(session.currentObservation.value)
    }
}

private class FakeMonotonicClock(var currentMillis: Long = 0L) : MonotonicClock {
    override fun nowMillis(): Long = currentMillis
}

private class SessionThrowingLocationDiagnosticLogger : LocationDiagnosticLogger {
    override fun log(observation: LocationObservation) {
        error("simulated diagnostic logger failure")
    }
}

private class SessionCancellingLocationDiagnosticLogger : LocationDiagnosticLogger {
    override fun log(observation: LocationObservation) {
        throw CancellationException("fabricated by a misbehaving synchronous logger, not real cancellation")
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

    /** Terminates the current channel with an upstream failure — simulates a genuine flow failure
     * upstream of the session's own collector, distinct from a submission/diagnostic failure. */
    fun closeWithError(cause: Throwable) {
        channel.close(cause)
    }

    override fun observeLocationUpdates(): Flow<LocationAcquisitionResult> {
        subscribeCount++
        return channel.receiveAsFlow()
    }
}

private class FakeSessionH3CellConverter(private val mapping: Map<Coordinate, CanonicalCell>) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        mapping[coordinate] ?: error("No fake mapping configured for $coordinate")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        error("not expected to be called in this test")

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        error("not expected to be called in this test")

    override fun isValidCell(cell: CanonicalCell): Boolean =
        error("not expected to be called in this test")
}

private class SessionThrowingTransitionDiagnosticLogger : TransitionDiagnosticLogger {
    override fun log(transition: ReconstructionTransitionDiagnostics) {
        error("simulated transition diagnostic logger failure")
    }
}

private class SessionCancellingTransitionDiagnosticLogger : TransitionDiagnosticLogger {
    override fun log(transition: ReconstructionTransitionDiagnostics) {
        throw CancellationException("fabricated by a misbehaving synchronous diagnostic component, not real cancellation")
    }
}

private class SessionThrowingH3CellConverter : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        error("simulated diagnostic H3 converter failure")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        error("not expected to be called in this test")

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        error("not expected to be called in this test")

    override fun isValidCell(cell: CanonicalCell): Boolean =
        error("not expected to be called in this test")
}

private class SessionThrowingH3GridTraversal : com.cedervs.worlddiscovery.core.discovery.H3GridTraversal {
    override fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>? =
        error("simulated diagnostic H3 traversal failure")
}

private class SessionRecordingTransitionDiagnosticLogger : TransitionDiagnosticLogger {
    private val _logged = mutableListOf<ReconstructionTransitionDiagnostics>()
    val logged: List<ReconstructionTransitionDiagnostics> get() = _logged

    override fun log(transition: ReconstructionTransitionDiagnostics) {
        _logged += transition
    }
}

private class FakeSessionH3GridTraversal(
    private val paths: Map<Pair<CanonicalCell, CanonicalCell>, List<CanonicalCell>> = emptyMap(),
) : com.cedervs.worlddiscovery.core.discovery.H3GridTraversal {
    override fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>? =
        paths[origin to destination]
}

private class FakeSessionDiscoveredCellRepository : DiscoveredCellRepository {
    private val storage = mutableMapOf<Pair<CanonicalCell, TrustStatus>, DiscoveredCell>()
    var upsertCallCount = 0
        private set
    var throwCancellationOnNextUpsert = false
    var throwOnNextUpsert = false

    /** If set, the next [upsert] call suspends here until the test completes this deferred —
     * lets a test hold a collector "mid-submission" (after currentObservation has already been
     * published for that fix, since publish happens before this call) to exercise what happens to
     * a paused/soon-to-be-cancelled collector's later phases. */
    var pauseNextUpsert: CompletableDeferred<Unit>? = null

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        storage[cell to trustStatus]

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        upsertCallCount++
        pauseNextUpsert?.let {
            pauseNextUpsert = null
            it.await()
        }
        if (throwCancellationOnNextUpsert) {
            throwCancellationOnNextUpsert = false
            throw CancellationException("simulated")
        }
        if (throwOnNextUpsert) {
            throwOnNextUpsert = false
            error("simulated persistence failure")
        }
        storage[discoveredCell.cell to discoveredCell.trustStatus] = discoveredCell
    }

    override fun observeAll(): Flow<List<DiscoveredCell>> = error("not expected to be called in this test")

    fun all(): List<DiscoveredCell> = storage.values.toList()
}
