package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the bundled OSM-derived mainland-France rendering resource — parsing, structural
 * integrity, and expected bounds. Mirrors `GeographicAreaReferenceTest` (core-discovery-engine's own
 * classification-resource test) in style, deliberately testing a wholly separate resource/parser/type.
 */
class MainlandFranceRenderingPolygonTest {

    @Test
    fun `the bundled mainland France rendering polygon loads non-empty`() {
        val polygon = loadMainlandFranceRenderingPolygon()

        assertTrue(polygon.rings.isNotEmpty())
        assertTrue(polygon.outerRing.size >= 3)
    }

    @Test
    fun `every coordinate in the bundled polygon is finite -- enforced by Coordinate's own validation, confirmed here by successful load`() {
        // Coordinate's init block already rejects non-finite/out-of-range values at parse time (see
        // core-discovery-engine's Coordinate.kt) -- a successful load is itself the finite-coordinates
        // proof; this test exists to make that guarantee explicit and named, not to re-implement it.
        val polygon = loadMainlandFranceRenderingPolygon()

        assertTrue(polygon.outerRing.all { it.latitude.isFinite() && it.longitude.isFinite() })
    }

    @Test
    fun `the bundled polygon's bounds are sane mainland-France bounds, not empty or corrupt data`() {
        val polygon = loadMainlandFranceRenderingPolygon()

        val lons = polygon.outerRing.map { it.longitude }
        val lats = polygon.outerRing.map { it.latitude }
        // Broad sanity bounds, not an exact match to the classification (geoBoundaries) artifact's
        // own bounds -- this is a genuinely different dataset; see MainlandFranceRenderingPolygon.kt's
        // own doc comment for why the two are expected to differ, not match exactly.
        assertTrue("west edge should be near Brittany, was ${lons.min()}", lons.min() in -6.0..-3.0)
        assertTrue("east edge should be past the Alps, was ${lons.max()}", lons.max() in 6.0..9.0)
        assertTrue("south edge should be near the Pyrenees/Mediterranean, was ${lats.min()}", lats.min() in 41.0..44.0)
        assertTrue("north edge should be near the Channel/Belgium, was ${lats.max()}", lats.max() in 50.0..52.0)
    }

    @Test
    fun `the bundled polygon has dramatically more detail than the old 74-vertex Natural-Earth-scale artifact`() {
        val polygon = loadMainlandFranceRenderingPolygon()

        assertTrue(
            "expected a highly detailed OSM-derived outline, was ${polygon.outerRing.size} vertices",
            polygon.outerRing.size > 1000,
        )
    }

    @Test
    fun `the bundled resource's raw provenance names OpenStreetMap, relation 1403916, and ODbL`() {
        val resourceStream = javaClass.getResourceAsStream("/geo/france-mainland-osm-render.json")
            ?: error("geo/france-mainland-osm-render.json resource not found on the classpath")
        val rawJson = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }

