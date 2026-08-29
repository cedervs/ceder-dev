package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant

/**
 * The local record that a canonical H3 cell has been discovered, at a given [trustStatus].
 *
 * Normal and Certified are strictly separate datasets (docs/architecture.md §1.3,
 * certified-mode.md §1): a cell can exist independently as NON_CERTIFIED and as CERTIFIED at
 * the same time, as two separate [DiscoveredCell] records that are never merged into one
 * another — see [DiscoveredCellMerger]. `(cell, trustStatus)` is therefore this record's
 * identity, mirroring the conceptual `(h3_index, mode)` uniqueness in architecture.md §8.
 */
data class DiscoveredCell(
    val cell: CanonicalCell,
    val trustStatus: TrustStatus,
    val firstDiscoveredAt: Instant,
    val lastObservedAt: Instant,
    val provenance: Provenance,
    val engineVersion: Int,
    val h3Resolution: Int,
)
