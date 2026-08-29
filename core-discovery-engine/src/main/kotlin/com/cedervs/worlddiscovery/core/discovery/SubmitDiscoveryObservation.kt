package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant

/**
 * The discovery engine's public entry point (docs/discovery-engine.md §7 "Discovery Core API"):
 *
 * ```
 * coordinate -> canonical H3 cell -> versioned DiscoveryEvent -> local discovered-cell persistence
 * ```
 *
 * This is deliberately the *only* thing a future location-tracking component needs to call.
 * It has no opinion on where [Coordinate] came from — GNSS, fused location, route
 * reconstruction, or imported evidence are all just a [Provenance] value supplied by the
 * caller. GPS acquisition, permissions, signal fusion/filtering, and background tracking are
 * explicitly out of scope for this phase and for this class.
 */
class SubmitDiscoveryObservation(
    private val cellConverter: H3CellConverter,
    private val repository: DiscoveredCellRepository,
) {
    suspend operator fun invoke(
        coordinate: Coordinate,
        timestamp: Instant,
        provenance: Provenance,
        trustStatus: TrustStatus,
    ): DiscoveredCell {
        val cell = cellConverter.toCanonicalCell(coordinate)
        val event = DiscoveryEvent(
            cell = cell,
            timestamp = timestamp,
            provenance = provenance,
            trustStatus = trustStatus,
            engineVersion = DiscoveryEngineVersion.CURRENT,
        )

        val existing = repository.find(cell, trustStatus)
        val merged = DiscoveredCellMerger.merge(existing, event)
        repository.upsert(merged)
        return merged
    }
}
