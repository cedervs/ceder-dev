package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Reference vectors below were captured by running the real `com.uber:h3:4.4.0` library
 * (H3Core.latLngToCellAddress) directly outside Gradle, not asserted from memory — see the
 * final implementation report for how they were obtained. This guards against a library
 * upgrade silently changing the canonical grid under us, per docs/discovery-engine.md §16.
 */
class H3JavaCellConverterTest {

    private val converter = H3JavaCellConverter()

    @Test
    fun `known coordinate maps to the expected resolution 12 cell`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)

        val cell = converter.toCanonicalCell(paris)

        assertEquals("8c1fb46625551ff", cell.h3Index)
        assertEquals(12, cell.resolution)
    }

    @Test
    fun `produced cells always use the canonical engine resolution`() {
        val cell = converter.toCanonicalCell(Coordinate(latitude = 40.7128, longitude = -74.0060))

        assertEquals(DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION, cell.resolution)
    }

    @Test
    fun `converting the same coordinate twice is deterministic`() {
        val coordinate = Coordinate(latitude = 48.8566, longitude = 2.3522)

        val first = converter.toCanonicalCell(coordinate)
        val second = converter.toCanonicalCell(coordinate)

        assertEquals(first, second)
    }

    @Test
    fun `nearby coordinates within the same cell resolve identically`() {
        val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
        val almostParis = Coordinate(latitude = 48.8566 + 0.00001, longitude = 2.3522 + 0.00001)

        assertEquals(converter.toCanonicalCell(paris), converter.toCanonicalCell(almostParis))
    }

    @Test
    fun `distant coordinates resolve to different cells`() {
        val paris = converter.toCanonicalCell(Coordinate(latitude = 48.8566, longitude = 2.3522))
        val newYork = converter.toCanonicalCell(Coordinate(latitude = 40.7128, longitude = -74.0060))

        assertNotEquals(paris, newYork)
    }
}
