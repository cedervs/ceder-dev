package com.cedervs.worlddiscovery.core.discovery

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the private constant in GeodesicDistance.kt — duplicated here only so these tests can
 * express expected values in closed form instead of hand-computed magic numbers. */
private const val TEST_EARTH_RADIUS_METERS = 6_371_000.0

class GeodesicDistanceTest {

    @Test
    fun `the distance from a coordinate to itself is zero`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)

        assertEquals(0.0, haversineDistanceMeters(paris, paris), 0.0)
    }

    @Test
    fun `one degree of latitude is close to the well-known ~111,195 meters, anywhere`() {
        // Distance along a meridian (constant longitude) for 1 degree of latitude is the same
        // everywhere on a sphere: circumference / 360 = 2*pi*6_371_000 / 360 ~= 111_194.9 m.
        val near = Coordinate(latitude = 48.0, longitude = 2.3522)
        val far = Coordinate(latitude = 49.0, longitude = 2.3522)

        val distance = haversineDistanceMeters(near, far)

        assertEquals(111_195.0, distance, 50.0)
    }

    @Test
    fun `one degree of longitude at the equator matches one degree of latitude`() {
        // At the equator, a circle of longitude has the same radius as the sphere itself, same as
        // any meridian — so 1 degree of longitude there should match 1 degree of latitude exactly.
        val a = Coordinate(latitude = 0.0, longitude = 2.0)
        val b = Coordinate(latitude = 0.0, longitude = 3.0)

        val distance = haversineDistanceMeters(a, b)

        assertEquals(111_195.0, distance, 50.0)
    }

    @Test
    fun `one degree of longitude away from the equator is meaningfully shorter, due to the cosine of latitude`() {
        val parisArea = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val oneDegreeEast = Coordinate(latitude = 48.8566, longitude = 3.3522)

        val distance = haversineDistanceMeters(parisArea, oneDegreeEast)

        // At ~49 degrees latitude, cos(latitude) ~= 0.66, so this must be well under the equator's
        // ~111,195 m figure for the same 1-degree longitude delta — a loose sanity bound, not an
        // exact hand-computed trig value.
        assertTrue("expected a meaningfully shorter distance at this latitude, got $distance", distance < 90_000.0)
        assertTrue("distance should still be a large, non-trivial figure, got $distance", distance > 50_000.0)
    }

    @Test
    fun `distance is symmetric`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val lyon = Coordinate(latitude = 45.7640, longitude = 4.8357)

        assertEquals(haversineDistanceMeters(paris, lyon), haversineDistanceMeters(lyon, paris), 0.0)
    }

    @Test
    fun `a short, sub-100-meter distance is computed accurately`() {
        // 0.0001 degree of latitude ~= 11.12 m — the near-zero regime of the clamped `a` term,
        // exercised separately from the ~111km-scale tests above.
        val start = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val elevenMetersNorth = Coordinate(latitude = 48.8567, longitude = 2.3522)

        val distance = haversineDistanceMeters(start, elevenMetersNorth)

        assertTrue("expected ~11m, got $distance", distance in 9.0..14.0)
    }

    @Test
    fun `crossing the antimeridian gives the short way around, not the long way`() {
        // 0.2 degrees apart in reality (179.9 to -179.9 wraps around 180/-180), not the ~359.8
        // degrees a naive unwrapped subtraction would suggest. sin/cos of half the raw delta are
        // periodic, so the formula handles this without an explicit longitude-wrapping step.
        val justWest = Coordinate(latitude = 0.0, longitude = 179.9)
        val justEast = Coordinate(latitude = 0.0, longitude = -179.9)

        val distance = haversineDistanceMeters(justWest, justEast)

        // 0.2/360 of the equatorial circumference (2*pi*R) ~= 22,239 m — nowhere near the
        // ~39.8 million meter "long way around" a wraparound bug would produce.
        assertTrue("expected ~22,239m (short way around the antimeridian), got $distance", distance in 20_000.0..25_000.0)
    }

    @Test
    fun `exact antipodes produce pi times the Earth's radius, never NaN`() {
        // The maximum possible great-circle distance on a sphere. This is the classic regime
        // where an unclamped `a` term can round to fractionally over 1.0 in floating point,
        // handing sqrt(1 - a) a negative input and producing NaN.
        val origin = Coordinate(latitude = 0.0, longitude = 0.0)
        val antipode = Coordinate(latitude = 0.0, longitude = 180.0)

        val distance = haversineDistanceMeters(origin, antipode)

        assertFalse("must never be NaN", distance.isNaN())
        assertEquals(PI * TEST_EARTH_RADIUS_METERS, distance, 10.0)
    }

    @Test
    fun `a near-antipodal pair stays finite and close to, but no more than, pi times the radius`() {
        val nearOrigin = Coordinate(latitude = 0.0001, longitude = 0.0)
        val nearAntipode = Coordinate(latitude = -0.0002, longitude = 179.999)

        val distance = haversineDistanceMeters(nearOrigin, nearAntipode)

        assertFalse("must never be NaN", distance.isNaN())
        val maxPossibleDistance = PI * TEST_EARTH_RADIUS_METERS
        assertTrue(
            "expected a distance close to but not exceeding the antipodal maximum ($maxPossibleDistance), got $distance",
            distance in (maxPossibleDistance - 1_000.0)..(maxPossibleDistance + 1.0),
        )
    }

    @Test
    fun `destinationPoint with zero distance returns the same coordinate`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)

        val result = destinationPoint(paris, bearingDegrees = 137.0, distanceMeters = 0.0)

        assertEquals(paris.latitude, result.latitude, 1e-9)
        assertEquals(paris.longitude, result.longitude, 1e-9)
    }

    @Test
    fun `destinationPoint north increases latitude by the well-known ~111,195 meters-per-degree`() {
        val start = Coordinate(latitude = 0.0, longitude = 0.0)

        val result = destinationPoint(start, bearingDegrees = 0.0, distanceMeters = 111_195.0)

        assertEquals(1.0, result.latitude, 0.01)
        assertEquals(0.0, result.longitude, 0.001)
    }

    @Test
    fun `destinationPoint south decreases latitude by the same amount`() {
        val start = Coordinate(latitude = 0.0, longitude = 0.0)

        val result = destinationPoint(start, bearingDegrees = 180.0, distanceMeters = 111_195.0)

        assertEquals(-1.0, result.latitude, 0.01)
        assertEquals(0.0, result.longitude, 0.001)
    }

    @Test
    fun `destinationPoint east increases longitude, at the equator by the same well-known amount`() {
        val start = Coordinate(latitude = 0.0, longitude = 0.0)

        val result = destinationPoint(start, bearingDegrees = 90.0, distanceMeters = 111_195.0)

        assertEquals(0.0, result.latitude, 0.001)
        assertEquals(1.0, result.longitude, 0.01)
    }

    @Test
    fun `destinationPoint west decreases longitude by the same amount`() {
        val start = Coordinate(latitude = 0.0, longitude = 0.0)

        val result = destinationPoint(start, bearingDegrees = 270.0, distanceMeters = 111_195.0)

        assertEquals(0.0, result.latitude, 0.001)
        assertEquals(-1.0, result.longitude, 0.01)
    }

    @Test
    fun `destinationPoint round-trips through haversineDistanceMeters for many bearings`() {
        // The direct and inverse geodesic problems must agree: travelling `distanceMeters` along
        // any bearing must land a point that haversineDistanceMeters reports as `distanceMeters`
        // away from the origin (this is the cross-check between the two functions in this file).
        val start = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val distanceMeters = 250.0

        for (bearing in 0..350 step 10) {
            val destination = destinationPoint(start, bearing.toDouble(), distanceMeters)
            val roundTripDistance = haversineDistanceMeters(start, destination)

            assertTrue(
                "bearing=$bearing: expected ~${distanceMeters}m, got $roundTripDistance",
                roundTripDistance in (distanceMeters - 1.0)..(distanceMeters + 1.0),
            )
        }
    }

    @Test
    fun `destinationPoint normalizes across the antimeridian instead of producing an invalid coordinate`() {
        // Starting just west of the antimeridian and heading east must wrap the resulting
        // longitude into the valid [-180, 180] range Coordinate itself enforces, rather than
        // throwing or producing e.g. longitude=180.3.
        val justWestOfAntimeridian = Coordinate(latitude = 0.0, longitude = 179.999)

        val result = destinationPoint(justWestOfAntimeridian, bearingDegrees = 90.0, distanceMeters = 500.0)

        assertTrue("expected a longitude just past the antimeridian, got ${result.longitude}", result.longitude < -179.9)
    }

    @Test
    fun `destinationPoint at the exact north pole never throws or produces NaN, for any bearing`() {
        val northPole = Coordinate(latitude = 90.0, longitude = 0.0)

        for (bearing in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
            val result = destinationPoint(northPole, bearing, distanceMeters = 1_000.0)

            assertFalse("bearing=$bearing: latitude must never be NaN", result.latitude.isNaN())
            assertFalse("bearing=$bearing: longitude must never be NaN", result.longitude.isNaN())
            // Every direction from the pole heads toward the equator — latitude must decrease
            // from 90, regardless of which bearing was requested.
            assertTrue("bearing=$bearing: expected latitude just under 90, got ${result.latitude}", result.latitude < 90.0)
            assertTrue("bearing=$bearing: expected latitude close to 90, got ${result.latitude}", result.latitude > 89.0)
        }
    }

    @Test
    fun `destinationPoint at the south pole never throws or produces NaN, for any bearing`() {
        val southPole = Coordinate(latitude = -90.0, longitude = 0.0)

        for (bearing in listOf(0.0, 90.0, 180.0, 270.0)) {
            val result = destinationPoint(southPole, bearing, distanceMeters = 1_000.0)

            assertFalse("bearing=$bearing: latitude must never be NaN", result.latitude.isNaN())
            assertFalse("bearing=$bearing: longitude must never be NaN", result.longitude.isNaN())
            assertTrue("bearing=$bearing: expected latitude just above -90, got ${result.latitude}", result.latitude > -90.0)
        }
    }

    @Test
    fun `destinationPoint at high latitude still stays approximately the requested distance away`() {
        // Near a pole, a fixed angular step covers far less east-west ground distance than at the
        // equator (the meridian-convergence effect) — the haversine round-trip check must still
        // hold, since destinationPoint itself operates on angular distance, not ground distance
        // at latitude, and haversineDistanceMeters correctly accounts for that convergence too.
        val highLatitude = Coordinate(latitude = 89.0, longitude = 10.0)
        val distanceMeters = 500.0

        for (bearing in listOf(0.0, 90.0, 180.0, 270.0)) {
            val destination = destinationPoint(highLatitude, bearing, distanceMeters)
            val roundTripDistance = haversineDistanceMeters(highLatitude, destination)

            assertTrue(
                "bearing=$bearing: expected ~${distanceMeters}m, got $roundTripDistance",
                roundTripDistance in (distanceMeters - 1.0)..(distanceMeters + 1.0),
            )
        }
    }
}
