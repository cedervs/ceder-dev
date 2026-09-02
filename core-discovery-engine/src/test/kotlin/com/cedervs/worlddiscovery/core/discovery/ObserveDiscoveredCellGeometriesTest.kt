package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveDiscoveredCellGeometriesTest {

    private lateinit var repository: FakeGeometryDiscoveredCellRepository
    private lateinit var converter: FakeGeometryH3CellConverter
    private lateinit var observeGeometries: ObserveDiscoveredCellGeometries

    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)
    private val parisBoundary = listOf(
        Coordinate(latitude = 48.8570, longitude = 2.3520),
        Coordinate(latitude = 48.8565, longitude = 2.3525),
        Coordinate(latitude = 48.8560, longitude = 2.3520),
    )
    private val nonCertifiedParis = DiscoveredCell(
        cell = parisCell,
        trustStatus = TrustStatus.NON_CERTIFIED,
        firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
        lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
        provenance = Provenance.OBSERVED,
        engineVersion = 1,
        h3Resolution = 12,
    )

    @Before
    fun setUp() {
        repository = FakeGeometryDiscoveredCellRepository()
        converter = FakeGeometryH3CellConverter(mapOf(parisCell to parisBoundary))
        observeGeometries = ObserveDiscoveredCellGeometries(repository, converter)
    }

    @Test
    fun `an empty repository produces an empty geometry list`() = runTest {
        val result = observeGeometries().first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `each discovered cell is paired with its boundary from the converter`() = runTest {
        repository.emit(listOf(nonCertifiedParis))

        val result = observeGeometries().first()

        assertEquals(1, result.size)
        val geometry = result.single()
        assertEquals(nonCertifiedParis, geometry.cell)
        assertEquals(parisBoundary, geometry.boundary)
    }

    @Test
    fun `a cell that fails isValidCell is skipped rather than passed to cellBoundary`() = runTest {
        val corrupted = CanonicalCell(h3Index = "corrupted", resolution = 12)
        val corruptedDiscoveredCell = nonCertifiedParis.copy(cell = corrupted)
        repository.emit(listOf(nonCertifiedParis, corruptedDiscoveredCell))

        val result = observeGeometries().first()

        assertEquals(listOf(parisCell), result.map { it.cell.cell })
    }

    @Test
    fun `an unexpected failure from cellBoundary propagates rather than being silently absorbed`() = runTest {
        converter.throwUnexpectedFor = parisCell
        repository.emit(listOf(nonCertifiedParis))

        val flow = observeGeometries()
        var caught: Throwable? = null
        val job = launch {
            try {
                flow.first()
            } catch (t: Throwable) {
                caught = t
            }
        }
        advanceUntilIdle()
        job.join()

        assertTrue(caught is IllegalStateException)
    }

    @Test
    fun `re-emits automatically when the repository emits a new snapshot`() = runBlocking {
        repository.emit(listOf(nonCertifiedParis))
        assertEquals(1, observeGeometries().first().size)

        repository.emit(emptyList())
        assertEquals(0, observeGeometries().first().size)
    }
}

private class FakeGeometryH3CellConverter(private val boundaries: Map<CanonicalCell, List<Coordinate>>) : H3CellConverter {
    /** Simulates a genuinely unexpected failure (a programming defect, a native H3 error, etc.)
     * from a cell that *is* otherwise valid — distinct from an invalid h3Index, which
     * [isValidCell] alone is responsible for catching before this is ever reached. */
    var throwUnexpectedFor: CanonicalCell? = null

    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        error("not expected to be called in this test")

    // Mirrors the real converters' contract: a pure, never-throwing predicate. Any cell not in
    // the fake's known-boundaries map is treated as invalid/corrupted, same as a real malformed
    // h3Index would be.
    override fun isValidCell(cell: CanonicalCell): Boolean = boundaries.containsKey(cell)

    override fun cellBoundary(cell: CanonicalCell): List<Coordinate> {
        if (cell == throwUnexpectedFor) error("simulated unexpected H3 failure")
        return boundaries[cell] ?: error("No fake boundary configured for $cell")
    }

    override fun cellCenter(cell: CanonicalCell): Coordinate =
        error("not expected to be called in this test")
}

private class FakeGeometryDiscoveredCellRepository : DiscoveredCellRepository {
    private val state = MutableStateFlow<List<DiscoveredCell>>(emptyList())

    fun emit(cells: List<DiscoveredCell>) {
        state.value = cells
    }

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        error("not expected to be called in this test")

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        error("not expected to be called in this test")
    }

    override fun observeAll(): Flow<List<DiscoveredCell>> = state
}
