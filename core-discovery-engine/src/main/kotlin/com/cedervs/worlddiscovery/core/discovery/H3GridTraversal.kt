package com.cedervs.worlddiscovery.core.discovery

/**
 * A narrow abstraction dedicated to H3's grid-path traversal (`gridPathCells`) — deliberately
 * kept separate from [H3CellConverter], which owns coordinate<->cell conversion and boundary
 * geometry, not grid-topology path-finding. Exists to prepare — not activate — spatial-continuity
 * reconstruction (`ForegroundReconstructionScheduler` in `core-location`); see
 * `docs/ai-context/LOCATION_TRACKING.md` for what remains CALIBRATION REQUIRED before any
 * reconstruction becomes active.
 */
interface H3GridTraversal {
    /**
     * The path of H3 cells from [origin] to [destination], inclusive of both endpoints, ordered
     * origin -> destination, with each cell a genuine H3 grid-neighbor of the previous one.
     * `origin == destination` returns a single-element list containing just that cell.
     *
     * A **contract violation** — either cell's `h3Index` not being a genuine H3 address, or the
     * two cells not having the same real H3 resolution (including a [CanonicalCell.resolution]
     * that doesn't match what's actually encoded in its own `h3Index`) — is never silently turned
     * into `null`; implementations must throw (see `H3JavaGridTraversal`'s doc comment for the
     * exact exception types, verified against the real library).
     *
     * Returns `null` — never throws — only for two individually valid, mutually comparable cells,
     * when H3 itself cannot compute a path: a pentagon distortion cell encountered along the way,
     * an excessive/problematic grid distance, or any other genuinely expected H3-internal
     * traversal failure. Callers must treat `null` as "no path available", never as an error to
     * propagate into the discovery pipeline.
     */
    fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>?
}
