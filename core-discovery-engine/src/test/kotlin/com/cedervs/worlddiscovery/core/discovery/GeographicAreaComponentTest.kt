package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeographicAreaComponentTest {

    @Test
    fun `the real France reference decomposes into exactly its 3 real components, in geometry order`() {
        val france = loadFranceGeographicAreaReference()

        val components = france.components()

        assertEquals(3, components.size)
        assertEquals(listOf(0, 1, 2), components.map { it.componentIndex })
        assertTrue(components.all { it.area === france })
        assertEquals(france.geometry.polygons, components.map { it.polygon })
    }

    @Test
    fun `each component's bounds cover only its own polygon, never the whole area`() {
        val france = loadFranceGeographicAreaReference()

        val components = france.components()

        // The whole area's bounds span both mainland Europe and French Guiana (see
        // GeographicAreaReferenceTest); no single component's own bounds should.
        for (component in components) {
            assertNotEquals(france.bounds, component.bounds)
        }
    }

    @Test
    fun `the metropolitan-France-like component's bounds do not extend into French Guiana's longitude range`() {
        val france = loadFranceGeographicAreaReference()
        val components = france.components()

        // Whichever component is the mainland/Corsica one (far east of French Guiana's -51..-54
        // range), its own bounds must not reach that far west -- this is the concrete guarantee
        // behind "tapping metropolitan France must not pull French Guiana into the camera fit".
        val mainlandLikeComponent = components.maxBy { it.bounds.northEastLongitude }
        assertTrue(mainlandLikeComponent.bounds.southWestLongitude > -50.0)
    }

    @Test
    fun `the French-Guiana-like component's bounds do not extend into mainland France's longitude range`() {
        val france = loadFranceGeographicAreaReference()
        val components = france.components()

        val guianaLikeComponent = components.minBy { it.bounds.southWestLongitude }
        assertTrue(guianaLikeComponent.bounds.northEastLongitude < 0.0)
    }

    @Test
    fun `for this pinned reference version, the real artifact's components decompose in a confirmed, specific order -- component 0 is mainland France, component 1 is Corsica, component 2 is French Guiana`() {
        // Confirmed by direct inspection of the checked-in artifact (see tools/geo/README.md) for
        // THIS pinned sourceVersion only. Unlike the prior Natural-Earth-based artifact (where this
        // order -- 0=Guiana, 1=mainland, 2=Corsica -- was an accident of upstream feature
        // iteration), this order is now a DELIBERATE contract the generator itself constructs (see
        // GenerateFranceReference.kt's own doc comment: mainland/Corsica are told apart by area,
        // French Guiana is a separately-fetched, separately-filtered source) -- still never relied
        // upon by production navigation logic, which only ever works off the componentIndex value
        // tagged onto a rendered feature at render time (see feature-map's
        // CountryOverlayRendering.kt/CountryOverlayComponentNavigation.kt), never an assumed
        // position. If the artifact is ever regenerated, only this test would need re-verification.
        val france = loadFranceGeographicAreaReference()
        val components = france.components()

        val mainland = components[0]
        val corsica = components[1]
        val guiana = components[2]

        assertTrue(
            "component 0 (mainland France) must span roughly -5 to 8.5 degrees longitude",
            mainland.bounds.southWestLongitude > -10.0 && mainland.bounds.northEastLongitude < 8.5,
        )
        assertTrue("component 0 (mainland France) must reach well north", mainland.bounds.northEastLatitude > 45.0)

        assertTrue(
            "component 1 (Corsica) must sit further east than the mainland's own east edge",
            corsica.bounds.southWestLongitude > mainland.bounds.northEastLongitude,
        )
        assertTrue(
            "component 1 (Corsica) must be a small island-scale bounding box, not a wide region",
            corsica.bounds.northEastLongitude - corsica.bounds.southWestLongitude < 3.0,
        )

        assertTrue("component 2 (French Guiana) must sit entirely west of -50 degrees", guiana.bounds.northEastLongitude < -50.0)
    }

    @Test
    fun `for this pinned reference version, real-artifact component navigation resolves each confirmed index to its own distinct, non-overlapping bounds`() {
        val france = loadFranceGeographicAreaReference()
        val components = france.components()

        // The concrete navigation guarantee, verified against the REAL checked-in artifact rather
        // than only a synthetic fixture: index 0 -> mainland bounds, index 1 -> Corsica bounds,
        // index 2 -> French Guiana bounds -- each independently reachable, none pulling another in.
        val mainlandBounds = components[0].bounds
        val corsicaBounds = components[1].bounds
        val guianaBounds = components[2].bounds

        assertTrue(mainlandBounds.northEastLongitude < corsicaBounds.southWestLongitude)
        assertTrue(guianaBounds.southWestLongitude < mainlandBounds.southWestLongitude)
        assertEquals("all three real components resolve to distinct bounds", 3, setOf(mainlandBounds, corsicaBounds, guianaBounds).size)
    }

    @Test
    fun `no component of the real reference carries surviving holes at this pinned filtering threshold`() {
        // Direct evidence for the GUF filtering rule documented in GenerateFranceReference.kt and
        // tools/geo/README.md: every measured hole in the raw GUF source (3,815 of them, largest
        // 45.71 in the generator's own area approximation) fell below the shared 50.0 threshold
        // applied to both exterior polygons and interior holes -- not because holes are
        // unconditionally discarded (see ClassifyDiscoveredCellsByGeographicAreaComponentsTest for
        // the actual containment behavior this produces), but because none of them measured as
        // large enough to be treated as real geography rather than raster noise.
        val france = loadFranceGeographicAreaReference()

        assertTrue(france.geometry.polygons.all { polygon -> polygon.holes.isEmpty() })
    }

    @Test
    fun `a single-polygon area decomposes into exactly one component`() {
        val ring = listOf(
            Coordinate(latitude = 0.0, longitude = 0.0),
            Coordinate(latitude = 0.0, longitude = 1.0),
            Coordinate(latitude = 1.0, longitude = 1.0),
            Coordinate(latitude = 1.0, longitude = 0.0),
        )
        val geometry = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(ring))))
        val area = GeographicArea(
            id = "zone:test",
            type = GeographicAreaType.ZONE,
            displayName = "Test Zone",
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.WORLD_DISCOVERY_ZONE,
        )

        val components = area.components()

        assertEquals(1, components.size)
        assertEquals(0, components.single().componentIndex)
        assertEquals(area.bounds, components.single().bounds)
    }
}
