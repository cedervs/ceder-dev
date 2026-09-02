package com.cedervs.worlddiscovery.core.discovery

/**
 * A discovered cell paired with its already-derived boundary polygon, ready for map rendering.
 * [boundary] is never a second identity for the cell — [cell] (and its `h3Index`) remains the
 * single source of truth; the boundary is purely a recomputable projection of it, produced by
 * [H3CellConverter.cellBoundary]. See [ObserveDiscoveredCellGeometries].
 */
data class DiscoveredCellGeometry(
    val cell: DiscoveredCell,
    val boundary: List<Coordinate>,
)
