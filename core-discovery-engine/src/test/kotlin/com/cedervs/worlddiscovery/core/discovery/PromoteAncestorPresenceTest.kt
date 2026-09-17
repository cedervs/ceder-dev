package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoteAncestorPresenceTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private fun testArea(id: String, type: GeographicAreaType, parentId: String?, ring: List<Coordinate>): GeographicArea {
        val geometry = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(ring))))
        return GeographicArea(
            id = id,
            type = type,
            displayName = id,
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.WORLD_DISCOVERY_ZONE,
            parentId = parentId,
        )
    }

    private fun visited(area: GeographicArea, certified: Boolean = false, nonCertified: Boolean = true) =
        GeographicAreaVisitedStatus(area = area, visited = true, certifiedPresent = certified, nonCertifiedPresent = nonCertified)

    private fun notVisited(area: GeographicArea) =
        GeographicAreaVisitedStatus(area = area, visited = false, certifiedPresent = false, nonCertifiedPresent = false)

    private val regionA = testArea("admin1:A", GeographicAreaType.ADMIN_1, "country:X", ring(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0))
    private val regionB = testArea("admin1:B", GeographicAreaType.ADMIN_1, "country:X", ring(2.0 to 0.0, 3.0 to 0.0, 3.0 to 1.0, 2.0 to 1.0))
    private val departmentA1 = testArea("admin2:A1", GeographicAreaType.ADMIN_2, "admin1:A", ring(0.0 to 0.0, 0.5 to 0.0, 0.5 to 0.5, 0.0 to 0.5))
    private val departmentA2 = testArea("admin2:A2", GeographicAreaType.ADMIN_2, "admin1:A", ring(0.5 to 0.5, 1.0 to 0.5, 1.0 to 1.0, 0.5 to 1.0))

    @Test
    fun `a parent with no matching children is returned completely unchanged`() {
        val parentStatuses = listOf(notVisited(regionA), notVisited(regionB))

        val result = promoteAncestorPresence(childStatuses = emptyList(), parentStatuses = parentStatuses)

        assertEquals(parentStatuses, result)
    }

    @Test
    fun `a visited child promotes its own real parent to visited`() {
        val children = listOf(visited(departmentA1), notVisited(departmentA2))
        val parents = listOf(notVisited(regionA), notVisited(regionB))

        val result = promoteAncestorPresence(childStatuses = children, parentStatuses = parents)

        val promotedRegionA = result.single { it.area == regionA }
        assertTrue("regionA must be promoted to visited -- departmentA1 (its real child) is visited", promotedRegionA.visited)
    }

    @Test
    fun `a visited child never promotes an unrelated sibling parent`() {
        val children = listOf(visited(departmentA1), notVisited(departmentA2))
        val parents = listOf(notVisited(regionA), notVisited(regionB))

        val result = promoteAncestorPresence(childStatuses = children, parentStatuses = parents)

        val promotedRegionB = result.single { it.area == regionB }
        assertFalse("regionB has no visited child and must stay unvisited", promotedRegionB.visited)
    }

    @Test
    fun `a visited PARENT never promotes any of its children -- promotion is strictly upward, never downward`() {
        val children = listOf(notVisited(departmentA1), notVisited(departmentA2))
        val parents = listOf(visited(regionA))

        // Deliberately calling promoteAncestorPresence in the WRONG direction (as if regionA's own
        // visited status could promote its children) to prove it does not -- this function only
        // ever promotes parentStatuses from childStatuses, never the reverse, regardless of which
        // lists are passed as which argument at a given call site.
        val stillChildren = promoteAncestorPresence(childStatuses = parents, parentStatuses = children)

        assertTrue(
            "a visited region must never promote its own departments to visited",
            stillChildren.all { !it.visited },
        )
    }

    @Test
    fun `certified and non-certified presence are promoted independently, never collapsed`() {
        val certifiedChild = visited(departmentA1, certified = true, nonCertified = false)
        val parents = listOf(notVisited(regionA))

        val result = promoteAncestorPresence(childStatuses = listOf(certifiedChild), parentStatuses = parents)

        val promoted = result.single()
        assertTrue(promoted.visited)
        assertTrue(promoted.certifiedPresent)
        assertFalse(promoted.nonCertifiedPresent)
    }

    @Test
    fun `a parent already visited on its own raw classification keeps its own certified-non-certified flags unioned with its children's`() {
        val alreadyCertifiedParent = GeographicAreaVisitedStatus(regionA, visited = true, certifiedPresent = true, nonCertifiedPresent = false)
        val nonCertifiedChild = visited(departmentA1, certified = false, nonCertified = true)

        val result = promoteAncestorPresence(childStatuses = listOf(nonCertifiedChild), parentStatuses = listOf(alreadyCertifiedParent))

        val promoted = result.single()
        assertTrue(promoted.visited)
        assertTrue("the parent's own real certified presence must survive promotion", promoted.certifiedPresent)
        assertTrue("the child's non-certified presence must still be promoted up", promoted.nonCertifiedPresent)
    }

    // ==========================================================================================
    // The exact scenario Codex's review flagged: Country classification (geoBoundaries) and
    // Region/Department classification (OpenStreetMap) can genuinely disagree near a shared
    // border. A discovered cell can fall inside a CHILD's own (OSM) geometry while its
    // corresponding PARENT's own (geoBoundaries) geometry does not contain it -- yet the required
    // product invariant is "visited child implies visited ancestor," always.
    // ==========================================================================================

    @Test
    fun `synthetic cross-dataset disagreement -- child geometry contains the point, parent geometry does not -- ancestor still resolves VISITED`() {
        // A deliberately disjoint "parent" polygon (simulating geoBoundaries disagreeing with the
        // real OSM child geometry near a shared border) -- the point that visits the child would
        // classify as NOT inside this parent polygon at all if tested directly.
        val disagreeingParent = testArea(
            "admin1:DISAGREE",
            GeographicAreaType.ADMIN_1,
            "country:X",
            ring(10.0 to 10.0, 11.0 to 10.0, 11.0 to 11.0, 10.0 to 11.0), // far away from the child below
        )
        val realChildInsideVisitedArea = testArea(
            "admin2:REAL",
            GeographicAreaType.ADMIN_2,
            "admin1:DISAGREE",
            ring(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0),
        )

        // Raw classification (as ClassifyDiscoveredCellsByGeographicArea/Areas would independently
        // produce): the child is visited (its own OSM-equivalent geometry contains the discovery),
        // but the parent's own raw classification says NOT visited (its geoBoundaries-equivalent
        // geometry -- deliberately disjoint here -- does not contain the same point).
        val childRawStatus = visited(realChildInsideVisitedArea)
        val parentRawStatus = notVisited(disagreeingParent)

        val promoted = promoteAncestorPresence(childStatuses = listOf(childRawStatus), parentStatuses = listOf(parentRawStatus))

        val promotedParent = promoted.single()
        assertTrue(
            "the required product invariant -- visited child implies visited ancestor -- must hold " +
                "even when the parent's OWN raw classification geometry disagrees with the child's",
            promotedParent.visited,
        )
        assertTrue(promotedParent.nonCertifiedPresent)
    }
}
