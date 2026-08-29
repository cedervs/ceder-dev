package com.cedervs.worlddiscovery.core.discovery

/**
 * A raw geographic coordinate, valid only transiently while being converted to a
 * [CanonicalCell] — see docs/discovery-engine.md §16 / §23: raw coordinates are never the
 * persisted discovery representation.
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
