package com.cedervs.worlddiscovery.core.location

import kotlinx.coroutines.flow.Flow

/**
 * A continuous stream of foreground location results, for an in-app-session tracking flow —
 * distinct from [LocationProvider]'s single explicit acquisition, which stays untouched for the
 * existing one-shot debug path.
 *
 * The single authoritative source of permission/location-services state: implementations decide
 * up front whether the stream can start at all, and emit [LocationAcquisitionResult] accordingly.
 * Callers (see [LocationTrackingSession]) never re-check permission or service state themselves —
 * duplicating that check in two places would create two competing sources of truth. Collection
 * stops when the collecting coroutine is cancelled; there is no separate stop method.
 *
 * Contract for a terminal [LocationAcquisitionResult.PermissionDenied]: implementations must
 * close their flow afterward (no further emissions) — [LocationTrackingSession] relies on this to
 * end its own collection rather than cancelling itself from within its own collector.
 * [LocationAcquisitionResult.LocationServicesDisabled], by contrast, is non-terminal: it may be
 * emitted once as an initial signal without closing the flow, since collection should resume on
 * its own once services return (see `FusedLocationUpdatesProvider`).
 */
interface LocationUpdatesProvider {
    fun observeLocationUpdates(): Flow<LocationAcquisitionResult>
}
