package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaProvenance
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaVisitedStatus
import com.cedervs.worlddiscovery.core.discovery.GeographicMultiPolygon
import com.cedervs.worlddiscovery.core.discovery.GeographicPolygon
import com.cedervs.worlddiscovery.core.discovery.computeGeographicBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdministrativeOverlayRenderingTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private fun testArea(id: String, type: GeographicAreaType, parentId: String, polygons: List<GeographicPolygon>): GeographicArea {
        val geometry = GeographicMultiPolygon(polygons)
        return GeographicArea(
            id = id,
            type = type,
            displayName = id,
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.EXTERNAL_REFERENCE_DATASET,
            parentId = parentId,
        )
    }

    private fun visited(area: GeographicArea): GeographicAreaVisitedStatus =
        GeographicAreaVisitedStatus(area, visited = true, certifiedPresent = false, nonCertifiedPresent = true)

    private fun unvisited(area: GeographicArea): GeographicAreaVisitedStatus =
        GeographicAreaVisitedStatus(area, visited = false, certifiedPresent = false, nonCertifiedPresent = false)

    private val singlePolygonRegion = testArea(
        "admin1:FR-NAQ",
        GeographicAreaType.ADMIN_1,
        "country:FR",
        listOf(GeographicPolygon(listOf(ring(0.0 to 44.0, 1.0 to 44.0, 1.0 to 45.0, 0.0 to 45.0)))),
    )

    // A region with a real second component (e.g. Bretagne's own coastal islands) -- proves the
    // overlay renders the area's FULL MultiPolygon as one Feature, never decomposed per-component
    // (see AdministrativeOverlayRendering.kt's own doc comment for why that's a deliberate scoping
    // decision, distinct from CountryOverlayRendering's per-component Features).
    private val multiPolygonRegion = testArea(
        "admin1:FR-BRE",
        GeographicAreaType.ADMIN_1,
        "country:FR",
        listOf(
            GeographicPolygon(listOf(ring(-4.0 to 47.0, -3.0 to 47.0, -3.0 to 48.0, -4.0 to 48.0))),
            GeographicPolygon(listOf(ring(-5.0 to 48.2, -4.8 to 48.2, -4.8 to 48.4, -5.0 to 48.4))),
        ),
    )

    @Test
    fun `administrativeOverlayFeatureCollection is empty when there are no candidate areas`() {
        val collection = administrativeOverlayFeatureCollection(areaStatuses = emptyList())

        assertTrue(collection.features()!!.isEmpty())
    }

    @Test
    fun `one Feature per candidate area, tagged with its own real areaId`() {
        val collection = administrativeOverlayFeatureCollection(listOf(visited(singlePolygonRegion), visited(multiPolygonRegion)))

        val features = collection.features()!!
        assertEquals(2, features.size)
        val areaIds = features.map { it.getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY) }.toSet()
        assertEquals(setOf("admin1:FR-NAQ", "admin1:FR-BRE"), areaIds)
    }

    @Test
    fun `a multi-component area (real coastal islands) renders as ONE Feature, never decomposed per-component`() {
        val collection = administrativeOverlayFeatureCollection(listOf(visited(multiPolygonRegion)))

        val features = collection.features()!!
        assertEquals(1, features.size)
        assertEquals("admin1:FR-BRE", features.single().getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY))
    }

    // ==========================================================================================
    // FH-1 runtime hierarchy fix: administrative existence and discovery presence are different
    // concepts -- an unvisited candidate is still rendered (tagged so it can be styled neutrally,
    // never as if it were visited), never silently dropped the way this file used to filter before
    // this fix.
    // ==========================================================================================

    @Test
    fun `an unvisited candidate area is still rendered as a Feature -- existence does not require visited state`() {
        val collection = administrativeOverlayFeatureCollection(listOf(unvisited(singlePolygonRegion)))

        val features = collection.features()!!
        assertEquals(1, features.size)
        assertEquals("admin1:FR-NAQ", features.single().getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY))
    }

    @Test
    fun `every rendered Feature carries an explicit visited property matching its own real status`() {
        val collection = administrativeOverlayFeatureCollection(listOf(visited(singlePolygonRegion), unvisited(multiPolygonRegion)))

        val features = collection.features()!!
        val visitedFeature = features.single { it.getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY) == singlePolygonRegion.id }
        val unvisitedFeature = features.single { it.getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY) == multiPolygonRegion.id }
        assertEquals("true", visitedFeature.getStringProperty(ADMIN_OVERLAY_VISITED_PROPERTY))
        assertEquals("false", unvisitedFeature.getStringProperty(ADMIN_OVERLAY_VISITED_PROPERTY))
    }

    @Test
    fun `a mix of visited and unvisited candidates for the same region set are both present and independently distinguishable`() {
        val collection = administrativeOverlayFeatureCollection(listOf(visited(singlePolygonRegion), unvisited(multiPolygonRegion)))

        assertEquals(2, collection.features()!!.size)
    }

    @Test
    fun `Region and Department overlays are gated by independent, non-overlapping-purpose zoom bands`() {
        // Not a claim the bands never numerically overlap (they deliberately do, for a smooth
        // drill-down handoff -- see AdministrativeOverlayRendering.kt's own doc comment) -- only
        // that each level's own interactivity is a genuinely separate, correctly ordered decision:
        // Region fades out no later than Department does.
        assertTrue(ADMIN1_OVERLAY_FADE_OUT_END_ZOOM <= ADMIN2_OVERLAY_FADE_OUT_END_ZOOM)
        assertTrue(isAdmin1OverlayInteractive(ADMIN1_OVERLAY_FADE_OUT_START_ZOOM))
        assertTrue(!isAdmin1OverlayInteractive(ADMIN1_OVERLAY_FADE_OUT_END_ZOOM))
        assertTrue(isAdmin2OverlayInteractive(ADMIN2_OVERLAY_FADE_OUT_START_ZOOM))
        assertTrue(!isAdmin2OverlayInteractive(ADMIN2_OVERLAY_FADE_OUT_END_ZOOM))
    }

    // ==========================================================================================
    // Physical-validation correction round: color is no longer a fixed per-level constant -- every
    // rendered Feature now also carries a GEOGRAPHIC_STYLE_ROLE_PROPERTY tag, computed from the
    // current selection (see GeographicHierarchyStyling.kt / GeographicHierarchyStylingTest.kt for
    // the exhaustive role-resolution coverage). This file only proves the tag is actually present and
    // threaded through administrativeOverlayFeatureCollection -- not the role-resolution logic itself.
    // ==========================================================================================

    @Test
    fun `with no selection, a visited Region is tagged ANCESTOR_CONTEXT -- only Country is the next selectable level from the World view`() {
        // Country (depth 1) is the implicit World selection's own direct sublevel; a Region (depth 2)
        // is two steps away and falls into the same de-emphasized tier as any other non-adjacent
        // level -- see GeographicHierarchyStylingTest's own equivalent case for the exhaustive proof.
        val collection = administrativeOverlayFeatureCollection(listOf(visited(singlePolygonRegion)))

        assertEquals(
            GeographicAreaStyleRole.ANCESTOR_CONTEXT.name,
            collection.features()!!.single().getStringProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY),
        )
    }

    @Test
    fun `a region matching the current selection is tagged SELECTED`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, singlePolygonRegion.id)

        val collection = administrativeOverlayFeatureCollection(listOf(visited(singlePolygonRegion)), selection)

        assertEquals(
            GeographicAreaStyleRole.SELECTED.name,
            collection.features()!!.single().getStringProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY),
        )
    }

    @Test
    fun `a region that is an ancestor's unrelated sibling is tagged ANCESTOR_CONTEXT, never SELECTED or DIRECT_SUBLEVEL`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, multiPolygonRegion.id)

        val collection = administrativeOverlayFeatureCollection(listOf(visited(singlePolygonRegion), visited(multiPolygonRegion)), selection)

        val singlePolygonFeature = collection.features()!!.single { it.getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY) == singlePolygonRegion.id }
        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT.name, singlePolygonFeature.getStringProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY))
    }

    @Test
    fun `styleRole and visited are independent tags -- an unvisited SELECTED area is still tagged SELECTED, just with visited=false`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, singlePolygonRegion.id)

        val collection = administrativeOverlayFeatureCollection(listOf(unvisited(singlePolygonRegion)), selection)

        val feature = collection.features()!!.single()
        assertEquals(GeographicAreaStyleRole.SELECTED.name, feature.getStringProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY))
        assertEquals("false", feature.getStringProperty(ADMIN_OVERLAY_VISITED_PROPERTY))
    }

    // ==========================================================================================
    // Physical-validation FOLLOW-UP round: effectiveAdmin2MinZoom -- Departments must render
    // immediately once a Region (or its own Department) is the active selection, without waiting for
    // the user to manually zoom past the generic ADMIN2_OVERLAY_MIN_ZOOM threshold; the World/Country
    // views must NOT gain any new Department clutter from this fix.
    // ==========================================================================================

    @Test
    fun `Admin1 selected -- effective Department min zoom drops to the Region's own render floor, below the generic threshold`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, "admin1:FR-NAQ")

        val effectiveMinZoom = effectiveAdmin2MinZoom(selection)

        assertEquals(ADMIN1_OVERLAY_MIN_ZOOM, effectiveMinZoom)
        assertTrue(
            "the whole point of this fix -- the effective floor must be strictly lower than the generic Department threshold",
            effectiveMinZoom < ADMIN2_OVERLAY_MIN_ZOOM,
        )
    }

    @Test
    fun `Admin2 (Department) selected -- effective min zoom stays lowered, so the floor doesn't snap back up one level deeper`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_2, "admin2:FR-87")

        assertEquals(ADMIN1_OVERLAY_MIN_ZOOM, effectiveAdmin2MinZoom(selection))
    }

    @Test
    fun `Country selected -- Departments do NOT become globally visible just because France itself is selected`() {
        val selection = GeographicFocusSelection(GeographicAreaType.COUNTRY, "country:FR")

        assertEquals(
            "Country selection must not lower the Department floor -- only an actual Region/Department selection does",
            ADMIN2_OVERLAY_MIN_ZOOM,
            effectiveAdmin2MinZoom(selection),
        )
    }

    @Test
    fun `no selection -- World view -- the generic Department threshold applies unchanged, no new clutter`() {
        assertEquals(ADMIN2_OVERLAY_MIN_ZOOM, effectiveAdmin2MinZoom(GeographicFocusSelection.NONE))
    }

    // ==========================================================================================
    // FH-1 runtime hierarchy fix -- administrativeRenderCandidates: the PARENT-SCOPED render
    // candidate decision DiscoveryMapView delegates to. Covers the physically-observed defect's own
    // required regression list: Regions always exposed regardless of visited state (A), a focused
    // Region exposes all its own children regardless of visited state (B), and Departments never
    // render all-96-at-once when only one Region is focused (H).
    // ==========================================================================================

    private val regionA = testArea("admin1:FR-A", GeographicAreaType.ADMIN_1, "country:FR", listOf(GeographicPolygon(listOf(ring(0.0 to 44.0, 1.0 to 44.0, 1.0 to 45.0, 0.0 to 45.0)))))
    private val regionB = testArea("admin1:FR-B", GeographicAreaType.ADMIN_1, "country:FR", listOf(GeographicPolygon(listOf(ring(2.0 to 44.0, 3.0 to 44.0, 3.0 to 45.0, 2.0 to 45.0)))))
    private val visitedDeptInA = testArea("admin2:FR-A1", GeographicAreaType.ADMIN_2, regionA.id, listOf(GeographicPolygon(listOf(ring(0.1 to 44.1, 0.2 to 44.1, 0.2 to 44.2, 0.1 to 44.2)))))
    private val unvisitedDeptInA = testArea("admin2:FR-A2", GeographicAreaType.ADMIN_2, regionA.id, listOf(GeographicPolygon(listOf(ring(0.3 to 44.1, 0.4 to 44.1, 0.4 to 44.2, 0.3 to 44.2)))))
    private val deptInB = testArea("admin2:FR-B1", GeographicAreaType.ADMIN_2, regionB.id, listOf(GeographicPolygon(listOf(ring(2.1 to 44.1, 2.2 to 44.1, 2.2 to 44.2, 2.1 to 44.2)))))

    @Test
    fun `A -- France (no Region focused) -- every loaded Region is a render candidate regardless of visited state`() {
        val candidates = administrativeRenderCandidates(
            admin1Statuses = listOf(visited(regionA), unvisited(regionB)),
            admin2Statuses = listOf(visited(visitedDeptInA), unvisited(unvisitedDeptInA), unvisited(deptInB)),
            focusedAdmin1Id = null,
        )

        assertEquals(setOf(regionA.id, regionB.id), candidates.regions.map { it.area.id }.toSet())
    }

    @Test
    fun `A -- no Region focused -- Department candidates are empty -- France selection does not expose Departments`() {
        val candidates = administrativeRenderCandidates(
            admin1Statuses = listOf(visited(regionA)),
            admin2Statuses = listOf(visited(visitedDeptInA), unvisited(unvisitedDeptInA)),
            focusedAdmin1Id = null,
        )

        assertTrue(candidates.departments.isEmpty())
    }

    @Test
    fun `B -- Region A focused -- exposes ALL of Region A's own Department children, visited and unvisited alike`() {
        val candidates = administrativeRenderCandidates(
            admin1Statuses = listOf(visited(regionA), unvisited(regionB)),
            admin2Statuses = listOf(visited(visitedDeptInA), unvisited(unvisitedDeptInA), unvisited(deptInB)),
            focusedAdmin1Id = regionA.id,
        )

        assertEquals(setOf(visitedDeptInA.id, unvisitedDeptInA.id), candidates.departments.map { it.area.id }.toSet())
    }

    @Test
    fun `B -- Region A focused -- still exposes all 13-equivalent Regions unconditionally, same as no focus`() {
        val candidates = administrativeRenderCandidates(
            admin1Statuses = listOf(visited(regionA), unvisited(regionB)),
            admin2Statuses = emptyList(),
            focusedAdmin1Id = regionA.id,
        )

        assertEquals(setOf(regionA.id, regionB.id), candidates.regions.map { it.area.id }.toSet())
    }

    @Test
    fun `C -- an unvisited Department candidate keeps its own real visited=false status, never silently promoted`() {
        val candidates = administrativeRenderCandidates(
            admin1Statuses = listOf(visited(regionA)),
            admin2Statuses = listOf(unvisited(unvisitedDeptInA)),
            focusedAdmin1Id = regionA.id,
        )

        assertEquals(false, candidates.departments.single().visited)
    }

    @Test
    fun `H -- Region A focused -- Region B's own Department never leaks into the render candidate set -- never all Departments at once`() {
        val candidates = administrativeRenderCandidates(
            admin1Statuses = listOf(visited(regionA), visited(regionB)),
            admin2Statuses = listOf(visited(visitedDeptInA), unvisited(unvisitedDeptInA), visited(deptInB)),
            focusedAdmin1Id = regionA.id,
        )

        assertTrue("Region B's own Department must never appear while Region A is focused", candidates.departments.none { it.area.id == deptInB.id })
        assertEquals(2, candidates.departments.size)
    }
}
