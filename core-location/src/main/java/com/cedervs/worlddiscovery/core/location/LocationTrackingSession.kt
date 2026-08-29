package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Controls one in-app-session location tracking session: collects [LocationUpdatesProvider]'s
 * stream while active and forwards every successful observation to the existing
 * [SubmitDiscoveryObservation] entry point, unchanged (same [Provenance.OBSERVED] /
 * [TrustStatus.NON_CERTIFIED] as the one-shot debug path — nothing here can produce a Certified
 * record, per certified-mode.md §1).
 *
 * Deliberately lifecycle-agnostic: [start]/[stop] are the only surface, driven by whatever caller
 * owns the lifecycle decision (this phase: application-foreground, see
 * `AppForegroundTrackingController`). Not tied to any Android component, screen, or ViewModel, so
 * it's directly testable with a fake [LocationUpdatesProvider] and a plain [CoroutineScope].
 *
 * Permission/location-services state is never re-checked here — [LocationUpdatesProvider] is the
 * single authoritative source for that; this class only reacts to what it emits. A terminal
 * [LocationAcquisitionResult.PermissionDenied] relies on the provider closing its own flow
 * afterward (see [LocationUpdatesProvider]'s contract) rather than this class cancelling its own
 * collecting job from within itself.
 */
class LocationTrackingSession(
    private val locationUpdatesProvider: LocationUpdatesProvider,
    private val submitDiscoveryObservation: SubmitDiscoveryObservation,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TrackingSessionState>(TrackingSessionState.Idle)
    val state: StateFlow<TrackingSessionState> = _state.asStateFlow()

    private var job: Job? = null

    /** No-op if a session is already active. */
    fun start() {
        if (job?.isActive == true) return

        _state.value = TrackingSessionState.Active
        job = scope.launch {
            locationUpdatesProvider.observeLocationUpdates().collect { result ->
                when (result) {
                    is LocationAcquisitionResult.Success -> {
                        _state.value = TrackingSessionState.Active
                        submitObservationSafely(result.coordinate)
                    }

                    LocationAcquisitionResult.PermissionDenied -> {
                        _state.value = TrackingSessionState.PermissionDenied
                    }

                    LocationAcquisitionResult.LocationServicesDisabled -> {
                        _state.value = TrackingSessionState.LocationServicesDisabled
                    }

                    // Transient provider hiccups: a single dropped fix must not stop an
                    // otherwise active session (discovery-engine.md leaves movement/sampling
                    // thresholds open — this is not the place to add filtering logic).
                    LocationAcquisitionResult.LocationUnavailable,
                    is LocationAcquisitionResult.Error,
                    -> Unit
                }
            }
        }
    }

    /** Cancels the active collection, if any, and resets to [TrackingSessionState.Idle]. */
    fun stop() {
        job?.cancel()
        job = null
        _state.value = TrackingSessionState.Idle
    }

    private suspend fun submitObservationSafely(coordinate: Coordinate) {
        try {
            submitDiscoveryObservation(
                coordinate = coordinate,
                timestamp = Instant.now(),
                provenance = Provenance.OBSERVED,
                trustStatus = TrustStatus.NON_CERTIFIED,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A single failed submission must not stop an otherwise active tracking session.
        }
    }
}