        assertTrue(rawJson.contains("openstreetmap"))
        assertTrue(rawJson.contains("1403916"))
        assertTrue(rawJson.contains("ODbL"))
    }

    @Test
    fun `the bundled resource's decoded generatedAt and sourceVersion both independently name the correct 2026-09-02 provenance date`() {
        // PHASE G3 STABILIZATION -- Codex found a mismatched/future date across generator, artifact,
        // and README (e.g. an ambiguous "2026-09-02/03"). Fixed in a prior stabilization pass; this
        // test was then itself strengthened (Codex's follow-up finding) to decode the real
        // MainlandFranceRenderingPolygonJson schema and assert BOTH date-bearing fields
        // independently, rather than only regex/substring-scanning the raw JSON text -- a raw-text
        // scan can't tell "generatedAt is right but sourceVersion is wrong" apart from "both right",
        // since either field containing the substring "2026-09-02" would satisfy it. Decoding each
        // field on its own and asserting each on its own closes that gap: this test fails if EITHER
        // field alone carries the wrong date, even while the other is correct.
        val resourceStream = javaClass.getResourceAsStream("/geo/france-mainland-osm-render.json")
            ?: error("geo/france-mainland-osm-render.json resource not found on the classpath")
        val rawJson = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val parsed = kotlinx.serialization.json.Json.decodeFromString<MainlandFranceRenderingPolygonJson>(rawJson)

        assertEquals("2026-09-02", parsed.generatedAt)
        // The exact current sourceVersion contract (GenerateFranceOsmRenderingPolygon.kt), not an
        // invented one -- the precise retrieval-method-and-date clause that string always carries.
        val expectedRetrievalStatement = "retrieved via polygons.openstreetmap.fr/get_geojson.py?id=1403916&params=0 on 2026-09-02"
        assertTrue(
            "expected sourceVersion to contain the exact retrieval statement \"$expectedRetrievalStatement\", was: ${parsed.sourceVersion}",
            parsed.sourceVersion.contains(expectedRetrievalStatement),
        )

        // Supplementary, not the main proof -- a coarser raw-text guard against a leftover/future or
        // ambiguous composite date slipping in anywhere else in the file.
        assertTrue("expected no leftover future date anywhere in the resource", !rawJson.contains("2026-09-03"))
        assertTrue("expected no ambiguous composite date anywhere in the resource", !rawJson.contains("2026-09-02/03"))
    }

    @Test
    fun `the bundled resource still parses successfully under the strengthened closed-ring parser contract`() {
        // The generator always emits closed rings (verified by its own post-simplification
        // validateRing check) -- this proves the STRENGTHENED parser (ring.size >= 4, first == last)
        // doesn't reject the real, current bundled artifact, not just synthetic fixtures.
        val polygon = loadMainlandFranceRenderingPolygon()

        assertEquals(polygon.outerRing.first(), polygon.rings[0].last())
    }

    @Test
    fun `parsing rejects a point that is not a longitude-latitude pair`() {
        val malformed = """
            {
              "sourceId": "test", "sourceVersion": "v1", "sourceProvenance": "EXTERNAL_REFERENCE_DATASET",
              "generatedAt": "2026-01-01", "license": "test",
              "polygon": [[[0.0, 0.0], [1.0, 0.0], [1.0, 2.0, 3.0], [0.0, 0.0]]]
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { parseMainlandFranceRenderingPolygon(malformed) }
    }

    @Test
    fun `parsing rejects a ring that is not explicitly closed -- never silently closes malformed source data`() {
        val unclosed = """
            {
              "sourceId": "test-source", "sourceVersion": "v1", "sourceProvenance": "EXTERNAL_REFERENCE_DATASET",
              "generatedAt": "2026-01-01", "license": "test license",
              "polygon": [[[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]]]
            }
        """.trimIndent()

        val exception = assertThrows(IllegalArgumentException::class.java) { parseMainlandFranceRenderingPolygon(unclosed) }
        assertTrue(exception.message!!.contains("not closed"))
    }

    @Test
    fun `parsing rejects a ring with fewer than 4 points -- too small to be a valid closed ring`() {
        val tooSmall = """
            {
              "sourceId": "test-source", "sourceVersion": "v1", "sourceProvenance": "EXTERNAL_REFERENCE_DATASET",
              "generatedAt": "2026-01-01", "license": "test license",
              "polygon": [[[0.0, 0.0], [1.0, 0.0], [0.0, 0.0]]]
            }
        """.trimIndent()

        val exception = assertThrows(IllegalArgumentException::class.java) { parseMainlandFranceRenderingPolygon(tooSmall) }
        assertTrue(exception.message!!.contains("point(s)"))
    }

    @Test
    fun `parsing a valid explicitly-closed ring is accepted and round-trips correctly`() {
        val closed = """
            {
              "sourceId": "test-source", "sourceVersion": "v1", "sourceProvenance": "EXTERNAL_REFERENCE_DATASET",
              "generatedAt": "2026-01-01", "license": "test license",
              "polygon": [[[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0], [0.0, 0.0]]]
            }
        """.trimIndent()

        val polygon = parseMainlandFranceRenderingPolygon(closed)

        assertEquals(1, polygon.rings.size)
        assertEquals(
            listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 1.0), Coordinate(1.0, 1.0), Coordinate(1.0, 0.0), Coordinate(0.0, 0.0)),
            polygon.outerRing,
        )
    }
}
