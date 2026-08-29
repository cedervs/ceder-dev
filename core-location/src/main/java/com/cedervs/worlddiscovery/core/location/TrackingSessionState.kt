package com.cedervs.worlddiscovery.core.location

/** State of an in-app-session [LocationTrackingSession] — deliberately generic, never carries a
 * coordinate or H3 cell (nothing location-derived is safe to display/log). */
sealed interface TrackingSessionState {
    data object Idle : TrackingSessionState
    data object Active : TrackingSessionState
    data object PermissionDenied : TrackingSessionState
    data object LocationServicesDisabled : TrackingSessionState
}
