package com.cedervs.worlddiscovery.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationProcessingState
import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationRecord
import com.cedervs.worlddiscovery.core.discovery.trajectory.ClaimToken
import com.cedervs.worlddiscovery.core.discovery.trajectory.ClaimTokenGenerator
import com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryBufferRetentionPolicy
import com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationSource
import com.cedervs.worlddiscovery.core.discovery.trajectory.buildObservationDedupKey
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the real [TrajectoryBufferDatabase] (in-memory) via Robolectric, matching
 * `RoomDiscoveredCellRepositoryTest`'s established pattern for this module.
 *
 * **On concurrency testing (Codex review round):** the claim/lease tests below exercise the atomic
 * `claimPending` transaction's *invariants* deterministically (single-threaded, one call at a time)
 * rather than attempting a genuine multi-thread race against Robolectric's in-memory SQLite — a
 * true concurrent-claim race is inherently timing-dependent and would make this suite flaky rather
 * than reliably prove anything. What is tested and does prove real safety: that two *sequential*
 * claim calls never return overlapping rows, that a stale token can never terminate a lease it no
 * longer owns, that a row can never be observed in an impossible `PROCESSING`-without-lease-metadata
 * state, and — new in this round — that the claim's result is scoped correctly *even if the token
 * generator were to (incorrectly) produce the same token twice*, via a deliberately-broken
 * [ClaimTokenGenerator] fixture.
 */
@RunWith(RobolectricTestRunner::class)
class RoomTrajectoryObservationBufferRepositoryTest {

