package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Product-level tests for the France Country -> Region (`ADMIN_1`) -> Department (`ADMIN_2`)
 * hierarchy, against the REAL bundled artifacts ([loadFranceAdministrativeAreas]) rather than
 * synthetic geometry — this is what actually proves the generated data is correct (originally just
 * Nouvelle-Aquitaine/Haute-Vienne, now the full metropolitan Region/Department coverage completed in
 * Phase FH-1), not just that the generic classifier mechanism works (see
 * [ClassifyDiscoveredCellsByGeographicAreasTest] for that).
 *
 * Every coordinate below is a well-known, public city-center location — never a personal GPS trace,
 * per this round's own explicit instruction:
 * - Limoges (45.8336, 1.2611) — inside Haute-Vienne (department 87) and Nouvelle-Aquitaine.
 * - Poitiers (46.5802, 0.3404) — inside Vienne (department 86) and Nouvelle-Aquitaine, but NOT
 *   Haute-Vienne — the "unrelated department" fixture.
 * - Paris (48.8566, 2.3522) — inside Île-de-France, entirely outside Nouvelle-Aquitaine, and (since
 *   Phase FH-1) inside its own loaded department (75) — the "unrelated region" fixture.
 */
class FranceAdministrativeHierarchyTest {

    private val limogesCell = CanonicalCell(h3Index = "test-cell-limoges", resolution = 12)
    private val limogesCenter = Coordinate(latitude = 45.8336, longitude = 1.2611)
    private val poitiersCell = CanonicalCell(h3Index = "test-cell-poitiers", resolution = 12)
    private val poitiersCenter = Coordinate(latitude = 46.5802, longitude = 0.3404)
    private val parisCell = CanonicalCell(h3Index = "test-cell-paris", resolution = 12)
    private val parisCenter = Coordinate(latitude = 48.8566, longitude = 2.3522)

    private val timestamp = Instant.parse("2026-01-01T10:00:00Z")

    private lateinit var cellConverter: FakeHierarchyCellConverter
    private lateinit var classifyArea: ClassifyDiscoveredCellsByGeographicArea
    private lateinit var classifyAreas: ClassifyDiscoveredCellsByGeographicAreas
    private lateinit var franceArea: GeographicArea
    private lateinit var administrativeAreas: FranceAdministrativeAreas

    @Before
    fun setUp() {
        cellConverter = FakeHierarchyCellConverter(
            mapOf(limogesCell to limogesCenter, poitiersCell to poitiersCenter, parisCell to parisCenter),
        )
        classifyArea = ClassifyDiscoveredCellsByGeographicArea(cellConverter)
        classifyAreas = ClassifyDiscoveredCellsByGeographicAreas(cellConverter)
        franceArea = loadFranceGeographicAreaReference()
        administrativeAreas = loadFranceAdministrativeAreas(franceArea)
    }

