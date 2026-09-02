package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeographicAreaReferenceTest {

    @Test
    fun `the bundled France reference loads with the expected identity and provenance`() {
        val france = loadFranceGeographicAreaReference()

        assertEquals("country:FR", france.id)
        assertEquals(GeographicAreaType.COUNTRY, france.type)
        assertEquals("France", france.displayName)
        assertEquals("geoboundaries", france.sourceId)
        assertEquals(GeographicAreaProvenance.EXTERNAL_REFERENCE_DATASET, france.sourceProvenance)
        assertTrue(france.sourceVersion.isNotBlank())
    }

    @Test
    fun `the bundled France reference preserves all three real MultiPolygon parts`() {
        val france = loadFranceGeographicAreaReference()

        // Confirmed by inspecting the real source data before writing the generator: mainland
        // France (from geoBoundaries FRA), Corsica (from geoBoundaries FRA), and French Guiana
        // (from geoBoundaries GUF, combined at generation time since FRA's own ADM0 geometry does
        // not include it) -- see tools/geo/README.md.
        assertEquals(3, france.geometry.polygons.size)
        assertTrue(france.geometry.polygons.all { it.outerRing.size >= 3 })
    }

    @Test
    fun `the bundled France reference's bounds are derived from its geometry, not a separate hardcoded value`() {
        val france = loadFranceGeographicAreaReference()

        assertEquals(computeGeographicBounds(france.geometry), france.bounds)
    }

    @Test
    fun `the France reference bounds span both mainland Europe and French Guiana`() {
        val france = loadFranceGeographicAreaReference()

        // French Guiana sits around longitude -52; mainland+Corsica extend past longitude 9.
        assertTrue(france.bounds.southWestLongitude < -50.0)
        assertTrue(france.bounds.northEastLongitude > 8.0)
    }

    @Test
    fun `the bundled France reference is dramatically more detailed than the prior Natural-Earth-based artifact`() {
        val france = loadFranceGeographicAreaReference()

        // The prior (Natural Earth 1:110m) artifact had 74 total vertices across 3 polygons -- the
        // exact physical-device visual problem this geoBoundaries-based regeneration exists to
        // fix. A regression back toward that scale would silently reintroduce the coarse-border
        // problem without any other test noticing (bounds/component-count alone don't measure
        // detail level).
        val totalVertices = france.geometry.polygons.sumOf { polygon -> polygon.outerRing.size }
        assertTrue(
            "expected dramatically more detail than the old 74-vertex artifact, was $totalVertices",
            totalVertices > 1000,
        )
    }

    @Test
    fun `the bundled artifact's own sourceVersion identifies both real sub-sources, since GeographicArea has no per-component provenance field`() {
        val france = loadFranceGeographicAreaReference()

        // GeographicArea has no per-component provenance field (see tools/geo/README.md's
        // "Architectural limitation" section) -- sourceVersion is the one field that survives
        // parsing into the domain object (see the note below about `license` NOT surviving) and
        // is where both real sub-sources are named, so this is checked directly, not assumed.
        assertTrue(france.sourceVersion.contains("FRA"))
        assertTrue(france.sourceVersion.contains("GUF"))
    }

    @Test
    fun `the bundled artifact's raw license field names both real per-component licenses, even though GeographicArea itself does not carry a license field at all`() {
        // A second, more fundamental gap than "no PER-COMPONENT provenance": parseGeographicAreaReference
        // reads GeographicAreaReferenceJson.license but never carries it into the returned
        // GeographicArea at all (see GeographicAreaReference.kt) -- so this test deliberately reads
        // the raw bundled resource text directly, the same way loadFranceGeographicAreaReference
        // itself does, rather than asserting on a GeographicArea field that doesn't exist. The full,
        // accurate combined-license text still exists in the checked-in artifact -- it just isn't
        // reachable from the running app today. See tools/geo/README.md's "Architectural
        // limitation" section for the future fix (a license field on GeographicArea itself).
        val resourceStream = javaClass.getResourceAsStream("/geo/france-reference.json")
            ?: error("geo/france-reference.json resource not found on the classpath")
        val rawJson = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }

        assertTrue("expected the mainland/Corsica CC0 license to be named", rawJson.contains("CC0"))
        assertTrue("expected the French Guiana CC BY 4.0 license to be named", rawJson.contains("CC BY 4.0"))
    }

    @Test
    fun `parsing rejects a point that is not a longitude-latitude pair`() {
        val malformed = """
            {
              "id": "country:XX", "type": "COUNTRY", "displayName": "Test",
              "sourceId": "test", "sourceVersion": "v1", "sourceProvenance": "EXTERNAL_REFERENCE_DATASET",
              "generatedAt": "2026-01-01", "license": "test",
              "polygons": [[[[1.0, 2.0, 3.0]]]]
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { parseGeographicAreaReference(malformed) }
    }

    @Test
    fun `parsing a minimal single-polygon reference round-trips correctly`() {
        val minimal = """
            {
              "id": "country:XX", "type": "COUNTRY", "displayName": "Testland",
              "sourceId": "test-source", "sourceVersion": "v1", "sourceProvenance": "WORLD_DISCOVERY_ZONE",
              "generatedAt": "2026-01-01", "license": "test license",
              "polygons": [[[[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]]]
            }
        """.trimIndent()

        val area = parseGeographicAreaReference(minimal)

        assertEquals("country:XX", area.id)
        assertEquals("Testland", area.displayName)
        assertEquals(GeographicAreaProvenance.WORLD_DISCOVERY_ZONE, area.sourceProvenance)
        assertEquals(1, area.geometry.polygons.size)
        assertEquals(
            listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 1.0), Coordinate(1.0, 1.0), Coordinate(1.0, 0.0)),
            area.geometry.polygons.single().outerRing,
        )
    }
}
