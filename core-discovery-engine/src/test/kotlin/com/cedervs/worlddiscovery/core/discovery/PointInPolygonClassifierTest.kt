package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointInPolygonClassifierTest {

    private fun coordinate(lon: Double, lat: Double) = Coordinate(latitude = lat, longitude = lon)

    private fun ring(vararg points: Pair<Double, Double>): GeographicRing =
        points.map { (lon, lat) -> coordinate(lon, lat) }

    // A square from (0,0) to (10,10).
    private val square = GeographicPolygon(listOf(ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)))

    @Test
    fun `a point well inside the polygon is contained`() {
        assertTrue(PointInPolygonClassifier.contains(square, coordinate(5.0, 5.0)))
    }

    @Test
    fun `a point well outside the polygon is not contained`() {
        assertFalse(PointInPolygonClassifier.contains(square, coordinate(20.0, 20.0)))
    }

    @Test
    fun `a point outside on one axis only is not contained`() {
        assertFalse(PointInPolygonClassifier.contains(square, coordinate(5.0, 20.0)))
        assertFalse(PointInPolygonClassifier.contains(square, coordinate(-5.0, 5.0)))
    }

    @Test
    fun `contains checks every polygon in a MultiPolygon, not just the first`() {
        val secondSquare = GeographicPolygon(listOf(ring(100.0 to 20.0, 110.0 to 20.0, 110.0 to 30.0, 100.0 to 30.0)))
        val multiPolygon = GeographicMultiPolygon(listOf(square, secondSquare))

        assertTrue(PointInPolygonClassifier.contains(multiPolygon, coordinate(5.0, 5.0)))
        assertTrue(PointInPolygonClassifier.contains(multiPolygon, coordinate(105.0, 25.0)))
        assertFalse(PointInPolygonClassifier.contains(multiPolygon, coordinate(50.0, 15.0)))
    }

    @Test
    fun `a point inside a hole is not contained even though it's inside the outer ring`() {
        val hole = ring(4.0 to 4.0, 6.0 to 4.0, 6.0 to 6.0, 4.0 to 6.0)
        val squareWithHole = GeographicPolygon(listOf(square.outerRing, hole))

        assertFalse(PointInPolygonClassifier.contains(squareWithHole, coordinate(5.0, 5.0)))
        assertTrue(PointInPolygonClassifier.contains(squareWithHole, coordinate(1.0, 1.0)))
    }

    @Test
    fun `a real point known to be inside metropolitan France classifies as inside the France reference`() {
        val franceArea = loadFranceGeographicAreaReference()

        // Paris, well inside mainland France.
        assertTrue(PointInPolygonClassifier.contains(franceArea.geometry, coordinate(2.3522, 48.8566)))
    }

    @Test
    fun `a real point known to be outside France classifies as outside the France reference`() {
        val franceArea = loadFranceGeographicAreaReference()

        // London, well outside France.
        assertFalse(PointInPolygonClassifier.contains(franceArea.geometry, coordinate(-0.1276, 51.5074)))
    }

    @Test
    fun `a point in French Guiana classifies as inside the France reference via its separate polygon`() {
        val franceArea = loadFranceGeographicAreaReference()

        // Cayenne, French Guiana -- inside France's third, geographically distant polygon.
        assertTrue(PointInPolygonClassifier.contains(franceArea.geometry, coordinate(-52.3260, 4.9333)))
    }

    // A 2-degree-wide strip straddling the antimeridian: 179 to -179 (i.e. 179 through 180 to
    // -179), latitude 10 to 20 -- a naive, non-antimeridian-safe ray cast would instead treat this
    // as the ~358-degree-wide complement spanning through 0 degrees longitude.
    private val antimeridianStrip = GeographicPolygon(
        listOf(ring(179.0 to 10.0, -179.0 to 10.0, -179.0 to 20.0, 179.0 to 20.0)),
    )

    @Test
    fun `a point just east of 179 is inside the antimeridian-straddling strip`() {
        assertTrue(PointInPolygonClassifier.contains(antimeridianStrip, coordinate(179.5, 15.0)))
    }

    @Test
    fun `a point just west of -179 is inside the antimeridian-straddling strip`() {
        assertTrue(PointInPolygonClassifier.contains(antimeridianStrip, coordinate(-179.5, 15.0)))
    }

    @Test
    fun `a point at 0 degrees longitude -- the true far side of the globe -- is outside the antimeridian-straddling strip`() {
        assertFalse(PointInPolygonClassifier.contains(antimeridianStrip, coordinate(0.0, 15.0)))
    }

    @Test
    fun `a point at 170 degrees -- just short of the strip -- is outside it`() {
        assertFalse(PointInPolygonClassifier.contains(antimeridianStrip, coordinate(170.0, 15.0)))
    }

    @Test
    fun `a point at -170 degrees -- just past the strip on the other side -- is outside it`() {
        assertFalse(PointInPolygonClassifier.contains(antimeridianStrip, coordinate(-170.0, 15.0)))
    }

    @Test
    fun `reversing the antimeridian strip's ring winding order does not change interior-exterior results`() {
        val reversed = GeographicPolygon(listOf(antimeridianStrip.outerRing.reversed()))

        assertTrue(PointInPolygonClassifier.contains(reversed, coordinate(179.5, 15.0)))
        assertTrue(PointInPolygonClassifier.contains(reversed, coordinate(-179.5, 15.0)))
        assertFalse(PointInPolygonClassifier.contains(reversed, coordinate(0.0, 15.0)))
    }

    @Test
    fun `a hole cut out of the antimeridian-straddling strip near the dateline is correctly excluded`() {
        // A small hole entirely inside the strip, itself straddling the dateline too.
        val hole = ring(179.5 to 13.0, -179.5 to 13.0, -179.5 to 17.0, 179.5 to 17.0)
        val stripWithHole = GeographicPolygon(listOf(antimeridianStrip.outerRing, hole))

        // Inside the hole -- must be excluded even though it's inside the outer ring.
        assertFalse(PointInPolygonClassifier.contains(stripWithHole, coordinate(180.0, 15.0)))
        // Inside the outer ring but outside the hole (near one edge of the strip).
        assertTrue(PointInPolygonClassifier.contains(stripWithHole, coordinate(179.1, 15.0)))
    }

    @Test
    fun `a point exactly at 0 degrees longitude classifies deterministically for an ordinary polygon straddling it`() {
        val straddlingZero = GeographicPolygon(listOf(ring(-5.0 to 0.0, 5.0 to 0.0, 5.0 to 10.0, -5.0 to 10.0)))

        val firstResult = PointInPolygonClassifier.contains(straddlingZero, coordinate(0.0, 5.0))
        val secondResult = PointInPolygonClassifier.contains(straddlingZero, coordinate(0.0, 5.0))

        assertTrue(firstResult)
        assertEquals("repeated calls with the same input must agree", firstResult, secondResult)
    }

    @Test
    fun `a boundary point exactly on a vertex is deterministic across repeated calls`() {
        // (0,0) is a vertex of `square`. Whatever the answer is, it must never flip between calls.
        val results = (1..5).map { PointInPolygonClassifier.contains(square, coordinate(0.0, 0.0)) }

        assertTrue("boundary-vertex classification must be stable across repeated calls", results.all { it == results.first() })
    }
}
