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
 *
 * [bearingDegrees]/[bearingAccuracyDegrees]/[speedAccuracyMetersPerSecond]/[elapsedRealtimeNanos]/
 * [isMockLocation] were added for the trajectory-reconstruction Phase 1 round — pure *capture*,
 * not a new filter (see the class doc comment above; **capturing a signal is not the same as
 * starting to act on it** — nothing here changes which fixes reach `SubmitDiscoveryObservation`
 * or how). All five default so every existing construction call site keeps compiling unchanged.
 * See `docs/ai-context/LOCATION_TRACKING.md` for why each was added and what it is for.
 */
data class LocationObservation(
    val coordinate: Coordinate,
    val observedAt: Instant,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val provider: String?,
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    /** `Location.getElapsedRealtimeNanos()` — always populated by a real Fused Location Provider
     * fix (API 17+); defaults to `0L` only for a hand-constructed `Location` that never set it
     * (e.g. an older/incomplete test fixture) — `0L` must be treated as "not genuinely available",
     * never as a real elapsed-time reading of zero. See
     * `com.cedervs.worlddiscovery.core.discovery.trajectory.buildObservationDedupKey`'s doc
     * comment for the one place this distinction already matters. */
    val elapsedRealtimeNanos: Long = 0L,
    val isMockLocation: Boolean = false,
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
@Suppress("DEPRECATION") // isFromMockProvider() is deprecated in favor of isMock() (API 31+) --
// this app's minSdk is 26, so the older API remains the only one available on every supported
// device; it stays fully functional (not merely "still compiles") on every API level this app
// targets, including 31+.
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
        bearingDegrees = if (hasBearing()) bearing else null,
        bearingAccuracyDegrees = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
        speedAccuracyMetersPerSecond = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        isMockLocation = isFromMockProvider(),
    )
}
