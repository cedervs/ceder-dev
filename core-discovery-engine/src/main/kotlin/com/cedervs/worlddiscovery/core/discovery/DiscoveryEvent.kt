package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant

/**
 * A single processed discovery observation: the result of converting one coordinate to its
 * canonical H3 cell, carrying enough metadata to be merged into local storage. Never carries
 * the raw coordinate — see [Coordinate] and docs/discovery-engine.md §16/§23.
 */
data class DiscoveryEvent(
    val cell: CanonicalCell,
    val timestamp: Instant,
    val provenance: Provenance,
    val trustStatus: TrustStatus,
    val engineVersion: Int,
) {
    /** Convenience accessor — always equal to [cell]'s resolution, never a second source of truth. */
    val h3Resolution: Int get() = cell.resolution
}
