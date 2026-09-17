package com.cedervs.worlddiscovery.core.database

import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationRecord
import com.cedervs.worlddiscovery.core.discovery.trajectory.ClaimToken
import com.cedervs.worlddiscovery.core.discovery.trajectory.ClaimTokenGenerator
import com.cedervs.worlddiscovery.core.discovery.trajectory.ClaimedObservationBatch
import com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryBufferRetentionPolicy
import com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationBufferRepository
import com.cedervs.worlddiscovery.core.discovery.trajectory.UuidClaimTokenGenerator
import java.time.Instant

/** Room-backed implementation of the trajectory buffer's storage port — mirrors
 * `RoomDiscoveredCellRepository`'s existing shape. Offline-first: every operation is a local
 * SQLite read/write against [TrajectoryBufferDatabase], never a network call, and never touches
 * [WorldDiscoveryDatabase]/`discovered_cells`.
 *
 * [claimTokenGenerator] defaults to [UuidClaimTokenGenerator] (the real, production generator) but
 * is injectable so tests can supply a deterministic one — matching this codebase's existing
 * testability conventions elsewhere. */
class RoomTrajectoryObservationBufferRepository(
    private val dao: BufferedObservationDao,
    private val claimTokenGenerator: ClaimTokenGenerator = UuidClaimTokenGenerator,
) : TrajectoryObservationBufferRepository {

    override suspend fun insert(record: BufferedObservationRecord): Boolean {
        val insertedId = dao.insert(record.toEntity())
        return insertedId != -1L
    }

    override suspend fun pendingObservationsOrderedBySequence(limit: Int?): List<BufferedObservationRecord> =
        dao.pendingOrderedById(limit ?: -1).map { it.toDomain() }

    override suspend fun count(): Int = dao.count()

    override suspend fun claimPendingObservations(limit: Int, claimedAt: Instant): ClaimedObservationBatch {
        require(limit > 0) { "limit must be > 0, got $limit" }
        val token = claimTokenGenerator.generate()
        val claimed = dao.claimPending(limit, token.value, claimedAt.toEpochMilli()).map { it.toDomain() }
        return ClaimedObservationBatch(claimToken = token, observations = claimed)
    }

    override suspend fun markProcessed(ids: List<Long>, claimToken: ClaimToken, processedAt: Instant): Int {
        if (ids.isEmpty()) return 0
        return dao.markProcessedByToken(ids, claimToken.value, processedAt.toEpochMilli())
    }

    override suspend fun reclaimStalledProcessing(olderThan: Instant): Int =
        dao.reclaimStalledProcessing(olderThan.toEpochMilli())

    override suspend fun purgeAccordingTo(policy: TrajectoryBufferRetentionPolicy, now: Instant): Int {
        var removed = 0
        val maxAge = policy.maxAge
        if (maxAge != null) {
            removed += dao.deleteReceivedAtOrBefore(now.minus(maxAge).toEpochMilli())
        }
        val maxCount = policy.maxObservationCount
        if (maxCount != null) {
            removed += dao.deleteExcessBeyondCount(maxCount)
        }
        return removed
    }
}
