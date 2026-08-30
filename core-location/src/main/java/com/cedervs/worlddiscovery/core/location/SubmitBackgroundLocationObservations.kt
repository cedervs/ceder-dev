package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import kotlinx.coroutines.CancellationException

/**
 * Submits every location from one background delivery through the existing discovery pipeline —
 * a single broadcast can carry a batch of several locations, not just one (see
 * `BACKGROUND_PROVISIONAL`'s `maxUpdateDelayMillis`). Each is submitted with its own
 * [BackgroundLocationObservation.observedAt] — that fix's own timestamp — never a single shared
 * "now" for the whole batch. Same [Provenance.OBSERVED] / [TrustStatus.NON_CERTIFIED] as every
 * other background/foreground submission path. Kept as its own class (rather than inline in
 * `AppContainer`, which needs a real `Context`) specifically so this logic is unit-testable with
 * fakes, matching [LocationTrackingSession]/`SubmitCurrentLocationUseCase`.
 */
class SubmitBackgroundLocationObservations(
    private val submitDiscoveryObservation: SubmitDiscoveryObservation,
) {
    suspend operator fun invoke(observations: List<BackgroundLocationObservation>) {
        for (observation in observations) {
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
