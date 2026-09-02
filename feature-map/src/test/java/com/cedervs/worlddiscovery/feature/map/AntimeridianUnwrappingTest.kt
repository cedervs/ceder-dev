package com.cedervs.worlddiscovery.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

class AntimeridianUnwrappingTest {

    @Test
    fun `a ring crossing the antimeridian is unwrapped into a contiguous sequence`() {
        // Real H3 resolution-12 boundary near +180 degrees, captured by running the real H3
        // library directly (H3Core.cellToBoundary on cell "8c7eb57221a2bff") — not invented by
        // hand. Vertex index 4 crosses from ~179.9999 to ~-179.9999.
        val rawRing = listOf(
            Point.fromLngLat(179.99989408795096, 1.1272600941060491E-4),
            Point.fromLngLat(179.99985295677513, 4.2529943245653316E-5),
            Point.fromLngLat(179.99989240464174, -3.99415330551343E-5),
            Point.fromLngLat(179.99997298374083, -5.2217063085225254E-5),
            Point.fromLngLat(-179.99998588496643, 1.797898450522571E-5),
            Point.fromLngLat(179.99997466711034, 1.0045058073046189E-4),
        )

        val unwrapped = unwrapAntimeridianRing(rawRing)

        assertEquals(rawRing.size, unwrapped.size)
        // No two consecutive vertices should jump by more than 180 degrees anymore — that jump
        // is exactly what previously drew an edge spanning almost the whole map width.
        for (i in 0 until unwrapped.size - 1) {
            val delta = kotlin.math.abs(unwrapped[i + 1].longitude() - unwrapped[i].longitude())
            assertTrue("consecutive vertices still jump by $delta degrees", delta < 180.0)
        }
        // The formerly-wrapped vertex is now expressed just past +180 rather than near -180,
        // keeping the ring on one contiguous side of the seam.
        assertEquals(180.00001411503357, unwrapped[4].longitude(), 1e-9)
        // Latitudes are never touched by unwrapping.
        for (i in rawRing.indices) {
            assertEquals(rawRing[i].latitude(), unwrapped[i].latitude(), 0.0)
        }
    }

    @Test
    fun `a ring nowhere near the antimeridian is left unchanged`() {
        val rawRing = listOf(
            Point.fromLngLat(2.3520, 48.8570),
            Point.fromLngLat(2.3525, 48.8565),
            Point.fromLngLat(2.3520, 48.8560),
        )

        val unwrapped = unwrapAntimeridianRing(rawRing)

        assertEquals(rawRing.map { it.longitude() }, unwrapped.map { it.longitude() })
        assertEquals(rawRing.map { it.latitude() }, unwrapped.map { it.latitude() })
    }

    @Test
    fun `an empty ring is returned unchanged`() {
        assertEquals(emptyList<Point>(), unwrapAntimeridianRing(emptyList()))
    }
}
