package com.cedervs.worlddiscovery.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact scenario Codex's re-review flagged: [promoteAncestorPresence] already fixes the
 * aggregate whole-Country [GeographicAreaVisitedStatus], but `MapReadState.franceComponents` (the
 * PER-COMPONENT statuses that actually drive Country-level rendering/click-navigation) is derived
 * independently and was never corrected — meaning France could resolve as logically visited while
 * still rendering nothing and accepting no clicks, because every real country component's own
 * `geoBoundaries` geometry disagreed with the visited child's OSM geometry near a shared border.
 * [promoteAncestorComponentPresence] fixes this at the correct, single, geometrically-matched
 * component — never all components, never a hardcoded index.
 */
class PromoteAncestorComponentPresenceTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private fun testAdminArea(id: String, parentId: String, ring: List<Coordinate>): GeographicArea {
        val geometry = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(ring))))
        return GeographicArea(
            id = id,
            type = GeographicAreaType.ADMIN_1,
            displayName = id,
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.WORLD_DISCOVERY_ZONE,
            parentId = parentId,
        )
    }

    // A France-like country with two spatially separate components: "mainland" (a large square) and
    // "corsica-like" (a small, distant square) -- mirroring the real mainland/Corsica/Guiana shape,
    // but deliberately synthetic/local rather than the real bundled geometry.
    private val mainlandPolygon = GeographicPolygon(listOf(ring(-5.0 to 41.0, 10.0 to 41.0, 10.0 to 51.0, -5.0 to 51.0)))
    private val corsicaLikePolygon = GeographicPolygon(listOf(ring(80.0 to 1.0, 81.0 to 1.0, 81.0 to 2.0, 80.0 to 2.0)))
    private val franceLikeArea = run {
        val geometry = GeographicMultiPolygon(listOf(mainlandPolygon, corsicaLikePolygon))
        GeographicArea(
            id = "country:X",
            type = GeographicAreaType.COUNTRY,
            displayName = "Testland",
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.EXTERNAL_REFERENCE_DATASET,
        )
    }
    private val mainlandComponent = franceLikeArea.components()[0]
    private val corsicaLikeComponent = franceLikeArea.components()[1]

    private fun notVisitedComponentStatus(component: GeographicAreaComponent) =
        GeographicAreaComponentVisitedStatus(component, visited = false, certifiedPresent = false, nonCertifiedPresent = false)

    private fun visitedChild(area: GeographicArea, certified: Boolean = false, nonCertified: Boolean = true) =
        GeographicAreaVisitedStatus(area = area, visited = true, certifiedPresent = certified, nonCertifiedPresent = nonCertified)

    private fun notVisitedChild(area: GeographicArea) =
        GeographicAreaVisitedStatus(area = area, visited = false, certifiedPresent = false, nonCertifiedPresent = false)

    // A region entirely inside the mainland component's own bounds/polygon -- its representative
    // point (the vertex-average of this single-polygon ring) sits well inside mainland, nowhere near
    // corsicaLikeComponent.
    private val regionInsideMainland = testAdminArea("admin1:MAINLAND-REGION", "country:X", ring(0.0 to 44.0, 1.0 to 44.0, 1.0 to 45.0, 0.0 to 45.0))

    @Test
    fun `no visited children promotes nothing, every component returned unchanged`() {
        val componentStatuses = listOf(notVisitedComponentStatus(mainlandComponent), notVisitedComponentStatus(corsicaLikeComponent))

        val result = promoteAncestorComponentPresence(childStatuses = emptyList(), componentStatuses = componentStatuses)

        assertEquals(componentStatuses, result)
    }

    @Test
    fun `a visited region promotes exactly the ONE real component its own geometry falls within`() {
        val componentStatuses = listOf(notVisitedComponentStatus(mainlandComponent), notVisitedComponentStatus(corsicaLikeComponent))

        val result = promoteAncestorComponentPresence(childStatuses = listOf(visitedChild(regionInsideMainland)), componentStatuses = componentStatuses)

        val mainlandResult = result.single { it.component == mainlandComponent }
        assertTrue("the mainland component must be promoted -- the visited region's own geometry falls inside it", mainlandResult.visited)
    }

    @Test
    fun `a visited region never promotes an unrelated, geographically distant component`() {
        val componentStatuses = listOf(notVisitedComponentStatus(mainlandComponent), notVisitedComponentStatus(corsicaLikeComponent))

        val result = promoteAncestorComponentPresence(childStatuses = listOf(visitedChild(regionInsideMainland)), componentStatuses = componentStatuses)

        val corsicaResult = result.single { it.component == corsicaLikeComponent }
        assertFalse(
            "an unrelated, geographically distant component must never be promoted just because SOME component was",
            corsicaResult.visited,
        )
    }

    @Test
    fun `an unvisited region promotes nothing`() {
        val componentStatuses = listOf(notVisitedComponentStatus(mainlandComponent), notVisitedComponentStatus(corsicaLikeComponent))

        val result = promoteAncestorComponentPresence(childStatuses = listOf(notVisitedChild(regionInsideMainland)), componentStatuses = componentStatuses)

        assertEquals(componentStatuses, result)
    }

    @Test
    fun `certified and non-certified presence are promoted independently at component level, never upgraded into each other`() {
        val componentStatuses = listOf(notVisitedComponentStatus(mainlandComponent))
        val certifiedRegion = visitedChild(regionInsideMainland, certified = true, nonCertified = false)

        val result = promoteAncestorComponentPresence(childStatuses = listOf(certifiedRegion), componentStatuses = componentStatuses)

        val promoted = result.single()
        assertTrue(promoted.visited)
        assertTrue(promoted.certifiedPresent)
        assertFalse("a certified-only child must never upgrade the component's own non-certified presence", promoted.nonCertifiedPresent)
    }

    @Test
    fun `a component's own already-true certified presence survives promotion unioned with a non-certified child`() {
        val alreadyCertifiedComponent = GeographicAreaComponentVisitedStatus(mainlandComponent, visited = true, certifiedPresent = true, nonCertifiedPresent = false)
        val nonCertifiedRegion = visitedChild(regionInsideMainland, certified = false, nonCertified = true)

        val result = promoteAncestorComponentPresence(childStatuses = listOf(nonCertifiedRegion), componentStatuses = listOf(alreadyCertifiedComponent))

        val promoted = result.single()
        assertTrue(promoted.certifiedPresent)
        assertTrue(promoted.nonCertifiedPresent)
    }

    // ==========================================================================================
    // The exact Codex-specified end-to-end synthetic scenario: child/admin geometry contains a
    // discovered cell, the parent Country's own AGGREGATE geometry (via classification against
    // franceLikeArea directly) does NOT -- proving the fix holds even where the raw per-component
    // classification would have failed on the same real cell.
    // ==========================================================================================

    @Test
    fun `end-to-end -- a region visited by a cell the Country's own raw component classification would have missed still promotes the correct single component`() {
        // A synthetic "disagreement" region: its own real geometry is INSIDE mainland (so its
        // representative point correctly matches mainlandComponent), but simulating the scenario
        // where the ORIGINAL discovered cell that visited it fell just outside mainland's own
        // geoBoundaries-equivalent polygon (never re-tested here -- the region's own [visited] flag
        // is taken as already true, exactly as ClassifyDiscoveredCellsByGeographicAreas would have
        // produced against the region's OWN, different, OSM-equivalent geometry).
        val disagreeingRegion = visitedChild(regionInsideMainland)
        val rawComponentStatuses = listOf(
            notVisitedComponentStatus(mainlandComponent), // raw Country-geometry classification found nothing here
            notVisitedComponentStatus(corsicaLikeComponent),
        )

        val promoted = promoteAncestorComponentPresence(childStatuses = listOf(disagreeingRegion), componentStatuses = rawComponentStatuses)

        val mainland = promoted.single { it.component == mainlandComponent }
        val corsica = promoted.single { it.component == corsicaLikeComponent }
        assertTrue("the correct single component must become visited/renderable", mainland.visited)
        assertFalse("the unrelated component must remain unvisited", corsica.visited)
    }

    @Test
    fun `findVerifiedInteriorPoint returns a point that is actually inside the area's own geometry`() {
        val point = findVerifiedInteriorPoint(regionInsideMainland)

        assertTrue(point != null && PointInPolygonClassifier.contains(regionInsideMainland.geometry, point))
    }

    @Test
    fun `a child area with no findable verified interior point promotes nothing, never a crash or a guess`() {
        // A degenerate, zero-area "region" (three collinear points) -- findVerifiedInteriorPoint
        // itself must return null for this (see InteriorPointFinderTest's own dedicated coverage);
        // this test proves promoteAncestorComponentPresence handles that null safely rather than
        // propagating an exception or falling back to an unverified guess.
        val degenerateRegion = testAdminArea("admin1:DEGENERATE", "country:X", ring(0.0 to 44.0, 1.0 to 44.0, 2.0 to 44.0))
        val componentStatuses = listOf(notVisitedComponentStatus(mainlandComponent), notVisitedComponentStatus(corsicaLikeComponent))

        val result = promoteAncestorComponentPresence(childStatuses = listOf(visitedChild(degenerateRegion)), componentStatuses = componentStatuses)

        assertTrue("a degenerate child with no verified interior point must promote nothing at all", result.none { it.visited })
    }

    // ==========================================================================================
    // End-to-end, classification-DRIVEN fixture -- the child's own GeographicAreaVisitedStatus
    // comes from real ClassifyDiscoveredCellsByGeographicArea classification of a discovered cell,
    // not a manually pre-constructed "visited" status -- exercising the genuine
    // discovered cell -> child classification -> child visited status -> Country component
    // promotion path Codex's review asked to be preferred over pure promotion-only fixtures.
    // ==========================================================================================

    @Test
    fun `end-to-end -- a real discovered cell classified into a visited region promotes the correct Country component via the real classification pipeline`() {
        val cellConverter = object : H3CellConverter {
            override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell = error("not expected to be called in this test")
            override fun cellBoundary(cell: CanonicalCell): List<Coordinate> = error("not expected to be called in this test")
            override fun cellCenter(cell: CanonicalCell): Coordinate = Coordinate(latitude = 44.5, longitude = 0.5) // inside regionInsideMainland
            override fun isValidCell(cell: CanonicalCell): Boolean = true
        }
        val discoveredCell = DiscoveredCell(
            cell = CanonicalCell(h3Index = "test-cell-mainland-region", resolution = 12),
            trustStatus = TrustStatus.NON_CERTIFIED,
            firstDiscoveredAt = java.time.Instant.parse("2026-01-01T10:00:00Z"),
            lastObservedAt = java.time.Instant.parse("2026-01-01T10:00:00Z"),
            provenance = Provenance.OBSERVED,
            engineVersion = 1,
            h3Resolution = 12,
        )

        // Genuine classification -- the region's own visited status is DERIVED, not hand-constructed.
        val realChildStatus = ClassifyDiscoveredCellsByGeographicArea(cellConverter)(listOf(discoveredCell), regionInsideMainland)
        assertTrue("the fixture cell must genuinely classify as visiting the region", realChildStatus.visited)

        val componentStatuses = listOf(notVisitedComponentStatus(mainlandComponent), notVisitedComponentStatus(corsicaLikeComponent))
        val promoted = promoteAncestorComponentPresence(childStatuses = listOf(realChildStatus), componentStatuses = componentStatuses)

        val mainland = promoted.single { it.component == mainlandComponent }
        val corsica = promoted.single { it.component == corsicaLikeComponent }
        assertTrue("the real classification-driven visited region must promote the correct mainland component", mainland.visited)
        assertTrue("the unrelated component must remain unvisited", corsica.visited.not())
        assertTrue(mainland.nonCertifiedPresent)
    }
}
