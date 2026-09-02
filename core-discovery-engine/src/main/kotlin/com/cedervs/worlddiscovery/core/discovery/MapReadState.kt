package com.cedervs.worlddiscovery.core.discovery

/**
 * One consistent read-side snapshot for the Map feature: [geometries] (fine, canonical
 * resolution-12 cells with their boundaries — close-zoom rendering), [franceVisitedStatus] (this
 * round's Country-level VISITED overlay — see [ClassifyDiscoveredCellsByGeographicArea]), and
 * [franceComponents] (per-component presence — see [ClassifyDiscoveredCellsByGeographicAreaComponents];
 * currently unconsumed by rendering, which only needs component *geometry*, not presence — kept
 * here for future statistics/hierarchy use). All three are derived from the exact **same**,
 * already-H3-validated [DiscoveredCell] list emitted by [DiscoveredCellRepository.observeAll] — see
 * [ObserveMapReadState] for both the single-subscription and single-validation rationale. A single
 * Room subscription feeding multiple derived read-side views, not one subscription per view (the
 * same principle already applied once in this codebase's history and reapplied here for a
 * genuinely new set of consumers, not a reintroduction of the previously-rejected aggregate-point
 * visualization).
 */
data class MapReadState(
    val geometries: List<DiscoveredCellGeometry>,
    val franceVisitedStatus: GeographicAreaVisitedStatus,
    val franceComponents: List<GeographicAreaComponentVisitedStatus>,
)
