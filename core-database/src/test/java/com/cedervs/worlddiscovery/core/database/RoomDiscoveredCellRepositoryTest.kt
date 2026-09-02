package com.cedervs.worlddiscovery.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `observeAll is empty before any cell has been discovered`() = runTest {
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun `observeAll reflects a cell already written before the flow was collected`() = runTest {
        repository.upsert(
            DiscoveredCell(
                cell = parisCell,
                trustStatus = TrustStatus.NON_CERTIFIED,
                firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
                lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
                provenance = Provenance.OBSERVED,
                engineVersion = 1,
                h3Resolution = 12,
            ),
        )

        val result = repository.observeAll().first()

        assertEquals(1, result.size)
        assertEquals(parisCell, result.single().cell)
    }

    @Test
    fun `observeAll re-emits the same live collector after a later upsert`() = runTest {
        // A single, still-active collection must see both the pre-upsert and post-upsert state —
        // collecting first().first() twice (once before, once after) would just start two
        // independent collections and prove nothing about live re-emission. The channel is the
        // synchronization: receive() genuinely suspends until Room's own invalidation tracking
        // (not a manual refresh) delivers the next value, so this fails for real if it doesn't.
        //
        // This whole block runs on a real dispatcher (Dispatchers.Default), not runTest's virtual
        // TestDispatcher. Room's InvalidationTracker re-queries and delivers the second emission
        // on its own real background query executor (Room.inMemoryDatabaseBuilder uses a genuine
        // ExecutorService, not the coroutine-test scheduler) — that work is entirely invisible to
        // runTest's TestCoroutineScheduler. Racing it against a *virtual-time* withTimeout(...)
        // is what caused the original failure: the scheduler only sees the timeout's own
        // delay(5_000) as "known" pending work, so as soon as the test coroutine looks idle it
        // fast-forwards straight to that deadline — without ever waiting for Room's real thread to
        // actually deliver the value. Raising the 5_000 figure would not fix this: the scheduler
        // still jumps to whatever the deadline is, near-instantly, regardless of its size. Moving
        // the collector and both waits onto Dispatchers.Default takes this synchronization out of
        // virtual time entirely, so the 5s timeout becomes a genuine wall-clock safety net against
        // a real hang, not a race against the scheduler's own idle-detection.
        withContext(Dispatchers.Default) {
            val emissionChannel = Channel<List<DiscoveredCell>>(Channel.UNLIMITED)
            val collectorJob = launch {
                repository.observeAll().collect { value -> emissionChannel.trySend(value) }
            }

            val initial = withTimeout(5_000) { emissionChannel.receive() }
            assertTrue(initial.isEmpty())

            repository.upsert(
                DiscoveredCell(
                    cell = parisCell,
                    trustStatus = TrustStatus.NON_CERTIFIED,
                    firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
                    lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
                    provenance = Provenance.OBSERVED,
                    engineVersion = 1,
                    h3Resolution = 12,
                ),
            )

            val afterUpsert = withTimeout(5_000) { emissionChannel.receive() }
            collectorJob.cancel()

            assertEquals(1, afterUpsert.size)
            assertEquals(parisCell, afterUpsert.single().cell)
        }
    }

    @Test
    fun `observeAll keeps Certified and Non-certified as two separate entries`() = runTest {
        val nonCertified = DiscoveredCell(
            cell = parisCell,
            trustStatus = TrustStatus.NON_CERTIFIED,
            firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
            lastObservedAt = Instant.parse("2026-01-01T10:00:00Z"),
            provenance = Provenance.OBSERVED,
            engineVersion = 1,
            h3Resolution = 12,
        )
        repository.upsert(nonCertified)
        repository.upsert(nonCertified.copy(trustStatus = TrustStatus.CERTIFIED))

        val result = repository.observeAll().first { it.size == 2 }

        assertEquals(setOf(TrustStatus.NON_CERTIFIED, TrustStatus.CERTIFIED), result.map { it.trustStatus }.toSet())
    }
}
