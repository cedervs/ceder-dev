package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeographicGeometryTest {

    private fun ring(vararg points: Pair<Double, Double>): GeographicRing =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    // A simple square roughly around Paris -- lon [2,3], lat [48,49] -- no antimeridian involved.
    private val simpleSquare = ring(2.0 to 48.0, 3.0 to 48.0, 3.0 to 49.0, 2.0 to 49.0)

    @Test
    fun `bounds of a single ordinary polygon match its raw min-max`() {
        val multiPolygon = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(simpleSquare))))

        val bounds = computeGeographicBounds(multiPolygon)

        assertEquals(48.0, bounds.southWestLatitude, 0.0)
        assertEquals(2.0, bounds.southWestLongitude, 0.0)
        assertEquals(49.0, bounds.northEastLatitude, 0.0)
        assertEquals(3.0, bounds.northEastLongitude, 0.0)
    }

    @Test
    fun `bounds of multiple disjoint polygons cover the union of all of them`() {
        // Mirrors France's own real shape: a European polygon and a South American one, far apart
        // in longitude, with no antimeridian crossing involved.
        val europePolygon = GeographicPolygon(listOf(ring(-4.0 to 42.0, 8.0 to 42.0, 8.0 to 51.0, -4.0 to 51.0)))
        val southAmericaPolygon = GeographicPolygon(listOf(ring(-54.0 to 2.0, -51.0 to 2.0, -51.0 to 5.0, -54.0 to 5.0)))
        val multiPolygon = GeographicMultiPolygon(listOf(europePolygon, southAmericaPolygon))

        val bounds = computeGeographicBounds(multiPolygon)

        assertEquals(2.0, bounds.southWestLatitude, 0.0)
        assertEquals(-54.0, bounds.southWestLongitude, 0.0)
        assertEquals(51.0, bounds.northEastLatitude, 0.0)
        assertEquals(8.0, bounds.northEastLongitude, 0.0)
    }

    @Test
    fun `a ring crossing the antimeridian produces a narrow bounding box, not a near-global one`() {
        // A small ring straddling +-180 degrees near Fiji: e.g. 179, -179.5, -179 as raw
        // longitudes -- a naive min/max would compute [-179, 179] (nearly the whole globe)
        // instead of the true, narrow box actually containing this ring.
        val antimeridianRing = ring(179.0 to -17.0, -179.5 to -17.0, -179.0 to -16.5, 179.5 to -16.5)
        val multiPolygon = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(antimeridianRing))))

        val bounds = computeGeographicBounds(multiPolygon)

        val longitudeSpan = bounds.northEastLongitude - bounds.southWestLongitude
        assertEquals(
            "the antimeridian-crossing ring's true span is about 2 degrees, not ~358",
            2.0,
            longitudeSpan,
            0.5,
        )
    }

    @Test
    fun `bounds of two separate components each independently near +-180 correctly merge into one narrow span`() {
        // Mirrors an Alaska/Russia-style case: neither component individually crosses the
        // antimeridian, but placed together they straddle it -- a per-ring-only unwrap (this
        // function's previous implementation) cannot catch this, since the problem is *between*
        // rings, not within one.
        val eastComponent = GeographicPolygon(listOf(ring(170.0 to -10.0, 175.0 to -10.0, 175.0 to -5.0, 170.0 to -5.0)))
        val westComponent = GeographicPolygon(listOf(ring(-175.0 to -10.0, -170.0 to -10.0, -170.0 to -5.0, -175.0 to -5.0)))
        val multiPolygon = GeographicMultiPolygon(listOf(eastComponent, westComponent))

        val bounds = computeGeographicBounds(multiPolygon)

        val longitudeSpan = bounds.northEastLongitude - bounds.southWestLongitude
        assertEquals(
            "true span from 170 to -170 (through 180) is 20 degrees, not ~345",
            20.0,
            longitudeSpan,
            0.01,
        )
        assertEquals(170.0, bounds.southWestLongitude, 0.01)
        assertEquals(190.0, bounds.northEastLongitude, 0.01)
    }

    @Test
    fun `bounds of components straddling the antimeridian on three sides still finds the true narrow gap`() {
        val components = listOf(
            GeographicPolygon(listOf(ring(178.0 to 0.0, 179.0 to 0.0, 179.0 to 1.0, 178.0 to 1.0))),
            GeographicPolygon(listOf(ring(-179.0 to 0.0, -178.0 to 0.0, -178.0 to 1.0, -179.0 to 1.0))),
            GeographicPolygon(listOf(ring(-177.0 to 0.0, -176.0 to 0.0, -176.0 to 1.0, -177.0 to 1.0))),
        )
        val multiPolygon = GeographicMultiPolygon(components)

        val bounds = computeGeographicBounds(multiPolygon)

        // The true data spans 178 through -176 (i.e. 178..184 unwrapped), a 6-degree arc -- the
        // largest actual gap in the data (176 degrees, from -176 back around to 178) must be
        // recognized as the "outside" arc, not the short one between individual components.
        assertEquals(178.0, bounds.southWestLongitude, 0.01)
        assertEquals(184.0, bounds.northEastLongitude, 0.01)
    }

    @Test
    fun `GeographicPolygon requires at least one ring`() {
        assertThrows(IllegalArgumentException::class.java) { GeographicPolygon(emptyList()) }
    }

    @Test
    fun `GeographicPolygon rejects a ring with fewer than 3 points`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeographicPolygon(listOf(ring(0.0 to 0.0, 1.0 to 1.0)))
        }
    }

    @Test
    fun `GeographicBounds rejects southWestLongitude greater than northEastLongitude`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeographicBounds(southWestLatitude = 0.0, southWestLongitude = 10.0, northEastLatitude = 5.0, northEastLongitude = 5.0)
        }
    }

    @Test
    fun `GeographicMultiPolygon requires at least one polygon`() {
        assertThrows(IllegalArgumentException::class.java) { GeographicMultiPolygon(emptyList()) }
    }

    @Test
    fun `GeographicPolygon exposes its outer ring and holes separately`() {
        val hole = ring(2.2 to 48.2, 2.8 to 48.2, 2.8 to 48.8, 2.2 to 48.8)
        val polygon = GeographicPolygon(listOf(simpleSquare, hole))

        assertEquals(simpleSquare, polygon.outerRing)
        assertEquals(listOf(hole), polygon.holes)
    }

    @Test
    fun `GeographicBounds rejects a southWest latitude above northEast latitude`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeographicBounds(southWestLatitude = 10.0, southWestLongitude = 0.0, northEastLatitude = 5.0, northEastLongitude = 1.0)
        }
    }

    @Test
    fun `GeographicBounds rejects a non-finite latitude`() {
        assertThrows(IllegalArgumentException::class.java) {
            GeographicBounds(
                southWestLatitude = Double.NaN,
                southWestLongitude = 0.0,
                northEastLatitude = 5.0,
                northEastLongitude = 1.0,
            )
        }
    }
}
