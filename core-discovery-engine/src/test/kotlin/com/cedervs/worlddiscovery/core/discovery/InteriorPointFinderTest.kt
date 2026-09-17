package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic-geometry coverage for [findVerifiedInteriorPoint] — the deterministic, VERIFIED
 * interior-point helper that replaced an earlier, unsafe unverified vertex-average "centroid" (a
 * Codex review finding: that average is not a guaranteed interior point for a concave polygon, a
 * polygon with a hole, or an antimeridian-crossing ring). Every test here asserts the SAME thing
 * about the result: if non-null, it must independently verify as inside the source geometry via
 * [PointInPolygonClassifier] — never merely "the function returned something."
 */
class InteriorPointFinderTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private fun singlePolygonArea(ring: List<Coordinate>, holes: List<List<Coordinate>> = emptyList()): GeographicArea {
        val geometry = GeographicMultiPolygon(listOf(GeographicPolygon(rings = listOf(ring) + holes)))
        return GeographicArea(
            id = "test:area",
            type = GeographicAreaType.ADMIN_1,
            displayName = "test",
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.WORLD_DISCOVERY_ZONE,
            parentId = "country:TEST",
        )
    }

    @Test
    fun `1 -- a simple convex polygon (square) finds a verified interior point`() {
        val square = ring(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 0.0 to 4.0)
        val area = singlePolygonArea(square)

        val point = findVerifiedInteriorPoint(area)

        assertNotNull(point)
        assertTrue(PointInPolygonClassifier.contains(area.geometry, point!!))
    }

    @Test
    fun `2 -- a strongly concave (crescent-like) polygon, where the naive vertex average falls OUTSIDE it, still finds a verified interior point`() {
        // A "C"/crescent shape: the plain vertex average of these points falls in the empty notch on
        // the right-hand side, genuinely outside the ring -- exactly the failure mode the old
        // vertex-average implementation had no protection against.
        val crescent = ring(
            0.0 to 0.0, 10.0 to 0.0, 10.0 to 2.0, 3.0 to 2.0, 3.0 to 8.0, 10.0 to 8.0, 10.0 to 10.0, 0.0 to 10.0,
        )
        val area = singlePolygonArea(crescent)

        // Confirms the premise: the naive vertex average really is outside this ring.
        val naiveAverageLon = crescent.sumOf { it.longitude } / crescent.size
        val naiveAverageLat = crescent.sumOf { it.latitude } / crescent.size
        assertTrue(
            "test premise failed -- the naive vertex average must be OUTSIDE this crescent for this test to be meaningful",
            !PointInPolygonClassifier.contains(area.geometry, Coordinate(naiveAverageLat, naiveAverageLon)),
        )

        val point = findVerifiedInteriorPoint(area)

        assertNotNull("a verified interior point must still be found despite the concave shape", point)
        assertTrue(PointInPolygonClassifier.contains(area.geometry, point!!))
    }

    @Test
    fun `3 -- a polygon with a hole, where the naive centroid falls INSIDE the hole, still finds a point outside the hole`() {
        val outer = ring(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)
        // A hole centered exactly where both the vertex average and the bbox center of the outer
        // ring would land (5,5) -- deliberately sized to also catch the mid-row (fraction 0.5)
        // scanline candidate, forcing the search to a different scan row.
        val hole = ring(3.0 to 3.0, 7.0 to 3.0, 7.0 to 7.0, 3.0 to 7.0)
        val area = singlePolygonArea(outer, holes = listOf(hole))

        val point = findVerifiedInteriorPoint(area)

        assertNotNull(point)
        assertTrue("the verified point must be inside the outer ring and OUTSIDE the hole", PointInPolygonClassifier.contains(area.geometry, point!!))
        // Explicit, doubly-sure check: verify it's not simply sitting in the hole.
        val holePolygonOnly = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(hole))))
        assertTrue("the verified point must not itself be inside the hole", !PointInPolygonClassifier.contains(holePolygonOnly, point))
    }

    @Test
    fun `4 -- an antimeridian-crossing polygon finds a verified interior point on the correct side`() {
        // A simple, roughly rectangular polygon straddling +-180 -- raw longitudes 179 to -179.
        val crossing = ring(179.0 to -5.0, -179.0 to -5.0, -179.0 to 5.0, 179.0 to 5.0)
        val area = singlePolygonArea(crossing)

        val point = findVerifiedInteriorPoint(area)

        assertNotNull(point)
        assertTrue(PointInPolygonClassifier.contains(area.geometry, point!!))
        // The point must genuinely be near the dateline (the polygon's own real extent), not some
        // wildly wrong value a naive (non-antimeridian-safe) longitude average could produce.
        val longitude = point!!.longitude
        assertTrue(
            "expected the point to be near the antimeridian (>170 or <-170), was $longitude",
            longitude > 170.0 || longitude < -170.0,
        )
    }

    @Test
    fun `5 -- a fragmented MultiPolygon where the FIRST (largest) component is degenerate still finds a point in a later, valid component`() {
        val degenerateFirstPolygon = GeographicPolygon(listOf(ring(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0))) // collinear, zero area
        val validSecondPolygon = GeographicPolygon(listOf(ring(50.0 to 50.0, 51.0 to 50.0, 51.0 to 51.0, 50.0 to 51.0)))
        val geometry = GeographicMultiPolygon(listOf(degenerateFirstPolygon, validSecondPolygon))
        val area = GeographicArea(
            id = "test:fragmented",
            type = GeographicAreaType.ADMIN_1,
            displayName = "test",
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.WORLD_DISCOVERY_ZONE,
            parentId = "country:TEST",
        )

        val point = findVerifiedInteriorPoint(area)

        assertNotNull("must not give up just because polygons[0] is degenerate -- must search later components", point)
        assertTrue(PointInPolygonClassifier.contains(validSecondPolygon, point!!))
    }

    @Test
    fun `6 -- a degenerate (zero-area, collinear) polygon finds no interior point -- fails explicitly, never a guess`() {
        val collinear = ring(0.0 to 44.0, 1.0 to 44.0, 2.0 to 44.0)
        val area = singlePolygonArea(collinear)

        assertNull(findVerifiedInteriorPoint(area))
    }

    @Test
    fun `every candidate returned, when found, is independently reproducible -- calling twice gives the same deterministic result`() {
        val square = ring(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 0.0 to 4.0)
        val area = singlePolygonArea(square)

        val first = findVerifiedInteriorPoint(area)
        val second = findVerifiedInteriorPoint(area)

        assertNotNull(first)
        org.junit.Assert.assertEquals(first, second)
    }
}
