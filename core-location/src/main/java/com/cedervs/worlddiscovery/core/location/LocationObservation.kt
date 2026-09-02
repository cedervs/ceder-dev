package com.cedervs.worlddiscovery.core.location

import android.location.Location
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant

/**
 * One location fix, carrying the quality/diagnostic metadata Android exposes on [Location]
 * alongside the [Coordinate] the discovery engine actually consumes. Introduced so a future
 * suspect discovered cell is diagnosable after the fact (see
 * `docs/ai-context/LOCATION_TRACKING.md`) instead of only guessable, as happened with an isolated
 * cell found ~93 m off a real track. Carrying this metadata is not, by itself, an acceptance/
 * rejection filter — every structurally valid observation still reaches
 * [com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation] exactly as before.
 * Filtering and its thresholds remain CALIBRATION REQUIRED, not implemented here.
 */
data class LocationObservation(
    val coordinate: Coordinate,
    val observedAt: Instant,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val provider: String?,
)

/**
 * The single place `android.location.Location` is converted into this app's own
 * [LocationObservation] — used by the one-shot, foreground-continuous, and background paths
 * alike ([FusedLocationProvider], [FusedLocationUpdatesProvider],
 * [extractBackgroundLocationObservations]), so all three capture the same metadata the same way
 * instead of each doing their own partial `Coordinate(location.latitude, location.longitude)`
 * conversion. Returns `null` only for a structurally invalid coordinate ([Coordinate]'s own
 * validation) — no other rejection logic exists here.
 */
fun Location.toLocationObservation(): LocationObservation? {
    val coordinate = runCatching { Coordinate(latitude, longitude) }.getOrNull() ?: return null
    return LocationObservation(
        coordinate = coordinate,
        // Never replaced by Instant.now(): an old or zero Location.time must stay observable
        // exactly as reported, not silently "corrected" — see docs/ai-context/LOCATION_TRACKING.md.
        observedAt = Instant.ofEpochMilli(time),
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMetersPerSecond = if (hasSpeed()) speed else null,
        provider = provider,
    )
}
