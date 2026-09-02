package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import kotlinx.coroutines.CancellationException

/**
 * Wires a single foreground [LocationProvider] acquisition to the existing discovery engine
 * ([SubmitDiscoveryObservation]) — the only place this phase connects real (or fake, in tests)
 * device location to H3 conversion and local persistence. No discovery logic is duplicated
 * here; this class only orchestrates.
 *
 * A location obtained this way is live, on-device, unvalidated data — per certified-mode.md §1
 * ("le téléphone est une source de données non fiable, le serveur est l'autorité finale") it is
 * recorded as [TrustStatus.NON_CERTIFIED] with [Provenance.OBSERVED]. No server validation
 * exists yet, so nothing here can legitimately produce a CERTIFIED record — using CERTIFIED for
 * a purely local submission would contradict the already-documented Certified model. Submits with
 * the fix's own [LocationObservation.observedAt] (from `Location.time`), never `Instant.now()` at
 * processing time.
 */
class SubmitCurrentLocationUseCase(
    private val locationProvider: LocationProvider,
    private val submitDiscoveryObservation: SubmitDiscoveryObservation,
    private val diagnosticLogger: LocationDiagnosticLogger = NoOpLocationDiagnosticLogger(),
) {
    suspend operator fun invoke(): LocationTestOutcome {
        val acquisition = try {
            locationProvider.getCurrentLocation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LocationTestOutcome.SubmissionError
        }

        return when (acquisition) {
            is LocationAcquisitionResult.Success -> {
                // Entirely separate from the submission below, on purpose: logSafely() guarantees
                // a logging failure can never propagate — see LocationDiagnosticLogger.kt — but
                // this call is also structurally outside the try/catch that follows so there is
                // no ambiguity about a logging failure ever being mistaken for a submission one.
                diagnosticLogger.logSafely(acquisition.observation)
                try {
                    submitDiscoveryObservation(
                        coordinate = acquisition.observation.coordinate,
                        timestamp = acquisition.observation.observedAt,
                        provenance = Provenance.OBSERVED,
                        trustStatus = TrustStatus.NON_CERTIFIED,
                    )
                    LocationTestOutcome.Success
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LocationTestOutcome.SubmissionError
                }
            }

            LocationAcquisitionResult.PermissionDenied -> LocationTestOutcome.PermissionDenied
            LocationAcquisitionResult.LocationServicesDisabled -> LocationTestOutcome.LocationServicesDisabled
            LocationAcquisitionResult.LocationUnavailable -> LocationTestOutcome.LocationUnavailable
            is LocationAcquisitionResult.Error -> LocationTestOutcome.SubmissionError
        }
    }
}
