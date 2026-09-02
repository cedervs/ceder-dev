package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import kotlinx.coroutines.CancellationException

/**
 * Submits every location from one background delivery through the existing discovery pipeline —
 * a single broadcast can carry a batch of several locations, not just one (see
 * `BACKGROUND_PROVISIONAL`'s `maxUpdateDelayMillis`). Each is submitted with its own
 * [LocationObservation.observedAt] — that fix's own timestamp — never a single shared "now" for
 * the whole batch. Same [Provenance.OBSERVED] / [TrustStatus.NON_CERTIFIED] as every other
 * background/foreground submission path. Kept as its own class (rather than inline in
 * `AppContainer`, which needs a real `Context`) specifically so this logic is unit-testable with
 * fakes, matching [LocationTrackingSession]/`SubmitCurrentLocationUseCase`.
 *
 * [backgroundDiagnosticLogger] logs the whole delivered batch once, before any per-observation
 * processing — debug-only, part of the background acquisition calibration experiment (see
 * `docs/ai-context/LOCATION_TRACKING.md`), entirely separate from [diagnosticLogger]'s existing
 * per-fix quality logging. Like every diagnostic here, it can never affect whether any observation
 * in the batch is submitted — see [BackgroundLocationDiagnosticLogger]'s non-throwing contract.
 */
class SubmitBackgroundLocationObservations(
    private val submitDiscoveryObservation: SubmitDiscoveryObservation,
    private val diagnosticLogger: LocationDiagnosticLogger = NoOpLocationDiagnosticLogger(),
    private val backgroundDiagnosticLogger: BackgroundLocationDiagnosticLogger = NoOpBackgroundLocationDiagnosticLogger(),
) {
    suspend operator fun invoke(observations: List<LocationObservation>) {
        backgroundDiagnosticLogger.logDeliverySafely(observations)
        for (observation in observations) {
            // Entirely separate from the submission below, on purpose: logSafely() guarantees a
            // logging failure can never propagate — see LocationDiagnosticLogger.kt — but this
            // call is also structurally outside the try/catch that follows so there is no
            // ambiguity about a logging failure ever being mistaken for a submission failure.
            diagnosticLogger.logSafely(observation)
            try {
                submitDiscoveryObservation(
                    coordinate = observation.coordinate,
                    timestamp = observation.observedAt,
                    provenance = Provenance.OBSERVED,
                    trustStatus = TrustStatus.NON_CERTIFIED,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A single failed submission must not stop the rest of this batch.
            }
        }
    }
}
