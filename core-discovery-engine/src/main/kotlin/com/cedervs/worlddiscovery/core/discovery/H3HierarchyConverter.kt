package com.cedervs.worlddiscovery.core.discovery

/**
 * A narrow abstraction dedicated to H3's parent/descendant hierarchy — deliberately kept separate
 * from [H3CellConverter] (coordinate<->cell conversion and boundary/center geometry) and
 * [H3GridTraversal] (grid-path finding), matching this module's existing convention of one narrow
 * interface per distinct H3 capability.
 *
 * Originally built to derive a coarser-resolution, H3-parent-based view of the canonical
 * (always resolution-12) discovered-cell data for a zoomed-out map visualization; that specific
 * visualization (parent-center point markers) was implemented, physically validated, and
 * abandoned as product-invalid — the product direction moved to an administrative-boundary-based
 * hierarchy instead (country → region → local area), which is a genuinely different concept from
 * H3's own cell hierarchy and must not be forced to reuse it. This interface is retained as a
 * clean, already-tested, generic H3 capability (real parent-cell lookup and pentagon-aware exact
 * descendant counts — see [descendantCount]'s doc comment) with no current production consumer,
 * not deleted, since a legitimate future use (e.g. H3-centroid-based clustering for a local
 * discovery-presence representation) remains plausible.
 */
interface H3HierarchyConverter {
    /**
     * The ancestor of [cell] at [parentResolution]. [parentResolution] must be less than or equal
     * to [cell]'s own resolution (H3 itself permits equal, returning [cell] unchanged); a
     * genuinely invalid `h3Index`, an out-of-range resolution, or [parentResolution] greater than
     * [cell]'s own resolution are all contract violations and must never be silently absorbed —
     * implementations let them throw (see `H3JavaHierarchyConverter`'s doc comment for the exact,
     * verified exception types).
     */
    fun parentCell(cell: CanonicalCell, parentResolution: Int): CanonicalCell

    /**
     * The exact number of resolution-[childResolution] descendants [parent] has. **Never** the
     * naive `7^(childResolution - parent.resolution)` formula — H3's aperture-7 hierarchy is not
     * uniform: 12 of the 122 base cells are pentagons, and a pentagon's descendant count is
     * genuinely smaller than a hexagon's at the same resolution delta (verified directly against
     * the real library: a resolution-0 pentagon has 11 534 406 001 resolution-12 descendants
     * versus 13 841 287 201 for an ordinary hexagon — a real, non-negligible difference). This
     * must always be the true count H3 itself reports, which is inherently pentagon-aware, never
     * a formula this codebase reimplements.
     */
    fun descendantCount(parent: CanonicalCell, childResolution: Int): Long
}
