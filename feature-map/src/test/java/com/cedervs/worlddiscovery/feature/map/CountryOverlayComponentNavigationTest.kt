package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaProvenance
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.cedervs.worlddiscovery.core.discovery.GeographicMultiPolygon
import com.cedervs.worlddiscovery.core.discovery.GeographicPolygon
import com.cedervs.worlddiscovery.core.discovery.components
import com.cedervs.worlddiscovery.core.discovery.computeGeographicBounds
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Feature

/**
 * Exercises `resolveClickedCountryComponent` end-to-end against a France-like 3-component fixture
 * (mainland, Corsica-like, Guiana-like — mirroring `CountryOverlayRenderingTest`'s own fixture, kept
 * synthetic/local rather than loading the real bundled reference, since this test only needs
 * *shape*, not the exact real geometry) through the exact production `toCountryOverlayFeature()`
 * tagging. `Feature` is a plain, pure-JVM-constructible type (no native MapLibre runtime needed —
 * verified directly against the real jar, same as `CountryOverlayCameraFitTest`'s `LatLngBounds`).
 */
class CountryOverlayComponentNavigationTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private val mainland = GeographicPolygon(listOf(ring(-4.0 to 42.0, 8.0 to 42.0, 8.0 to 51.0, -4.0 to 51.0)))
    private val corsica = GeographicPolygon(listOf(ring(8.5 to 41.4, 9.6 to 41.4, 9.6 to 43.0, 8.5 to 43.0)))
    private val guiana = GeographicPolygon(listOf(ring(-54.0 to 2.0, -51.0 to 2.0, -51.0 to 5.0, -54.0 to 5.0)))

    // Index order: 0=mainland, 1=corsica, 2=guiana.
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

    private val allComponents = franceLikeArea.components()
    private val currentAreaId = franceLikeArea.id
    private val interactiveZoom = 3.0 // well below COUNTRY_OVERLAY_FADE_OUT_END_ZOOM

    private fun featureFor(componentIndex: Int): Feature = allComponents[componentIndex].toCountryOverlayFeature()

    private fun featureWith(areaId: String?, componentIndex: Any?): Feature {
        val properties = JsonObject()
        if (areaId != null) properties.addProperty(COUNTRY_OVERLAY_AREA_ID_PROPERTY, areaId)
        when (componentIndex) {
            null -> Unit
            is Number -> properties.addProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY, componentIndex)
            is String -> properties.addProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY, componentIndex)
            else -> error("test helper only supports Number, String, or null for componentIndex")
        }
        return Feature.fromGeometry(featureFor(0).geometry(), properties)
    }

    @Test
    fun `no hit features resolves to no navigation`() {
        val result = resolveClickedCountryComponent(emptyList(), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `clicking metropolitan France's own feature resolves to the metropolitan component, not French Guiana`() {
        val result = resolveClickedCountryComponent(listOf(featureFor(0)), allComponents, currentAreaId, interactiveZoom)

        assertEquals(0, result?.componentIndex)
        // The concrete guarantee behind the product rule: this component's own bounds must never
        // reach into French Guiana's longitude range.
        assertTrue(result!!.bounds.southWestLongitude > -50.0)
        assertEquals(mainland, result.polygon)
    }

    @Test
    fun `clicking Corsica's own feature resolves independently of mainland and Guiana`() {
        val result = resolveClickedCountryComponent(listOf(featureFor(1)), allComponents, currentAreaId, interactiveZoom)

        assertEquals(1, result?.componentIndex)
        assertEquals(corsica, result!!.polygon)
        assertTrue(result.bounds.southWestLongitude > 8.0)
        assertTrue(result.bounds.northEastLongitude < mainland.outerRing.maxOf { it.longitude } + 5.0)
    }

    @Test
    fun `clicking French Guiana's own feature resolves independently, not pulling mainland France in`() {
        val result = resolveClickedCountryComponent(listOf(featureFor(2)), allComponents, currentAreaId, interactiveZoom)

        assertEquals(2, result?.componentIndex)
        assertEquals(guiana, result!!.polygon)
        assertTrue(result.bounds.northEastLongitude < 0.0)
    }

    @Test
    fun `each component resolves to bounds distinct from the other two`() {
        val mainlandResult = resolveClickedCountryComponent(listOf(featureFor(0)), allComponents, currentAreaId, interactiveZoom)!!
        val corsicaResult = resolveClickedCountryComponent(listOf(featureFor(1)), allComponents, currentAreaId, interactiveZoom)!!
        val guianaResult = resolveClickedCountryComponent(listOf(featureFor(2)), allComponents, currentAreaId, interactiveZoom)!!

        val allBounds = setOf(mainlandResult.bounds, corsicaResult.bounds, guianaResult.bounds)
        assertEquals("all three components must resolve to distinct bounds", 3, allBounds.size)
    }

    @Test
    fun `resolution is limited to the currently-visited component list, not the whole area`() {
        // Only the mainland component is "visited" -- Corsica's own feature must not resolve even
        // though it's geometrically valid, because it isn't in the current navigable set.
        val onlyMainlandVisited = listOf(allComponents[0])

        val result = resolveClickedCountryComponent(listOf(featureFor(1)), onlyMainlandVisited, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a faded-out overlay -- zoom at or past the fade-out end -- never resolves to a component`() {
        val result = resolveClickedCountryComponent(
            listOf(featureFor(0)),
            allComponents,
            currentAreaId,
            COUNTRY_OVERLAY_FADE_OUT_END_ZOOM,
        )

        assertNull(result)
    }

    @Test
    fun `a feature with no componentIndex property resolves to no navigation`() {
        val bareFeature = featureWith(areaId = currentAreaId, componentIndex = null)

        val result = resolveClickedCountryComponent(listOf(bareFeature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a feature with no areaId property resolves to no navigation`() {
        val feature = featureWith(areaId = null, componentIndex = 0)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a feature with a wrong areaId resolves to no navigation, even with an otherwise-valid componentIndex`() {
        val feature = featureWith(areaId = "country:OTHER", componentIndex = 0)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a stale feature -- wrong areaId, but a componentIndex that is numerically valid for the current area -- is still rejected`() {
        // Exact scenario from the review: a feature from a previous/different area render, whose
        // componentIndex happens to coincide with a real index in the current area. The areaId
        // mismatch alone must be enough to reject it.
        val staleFeature = featureWith(areaId = "country:STALE", componentIndex = 1)

        val result = resolveClickedCountryComponent(listOf(staleFeature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a non-finite componentIndex -- NaN -- resolves to no navigation`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = Double.NaN)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a non-finite componentIndex -- positive infinity -- resolves to no navigation`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = Double.POSITIVE_INFINITY)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a fractional componentIndex -- 1point5 -- is rejected outright, never truncated to 1`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = 1.5)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a negative componentIndex resolves to no navigation`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = -1)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `an out-of-range componentIndex -- larger than any real component -- resolves to no navigation`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = 99)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a valid integer componentIndex expressed as a Double still resolves correctly`() {
        // Gson typically decodes JSON numbers as Double -- 1.0, not the Int 1 -- so the strict
        // integer check must accept an integer-valued Double, not just a boxed Int.
        val feature = featureWith(areaId = currentAreaId, componentIndex = 1.0)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertEquals(1, result?.componentIndex)
    }

    // ==========================================================================================
    // Strict JSON type for componentIndex: it must be a genuine JSON number, never a JSON string
    // -- even one that "looks like" a valid number. Feature.getNumberProperty()/
    // JsonPrimitive.getAsNumber() would otherwise silently coerce a string primitive into a
    // Number, exactly the loophole this hardens against (verified directly against the real Gson
    // jar: a String-backed JsonPrimitive has isNumber() == false but getAsNumber() still succeeds).
    // ==========================================================================================

    @Test
    fun `a JSON numeric componentIndex of exactly 1 is accepted`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = 1)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertEquals(1, result?.componentIndex)
    }

    @Test
    fun `a JSON numeric componentIndex of 1point0 is accepted as mathematically exact`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = 1.0)

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertEquals(1, result?.componentIndex)
    }

    @Test
    fun `a JSON STRING componentIndex of the digit 1 is rejected, never coerced into the number 1`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = "1")

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a JSON STRING componentIndex of 1point0 is rejected`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = "1.0")

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a JSON STRING componentIndex of 01 is rejected`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = "01")

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }

    @Test
    fun `a JSON STRING componentIndex that is not even numeric-looking is rejected without crashing`() {
        val feature = featureWith(areaId = currentAreaId, componentIndex = "not-a-number")

        val result = resolveClickedCountryComponent(listOf(feature), allComponents, currentAreaId, interactiveZoom)

        assertNull(result)
    }
}
