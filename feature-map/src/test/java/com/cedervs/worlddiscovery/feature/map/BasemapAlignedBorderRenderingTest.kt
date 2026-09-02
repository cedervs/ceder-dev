package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaProvenance
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.cedervs.worlddiscovery.core.discovery.GeographicMultiPolygon
import com.cedervs.worlddiscovery.core.discovery.GeographicPolygon
import com.cedervs.worlddiscovery.core.discovery.components
import com.cedervs.worlddiscovery.core.discovery.computeGeographicBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property

/**
 * `Expression` is a plain, pure-JVM-constructible type (no native MapLibre runtime needed —
 * verified directly against the real jar during this prototype's own implementation, the same way
 * `LatLngBounds`/`CameraPosition`/`org.maplibre.geojson.*` were verified in earlier rounds), and its
 * `equals()` compares structurally — confirmed empirically before relying on it here — so these
 * tests compare real `Expression` objects directly rather than fragile string matching.
 */
class BasemapAlignedBorderRenderingTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private val mainland = GeographicPolygon(listOf(ring(-4.0 to 42.0, 8.0 to 42.0, 8.0 to 51.0, -4.0 to 51.0)))
    private val corsica = GeographicPolygon(listOf(ring(8.5 to 41.4, 9.6 to 41.4, 9.6 to 43.0, 8.5 to 43.0)))
    private val guiana = GeographicPolygon(listOf(ring(-54.0 to 2.0, -51.0 to 2.0, -51.0 to 5.0, -54.0 to 5.0)))

    private val franceLikeArea = run {
        val geometry = GeographicMultiPolygon(listOf(mainland, corsica, guiana))
        GeographicArea(
            id = "country:FR",
            type = GeographicAreaType.COUNTRY,
            displayName = "France",
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.EXTERNAL_REFERENCE_DATASET,
        )
    }

    private val allComponents = franceLikeArea.components() // 0=mainland, 1=corsica, 2=guiana

    @Test
    fun `source id and source-layer constants match the real basemap vector data, as verified against the live style`() {
        assertEquals("openmaptiles", BASEMAP_VECTOR_SOURCE_ID)
        assertEquals("boundary", BASEMAP_BOUNDARY_SOURCE_LAYER)
    }

    @Test
    fun `mainland France component index constant is 0, matching the Phase A1 generator's own order contract`() {
        assertEquals(0, MAINLAND_FRANCE_COMPONENT_INDEX_PROTOTYPE)
    }

    @Test
    fun `isMainlandFranceVisited is true only when component 0 is present`() {
        assertTrue(isMainlandFranceVisited(listOf(allComponents[0])))
        assertTrue(isMainlandFranceVisited(listOf(allComponents[0], allComponents[1])))
    }

    @Test
    fun `isMainlandFranceVisited is false when mainland is absent, even if other components are visited`() {
        assertFalse(isMainlandFranceVisited(listOf(allComponents[1])))
        assertFalse(isMainlandFranceVisited(listOf(allComponents[2])))
        assertFalse(isMainlandFranceVisited(listOf(allComponents[1], allComponents[2])))
    }

    @Test
    fun `isMainlandFranceVisited is false for an empty visited list -- no accidental default-on boundary`() {
        assertFalse(isMainlandFranceVisited(emptyList()))
    }

    @Test
    fun `basemapAlignedBorderVisibility maps true to VISIBLE and false to NONE`() {
        assertEquals(Property.VISIBLE, basemapAlignedBorderVisibility(true))
        assertEquals(Property.NONE, basemapAlignedBorderVisibility(false))
    }

    @Test
    fun `the France admin-2 border filter matches admin_level 2 and either adm0 side equal to FRA`() {
        // "FRA", not "FR" -- confirmed against a real decoded OpenFreeMap tile (see
        // BasemapAlignedBorderRendering.kt's doc comment on MAINLAND_FRANCE_ADM0_CODE): OpenMapTiles'
        // adm0_l/adm0_r fields use ISO 3166-1 alpha-3 codes, not alpha-2.
        val expected = Expression.all(
            Expression.eq(Expression.get("admin_level"), 2),
            Expression.any(
                Expression.eq(Expression.get("adm0_l"), "FRA"),
                Expression.eq(Expression.get("adm0_r"), "FRA"),
            ),
            Expression.neq(Expression.get("maritime"), 1),
            Expression.neq(Expression.get("disputed"), 1),
            Expression.not(Expression.has("claimed_by")),
        )

        assertEquals(expected, mainlandFranceAdmin2BorderFilter())
    }

    @Test
    fun `the France admin-2 border filter excludes maritime and disputed segments, matching the basemap's own boundary_2 rules`() {
        // Not a full expression-evaluation test (no native runtime to evaluate against real
        // features here) -- structurally confirms the exclusions are present, matching what was
        // directly verified against the real Liberty style's own boundary_2 layer during the
        // investigation this prototype follows up on.
        val filterString = mainlandFranceAdmin2BorderFilter().toString()

        assertTrue(filterString.contains("maritime"))
        assertTrue(filterString.contains("disputed"))
        assertTrue(filterString.contains("claimed_by"))
        assertTrue(filterString.contains("admin_level"))
    }

    @Test
    fun `the mainland outline suppression filter excludes exactly componentIndex 0, nothing else`() {
        val expected = Expression.neq(Expression.get(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY), MAINLAND_FRANCE_COMPONENT_INDEX_PROTOTYPE)

        assertEquals(expected, mainlandFranceOutlineSuppressionFilter())
    }
}
