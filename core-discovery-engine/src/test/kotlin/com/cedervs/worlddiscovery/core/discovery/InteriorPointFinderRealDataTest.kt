package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real-artifact regression proof behind [promoteAncestorComponentPresence]'s own doc-comment
 * claim: for every one of this app's 13 bundled metropolitan France `ADMIN_1` regions,
 * [findVerifiedInteriorPoint] finds a point that is both genuinely inside that region's own real
 * geometry AND matches exactly one real France Country component — never zero, never more than one.
 *
 * **This is a verified property of THIS bundled data, not a worldwide guarantee** — see
 * [findVerifiedInteriorPoint]'s and [promoteAncestorComponentPresence]'s own doc comments for why a
 * future genuinely fragmented `ADMIN_1` (a region itself split across two disjoint Country
 * components, which does not occur among France's own current regions) is out of scope for this
 * matching strategy as it exists today.
 *
 * No personal GPS data anywhere here — every coordinate is either loaded from the checked-in
 * reference artifacts themselves or derived purely from their own geometry.
 */
class InteriorPointFinderRealDataTest {

    private lateinit var franceArea: GeographicArea
    private lateinit var administrativeAreas: FranceAdministrativeAreas
    private lateinit var mainlandComponent: GeographicAreaComponent
    private lateinit var corsicaComponent: GeographicAreaComponent

    @Before
    fun setUp() {
        franceArea = loadFranceGeographicAreaReference()
        administrativeAreas = loadFranceAdministrativeAreas(franceArea)
        val components = franceArea.components()
        // Component order is the generator's own documented contract (mainland, then Corsica, then
        // French Guiana) -- see GenerateFranceReference.kt / GeographicAreaReferenceTest.
        mainlandComponent = components[0]
        corsicaComponent = components[1]
    }

    @Test
    fun `every one of the 13 bundled metropolitan regions has a findable, verified interior point`() {
        assertEquals(13, administrativeAreas.regions.size)
        for (region in administrativeAreas.regions) {
            val point = findVerifiedInteriorPoint(region)
            assertNotNull("expected a verified interior point for ${region.id}", point)
        }
    }

    @Test
    fun `every region's verified interior point is genuinely inside that region's own real geometry`() {
        for (region in administrativeAreas.regions) {
            val point = findVerifiedInteriorPoint(region)!!
            assertTrue(
                "the verified interior point for ${region.id} must actually be inside its own geometry",
                PointInPolygonClassifier.contains(region.geometry, point),
            )
        }
    }

    @Test
    fun `every region's verified interior point matches EXACTLY ONE real France Country component`() {
        for (region in administrativeAreas.regions) {
            val point = findVerifiedInteriorPoint(region)!!
            val matchingComponents = franceArea.components().filter { component ->
                PointInPolygonClassifier.contains(component.polygon, point)
            }
            assertEquals(
                "expected ${region.id}'s verified point to match exactly one Country component, matched ${matchingComponents.map { it.componentIndex }}",
                1,
                matchingComponents.size,
            )
        }
    }

    @Test
    fun `Corse maps to the Corsica component -- every other metropolitan region maps to the mainland component`() {
        val corse = administrativeAreas.regions.single { it.id == "admin1:FR-20R" }
        val corsePoint = findVerifiedInteriorPoint(corse)!!
        assertTrue("Corse must map to the Corsica component", PointInPolygonClassifier.contains(corsicaComponent.polygon, corsePoint))
        assertTrue("Corse must NOT map to the mainland component", !PointInPolygonClassifier.contains(mainlandComponent.polygon, corsePoint))

        val otherRegions = administrativeAreas.regions.filter { it.id != "admin1:FR-20R" }
        assertEquals(12, otherRegions.size)
        for (region in otherRegions) {
            val point = findVerifiedInteriorPoint(region)!!
            assertTrue(
                "${region.id} must map to the mainland component",
                PointInPolygonClassifier.contains(mainlandComponent.polygon, point),
            )
            assertTrue(
                "${region.id} must NOT map to the Corsica component",
                !PointInPolygonClassifier.contains(corsicaComponent.polygon, point),
            )
        }
    }

    @Test
    fun `every one of the 96 bundled metropolitan departments has a findable, verified interior point`() {
        assertEquals(96, administrativeAreas.departments.size)
        for (department in administrativeAreas.departments) {
            val point = findVerifiedInteriorPoint(department)
            assertNotNull("expected a verified interior point for ${department.id}", point)
        }
    }

    @Test
    fun `every department's verified interior point is genuinely inside that department's own real geometry`() {
        for (department in administrativeAreas.departments) {
            val point = findVerifiedInteriorPoint(department)!!
            assertTrue(
                "the verified interior point for ${department.id} must actually be inside its own geometry",
                PointInPolygonClassifier.contains(department.geometry, point),
            )
        }
    }

    @Test
    fun `every department's verified interior point matches EXACTLY ONE real France Country component`() {
        for (department in administrativeAreas.departments) {
            val point = findVerifiedInteriorPoint(department)!!
            val matchingComponents = franceArea.components().filter { component ->
                PointInPolygonClassifier.contains(component.polygon, point)
            }
            assertEquals(
                "expected ${department.id}'s verified point to match exactly one Country component, matched ${matchingComponents.map { it.componentIndex }}",
                1,
                matchingComponents.size,
            )
        }
    }

    @Test
    fun `Corse's two departments map to the Corsica component -- every other department maps to the mainland component`() {
        val corseDepartmentIds = setOf("admin2:FR-2A", "admin2:FR-2B")
        val corseDepartments = administrativeAreas.departments.filter { it.id in corseDepartmentIds }
        assertEquals(2, corseDepartments.size)
        for (department in corseDepartments) {
            val point = findVerifiedInteriorPoint(department)!!
            assertTrue("${department.id} must map to the Corsica component", PointInPolygonClassifier.contains(corsicaComponent.polygon, point))
            assertTrue("${department.id} must NOT map to the mainland component", !PointInPolygonClassifier.contains(mainlandComponent.polygon, point))
        }

        val otherDepartments = administrativeAreas.departments.filter { it.id !in corseDepartmentIds }
        assertEquals(94, otherDepartments.size)
        for (department in otherDepartments) {
            val point = findVerifiedInteriorPoint(department)!!
            assertTrue(
                "${department.id} must map to the mainland component",
                PointInPolygonClassifier.contains(mainlandComponent.polygon, point),
            )
            assertTrue(
                "${department.id} must NOT map to the Corsica component",
                !PointInPolygonClassifier.contains(corsicaComponent.polygon, point),
            )
        }
    }

    @Test
    fun `end-to-end -- promoteAncestorComponentPresence, driven by the real interior-point finder, promotes exactly the mainland component for a visited mainland region`() {
        val nouvelleAquitaine = administrativeAreas.regions.single { it.id == "admin1:FR-NAQ" }
        val visitedStatus = GeographicAreaVisitedStatus(nouvelleAquitaine, visited = true, certifiedPresent = false, nonCertifiedPresent = true)
        val rawComponentStatuses = franceArea.components().map { component ->
            GeographicAreaComponentVisitedStatus(component, visited = false, certifiedPresent = false, nonCertifiedPresent = false)
        }

        val promoted = promoteAncestorComponentPresence(childStatuses = listOf(visitedStatus), componentStatuses = rawComponentStatuses)

        val mainland = promoted.single { it.component.componentIndex == mainlandComponent.componentIndex }
        assertTrue("the real mainland component must be promoted", mainland.visited)
        val everyOtherComponent = promoted.filter { it.component.componentIndex != mainlandComponent.componentIndex }
        assertTrue("no other real component may be promoted", everyOtherComponent.none { it.visited })
    }
}
