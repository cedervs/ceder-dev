package com.cedervs.worlddiscovery.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real Room database (in-memory) via Robolectric, so this runs as a fast JVM
 * unit test (`./gradlew :core-database:testDebugUnitTest`) without requiring a device/emulator.
 */
@RunWith(RobolectricTestRunner::class)
class RoomDiscoveredCellRepositoryTest {

    private lateinit var database: WorldDiscoveryDatabase
    private lateinit var repository: RoomDiscoveredCellRepository

    private val parisCell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WorldDiscoveryDatabase::class.java,
        ).build()
        repository = RoomDiscoveredCellRepository(database.discoveredCellDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `find returns null when the cell has never been discovered`() = runTest {
        assertNull(repository.find(parisCell, TrustStatus.NON_CERTIFIED))
    }

    @Test
    fun `upsert then find round-trips all fields correctly`() = runTest {
        val discoveredCell = DiscoveredCell(
            cell = parisCell,
            trustStatus = TrustStatus.NON_CERTIFIED,
            firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
            lastObservedAt = Instant.parse("2026-01-02T10:00:00Z"),
            provenance = Provenance.OBSERVED,
            engineVersion = 1,
            h3Resolution = 12,
        )

        repository.upsert(discoveredCell)
        val loaded = repository.find(parisCell, TrustStatus.NON_CERTIFIED)

        assertEquals(discoveredCell, loaded)
    }

    @Test
    fun `writing the same cell twice does not create a duplicate row`() = runTest {
        val first = DiscoveredCell(
            cell = parisCell,
            trustStatus = TrustStatus.NON_CERTIFIED,
            firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
            lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
            provenance = Provenance.OBSERVED,
            engineVersion = 1,
            h3Resolution = 12,
        )
        val second = first.copy(lastObservedAt = Instant.parse("2026-01-05T10:00:00Z"))

        repository.upsert(first)
        repository.upsert(second)

        assertEquals(1, database.discoveredCellDao().count())
        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), repository.find(parisCell, TrustStatus.NON_CERTIFIED)?.lastObservedAt)
    }

    @Test
    fun `Certified and Non-certified records for the same cell are stored independently`() = runTest {
        val nonCertified = DiscoveredCell(
            cell = parisCell,
            trustStatus = TrustStatus.NON_CERTIFIED,
            firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
            lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
            provenance = Provenance.OBSERVED,
            engineVersion = 1,
            h3Resolution = 12,
        )
        val certified = nonCertified.copy(trustStatus = TrustStatus.CERTIFIED)

        repository.upsert(nonCertified)
        repository.upsert(certified)

        assertEquals(2, database.discoveredCellDao().count())
        assertEquals(nonCertified, repository.find(parisCell, TrustStatus.NON_CERTIFIED))
        assertEquals(certified, repository.find(parisCell, TrustStatus.CERTIFIED))
    }

    @Test
    fun `repository works entirely offline against a local database`() = runTest {
        // No network/backend dependency exists anywhere in this call chain — the assertion is
        // that this completes at all using only an in-memory local database.
        repository.upsert(
            DiscoveredCell(
                cell = parisCell,
                trustStatus = TrustStatus.NON_CERTIFIED,
                firstDiscoveredAt = Instant.now(),
                lastObservedAt = Instant.now(),
                provenance = Provenance.OBSERVED,
                engineVersion = 1,
                h3Resolution = 12,
            ),
        )

        assertEquals(1, database.discoveredCellDao().count())
    }
}
