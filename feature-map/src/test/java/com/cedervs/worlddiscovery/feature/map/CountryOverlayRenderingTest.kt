package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.ClassifyDiscoveredCellsByGeographicAreaComponents
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaProvenance
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.cedervs.worlddiscovery.core.discovery.GeographicMultiPolygon
import com.cedervs.worlddiscovery.core.discovery.GeographicPolygon
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import com.cedervs.worlddiscovery.core.discovery.components
import com.cedervs.worlddiscovery.core.discovery.computeGeographicBounds
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Polygon

class CountryOverlayRenderingTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    // Index order: 0=mainland, 1=corsica, 2=guiana -- deliberately arbitrary/synthetic (the real
    // artifact's own order is different again -- see GeographicAreaComponentTest in
    // core-discovery-engine); nothing here depends on any particular ordering.
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

    private val mainlandComponent = franceLikeArea.components()[0]
    private val corsicaComponent = franceLikeArea.components()[1]
    private val guianaComponent = franceLikeArea.components()[2]

    @Test
    fun `countryOverlayFeatureCollection is empty when no component is visited`() {
        val collection = countryOverlayFeatureCollection(visitedComponents = emptyList())

        assertTrue(collection.features()!!.isEmpty())
    }

    @Test
    fun `countryOverlayFeatureCollection carries exactly one feature per visited component, never the unvisited ones`() {
        val collection = countryOverlayFeatureCollection(visitedComponents = listOf(mainlandComponent))

        assertEquals(1, collection.features()!!.size)
    }

    @Test
    fun `countryOverlayFeatureCollection carries all three when all three components are visited`() {
        val collection = countryOverlayFeatureCollection(
            visitedComponents = listOf(mainlandComponent, corsicaComponent, guianaComponent),
        )

        assertEquals(3, collection.features()!!.size)
    }

    @Test
    fun `each component feature carries the area's stable id, for click-query matching`() {
        val features = franceLikeArea.components().map { it.toCountryOverlayFeature() }

        assertTrue(features.all { it.getStringProperty(COUNTRY_OVERLAY_AREA_ID_PROPERTY) == "country:FR" })
    }

    @Test
    fun `each component feature carries its own distinct componentIndex`() {
        val features = franceLikeArea.components().map { it.toCountryOverlayFeature() }

        val indices = features.map { it.getNumberProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY).toInt() }
        assertEquals(listOf(0, 1, 2), indices)
    }

    @Test
    fun `a component feature's geometry is a single Polygon, never a MultiPolygon covering other components`() {
        val mainlandFeature = mainlandComponent.toCountryOverlayFeature()

        assertTrue(mainlandFeature.geometry() is Polygon)
    }

    @Test
    fun `each rendered component ring is closed -- first vertex repeated at the end`() {
        val features = franceLikeArea.components().map { it.toCountryOverlayFeature() }

        for (feature in features) {
            val polygon = feature.geometry() as Polygon
            val outerRing = polygon.coordinates().first()
            assertEquals(
                "ring must be closed for valid GeoJSON polygon rendering",
                outerRing.first(),
                outerRing.last(),
            )
        }
    }

    // ==========================================================================================
    // PHASE F/G3 -- mainland's rendering polygon can be overridden independently of classification.
    // ==========================================================================================

    private val syntheticOsmLikePolygon = GeographicPolygon(listOf(ring(-4.5 to 42.5, 7.5 to 42.5, 7.5 to 50.5, -4.5 to 50.5)))

    @Test
    fun `when a mainland rendering override is supplied, mainland's feature geometry uses it instead of the classification polygon`() {
        val collection = countryOverlayFeatureCollection(
            visitedComponents = listOf(mainlandComponent),
            mainlandRenderingPolygon = syntheticOsmLikePolygon,
        )

        val renderedRing = (collection.features()!!.single().geometry() as Polygon).coordinates().first()
        // The synthetic override's own first ring point, exactly as constructed above.
        assertEquals(-4.5, renderedRing.first().longitude(), 1e-9)
        assertEquals(42.5, renderedRing.first().latitude(), 1e-9)
        // 4 distinct corners + 1 closing point = 5, never the classification square's own vertex count.
        assertEquals(5, renderedRing.size)
    }

    @Test
    fun `with no override supplied, mainland renders its own classification polygon exactly as before`() {
        val collection = countryOverlayFeatureCollection(visitedComponents = listOf(mainlandComponent))

        val renderedRing = (collection.features()!!.single().geometry() as Polygon).coordinates().first()
        val classificationFirstPoint = mainlandComponent.polygon.outerRing.first()
        assertEquals(classificationFirstPoint.longitude, renderedRing.first().longitude(), 1e-9)
        assertEquals(classificationFirstPoint.latitude, renderedRing.first().latitude(), 1e-9)
    }

    @Test
    fun `a mainland rendering override never changes which components are considered visited`() {
        // Same visited set, with vs without an override -- the override can only ever change shape,
        // never which components appear at all (that remains entirely classification-derived).
        val withoutOverride = countryOverlayFeatureCollection(listOf(mainlandComponent, corsicaComponent, guianaComponent))
        val withOverride = countryOverlayFeatureCollection(
            listOf(mainlandComponent, corsicaComponent, guianaComponent),
            mainlandRenderingPolygon = syntheticOsmLikePolygon,
        )

        val withoutIndices = withoutOverride.features()!!.map { it.getNumberProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY).toInt() }.toSet()
        val withIndices = withOverride.features()!!.map { it.getNumberProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY).toInt() }.toSet()
        assertEquals(setOf(0, 1, 2), withoutIndices)
        assertEquals(withoutIndices, withIndices)
    }

    @Test
    fun `a mainland rendering override does not affect Corsica or French Guiana's own rendered geometry`() {
        val withoutOverride = countryOverlayFeatureCollection(listOf(corsicaComponent, guianaComponent))
        val withOverride = countryOverlayFeatureCollection(
            listOf(corsicaComponent, guianaComponent),
            mainlandRenderingPolygon = syntheticOsmLikePolygon,
        )

        val geometriesWithout = withoutOverride.features()!!.map { (it.geometry() as Polygon).coordinates() }
        val geometriesWith = withOverride.features()!!.map { (it.geometry() as Polygon).coordinates() }
        assertEquals(geometriesWithout, geometriesWith)
    }

    @Test
    fun `toCountryOverlayFeature with an explicit override on a non-mainland component still tags the real componentIndex`() {
        // Exercises toCountryOverlayFeature directly (not just via countryOverlayFeatureCollection's
        // own mainland-only gating) -- the override parameter itself never touches the tagged
        // properties, only geometry; click resolution depends only on those properties.
        val feature = corsicaComponent.toCountryOverlayFeature(syntheticOsmLikePolygon)

        assertEquals(1, feature.getNumberProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY).toInt())
        assertEquals("country:FR", feature.getStringProperty(COUNTRY_OVERLAY_AREA_ID_PROPERTY))
    }

    @Test
    fun `isCountryOverlayInteractive is true below the fade-out end zoom`() {
        assertTrue(isCountryOverlayInteractive(COUNTRY_OVERLAY_FADE_OUT_END_ZOOM - 0.01))
    }

    @Test
    fun `isCountryOverlayInteractive is false at or beyond the fade-out end zoom`() {
        assertFalse(isCountryOverlayInteractive(COUNTRY_OVERLAY_FADE_OUT_END_ZOOM))
        assertFalse(isCountryOverlayInteractive(COUNTRY_OVERLAY_FADE_OUT_END_ZOOM + 1.0))
    }

    // ==========================================================================================
    // End-to-end: real discovery presence (via ClassifyDiscoveredCellsByGeographicAreaComponents)
    // -> filtered to visited -> rendered feature set. Proves the actual product rule: highlighting
    // follows component-level presence, never "any discovery anywhere in France colors everything".
    // ==========================================================================================

    private val parisCell = CanonicalCell(h3Index = "paris", resolution = 12)
    private val ajaccioCell = CanonicalCell(h3Index = "ajaccio", resolution = 12)
    private val cayenneCell = CanonicalCell(h3Index = "cayenne", resolution = 12)
    private val parisCenter = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val ajaccioCenter = Coordinate(latitude = 41.9192, longitude = 8.7386)
    private val cayenneCenter = Coordinate(latitude = 4.9333, longitude = -52.3260)

    private fun renderedComponentIndicesFor(cells: List<DiscoveredCell>): Set<Int> {
        val converter = FakeRenderingCellConverter(
            mapOf(parisCell to parisCenter, ajaccioCell to ajaccioCenter, cayenneCell to cayenneCenter),
        )
        val statuses = ClassifyDiscoveredCellsByGeographicAreaComponents(converter)(cells, franceLikeArea)
        val visitedComponents = statuses.filter { it.visited }.map { it.component }
        val collection = countryOverlayFeatureCollection(visitedComponents)
        return collection.features()!!
            .map { it.getNumberProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY).toInt() }
            .toSet()
    }

    private fun discoveredCell(cell: CanonicalCell) = DiscoveredCell(
        cell = cell,
        trustStatus = TrustStatus.NON_CERTIFIED,
        firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
        lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    @Test
    fun `no discoveries renders no components`() {
        assertEquals(emptySet<Int>(), renderedComponentIndicesFor(emptyList()))
    }

    @Test
    fun `metropolitan-only presence renders exactly the mainland component`() {
        assertEquals(setOf(0), renderedComponentIndicesFor(listOf(discoveredCell(parisCell))))
    }

    @Test
    fun `Corsica-only presence renders exactly the Corsica component`() {
        assertEquals(setOf(1), renderedComponentIndicesFor(listOf(discoveredCell(ajaccioCell))))
    }

    @Test
    fun `French-Guiana-only presence renders exactly the French Guiana component`() {
        assertEquals(setOf(2), renderedComponentIndicesFor(listOf(discoveredCell(cayenneCell))))
    }

    @Test
    fun `metropolitan plus Corsica presence renders exactly those two components, not French Guiana`() {
        val cells = listOf(discoveredCell(parisCell), discoveredCell(ajaccioCell))
        assertEquals(setOf(0, 1), renderedComponentIndicesFor(cells))
    }

    @Test
    fun `presence in all three components renders all three`() {
        val cells = listOf(discoveredCell(parisCell), discoveredCell(ajaccioCell), discoveredCell(cayenneCell))
        assertEquals(setOf(0, 1, 2), renderedComponentIndicesFor(cells))
    }
}

private class FakeRenderingCellConverter(private val centers: Map<CanonicalCell, Coordinate>) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell = error("not expected to be called in this test")
    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> = error("not expected to be called in this test")
    override fun cellCenter(cell: CanonicalCell): Coordinate = centers[cell] ?: error("No fake center configured for $cell")
    override fun isValidCell(cell: CanonicalCell): Boolean = error("not expected to be called in this test")
}
