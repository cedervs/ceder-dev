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
 */
class ObserveMapReadState(
    private val repository: DiscoveredCellRepository,
    private val cellConverter: H3CellConverter,
    private val classifyDiscoveredCellsByGeographicArea: ClassifyDiscoveredCellsByGeographicArea,
    private val classifyDiscoveredCellsByGeographicAreaComponents: ClassifyDiscoveredCellsByGeographicAreaComponents,
    private val franceArea: GeographicArea,
) {
    operator fun invoke(): Flow<MapReadState> =
        repository.observeAll().map { cells ->
            val validCells = cells.filter { cell -> cellConverter.isValidCell(cell.cell) }

            val geometries = validCells.map { cell ->
                DiscoveredCellGeometry(cell, cellConverter.cellBoundary(cell.cell))
            }
            val franceStatus = classifyDiscoveredCellsByGeographicArea(validCells, franceArea)
            val franceComponents = classifyDiscoveredCellsByGeographicAreaComponents(validCells, franceArea)

            MapReadState(
                geometries = geometries,
                franceVisitedStatus = franceStatus,
                franceComponents = franceComponents,
            )
        }
}
