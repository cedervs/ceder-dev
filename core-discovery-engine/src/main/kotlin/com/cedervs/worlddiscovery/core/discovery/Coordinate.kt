package com.cedervs.worlddiscovery.core.discovery

/**
 * A raw geographic coordinate, held only transiently — never persisted as discovery truth. Two
 * legitimate transient uses exist: conversion into a [CanonicalCell] (see
 * docs/discovery-engine.md §16 / §23), and live current-position UI rendering (the "where am I
 * right now" map marker, entirely separate from "what have I discovered" — see
 * `docs/ai-context/LOCATION_TRACKING.md`). Neither use ever persists a raw coordinate; the map
 * marker in particular is transient process-memory UI state only, cleared when tracking stops.
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