    private lateinit var database: TrajectoryBufferDatabase
    private lateinit var repository: RoomTrajectoryObservationBufferRepository

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TrajectoryBufferDatabase::class.java,
        ).build()
        repository = RoomTrajectoryObservationBufferRepository(database.bufferedObservationDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun record(
        elapsedRealtimeNanos: Long,
        source: TrajectoryObservationSource = TrajectoryObservationSource.FOREGROUND,
        processSessionId: String = "session-1",
        clockDomainId: String = "domain-1",
        receivedAt: Instant = Instant.parse("2026-01-01T10:00:00Z"),
    ) = BufferedObservationRecord(
        id = 0L,
        dedupKey = buildObservationDedupKey(clockDomainId, elapsedRealtimeNanos, receivedAt.toEpochMilli(), paris, 10f),
        source = source,
        processSessionId = processSessionId,
        elapsedRealtimeClockDomainId = clockDomainId,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        coordinate = paris,
        providerTimeEpochMillis = receivedAt.toEpochMilli(),
        receivedAt = receivedAt,
        accuracyMeters = 10f,
        speedMetersPerSecond = null,
        speedAccuracyMetersPerSecond = null,
        bearingDegrees = null,
        bearingAccuracyDegrees = null,
        provider = "fused",
        isMockLocation = false,
        batchId = null,
        indexInBatch = null,
        processingState = BufferedObservationProcessingState.PENDING,
        claimToken = null,
        processingStartedAt = null,
        processedAt = null,
    )

    // ==============================================================================================
    // Insert / idempotent dedup.
    // ==============================================================================================

    @Test
    fun `insert returns true for a genuinely new observation`() = runTest {
        assertTrue(repository.insert(record(elapsedRealtimeNanos = 1L)))
        assertEquals(1, repository.count())
    }

    @Test
    fun `inserting the same dedupKey twice is idempotent -- second insert is a no-op, not an error`() = runTest {
        val first = record(elapsedRealtimeNanos = 1L)
        val second = record(elapsedRealtimeNanos = 1L) // same clock domain/elapsedRealtimeNanos -> same dedupKey

        val firstInserted = repository.insert(first)
        val secondInserted = repository.insert(second)

        assertTrue(firstInserted)
        assertFalse("a genuine redelivery must not create a second row", secondInserted)
        assertEquals(1, repository.count())
    }

    @Test
    fun `two observations with different elapsedRealtimeNanos are both inserted`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.insert(record(elapsedRealtimeNanos = 2L))

        assertEquals(2, repository.count())
    }

    // ==============================================================================================
    // Ordering (read-only peek).
    // ==============================================================================================

    @Test
    fun `pendingObservationsOrderedBySequence returns rows in insertion order`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 3L)) // inserted first, despite the higher "3"
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.insert(record(elapsedRealtimeNanos = 2L))

        val ordered = repository.pendingObservationsOrderedBySequence()

        assertEquals(listOf(3L, 1L, 2L), ordered.map { it.elapsedRealtimeNanos })
    }

    @Test
    fun `pendingObservationsOrderedBySequence respects an explicit limit`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.insert(record(elapsedRealtimeNanos = 2L))
        repository.insert(record(elapsedRealtimeNanos = 3L))

        val limited = repository.pendingObservationsOrderedBySequence(limit = 2)

        assertEquals(2, limited.size)
        assertEquals(listOf(1L, 2L), limited.map { it.elapsedRealtimeNanos })
    }

    @Test
    fun `pendingObservationsOrderedBySequence never returns a PROCESSED row`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        val claimed = repository.claimPendingObservations(limit = 10, claimedAt = Instant.now())
        repository.markProcessed(claimed.observations.map { it.id }, claimed.claimToken, Instant.now())

        assertTrue(repository.pendingObservationsOrderedBySequence().isEmpty())
    }

    // ==============================================================================================
    // Atomic claim/lease (Codex review blocking fix: replaces the unsafe two-step
    // pendingObservationsOrderedBySequence + markProcessing protocol).
    // ==============================================================================================

    @Test
    fun `claimPendingObservations transitions rows to PROCESSING with a generated token and the given timestamp`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))

        val batch = repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T10:00:00Z"))

        assertEquals(1, batch.observations.size)
        val row = batch.observations.single()
        assertEquals(BufferedObservationProcessingState.PROCESSING, row.processingState)
        assertEquals(batch.claimToken, row.claimToken)
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), row.processingStartedAt)
    }

    @Test
    fun `two successive claims produce different tokens`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.insert(record(elapsedRealtimeNanos = 2L))

        val first = repository.claimPendingObservations(limit = 1, claimedAt = Instant.now())
        val second = repository.claimPendingObservations(limit = 1, claimedAt = Instant.now())

        assertNotEquals(first.claimToken, second.claimToken)
    }

    @Test
    fun `two successive claims never return overlapping rows`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.insert(record(elapsedRealtimeNanos = 2L))
        repository.insert(record(elapsedRealtimeNanos = 3L))

        val firstClaim = repository.claimPendingObservations(limit = 2, claimedAt = Instant.now())
        val secondClaim = repository.claimPendingObservations(limit = 2, claimedAt = Instant.now())

        val firstIds = firstClaim.observations.map { it.id }.toSet()
        val secondIds = secondClaim.observations.map { it.id }.toSet()

        assertEquals(2, firstClaim.observations.size)
        assertEquals(1, secondClaim.observations.size) // only 1 PENDING row was left after the first claim took 2
        assertTrue("a claim must never return a row already owned by a different, still-active claim", firstIds.intersect(secondIds).isEmpty())
    }

    @Test
    fun `a claim's result is correctly scoped to its own selected ids even if the token generator reuses a token`() = runTest {
        // Regression test for the exact scenario Codex identified: a repository whose token
        // generator is broken (always returns the same token) must still never let a later claim's
        // result include an earlier claim's rows -- the DAO-level id+token scoping (not token
        // uniqueness alone) is what must guarantee this.
        val fixedToken = ClaimToken("always-the-same-token")
        val brokenGenerator = ClaimTokenGenerator { fixedToken }
        val repoWithBrokenGenerator = RoomTrajectoryObservationBufferRepository(database.bufferedObservationDao(), brokenGenerator)

        repoWithBrokenGenerator.insert(record(elapsedRealtimeNanos = 1L))
        repoWithBrokenGenerator.insert(record(elapsedRealtimeNanos = 2L))

        val firstClaim = repoWithBrokenGenerator.claimPendingObservations(limit = 1, claimedAt = Instant.now())
        val secondClaim = repoWithBrokenGenerator.claimPendingObservations(limit = 1, claimedAt = Instant.now())

        assertEquals(fixedToken, firstClaim.claimToken)
        assertEquals(fixedToken, secondClaim.claimToken)
        assertEquals(
            "even with a reused token, the second claim's batch must contain only the row it itself selected",
            1,
            secondClaim.observations.size,
        )
        assertNotEquals(
            "the two claims, despite sharing a token, must never report the same row",
            firstClaim.observations.single().id,
            secondClaim.observations.single().id,
        )
    }

    @Test
    fun `claimPendingObservations respects an explicit limit, leaving the rest PENDING`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.insert(record(elapsedRealtimeNanos = 2L))
        repository.insert(record(elapsedRealtimeNanos = 3L))

        val batch = repository.claimPendingObservations(limit = 1, claimedAt = Instant.now())

        assertEquals(1, batch.observations.size)
        assertEquals(2, repository.pendingObservationsOrderedBySequence().count { it.processingState == BufferedObservationProcessingState.PENDING })
    }

    @Test
    fun `claimPendingObservations claims the oldest rows first, deterministically`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 3L))
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.insert(record(elapsedRealtimeNanos = 2L))

        val batch = repository.claimPendingObservations(limit = 2, claimedAt = Instant.now())

        assertEquals(listOf(3L, 1L), batch.observations.map { it.elapsedRealtimeNanos })
    }

    @Test
    fun `a claimed row always carries both claimToken and processingStartedAt together -- never one without the other`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))

        val claimed = repository.claimPendingObservations(limit = 10, claimedAt = Instant.now()).observations.single()

        assertNotNull(claimed.claimToken)
        assertNotNull(claimed.processingStartedAt)
    }

    @Test
    fun `claiming with no PENDING rows available returns an empty batch, with a token still assigned`() = runTest {
        val batch = repository.claimPendingObservations(limit = 10, claimedAt = Instant.now())

        assertTrue(batch.observations.isEmpty())
        assertNotNull(batch.claimToken)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `claimPendingObservations rejects a limit of 0`() = runTest {
        repository.claimPendingObservations(limit = 0, claimedAt = Instant.now())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `claimPendingObservations rejects a negative limit`() = runTest {
        repository.claimPendingObservations(limit = -1, claimedAt = Instant.now())
    }

    // ==============================================================================================
    // Ownership-checked terminal transition.
    // ==============================================================================================

    @Test
    fun `markProcessed only affects a row owned by the given claimToken`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        val claimed = repository.claimPendingObservations(limit = 10, claimedAt = Instant.now()).observations.single()

        val wrongToken = repository.markProcessed(listOf(claimed.id), ClaimToken("token-WRONG"), Instant.now())
        val rightToken = repository.markProcessed(listOf(claimed.id), claimed.claimToken!!, Instant.now())

        assertEquals("a worker must never be able to terminate a lease it does not own", 0, wrongToken)
        assertEquals(1, rightToken)
    }

    @Test
    fun `markProcessed transitions a row so it stops appearing as pending`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        val batch = repository.claimPendingObservations(limit = 10, claimedAt = Instant.now())

        val updated = repository.markProcessed(batch.observations.map { it.id }, batch.claimToken, Instant.now())

        assertEquals(1, updated)
        assertTrue(repository.pendingObservationsOrderedBySequence().isEmpty())
    }

    // ==============================================================================================
    // Reclaim / crash recovery / ownership invalidation.
    // ==============================================================================================

    @Test
    fun `reclaimStalledProcessing resets a stale PROCESSING row back to PENDING, simulating crash recovery`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T10:00:00Z"))
        // Simulates a process death here: markProcessed is never called.

        val reclaimed = repository.reclaimStalledProcessing(olderThan = Instant.parse("2026-01-01T10:00:01Z"))

        assertEquals(1, reclaimed)
        val stillPending = repository.pendingObservationsOrderedBySequence().single()
        assertEquals(BufferedObservationProcessingState.PENDING, stillPending.processingState)
        assertNull(stillPending.processingStartedAt)
        assertNull("reclaim must clear the stale owner's token", stillPending.claimToken)
    }

    @Test
    fun `reclaimStalledProcessing does not touch a PROCESSING row that started after the cutoff`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T10:00:10Z"))

        val reclaimed = repository.reclaimStalledProcessing(olderThan = Instant.parse("2026-01-01T10:00:00Z"))

        assertEquals(0, reclaimed)
    }

    @Test
    fun `a stale token can never terminate the new lease a different worker claims after reclaim`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        val firstClaim = repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T10:00:00Z"))
        val originalId = firstClaim.observations.single().id
        repository.reclaimStalledProcessing(olderThan = Instant.parse("2026-01-01T10:00:01Z"))
        val secondClaim = repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T10:05:00Z"))
        val reclaimedRow = secondClaim.observations.single()

        assertEquals("the reclaimed row keeps its identity", originalId, reclaimedRow.id)
        assertEquals(secondClaim.claimToken, reclaimedRow.claimToken)

        // Worker A finishes late, still holding its now-invalid token.
        val lateCompletionByStaleOwner = repository.markProcessed(listOf(originalId), firstClaim.claimToken, Instant.parse("2026-01-01T10:05:01Z"))
        assertEquals("a stale owner must never be able to complete the new lease", 0, lateCompletionByStaleOwner)

        // Worker B, the genuine current owner, can still complete it correctly.
        val completionByCurrentOwner = repository.markProcessed(listOf(originalId), secondClaim.claimToken, Instant.parse("2026-01-01T10:05:02Z"))
        assertEquals(1, completionByCurrentOwner)
    }

    @Test
    fun `a reclaimed observation can be claimed and processed again -- idempotent reprocessing`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T10:00:00Z"))
        repository.reclaimStalledProcessing(olderThan = Instant.parse("2026-01-01T10:00:01Z"))

        val reClaimed = repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T10:05:00Z"))
        val processed = repository.markProcessed(reClaimed.observations.map { it.id }, reClaimed.claimToken, Instant.parse("2026-01-01T10:05:01Z"))

        assertEquals(1, reClaimed.observations.size)
        assertEquals(1, processed)
    }

    // ==============================================================================================
    // Retention / purge.
    // ==============================================================================================

    @Test
    fun `purgeAccordingTo with maxAge removes only observations older than the cutoff`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L, receivedAt = Instant.parse("2026-01-01T00:00:00Z")))
        repository.insert(record(elapsedRealtimeNanos = 2L, receivedAt = Instant.parse("2026-01-02T00:00:00Z")))

        val removed = repository.purgeAccordingTo(
            TrajectoryBufferRetentionPolicy.boundedByAge(Duration.ofHours(6)),
            now = Instant.parse("2026-01-02T01:00:00Z"),
        )

        assertEquals(1, removed)
        assertEquals(2L, repository.pendingObservationsOrderedBySequence().single().elapsedRealtimeNanos)
    }

    @Test
    fun `purgeAccordingTo with maxObservationCount keeps only the newest rows`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.insert(record(elapsedRealtimeNanos = 2L))
        repository.insert(record(elapsedRealtimeNanos = 3L))

        val removed = repository.purgeAccordingTo(
            TrajectoryBufferRetentionPolicy.boundedByCount(2),
            now = Instant.now(),
        )

        assertEquals(1, removed)
        assertEquals(listOf(2L, 3L), repository.pendingObservationsOrderedBySequence().map { it.elapsedRealtimeNanos })
    }

    @Test
    fun `purgeAccordingTo with both bounds applies both independently`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L, receivedAt = Instant.parse("2026-01-01T00:00:00Z"))) // too old
        repository.insert(record(elapsedRealtimeNanos = 2L, receivedAt = Instant.parse("2026-01-02T00:00:00Z")))
        repository.insert(record(elapsedRealtimeNanos = 3L, receivedAt = Instant.parse("2026-01-02T00:00:01Z")))
        repository.insert(record(elapsedRealtimeNanos = 4L, receivedAt = Instant.parse("2026-01-02T00:00:02Z")))

        repository.purgeAccordingTo(
            TrajectoryBufferRetentionPolicy.bounded(Duration.ofHours(6), 2),
            now = Instant.parse("2026-01-02T01:00:00Z"),
        )

        // #1 removed by age; then only the newest 2 of the remaining (#2, #3, #4) survive the count bound.
        assertEquals(listOf(3L, 4L), repository.pendingObservationsOrderedBySequence().map { it.elapsedRealtimeNanos })
    }

    @Test
    fun `purgeAccordingTo with unboundedForTestingOnly policy removes nothing`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L, receivedAt = Instant.EPOCH))

        val removed = repository.purgeAccordingTo(TrajectoryBufferRetentionPolicy.unboundedForTestingOnly(), now = Instant.now())

        assertEquals(0, removed)
        assertEquals(1, repository.count())
    }

    @Test
    fun `purge is a genuine delete, not a state flag -- count reflects the removal`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L, receivedAt = Instant.EPOCH))

        repository.purgeAccordingTo(TrajectoryBufferRetentionPolicy.boundedByAge(Duration.ofSeconds(1)), now = Instant.now())

        assertEquals(0, repository.count())
    }

    // ==============================================================================================
    // Purge vs. an active lease (Codex review blocking fix): purge must never silently delete a row
    // a worker currently owns -- it must go through reclaim first.
    // ==============================================================================================

    @Test
    fun `age-based purge never removes a row currently PROCESSING, even if its receivedAt is older than the cutoff`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L, receivedAt = Instant.parse("2026-01-01T00:00:00Z")))
        repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T00:00:01Z"))

        val removed = repository.purgeAccordingTo(
            TrajectoryBufferRetentionPolicy.boundedByAge(Duration.ofSeconds(1)),
            now = Instant.parse("2026-01-02T00:00:00Z"),
        )

        assertEquals(0, removed)
        assertEquals(1, repository.count())
    }

    @Test
    fun `count-based purge never removes or counts a row currently PROCESSING`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L))
        repository.claimPendingObservations(limit = 1, claimedAt = Instant.now()) // row 1 leased
        repository.insert(record(elapsedRealtimeNanos = 2L))
        repository.insert(record(elapsedRealtimeNanos = 3L))

        val removed = repository.purgeAccordingTo(
            TrajectoryBufferRetentionPolicy.boundedByCount(1),
            now = Instant.now(),
        )

        // Only rows 2 and 3 (not PROCESSING) are eligible; keeping the newest 1 of those removes exactly 1.
        assertEquals(1, removed)
        assertEquals(2, repository.count()) // row 1 (PROCESSING, protected) + the newest surviving eligible row
    }

    @Test
    fun `a stale PROCESSING row only becomes purgeable after reclaim returns it to PENDING`() = runTest {
        repository.insert(record(elapsedRealtimeNanos = 1L, receivedAt = Instant.parse("2026-01-01T00:00:00Z")))
        repository.claimPendingObservations(limit = 10, claimedAt = Instant.parse("2026-01-01T00:00:01Z"))

        val removedBeforeReclaim = repository.purgeAccordingTo(
            TrajectoryBufferRetentionPolicy.boundedByAge(Duration.ofSeconds(1)),
            now = Instant.parse("2026-01-02T00:00:00Z"),
        )
        assertEquals(0, removedBeforeReclaim)

        repository.reclaimStalledProcessing(olderThan = Instant.parse("2026-01-01T00:00:02Z"))

        val removedAfterReclaim = repository.purgeAccordingTo(
            TrajectoryBufferRetentionPolicy.boundedByAge(Duration.ofSeconds(1)),
            now = Instant.parse("2026-01-02T00:00:00Z"),
        )
        assertEquals(1, removedAfterReclaim)
        assertEquals(0, repository.count())
    }
}
