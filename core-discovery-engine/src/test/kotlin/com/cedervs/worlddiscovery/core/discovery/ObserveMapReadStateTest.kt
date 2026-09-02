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

    private lateinit var repository: CountingReadStateRepository
    private lateinit var observeMapReadState: ObserveMapReadState
    private lateinit var franceArea: GeographicArea

    @Before
    fun setUp() {
        repository = CountingReadStateRepository()
        val cellConverter = FakeReadStateCellConverter(mapOf(parisCell to parisBoundary), mapOf(parisCell to parisCenter))
        franceArea = loadFranceGeographicAreaReference()
        observeMapReadState = ObserveMapReadState(
            repository,
            cellConverter,
            ClassifyDiscoveredCellsByGeographicArea(cellConverter),
            ClassifyDiscoveredCellsByGeographicAreaComponents(cellConverter),
            franceArea,
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
    fun `one repository observeAll subscription feeds both geometries and France visited status`() = runTest {
        repository.emit(listOf(discoveredParis))

        observeMapReadState().first()

        // If a future change reintroduced two separate subscriptions (one per derived output),
        // this call count would be 2 -- the exact regression this test guards against, matching
        // the same "single canonical emission, multiple derived outputs" architecture requirement
        // already established for this read-side once before.
        assertEquals(1, repository.observeAllCallCount)
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
