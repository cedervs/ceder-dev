package com.cedervs.worlddiscovery.core.discovery

/** Converts a raw coordinate to its canonical H3 cell, and a canonical cell to its boundary
 * geometry. Kept as an interface so the H3 library choice stays swappable (docs/architecture.md
 * principle 10) and so callers can be tested against a fake without touching the real H3
 * library. */
interface H3CellConverter {
    fun toCanonicalCell(coordinate: Coordinate): CanonicalCell

    /**
     * The cell's boundary polygon as an ordered ring of vertices, derived from [cell]'s existing
     * `h3Index` — never a second identity for the cell, purely a recomputable projection of it.
     * Not closed (the first vertex is not repeated at the end); callers close the ring
     * themselves if their consumer requires it (e.g. GeoJSON).
     */
    fun cellBoundary(cell: CanonicalCell): List<Coordinate>

    /**
     * [cell]'s H3 center point — like [cellBoundary], purely a recomputable projection of the
     * existing `h3Index`, never a second identity. A resolution-12-cell-center-point-based
     * zoomed-out visualization built directly on this was implemented, physically validated, and
     * abandoned as product-invalid (see `docs/ai-context/LOCATION_TRACKING.md`/git history around
     * that increment) — but this method itself is genuinely used today: it is the representative
     * point [ClassifyDiscoveredCellsByGeographicArea]/[ClassifyDiscoveredCellsByGeographicAreaComponents]
     * test against a [GeographicArea]'s reference geometry (see those classes' doc comments for why
     * a cell's center, not a boundary intersection, is this experiment's deliberate simplification).
     */
    fun cellCenter(cell: CanonicalCell): Coordinate

    /**
     * Whether [cell]'s `h3Index` is a genuine H3 cell address — a pure predicate, guaranteed to
     * never throw regardless of how malformed `h3Index` is. Callers that only trust cells already
     * derived from [toCanonicalCell] never need this; it exists for cells reconstructed from
     * external storage (see [ObserveDiscoveredCellGeometries]), where corruption — however
     * unlikely today — should be detected explicitly before calling [cellBoundary], rather than
     * discovered by catching whatever that call happens to throw.
     */
    fun isValidCell(cell: CanonicalCell): Boolean
}
