package com.cedervs.worlddiscovery.core.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Map feature's single read-side entry point for this round — subscribes to
 * [DiscoveredCellRepository.observeAll] **once** and derives [MapReadState.geometries],
 * [MapReadState.franceVisitedStatus], and [MapReadState.franceComponents] from the exact same
 * emitted [DiscoveredCell] list, so they always correspond to the same discovery snapshot and a
 * single discovery-table write never triggers more than one `SELECT *`.
 *
 * **Validate the raw cells exactly once, then feed every derivation from that one validated
 * snapshot.** [DiscoveredCellDao]/[DiscoveredCellRepository] never guarantee every stored row's
 * `h3Index` is still a genuine H3 cell address (see [H3CellConverter.isValidCell]'s own doc
 * comment) — this used to be checked only for the fine-geometry derivation
 * ([ObserveDiscoveredCellGeometries]'s own established pattern), while country classification read
 * the raw, unvalidated `cells` list directly, so a single corrupt row could make fine rendering
 * silently skip it while classification still fed it straight into
 * [H3CellConverter.cellCenter]/[PointInPolygonClassifier] — genuinely risking the *entire* Map read
 * state failing (an unhandled exception from a bad H3 index) instead of just that one cell being
 * absent from the display, as the fine-geometry path already tolerates. Filtering to [validCells]
 * once, up front, and deriving geometries *and* both classifications from that same filtered list
 * closes that gap structurally, without adding any broad catch-all exception handling anywhere — a
 * genuinely unexpected failure inside [cellConverter]/[classifyDiscoveredCellsByGeographicArea]/
 * [classifyDiscoveredCellsByGeographicAreaComponents] on an already-validated cell still propagates
 * as a real programming error, exactly as before.
 *
 * The geometry derivation mirrors [ObserveDiscoveredCellGeometries] exactly (kept as its own,
 * independently useful, independently tested class — not deleted, just not the thing this
 * particular read-side wiring subscribes through, to avoid a second subscription).
 *
 * **Country/Region/Department presence correction.** Country classification uses `geoBoundaries`
 * geometry; Region/Department classification uses OpenStreetMap geometry (see `tools/geo/README.md`)
 * — the two can genuinely disagree by a few meters near a shared border, so each level's own *raw*
 * classification result is passed through [promoteAncestorPresence] (child-to-parent only, never the
 * reverse — see that function's own doc comment) before being placed into [MapReadState]. This
 * guarantees "a visited child implies its ancestors are visited" without ever implying "a visited
 * parent implies any of its children are visited" — see [MapReadState.franceAdmin1Statuses]'s own doc
 * comment for the full rationale.
 *
 * **The same correction also applies at COMPONENT granularity, via [promoteAncestorComponentPresence]
 * — not only to the aggregate whole-Country [MapReadState.franceVisitedStatus].** Without this,
 * [franceComponents] (the per-component statuses that actually drive Country-level rendering and
 * click-navigation — see `CountryOverlayRendering.kt`/`CountryOverlayComponentNavigation.kt`) could
 * stay entirely unvisited even while a visited Region and the promoted aggregate Country status both
 * correctly read `visited == true`: France would be logically visited yet render nothing and accept
 * no clicks at all. [promoteAncestorComponentPresence] promotes each visited admin area into the ONE
 * real country component its own geometry actually falls within — never all components, and never a
 * hardcoded index — see that function's own doc comment.
 */
class ObserveMapReadState(
    private val repository: DiscoveredCellRepository,
    private val cellConverter: H3CellConverter,
    private val classifyDiscoveredCellsByGeographicArea: ClassifyDiscoveredCellsByGeographicArea,
    private val classifyDiscoveredCellsByGeographicAreaComponents: ClassifyDiscoveredCellsByGeographicAreaComponents,
    private val classifyDiscoveredCellsByGeographicAreas: ClassifyDiscoveredCellsByGeographicAreas,
    private val franceArea: GeographicArea,
    private val franceAdministrativeAreas: FranceAdministrativeAreas,
    // Structural-continuity evidence for the derived first-discovery corridor -- see
    // DiscoveredRoute.kt's own deriveRouteSegments doc comment for why H3 grid-adjacency, not just
    // time/speed, is required before two first-discovered cells are ever connected.
    private val gridTraversal: H3GridTraversal,
) {
    operator fun invoke(): Flow<MapReadState> =
        repository.observeAll().map { cells ->
            val validCells = cells.filter { cell -> cellConverter.isValidCell(cell.cell) }

            val geometries = validCells.map { cell ->
                DiscoveredCellGeometry(cell, cellConverter.cellBoundary(cell.cell))
            }
            val franceStatusRaw = classifyDiscoveredCellsByGeographicArea(validCells, franceArea)
            val franceComponentsRaw = classifyDiscoveredCellsByGeographicAreaComponents(validCells, franceArea)
            // Country -> Region -> Department: each level classified independently against the
            // exact same validCells snapshot, then corrected by promoting real child presence
            // upward (never a parent's presence downward) -- see this class's own doc comment and
            // MapReadState's for the full cross-dataset-disagreement rationale.
            val admin2Statuses = classifyDiscoveredCellsByGeographicAreas(validCells, franceAdministrativeAreas.departments)
            val admin1StatusesRaw = classifyDiscoveredCellsByGeographicAreas(validCells, franceAdministrativeAreas.regions)
            val admin1Statuses = promoteAncestorPresence(childStatuses = admin2Statuses, parentStatuses = admin1StatusesRaw)
            val franceStatus = promoteAncestorPresence(childStatuses = admin1Statuses, parentStatuses = listOf(franceStatusRaw)).single()
            // Same promotion, at COMPONENT granularity -- see this class's own doc comment for why
            // this is a second, necessary correction, not a duplicate of the aggregate one above.
            val franceComponents = promoteAncestorComponentPresence(childStatuses = admin1Statuses, componentStatuses = franceComponentsRaw)
            // Derived first-discovery corridor -- see DiscoveredRoute.kt's own doc comment (never a
            // GPS route, never canonical truth). Computed from the exact same validCells snapshot as
            // every field above, fresh on every emission, never persisted; MapReadState carries the
            // globally-derived (not yet Department-clipped) segments, since this class has no notion
            // of "current UI selection" -- feature-map clips to whichever Department is actually
            // selected.
            val routeSegments = deriveRouteSegments(validCells, cellConverter, gridTraversal)

            MapReadState(
                geometries = geometries,
                franceVisitedStatus = franceStatus,
                franceComponents = franceComponents,
                franceAdmin1Statuses = admin1Statuses,
                franceAdmin2Statuses = admin2Statuses,
                routeSegments = routeSegments,
            )
        }
}