    private fun discoveredCell(cell: CanonicalCell) = DiscoveredCell(
        cell = cell,
        trustStatus = TrustStatus.NON_CERTIFIED,
        firstDiscoveredAt = timestamp,
        lastObservedAt = timestamp,
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    @Test
    fun `a cell inside Nouvelle-Aquitaine classifies to Nouvelle-Aquitaine`() {
        val statuses = classifyAreas(listOf(discoveredCell(limogesCell)), administrativeAreas.regions)

        val naq = statuses.single { it.area.id == "admin1:FR-NAQ" }
        assertTrue(naq.visited)
    }

    @Test
    fun `a cell inside Haute-Vienne classifies to Haute-Vienne`() {
        val statuses = classifyAreas(listOf(discoveredCell(limogesCell)), administrativeAreas.departments)

        val hauteVienne = statuses.single { it.area.id == "admin2:FR-87" }
        assertTrue(hauteVienne.visited)
    }

    @Test
    fun `presence in Haute-Vienne derives France, Nouvelle-Aquitaine and Haute-Vienne all visited, independently`() {
        val cells = listOf(discoveredCell(limogesCell))

        val franceStatus = classifyArea(cells, franceArea)
        val regionStatuses = classifyAreas(cells, administrativeAreas.regions)
        val departmentStatuses = classifyAreas(cells, administrativeAreas.departments)

        assertTrue("France itself must be visited", franceStatus.visited)
        assertTrue("Nouvelle-Aquitaine must be visited", regionStatuses.single { it.area.id == "admin1:FR-NAQ" }.visited)
        assertTrue("Haute-Vienne must be visited", departmentStatuses.single { it.area.id == "admin2:FR-87" }.visited)
    }

    @Test
    fun `an unrelated region stays unvisited when the only presence is in Haute-Vienne`() {
        val statuses = classifyAreas(listOf(discoveredCell(limogesCell)), administrativeAreas.regions)

        val ileDeFrance = statuses.single { it.area.id == "admin1:FR-IDF" }
        assertFalse("Île-de-France must not become visited from a Haute-Vienne discovery alone", ileDeFrance.visited)
        // Every other loaded region besides Nouvelle-Aquitaine itself must also stay unvisited.
        val visitedRegionIds = statuses.filter { it.visited }.map { it.area.id }
        assertEquals(listOf("admin1:FR-NAQ"), visitedRegionIds)
    }

    @Test
    fun `an unrelated department stays unvisited when the only presence is in Haute-Vienne`() {
        val statuses = classifyAreas(listOf(discoveredCell(limogesCell)), administrativeAreas.departments)

        val gironde = statuses.single { it.area.id == "admin2:FR-33" }
        assertFalse("Gironde must not become visited from a Haute-Vienne discovery alone", gironde.visited)
        val visitedDepartmentIds = statuses.filter { it.visited }.map { it.area.id }
        assertEquals(listOf("admin2:FR-87"), visitedDepartmentIds)
    }

    @Test
    fun `a presence in Vienne (a different Nouvelle-Aquitaine department) visits the region but not Haute-Vienne`() {
        val regionStatuses = classifyAreas(listOf(discoveredCell(poitiersCell)), administrativeAreas.regions)
        val departmentStatuses = classifyAreas(listOf(discoveredCell(poitiersCell)), administrativeAreas.departments)

        assertTrue(regionStatuses.single { it.area.id == "admin1:FR-NAQ" }.visited)
        assertTrue(departmentStatuses.single { it.area.id == "admin2:FR-86" }.visited)
        assertFalse(departmentStatuses.single { it.area.id == "admin2:FR-87" }.visited)
    }

    @Test
    fun `a presence entirely outside Nouvelle-Aquitaine (Paris) visits Paris but no Nouvelle-Aquitaine department`() {
        // Paris now has its own loaded department (admin2:FR-75, Phase FH-1) -- a Paris discovery
        // must visit exactly that department and skip every Nouvelle-Aquitaine one cleanly (see
        // ClassifyDiscoveredCellsByGeographicAreas' own doc comment: "no match" is not an error),
        // never a crash and never a false positive.
        val departmentStatuses = classifyAreas(listOf(discoveredCell(parisCell)), administrativeAreas.departments)

        val paris = departmentStatuses.single { it.area.id == "admin2:FR-75" }
        assertTrue("Paris department must be visited", paris.visited)
        val visitedDepartmentIds = departmentStatuses.filter { it.visited }.map { it.area.id }
        assertEquals(listOf("admin2:FR-75"), visitedDepartmentIds)
    }

    @Test
    fun `every loaded region carries country France as its parent`() {
        administrativeAreas.regions.forEach { region ->
            assertEquals("country:FR", region.parentId)
            assertEquals(GeographicAreaType.ADMIN_1, region.type)
        }
    }

    @Test
    fun `every loaded department parentId resolves to one of the loaded regions`() {
        val regionIds = administrativeAreas.regions.map { it.id }.toSet()
        administrativeAreas.departments.forEach { department ->
            assertEquals(GeographicAreaType.ADMIN_2, department.type)
            assertTrue(
                "Department ${department.id} has parentId ${department.parentId} which is not a loaded region",
                department.parentId in regionIds,
            )
        }
    }

    @Test
    fun `Nouvelle-Aquitaine's own 12 departments still carry admin1 FR-NAQ as parent`() {
        val naqDepartmentIds = setOf(
            "admin2:FR-16", "admin2:FR-17", "admin2:FR-19", "admin2:FR-23", "admin2:FR-24",
            "admin2:FR-33", "admin2:FR-40", "admin2:FR-47", "admin2:FR-64", "admin2:FR-79",
            "admin2:FR-86", "admin2:FR-87",
        )
        val naqDepartments = administrativeAreas.departments.filter { it.id in naqDepartmentIds }

        assertEquals(12, naqDepartments.size)
        naqDepartments.forEach { department -> assertEquals("admin1:FR-NAQ", department.parentId) }
    }

    @Test
    fun `every metropolitan region has at least one department child`() {
        val departmentsByParent = administrativeAreas.departments.groupBy { it.parentId }

        administrativeAreas.regions.forEach { region ->
            val children = departmentsByParent[region.id].orEmpty()
            assertTrue("Region ${region.id} (${region.displayName}) has no loaded department children", children.isNotEmpty())
        }
    }

    @Test
    fun `region and department ids are each globally unique`() {
        val regionIds = administrativeAreas.regions.map { it.id }
        val departmentIds = administrativeAreas.departments.map { it.id }

        assertEquals("Region ids must be unique", regionIds.size, regionIds.toSet().size)
        assertEquals("Department ids must be unique", departmentIds.size, departmentIds.toSet().size)
    }

    @Test
    fun `exactly 13 metropolitan regions and 96 metropolitan departments are loaded`() {
        assertEquals(13, administrativeAreas.regions.size)
        assertEquals(96, administrativeAreas.departments.size)
    }
}

private class FakeHierarchyCellConverter(private val centers: Map<CanonicalCell, Coordinate>) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell = error("not expected to be called in this test")
    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> = error("not expected to be called in this test")
    override fun cellCenter(cell: CanonicalCell): Coordinate = centers[cell] ?: error("No fake center configured for $cell")
    override fun isValidCell(cell: CanonicalCell): Boolean = error("not expected to be called in this test")
}
