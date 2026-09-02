package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `known cell boundary matches the real H3 library's output`() {
        // Captured by running the real com.uber:h3:4.5.0 H3Core.cellToBoundary directly outside
        // Gradle — same discipline as the reference vector above, not asserted from memory.
        val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

        val boundary = converter.cellBoundary(parisCell)

        assertEquals(6, boundary.size)
        assertEquals(Coordinate(latitude = 48.85675387511219, longitude = 2.3521627015914643), boundary[0])
        assertEquals(Coordinate(latitude = 48.85668532108757, longitude = 2.352264496729447), boundary[5])
    }

    @Test
    fun `cell boundary is not closed (first vertex is not repeated at the end)`() {
        val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

        val boundary = converter.cellBoundary(parisCell)

        assertNotEquals(boundary.first(), boundary.last())
    }

    @Test
    fun `converting the same cell boundary twice is deterministic`() {
        val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

        val first = converter.cellBoundary(parisCell)
        val second = converter.cellBoundary(parisCell)

        assertEquals(first, second)
    }

    @Test
    fun `isValidCell is true for a genuine H3 cell index`() {
        val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

        assertTrue(converter.isValidCell(parisCell))
    }

    @Test
    fun `isValidCell is false for a real but semantically invalid H3 index`() {
        // Verified directly against the real library: this is a syntactically valid hex string
        // that H3Core.isValidCell itself cleanly returns false for (no exception) — a genuine
        // invalid index, not an artificial one.
        val invalidCell = CanonicalCell(h3Index = "ffffffffffffffff", resolution = 12)

        assertFalse(converter.isValidCell(invalidCell))
    }

    @Test
    fun `isValidCell is false, not throwing, for a non-hexadecimal h3Index`() {
        // Verified directly against the real library: H3Core.isValidCell("not-a-real-h3-index")
        // throws NumberFormatException internally (it isn't parseable as hex at all) rather than
        // returning false — H3JavaCellConverter.isValidCell must still behave as a pure,
        // never-throwing predicate for its own callers.
        val corruptCell = CanonicalCell(h3Index = "not-a-real-h3-index", resolution = 12)

        assertFalse(converter.isValidCell(corruptCell))
    }
}
