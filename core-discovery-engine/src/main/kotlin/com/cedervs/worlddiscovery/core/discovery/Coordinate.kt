package com.cedervs.worlddiscovery.core.discovery

/**
 * A raw geographic coordinate — never persisted as *discovery truth*. Legitimate uses:
 * conversion into a [CanonicalCell] (see docs/discovery-engine.md §16 / §23), live current-
 * position UI rendering (the "where am I right now" map marker, transient process-memory UI
 * state only, cleared when tracking stops — see `docs/ai-context/LOCATION_TRACKING.md`), and,
 * since the trajectory-reconstruction Phase 1 round, short-lived storage inside the local
 * trajectory buffer (`com.cedervs.worlddiscovery.core.discovery.trajectory`'s
 * `BufferedObservationRecord`) — a bounded, private, never-synchronized working set that exists
 * solely to make future trajectory reconstruction possible, explicitly distinct from
 * `discovered_cells` (canonical discovery truth) and governed by its own retention policy. None
 * of these uses ever feed a raw coordinate into `discovered_cells` or any synchronized/exported
 * data.
 */
data class Coordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Invalid latitude: $latitude (must be finite and within [-90, 90])"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Invalid longitude: $longitude (must be finite and within [-180, 180])"
        }
    }
}
