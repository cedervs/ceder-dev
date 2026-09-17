package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaProvenance
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
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
    fun `administrativeOverlayFeatureCollection is empty when no area is visited`() {
        val collection = administrativeOverlayFeatureCollection(visitedAreas = emptyList())

        assertTrue(collection.features()!!.isEmpty())
    }

    @Test
    fun `one Feature per visited area, tagged with its own real areaId`() {
        val collection = administrativeOverlayFeatureCollection(listOf(singlePolygonRegion, multiPolygonRegion))

        val features = collection.features()!!
        assertEquals(2, features.size)
        val areaIds = features.map { it.getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY) }.toSet()
        assertEquals(setOf("admin1:FR-NAQ", "admin1:FR-BRE"), areaIds)
    }

    @Test
    fun `a multi-component area (real coastal islands) renders as ONE Feature, never decomposed per-component`() {
        val collection = administrativeOverlayFeatureCollection(listOf(multiPolygonRegion))

        val features = collection.features()!!
        assertEquals(1, features.size)
        assertEquals("admin1:FR-BRE", features.single().getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY))
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
        val collection = administrativeOverlayFeatureCollection(listOf(singlePolygonRegion))

        assertEquals(
            GeographicAreaStyleRole.ANCESTOR_CONTEXT.name,
            collection.features()!!.single().getStringProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY),
        )
    }

    @Test
    fun `a region matching the current selection is tagged SELECTED`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, singlePolygonRegion.id)

        val collection = administrativeOverlayFeatureCollection(listOf(singlePolygonRegion), selection)

        assertEquals(
            GeographicAreaStyleRole.SELECTED.name,
            collection.features()!!.single().getStringProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY),
        )
    }

    @Test
    fun `a region that is an ancestor's unrelated sibling is tagged ANCESTOR_CONTEXT, never SELECTED or DIRECT_SUBLEVEL`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, multiPolygonRegion.id)

        val collection = administrativeOverlayFeatureCollection(listOf(singlePolygonRegion, multiPolygonRegion), selection)

        val singlePolygonFeature = collection.features()!!.single { it.getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY) == singlePolygonRegion.id }
        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT.name, singlePolygonFeature.getStringProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY))
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
}
