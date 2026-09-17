package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit-level tests for the generic multi-sibling-area classifier, using small synthetic squares
 * (never real France geometry -- that's [FranceAdministrativeHierarchyTest]'s job) so these stay
 * fast and independent of any bundled artifact. Mirrors
 * [ClassifyDiscoveredCellsByGeographicAreaComponentsTest]'s own style, one level up: siblings here
 * are independent [GeographicArea]s, not components of a single area.
 */
class ClassifyDiscoveredCellsByGeographicAreasTest {

    private val squareA = testArea("area:A", south = 0.0, west = 0.0, north = 1.0, east = 1.0)
    private val squareB = testArea("area:B", south = 0.0, west = 2.0, north = 1.0, east = 3.0)
    private val siblings = listOf(squareA, squareB)

    private val insideACell = CanonicalCell(h3Index = "test-cell-inside-a", resolution = 12)
    private val insideACenter = Coordinate(latitude = 0.5, longitude = 0.5)
    private val insideBCell = CanonicalCell(h3Index = "test-cell-inside-b", resolution = 12)
    private val insideBCenter = Coordinate(latitude = 0.5, longitude = 2.5)
    private val outsideBothCell = CanonicalCell(h3Index = "test-cell-outside-both", resolution = 12)
    private val outsideBothCenter = Coordinate(latitude = 10.0, longitude = 10.0)

    private val timestamp = Instant.parse("2026-01-01T10:00:00Z")

    private lateinit var cellConverter: FakeMultiAreaCellConverter
    private lateinit var classify: ClassifyDiscoveredCellsByGeographicAreas

    @Before
    fun setUp() {
        cellConverter = FakeMultiAreaCellConverter(
            mapOf(insideACell to insideACenter, insideBCell to insideBCenter, outsideBothCell to outsideBothCenter),
        )
        classify = ClassifyDiscoveredCellsByGeographicAreas(cellConverter)
    }

    private fun discoveredCell(cell: CanonicalCell, trustStatus: TrustStatus) = DiscoveredCell(
        cell = cell,
        trustStatus = trustStatus,
        firstDiscoveredAt = timestamp,
        lastObservedAt = timestamp,
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    @Test
    fun `returns one status per area, in the same order as the input list`() {
        val statuses = classify(emptyList(), siblings)

        assertEquals(listOf(squareA, squareB), statuses.map { it.area })
    }

    @Test
    fun `no discoveries means every area is not visited`() {
        val statuses = classify(emptyList(), siblings)

        assertTrue(statuses.all { !it.visited })
    }

    @Test
    fun `a discovery inside one area marks only that area visited, its sibling stays unvisited`() {
        val statuses = classify(listOf(discoveredCell(insideACell, TrustStatus.NON_CERTIFIED)), siblings)

        val areaA = statuses.single { it.area == squareA }
        val areaB = statuses.single { it.area == squareB }
        assertTrue(areaA.visited)
        assertFalse("an unrelated sibling area must never become visited", areaB.visited)
    }

    @Test
    fun `discoveries inside both areas simultaneously mark both visited independently`() {
        val statuses = classify(
            listOf(
                discoveredCell(insideACell, TrustStatus.NON_CERTIFIED),
                discoveredCell(insideBCell, TrustStatus.CERTIFIED),
            ),
            siblings,
        )

        val areaA = statuses.single { it.area == squareA }
        val areaB = statuses.single { it.area == squareB }
        assertTrue(areaA.visited)
        assertTrue(areaA.nonCertifiedPresent)
        assertTrue(areaB.visited)
        assertTrue(areaB.certifiedPresent)
    }

    @Test
    fun `a discovery outside every candidate area is skipped, never an error, and marks nothing visited`() {
        val statuses = classify(listOf(discoveredCell(outsideBothCell, TrustStatus.NON_CERTIFIED)), siblings)

        assertTrue(statuses.all { !it.visited })
    }

    @Test
    fun `an empty candidate area list produces an empty result, even with real discoveries`() {
        val statuses = classify(listOf(discoveredCell(insideACell, TrustStatus.NON_CERTIFIED)), emptyList())

        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `a point outside every candidate area's own bounding box -- not merely outside its polygon -- is still correctly skipped`() {
        // Exercises the new GeographicBounds.contains prefilter's own short-circuit path directly
        // (outsideBothCell sits at lat=10/lon=10, entirely outside both squareA's and squareB's own
        // bounds, not just outside their polygons) -- proves the prefilter is a safe narrowing, not
        // a source of a missed match, by getting the same correct "not visited" result as without it.
        val statuses = classify(listOf(discoveredCell(outsideBothCell, TrustStatus.CERTIFIED)), siblings)

        assertTrue(statuses.none { it.visited })
    }

    private fun testArea(id: String, south: Double, west: Double, north: Double, east: Double): GeographicArea {
        val ring = listOf(
            Coordinate(south, west),
            Coordinate(south, east),
            Coordinate(north, east),
            Coordinate(north, west),
        )
        val geometry = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(ring))))
        return GeographicArea(
            id = id,
            type = GeographicAreaType.ADMIN_1,
            displayName = id,
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "test",
            sourceProvenance = GeographicAreaProvenance.WORLD_DISCOVERY_ZONE,
            parentId = "country:TEST",
        )
    }
}

private class FakeMultiAreaCellConverter(private val centers: Map<CanonicalCell, Coordinate>) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell = error("not expected to be called in this test")
    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> = error("not expected to be called in this test")
    override fun cellCenter(cell: CanonicalCell): Coordinate = centers[cell] ?: error("No fake center configured for $cell")
    override fun isValidCell(cell: CanonicalCell): Boolean = error("not expected to be called in this test")
}
