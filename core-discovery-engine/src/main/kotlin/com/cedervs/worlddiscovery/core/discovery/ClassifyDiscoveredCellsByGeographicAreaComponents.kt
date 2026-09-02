package com.cedervs.worlddiscovery.core.discovery

/** Presence-only status ([ClassifyDiscoveredCellsByGeographicArea]'s own doc comment explains why
 * this is never a completion/exploration fraction) for one [GeographicAreaComponent] — the
 * component-level counterpart to [GeographicAreaVisitedStatus], kept as a genuinely separate type
 * (not reused) so a caller can never accidentally treat a component's own bounds/id as if they were
 * the whole [GeographicArea]'s. */
data class GeographicAreaComponentVisitedStatus(
    val component: GeographicAreaComponent,
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
}

/**
 * Derives per-[GeographicAreaComponent] presence, separately from [ClassifyDiscoveredCellsByGeographicArea]'s
 * whole-[GeographicArea] status — see that class's doc comment for the shared representative-position
 * caveat ([H3CellConverter.cellCenter], not a boundary intersection) and no-persistent-cache rationale,
 * both of which apply identically here.
 *
 * This exists to let an individual component "know whether it contains discoveries" (e.g. for a
 * future statistics/hierarchy view), **not** to drive this round's map navigation — navigation only
 * ever needs a component's *geometry* ([GeographicAreaComponent.bounds]), which is available
 * directly from [GeographicArea.components] with no discovery data involved at all. This class is
 * therefore currently unconsumed by any rendering/navigation code, retained the same way
 * `H3HierarchyConverter` was retained this round: small, generic, already-tested, plausibly useful
 * infrastructure, not forced into a current call site.
 *
 * Never invents an exploration percentage and never implies an entire component is explored —
 * [visited] is presence-only, exactly like [GeographicAreaVisitedStatus.visited].
 */
class ClassifyDiscoveredCellsByGeographicAreaComponents(
    private val cellConverter: H3CellConverter,
) {
    operator fun invoke(cells: List<DiscoveredCell>, area: GeographicArea): List<GeographicAreaComponentVisitedStatus> {
        val components = area.components()
        val certifiedPresent = BooleanArray(components.size)
        val nonCertifiedPresent = BooleanArray(components.size)

        for (cell in cells) {
            val center = cellConverter.cellCenter(cell.cell)
            // A cell's representative center belongs to at most one component -- components are
            // the disjoint polygons of one MultiPolygon, never overlapping -- so the first match is
            // conclusive and the rest need not be checked.
            val componentIndex = components.indexOfFirst { component ->
                PointInPolygonClassifier.contains(component.polygon, center)
            }
            if (componentIndex < 0) continue

            when (cell.trustStatus) {
                TrustStatus.CERTIFIED -> certifiedPresent[componentIndex] = true
                TrustStatus.NON_CERTIFIED -> nonCertifiedPresent[componentIndex] = true
            }
        }

        return components.mapIndexed { index, component ->
            GeographicAreaComponentVisitedStatus(
                component = component,
                visited = certifiedPresent[index] || nonCertifiedPresent[index],
                certifiedPresent = certifiedPresent[index],
                nonCertifiedPresent = nonCertifiedPresent[index],
            )
        }
    }
}
