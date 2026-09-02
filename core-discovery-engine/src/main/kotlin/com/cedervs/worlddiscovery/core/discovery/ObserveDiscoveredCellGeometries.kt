package com.cedervs.worlddiscovery.core.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Map feature's read-side entry point — mirrors [SubmitDiscoveryObservation] on the write
 * side. Combines [DiscoveredCellRepository]'s reactive read with [H3CellConverter]'s boundary
 * conversion so callers (e.g. `feature-map`) receive ready-to-render geometry without needing
 * any H3 knowledge of their own, and without a second place doing H3 conversion. Read-only: never
 * modifies discovery history.
 *
 * Every current write path (`SubmitDiscoveryObservation`, used by every foreground/background/
 * one-shot submission — see docs/architecture.md's single-pipeline principle) derives
 * `CanonicalCell` from the real H3 library, which cannot itself produce a syntactically invalid
 * index. No code path today can persist a `discovered_cells` row whose `h3Index` fails H3's own
 * validity rules. [H3CellConverter.isValidCell] is still cheap, proportionate insurance against
 * that changing later (or against external tampering with the local database): a cell that fails
 * it is skipped rather than passed to [H3CellConverter.cellBoundary] at all — this class never
 * catches an exception itself, so any genuinely unexpected failure from [H3CellConverter]
 * propagates normally instead of being silently absorbed.
 */
class ObserveDiscoveredCellGeometries(
    private val repository: DiscoveredCellRepository,
    private val cellConverter: H3CellConverter,
) {
    operator fun invoke(): Flow<List<DiscoveredCellGeometry>> =
        repository.observeAll().map { cells ->
            cells.mapNotNull { cell ->
                if (!cellConverter.isValidCell(cell.cell)) return@mapNotNull null
                DiscoveredCellGeometry(cell, cellConverter.cellBoundary(cell.cell))
            }
        }
}
