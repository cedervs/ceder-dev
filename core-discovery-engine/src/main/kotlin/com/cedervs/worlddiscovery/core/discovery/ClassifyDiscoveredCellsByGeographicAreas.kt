package com.cedervs.worlddiscovery.core.discovery

/**
 * Derives [GeographicAreaVisitedStatus] for every area in [areas] in a single pass over [cells] —
 * the generic, multi-area sibling of [ClassifyDiscoveredCellsByGeographicArea] (which classifies
 * exactly one area) and the whole-area counterpart of
 * [ClassifyDiscoveredCellsByGeographicAreaComponents] (which classifies the *components of one
 * area*, not independent sibling areas). This is what makes France's Region (`ADMIN_1`) and
 * Department (`ADMIN_2`) levels possible without any new classification concept: [areas] is simply
 * "all 13 loaded regions" or "all 12 loaded Nouvelle-Aquitaine departments" — nothing here knows or
 * cares that it's France, or which administrative level it's given; the exact same class serves
 * both levels (and any future country's regions/departments) unchanged.
 *
 * **[areas] must be genuinely disjoint siblings** (no cell's representative point can fall inside
 * more than one) for the single-pass `indexOfFirst` short-circuit below to be correct — true for
 * real, non-overlapping administrative subdivisions at the same level, exactly the same assumption
 * [ClassifyDiscoveredCellsByGeographicAreaComponents] already makes about one area's own components.
 * A cell whose center falls inside none of [areas] (e.g. a discovery in a region whose departments
 * aren't loaded yet, or outside France entirely) is simply skipped — never an error, matching every
 * other classifier in this file's own established "derived, never forced" philosophy.
 *
 * Shares [ClassifyDiscoveredCellsByGeographicArea]'s own representative-position caveat
 * ([H3CellConverter.cellCenter], not a boundary intersection) and no-persistent-cache rationale —
 * both apply identically here, at this app's current data volume (a France-only check against a
 * handful to low-thousands of discovered cells, now against up to ~13-25 candidate areas instead of
 * 1 — still far cheaper than the H3 boundary computation already performed for the same cells on
 * every render). A cheap [GeographicBounds.contains] check narrows candidates before the real
 * polygon test — see that function's own doc comment for why this is a scoped, safe prefilter here
 * and not yet a general spatial index; a genuinely worldwide rollout (far more, smaller candidate
 * areas) will need a real spatial index instead of this linear per-area bounds scan.
 */
class ClassifyDiscoveredCellsByGeographicAreas(
    private val cellConverter: H3CellConverter,
) {
    operator fun invoke(cells: List<DiscoveredCell>, areas: List<GeographicArea>): List<GeographicAreaVisitedStatus> {
        val certifiedPresent = BooleanArray(areas.size)
        val nonCertifiedPresent = BooleanArray(areas.size)

        for (cell in cells) {
            val center = cellConverter.cellCenter(cell.cell)
            val areaIndex = areas.indexOfFirst { area -> area.bounds.contains(center) && PointInPolygonClassifier.contains(area.geometry, center) }
            if (areaIndex < 0) continue

            when (cell.trustStatus) {
                TrustStatus.CERTIFIED -> certifiedPresent[areaIndex] = true
                TrustStatus.NON_CERTIFIED -> nonCertifiedPresent[areaIndex] = true
            }
        }

        return areas.mapIndexed { index, area ->
            GeographicAreaVisitedStatus(
                area = area,
                visited = certifiedPresent[index] || nonCertifiedPresent[index],
                certifiedPresent = certifiedPresent[index],
                nonCertifiedPresent = nonCertifiedPresent[index],
            )
        }
    }
}
