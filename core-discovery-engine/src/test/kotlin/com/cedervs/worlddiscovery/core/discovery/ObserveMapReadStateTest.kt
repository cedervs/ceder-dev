package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObserveMapReadStateTest {

    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
    private val parisCenter = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val parisBoundary = listOf(
        Coordinate(latitude = 48.8570, longitude = 2.3520),
        Coordinate(latitude = 48.8565, longitude = 2.3525),
        Coordinate(latitude = 48.8560, longitude = 2.3520),
    )

    private val discoveredParis = DiscoveredCell(
        cell = parisCell,
        trustStatus = TrustStatus.NON_CERTIFIED,
        firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
        lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    // Synthetic coordinates used to test corridor derivation without encoding a real user route.
    // originCenter is a verified interior point of the real, bundled Haute-Vienne (department 87)
    // polygon -- computed via findVerifiedInteriorPoint against the actual loaded boundary data,
    // never a real named place's coordinate -- so the real Country/Region/Department promotion
    // pipeline genuinely exercises real point-in-polygon containment end-to-end through the real
    // ObserveMapReadState wiring, not just the isolated classify/promote functions (see
    // FranceAdministrativeHierarchyTest/PromoteAncestorPresenceTest for those).
    private val originCell = CanonicalCell(h3Index = "8caaaa00000000a1", resolution = 12)
    private val originCenter = Coordinate(latitude = 45.9190235, longitude = 1.2698745)
    private val originBoundary = listOf(
        Coordinate(latitude = 45.9194, longitude = 1.2697),
        Coordinate(latitude = 45.9189, longitude = 1.2702),
        Coordinate(latitude = 45.9184, longitude = 1.2697),
    )
    private val discoveredOrigin = DiscoveredCell(
        cell = originCell,
        trustStatus = TrustStatus.NON_CERTIFIED,
        firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
        lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    // destinationCenter is a purely synthetic offset from originCenter (not any real place, not
    // required to fall inside any administrative polygon) -- used only for the route-derivation
    // tests below (see DiscoveredRouteTest.kt for the exhaustive pure-function coverage; this file
    // only proves the real ObserveMapReadState wiring).
    private val destinationCell = CanonicalCell(h3Index = "8caaaa00000000a2", resolution = 12)
    private val destinationCenter = Coordinate(latitude = 46.0190235, longitude = 1.3698745)
    private val destinationBoundary = listOf(
        Coordinate(latitude = 46.0194, longitude = 1.3696),
        Coordinate(latitude = 46.0189, longitude = 1.3702),
        Coordinate(latitude = 46.0184, longitude = 1.3696),
    )
    private val discoveredDestination = DiscoveredCell(
        cell = destinationCell,
        trustStatus = TrustStatus.NON_CERTIFIED,
        // 10 minutes after origin -- a synthetic ~13.6 km offset, giving ~23 m/s implied speed,
        // comfortably under the 55 m/s plausibility ceiling.
        firstDiscoveredAt = Instant.parse("2026-01-01T10:10:00Z"),
        lastObservedAt = Instant.parse("2026-01-01T10:10:00Z"),
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    private lateinit var repository: CountingReadStateRepository
    private lateinit var observeMapReadState: ObserveMapReadState
    private lateinit var franceArea: GeographicArea
    private lateinit var administrativeAreas: FranceAdministrativeAreas

    // A fake, not the real H3JavaGridTraversal -- this file's own job is proving the
    // ObserveMapReadState WIRING is correct (one subscription, routeSegments derived from the same
    // validCells snapshot, etc.), never re-proving real H3 grid-topology behavior itself (see
    // DiscoveredRouteTest.kt's own real-H3-fixture tests for that). Configured below to report
    // origin/destination as structurally adjacent purely so this file's wiring tests have a real
    // segment to observe, exactly like FakeReadStateCellConverter stands in for real H3CellConverter
    // here.
    private lateinit var gridTraversal: FakeReadStateGridTraversal

    @Before
    fun setUp() {
        repository = CountingReadStateRepository()
        val cellConverter = FakeReadStateCellConverter(
            mapOf(parisCell to parisBoundary, originCell to originBoundary, destinationCell to destinationBoundary),
            mapOf(parisCell to parisCenter, originCell to originCenter, destinationCell to destinationCenter),
        )
        gridTraversal = FakeReadStateGridTraversal(
            mapOf((originCell to destinationCell) to listOf(originCell, destinationCell)),
        )
        franceArea = loadFranceGeographicAreaReference()
        administrativeAreas = loadFranceAdministrativeAreas(franceArea)
        observeMapReadState = ObserveMapReadState(
            repository,
            cellConverter,
            ClassifyDiscoveredCellsByGeographicArea(cellConverter),
            ClassifyDiscoveredCellsByGeographicAreaComponents(cellConverter),
            ClassifyDiscoveredCellsByGeographicAreas(cellConverter),
            franceArea,
            administrativeAreas,
            gridTraversal,
        )
    }

    @Test
    fun `an empty repository produces empty geometries and a not-visited France status`() = runTest {
        val state = observeMapReadState().first()

        assertTrue(state.geometries.isEmpty())
        assertEquals(false, state.franceVisitedStatus.visited)
    }

    @Test
    fun `a discovered cell inside France produces both its geometry and a visited France status from one emission`() = runTest {
        repository.emit(listOf(discoveredParis))

        val state = observeMapReadState().first()

        assertEquals(1, state.geometries.size)
        assertEquals(parisBoundary, state.geometries.single().boundary)
        assertTrue(state.franceVisitedStatus.visited)
    }

    @Test
    fun `an invalid H3 cell alongside valid cells never reaches cell center or boundary conversion, and the valid cells still produce a correct read state`() = runTest {
        // The invalid cell is deliberately NOT registered in the fake converter's boundary/center
        // maps below -- if either isValidCell's filtering were skipped for ANY derivation (fine
        // geometries, France status, or France components), that derivation would call cellBoundary
        // or cellCenter on it and the fake would throw "No fake ... configured", failing this test.
        val invalidCell = CanonicalCell(h3Index = "invalid", resolution = 12)
        val invalidDiscoveredCell = discoveredParis.copy(cell = invalidCell)
        repository.emit(listOf(discoveredParis, invalidDiscoveredCell))

        val state = observeMapReadState().first()

        assertEquals(1, state.geometries.size)
        assertEquals(parisCell, state.geometries.single().cell.cell)
        assertTrue(state.franceVisitedStatus.visited)
    }

    @Test
    fun `franceComponents mirrors franceArea components(), one status per component, from the same emission`() = runTest {
        repository.emit(listOf(discoveredParis))

        val state = observeMapReadState().first()

        assertEquals(franceArea.components().size, state.franceComponents.size)
        assertTrue(state.franceComponents.any { it.visited })
    }

    @Test
    fun `franceAdmin1Statuses covers every loaded region and marks Ile-de-France visited from a Paris discovery, from the same emission`() = runTest {
        repository.emit(listOf(discoveredParis))

        val state = observeMapReadState().first()

        assertEquals(administrativeAreas.regions.size, state.franceAdmin1Statuses.size)
        val idf = state.franceAdmin1Statuses.single { it.area.id == "admin1:FR-IDF" }
        assertTrue(idf.visited)
        // A Paris discovery must not spuriously mark an unrelated region visited.
        val corse = state.franceAdmin1Statuses.single { it.area.id == "admin1:FR-20R" }
        assertTrue("an unrelated region must stay unvisited", !corse.visited)
    }

    @Test
    fun `franceAdmin2Statuses covers every loaded department and marks only Paris visited from a Paris discovery`() = runTest {
        // Paris (Ile-de-France) has its own loaded department (admin2:FR-75, Phase FH-1) -- a Paris
        // discovery must visit exactly that one and leave every other loaded department, including
        // all of Nouvelle-Aquitaine's, unvisited.
        repository.emit(listOf(discoveredParis))

        val state = observeMapReadState().first()

        assertEquals(administrativeAreas.departments.size, state.franceAdmin2Statuses.size)
        val visitedIds = state.franceAdmin2Statuses.filter { it.visited }.map { it.area.id }
        assertEquals(listOf("admin2:FR-75"), visitedIds)
    }

    @Test
    fun `end-to-end through the real pipeline -- a discovery inside Haute-Vienne visits Haute-Vienne, Nouvelle-Aquitaine and France, all from one emission`() = runTest {
        repository.emit(listOf(discoveredOrigin))

        val state = observeMapReadState().first()

        assertTrue("France must be visited (promoted from Haute-Vienne, or independently, or both)", state.franceVisitedStatus.visited)
        assertTrue("Nouvelle-Aquitaine must be visited", state.franceAdmin1Statuses.single { it.area.id == "admin1:FR-NAQ" }.visited)
        assertTrue("Haute-Vienne must be visited", state.franceAdmin2Statuses.single { it.area.id == "admin2:FR-87" }.visited)
        // promoteAncestorComponentPresence wiring: the mainland Country COMPONENT (what actually
        // drives Country-level rendering/click-navigation) must also read visited -- not just the
        // aggregate franceVisitedStatus above.
        assertTrue("the mainland France component must be visited too, not just the aggregate Country status", state.franceComponents.any { it.visited })
    }

    @Test
    fun `end-to-end -- Nouvelle-Aquitaine visited via a Haute-Vienne discovery does not spuriously visit an unrelated sibling department`() = runTest {
        repository.emit(listOf(discoveredOrigin))

        val state = observeMapReadState().first()

        val vienne = state.franceAdmin2Statuses.single { it.area.id == "admin2:FR-86" }
        assertTrue(
            "Vienne (a sibling department, never visited itself) must stay unvisited even though its " +
                "parent region Nouvelle-Aquitaine is now visited -- promotion is child-to-parent only",
            !vienne.visited,
        )
    }

    @Test
    fun `one repository observeAll subscription feeds both geometries and France visited status`() = runTest {
        repository.emit(listOf(discoveredParis))

        observeMapReadState().first()

        // If a future change reintroduced two separate subscriptions (one per derived output),
        // this call count would be 2 -- the exact regression this test guards against, matching
        // the same "single canonical emission, multiple derived outputs" architecture requirement
        // already established for this read-side once before.
        assertEquals(1, repository.observeAllCallCount)
    }

    // ==============================================================================================
    // routeSegments -- end-to-end through the real ObserveMapReadState wiring (see
    // DiscoveredRouteTest.kt for exhaustive coverage of deriveRouteSegments/clipRouteSegmentsToArea
    // themselves as pure functions).
    // ==============================================================================================

    @Test
    fun `routeSegments is empty when fewer than 2 cells are discovered`() = runTest {
        repository.emit(listOf(discoveredOrigin))

        val state = observeMapReadState().first()

        assertTrue(state.routeSegments.isEmpty())
    }

    @Test
    fun `routeSegments derives a real segment end-to-end from a genuine origin-to-destination discovery, from the same emission`() = runTest {
        repository.emit(listOf(discoveredOrigin, discoveredDestination))

        val state = observeMapReadState().first()

        assertEquals(1, state.routeSegments.size)
        assertEquals(2, state.routeSegments.single().points.size)
        assertEquals(
            listOf(originCenter, destinationCenter),
            state.routeSegments.single().points.map { it.coordinate },
        )
    }

    @Test
    fun `F -- deriving routeSegments never calls DiscoveredCellRepository upsert -- the route is never written back as a second discovery truth`() = runTest {
        // CountingReadStateRepository.upsert throws unconditionally -- if route derivation, or any
        // other MapReadState field, ever called it, this test (and every other test in this file)
        // would fail immediately rather than silently passing.
        repository.emit(listOf(discoveredOrigin, discoveredDestination))

        val state = observeMapReadState().first()

        assertTrue(state.routeSegments.isNotEmpty())
        assertEquals(
            "the underlying repository's own stored snapshot must be byte-for-byte unchanged by reading routeSegments",
            listOf(discoveredOrigin, discoveredDestination),
            repository.observeAll().first(),
        )
    }

    @Test
    fun `an invalid H3 cell never reaches route derivation either -- validCells filtering covers routeSegments too`() = runTest {
        val invalidCell = CanonicalCell(h3Index = "invalid", resolution = 12)
        val invalidDiscoveredCell = discoveredDestination.copy(cell = invalidCell)
        repository.emit(listOf(discoveredOrigin, invalidDiscoveredCell))

        // Must not throw ("No fake center configured for invalid") and must simply treat the
        // invalid cell as absent, exactly like every other derivation in this class.
        val state = observeMapReadState().first()

        assertTrue(state.routeSegments.isEmpty())
    }
}

private class CountingReadStateRepository : DiscoveredCellRepository {
    private val state = MutableStateFlow<List<DiscoveredCell>>(emptyList())
    var observeAllCallCount = 0
        private set

    fun emit(cells: List<DiscoveredCell>) {
        state.value = cells
    }

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        error("not expected to be called in this test")

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        error("not expected to be called in this test")
    }

    override fun observeAll(): Flow<List<DiscoveredCell>> {
        observeAllCallCount++
        return state
    }
}

private class FakeReadStateCellConverter(
    private val boundaries: Map<CanonicalCell, List<Coordinate>>,
    private val centers: Map<CanonicalCell, Coordinate>,
) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        error("not expected to be called in this test")

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> =
        boundaries[cell] ?: error("No fake boundary configured for $cell")

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        centers[cell] ?: error("No fake center configured for $cell")

    override fun isValidCell(cell: CanonicalCell): Boolean = boundaries.containsKey(cell)
}

private class FakeReadStateGridTraversal(private val paths: Map<Pair<CanonicalCell, CanonicalCell>, List<CanonicalCell>?>) : H3GridTraversal {
    override fun pathBetween(origin: CanonicalCell, destination: CanonicalCell): List<CanonicalCell>? {
        if (origin == destination) return listOf(origin)
        val key = origin to destination
        val reverseKey = destination to origin
        return when {
            paths.containsKey(key) -> paths.getValue(key)
            paths.containsKey(reverseKey) -> paths.getValue(reverseKey)?.reversed()
            else -> null // no configured structural relationship -- conservative default: not adjacent.
        }
    }
}
