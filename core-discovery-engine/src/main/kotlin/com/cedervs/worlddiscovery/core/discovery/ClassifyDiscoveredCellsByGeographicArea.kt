package com.cedervs.worlddiscovery.core.discovery

/**
 * Whether [area] has been **visited** — presence only, never a completion/exploration fraction.
 * `VISITED` means "at least one canonical discovered H3 cell falls inside [area] under
 * [area]'s own [GeographicArea.sourceVersion]" — nothing more. Deliberately does **not** expose
 * an "explored percentage" or any coverage-based field: eligibility, excluded/unreachable areas,
 * and precision modes are not finalized (see `docs/discovery-engine.md` §10), so no completion
 * metric can be computed honestly yet.
 *
 * [certifiedPresent]/[nonCertifiedPresent] are independent presence flags, not a derived trust
 * verdict for the whole area — **never** collapse them into "this area is Certified" just because
 * one discovered cell inside it happens to be. A caller that only wants the simple
 * visited/not-visited signal can ignore both and use [visited] alone.
 */
data class GeographicAreaVisitedStatus(
    val area: GeographicArea,
    val visited: Boolean,
    val certifiedPresent: Boolean,
    val nonCertifiedPresent: Boolean,
) {
    init {
        if (!visited) {
            require(!certifiedPresent && !nonCertifiedPresent) {
                "certifiedPresent/nonCertifiedPresent must both be false when visited is false"
            }
        }
    }

    companion object {
        /** A not-visited placeholder for the real, bundled France reference — for a caller (e.g.
         * `MapScreen`'s `collectAsState(initial = ...)`) that needs *some* valid value before the
         * first real emission arrives, without inventing a second, fake `GeographicArea`. Loads
         * the same bundled artifact [loadFranceGeographicAreaReference] does, once. */
        val franceNotVisitedPlaceholder: GeographicAreaVisitedStatus by lazy {
            GeographicAreaVisitedStatus(
                area = loadFranceGeographicAreaReference(),
                visited = false,
                certifiedPresent = false,
                nonCertifiedPresent = false,
            )
        }
    }
}

/**
 * Derives [GeographicAreaVisitedStatus] for [area] from the canonical [DiscoveredCell] list — a
 * **read-time-only** derivation, never a second discovery truth and never persisted as new
 * authoritative data. [DiscoveredCellRepository]/[DiscoveredCellDao] remain the sole discovery
 * authority; this class only reads what they already hold.
 *
 * **Representative position (this experiment's explicit, provisional rule)**: each discovered
 * cell's [H3CellConverter.cellCenter] is used as its point for the point-in-polygon test — not a
 * boundary intersection. A cell whose *center* falls just outside [area] but whose boundary
 * actually overlaps it (or vice versa) is classified by its center alone. This is a deliberate
 * simplification for this first experiment, not a hidden approximation: a future iteration may use
 * genuine boundary-intersection rules instead — see `docs/ai-context/LOCATION_TRACKING.md`-style
 * CALIBRATION REQUIRED framing; this class's behavior is exactly what it claims to be, no more.
 *
 * No persistent `(h3Index, sourceVersion) -> area` cache is used here — deliberately: at this
 * prototype's scale (a France-only country check against a handful to low-thousands of discovered
 * cells), recomputing point-in-polygon on every classification is far cheaper than the H3 boundary
 * computation [ObserveDiscoveredCellGeometries] already performs for the very same cells on every
 * render, so a cache would add real complexity to remove a cost that isn't actually a bottleneck
 * yet. If a cache is ever justified by real data volume, it must be keyed by
 * `(h3Index, area.sourceVersion)` — never by `h3Index` alone — so a geographic-reference upgrade
 * can never serve a stale classification silently.
 */
class ClassifyDiscoveredCellsByGeographicArea(
    private val cellConverter: H3CellConverter,
) {
    operator fun invoke(cells: List<DiscoveredCell>, area: GeographicArea): GeographicAreaVisitedStatus {
        var certifiedPresent = false
        var nonCertifiedPresent = false

        for (cell in cells) {
            if (certifiedPresent && nonCertifiedPresent) break // both already known true, nothing left to learn
            val center = cellConverter.cellCenter(cell.cell)
            if (!PointInPolygonClassifier.contains(area.geometry, center)) continue

            when (cell.trustStatus) {
                TrustStatus.CERTIFIED -> certifiedPresent = true
                TrustStatus.NON_CERTIFIED -> nonCertifiedPresent = true
            }
        }

        return GeographicAreaVisitedStatus(
            area = area,
            visited = certifiedPresent || nonCertifiedPresent,
            certifiedPresent = certifiedPresent,
            nonCertifiedPresent = nonCertifiedPresent,
        )
    }
}
