package com.cedervs.worlddiscovery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface BufferedObservationDao {

    /**
     * `IGNORE` on the `dedupKey` unique index is the actual idempotency mechanism — a genuine
     * redelivery (same `dedupKey`) is silently dropped by SQLite itself, not detected by a
     * separate existence check first (which would race). Returns the new row's id, or `-1` if the
     * insert was ignored (Room's own documented return value for a no-op `IGNORE` insert).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BufferedObservationEntity): Long

    /** [limit] of `-1` means "no limit" — a standard SQLite `LIMIT` semantic (a negative value
     * disables the limit entirely), used so the repository can express "give me everything"
     * without a second query variant. **Read-only** — never claims/mutates anything; see
     * `com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationBufferRepository.pendingObservationsOrderedBySequence`'s
     * own doc comment for why this must never be used to decide what to process. */
    @Query(
        "SELECT * FROM buffered_observations " +
            "WHERE processingState IN ('PENDING', 'PROCESSING') " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun pendingOrderedById(limit: Int): List<BufferedObservationEntity>

    @Query("SELECT COUNT(*) FROM buffered_observations")
    suspend fun count(): Int

    /** Step 1 of [claimPending]'s transaction: the ids of every row currently eligible to be
     * claimed, oldest first. Not `@Transaction` itself — only meaningful as part of [claimPending]. */
    @Query(
        "SELECT id FROM buffered_observations " +
            "WHERE processingState = 'PENDING' ORDER BY id ASC LIMIT :limit",
    )
    suspend fun eligibleForClaimIds(limit: Int): List<Long>

    /** Step 2 of [claimPending]'s transaction: claims exactly [ids] for [token], but **only** those
     * still `PENDING` at the moment this statement runs — the `AND processingState = 'PENDING'`
     * guard is what makes re-running step 1 and 2 inside one transaction race-free even though
     * they are two separate statements: SQLite's transaction isolation guarantees no other
     * connection's write is interleaved between them. */
    @Query(
        "UPDATE buffered_observations SET processingState = 'PROCESSING', claimToken = :token, " +
            "processingStartedAtEpochMillis = :startedAtEpochMillis " +
            "WHERE id IN (:ids) AND processingState = 'PENDING'",
    )
    suspend fun claimByIds(ids: List<Long>, token: String, startedAtEpochMillis: Long): Int

    /**
     * Step 3 of [claimPending]'s transaction: reads back **only** the rows identified by [ids]
     * (the exact set selected in step 1 of *this* call) that also carry [token] — the actual return
     * value of [claimPending]. Scoping by [ids] as well as [token] (Codex review round 2 fix) is
     * what makes the claim's result correct **independent of whether [token] happens to be unique**:
     * an earlier version of this query (`rowsForToken`) filtered by `token` alone, so if a token were
     * ever reused across two claim calls (accidentally, or by a future bug), the second call would
     * silently receive the union of both calls' rows. Filtering by this call's own [ids] makes that
     * impossible even in that scenario — the result can never contain a row this call did not itself
     * select in step 1. Combined with [ClaimTokenGenerator]'s uniqueness guarantee (see
     * `:core-discovery-engine`'s `ClaimToken`), this is defense in depth, not the primary guarantee.
     */
    @Query("SELECT * FROM buffered_observations WHERE id IN (:ids) AND claimToken = :token AND processingState = 'PROCESSING' ORDER BY id ASC")
    suspend fun rowsForIdsAndToken(ids: List<Long>, token: String): List<BufferedObservationEntity>

    /**
     * **The atomic claim primitive.** See
     * `com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationBufferRepository.claimPendingObservations`
     * for the full contract this backs. `@Transaction` is what makes the three steps below
     * (select eligible ids, claim them, read back the claim) race-free against a concurrent caller
     * of this same method: Room wraps the whole method body in one SQLite transaction, so another
     * transaction's claim cannot observe or claim the same PENDING rows in between these steps.
     *
     * A row can never end up `PROCESSING` with a `null` `processingStartedAtEpochMillis`/`claimToken`
     * through this method — both are always set together with the state change in [claimByIds]. The
     * read-back ([rowsForIdsAndToken]) is scoped to exactly the ids [eligibleForClaimIds] selected in
     * *this* call — see that method's own doc comment for why that no longer depends on [token]
     * being unique to be correct.
     */
    @Transaction
    suspend fun claimPending(limit: Int, token: String, startedAtEpochMillis: Long): List<BufferedObservationEntity> {
        val ids = eligibleForClaimIds(limit)
        if (ids.isEmpty()) return emptyList()
        claimByIds(ids, token, startedAtEpochMillis)
        return rowsForIdsAndToken(ids, token)
    }

    /** `AND claimToken = :token` is the ownership check — a stale/reclaimed token no longer
     * matches any row (see [reclaimStalledProcessing]), so this silently affects zero rows for a
     * late caller, never disturbing whichever newer claim (if any) now owns the row. */
    @Query(
        "UPDATE buffered_observations SET processingState = 'PROCESSED', processedAtEpochMillis = :processedAtEpochMillis " +
            "WHERE id IN (:ids) AND processingState = 'PROCESSING' AND claimToken = :token",
    )
    suspend fun markProcessedByToken(ids: List<Long>, token: String, processedAtEpochMillis: Long): Int

    /** The crash-recovery primitive's actual SQL — see
     * `com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationBufferRepository.reclaimStalledProcessing`'s
     * doc comment for the full contract. Clears both `processingStartedAtEpochMillis` and
     * `claimToken` back to `null` along with the state, so a freshly reclaimed row looks identical
     * to one that was never picked up at all, **and** so the previous owner's token can never again
     * match this row (invalidating it for [markProcessedByToken]). */
    @Query(
        "UPDATE buffered_observations SET processingState = 'PENDING', processingStartedAtEpochMillis = NULL, claimToken = NULL " +
            "WHERE processingState = 'PROCESSING' AND processingStartedAtEpochMillis <= :olderThanEpochMillis",
    )
    suspend fun reclaimStalledProcessing(olderThanEpochMillis: Long): Int

    /** `processingState != 'PROCESSING'` protects an active lease from age-based purge — see
     * `TrajectoryObservationBufferRepository.purgeAccordingTo`'s own doc comment for why. */
    @Query(
        "DELETE FROM buffered_observations WHERE receivedAtEpochMillis <= :cutoffEpochMillis " +
            "AND processingState != 'PROCESSING'",
    )
    suspend fun deleteReceivedAtOrBefore(cutoffEpochMillis: Long): Int

    /** Keeps only the newest [maxCount] rows **among those not currently `PROCESSING`** by id,
     * deleting the rest — an active lease is never counted against, or removed by, the count bound
     * (see `TrajectoryObservationBufferRepository.purgeAccordingTo`'s own doc comment). Written as
     * `NOT IN (SELECT ... ORDER BY ... LIMIT ...)` rather than `DELETE ... ORDER BY ... LIMIT` — the
     * latter is not standard SQL and is not guaranteed available in the SQLite build Android ships. */
    @Query(
        "DELETE FROM buffered_observations WHERE processingState != 'PROCESSING' AND id NOT IN " +
            "(SELECT id FROM buffered_observations WHERE processingState != 'PROCESSING' ORDER BY id DESC LIMIT :maxCount)",
    )
    suspend fun deleteExcessBeyondCount(maxCount: Int): Int
}
