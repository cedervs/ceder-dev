package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertThrows
import org.junit.Test

class CoordinateTest {

    @Test
    fun `valid coordinate is accepted`() {
        val coordinate = Coordinate(latitude = 48.8566, longitude = 2.3522)

        assert(coordinate.latitude == 48.8566)
        assert(coordinate.longitude == 2.3522)
    }

    @Test
    fun `boundary values are accepted`() {
        Coordinate(latitude = 90.0, longitude = 180.0)
        Coordinate(latitude = -90.0, longitude = -180.0)
    }

    @Test
    fun `latitude above 90 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(latitude = 90.1, longitude = 0.0)
        }
    }

    @Test
    fun `latitude below negative 90 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(latitude = -90.1, longitude = 0.0)
        }
    }

    @Test
    fun `longitude above 180 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(latitude = 0.0, longitude = 180.1)
        }
    }

    @Test
    fun `longitude below negative 180 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(latitude = 0.0, longitude = -180.1)
        }
    }

    @Test
    fun `NaN latitude is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(latitude = Double.NaN, longitude = 0.0)
        }
    }

    @Test
    fun `infinite longitude is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Coordinate(latitude = 0.0, longitude = Double.POSITIVE_INFINITY)
        }
    }
}
