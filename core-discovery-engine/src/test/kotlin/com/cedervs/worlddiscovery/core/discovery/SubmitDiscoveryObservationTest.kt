package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubmitDiscoveryObservationTest {

    private lateinit var converter: FakeH3CellConverter
    private lateinit var repository: FakeDiscoveredCellRepository
    private lateinit var submitObservation: SubmitDiscoveryObservation

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

    @Before
    fun setUp() {
        converter = FakeH3CellConverter(mapOf(paris to parisCell))
        repository = FakeDiscoveredCellRepository()
        submitObservation = SubmitDiscoveryObservation(converter, repository)
    }

    @Test
    fun `submitting the same coordinate twice does not create two discovered cells`() = runTest {
        val timestamp = Instant.parse("2026-01-01T10:00:00Z")

        submitObservation(paris, timestamp, Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        submitObservation(paris, timestamp.plusSeconds(60), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)

        assertEquals(1, repository.all().size)
    }

    @Test
    fun `two observations mapped to the same H3 cell merge into one record`() = runTest {
        val nearbyCoordinate = Coordinate(latitude = 48.85661, longitude = 2.35221)
        converter.mapping = converter.mapping + (nearbyCoordinate to parisCell)

        submitObservation(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        submitObservation(
            nearbyCoordinate,
            Instant.parse("2026-01-01T10:05:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(1, repository.all().size)
    }

    @Test
    fun `firstDiscoveredAt remains the earliest submission timestamp`() = runTest {
        submitObservation(paris, Instant.parse("2026-01-05T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        val result = submitObservation(
            paris,
            Instant.parse("2026-01-01T10:00:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), result.firstDiscoveredAt)
    }

    @Test
    fun `lastObservedAt advances to the latest submission timestamp`() = runTest {
        submitObservation(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        val result = submitObservation(
            paris,
            Instant.parse("2026-01-05T10:00:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), result.lastObservedAt)
    }

    @Test
    fun `trust status is preserved across merges and Certified stays independent from Non-certified`() = runTest {
        submitObservation(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)
        submitObservation(paris, Instant.parse("2026-01-02T10:00:00Z"), Provenance.OBSERVED, TrustStatus.CERTIFIED)

        assertEquals(2, repository.all().size)
        val statuses = repository.all().map { it.trustStatus }.toSet()
        assertEquals(setOf(TrustStatus.NON_CERTIFIED, TrustStatus.CERTIFIED), statuses)
    }

    @Test
    fun `engine version and H3 resolution are recorded on the persisted cell`() = runTest {
        val result = submitObservation(
            paris,
            Instant.parse("2026-01-01T10:00:00Z"),
            Provenance.OBSERVED,
            TrustStatus.NON_CERTIFIED,
        )

        assertEquals(DiscoveryEngineVersion.CURRENT, result.engineVersion)
        assertEquals(DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION, result.h3Resolution)
    }

    @Test
    fun `repository and use case operate fully in memory without any backend dependency`() = runTest {
        submitObservation(paris, Instant.parse("2026-01-01T10:00:00Z"), Provenance.OBSERVED, TrustStatus.NON_CERTIFIED)

        assertTrue(repository.all().isNotEmpty())
    }
}

private class FakeH3CellConverter(var mapping: Map<Coordinate, CanonicalCell>) : H3CellConverter {
    override fun toCanonicalCell(coordinate: Coordinate): CanonicalCell =
        mapping[coordinate] ?: error("No fake mapping configured for $coordinate")
}

private class FakeDiscoveredCellRepository : DiscoveredCellRepository {
    private val storage = mutableMapOf<Pair<CanonicalCell, TrustStatus>, DiscoveredCell>()

    override suspend fun find(cell: CanonicalCell, trustStatus: TrustStatus): DiscoveredCell? =
        storage[cell to trustStatus]

    override suspend fun upsert(discoveredCell: DiscoveredCell) {
        storage[discoveredCell.cell to discoveredCell.trustStatus] = discoveredCell
    }

    fun all(): List<DiscoveredCell> = storage.values.toList()
}
