package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.time.Instant

/**
 * Storage port for the local trajectory buffer — mirrors
 * `com.cedervs.worlddiscovery.core.discovery.DiscoveredCellRepository`'s existing split (a pure
 * port here, a Room-backed implementation in `:core-database`). Nothing in this codebase wires a
 * real implementation into the live tracking pipeline yet — see
 * `docs/ai-context/LOCATION_TRACKING.md` for why that is explicitly deferred past Phase 1.
 */
interface TrajectoryObservationBufferRepository {

    /**
     * Inserts [record] (with [BufferedObservationRecord.id] `0`, letting storage assign the real
     * id) unless an observation with the same [BufferedObservationRecord.dedupKey] already exists,
     * in which case this is a silent no-op — **insertion must be idempotent**, never an error, for
     * a genuine redelivery (see [buildObservationDedupKey]). Returns `true` if a new row was
     * actually inserted, `false` if it was already present.
     */
    suspend fun insert(record: BufferedObservationRecord): Boolean

    /**
     * A **read-only snapshot** of every buffered observation in
     * [BufferedObservationProcessingState.PENDING] or [BufferedObservationProcessingState.PROCESSING],
     * ordered by [BufferedObservationRecord.id] ascending. For inspection/diagnostics/tests only —
     * **never use this to decide what to process**: a concurrent [claimPendingObservations] call
     * can claim, process, and remove any of these same rows before this snapshot's caller acts on
     * it. A real consumer that intends to actually process rows must call
     * [claimPendingObservations], which is atomic; this method is not. Never returns
     * [BufferedObservationProcessingState.PROCESSED]/[BufferedObservationProcessingState.DISCARDED]
     * rows.
     */
    suspend fun pendingObservationsOrderedBySequence(limit: Int? = null): List<BufferedObservationRecord>

    /** Total row count, regardless of [BufferedObservationProcessingState] — used by tests and by
     * a future [TrajectoryBufferRetentionPolicy.maxObservationCount] enforcement. */
    suspend fun count(): Int

    /**
     * **The only safe way to start processing rows.** Atomically (a single storage-level
     * transaction) selects up to [limit] rows currently [BufferedObservationProcessingState.PENDING]
     * (oldest [BufferedObservationRecord.id] first), transitions exactly those rows to
     * [BufferedObservationProcessingState.PROCESSING] with [BufferedObservationRecord.claimToken]
     * set to a fresh [ClaimToken] this call generates itself and
     * [BufferedObservationRecord.processingStartedAt] set to [claimedAt], and returns a
     * [ClaimedObservationBatch] carrying that token and exactly the rows this call itself just
     * claimed.
     *
     * This replaces an earlier, unsafe two-step protocol (`pendingObservationsOrderedBySequence`
     * followed by a separate `markProcessing(ids)`) that had a real race: two concurrent workers
     * could both read the same PENDING ids before either one marked them, and both would then
     * believe they owned the same rows. A single atomic claim makes that impossible — a row can be
     * returned by at most one `claimPendingObservations` call.
     *
     * **Correction (second review round): the claim token is no longer caller-supplied.** An
     * earlier version of this method took a `claimToken: String` parameter from the caller, relying
     * on a documented convention that the caller would always generate a fresh, unique value. That
     * made uniqueness an *external* responsibility the repository could not itself guarantee — a
     * caller reusing a token across two calls (by mistake or by a bug) would receive rows claimed
     * by an *earlier* call mixed into a later one. The token is now generated internally by this
     * repository's own [ClaimTokenGenerator] and simply handed back to the caller inside the
     * returned [ClaimedObservationBatch] — a caller can no longer construct or supply one.
     *
     * [limit] must be strictly positive (`> 0`) — implementations throw [IllegalArgumentException]
     * otherwise. There is no "unlimited claim" option: a real consumer processing rows should always
     * claim a bounded batch; `pendingObservationsOrderedBySequence` (unbounded, nullable `limit`)
     * remains the read-only diagnostic path for "give me everything".
     */
    suspend fun claimPendingObservations(limit: Int, claimedAt: Instant): ClaimedObservationBatch

    /**
     * Marks [ids] as [BufferedObservationProcessingState.PROCESSED], recording [processedAt] —
     * **only for rows currently owned by [claimToken]**. A row whose lease has since been
     * reclaimed (see [reclaimStalledProcessing]) no longer carries this [claimToken], so a late
     * caller that held a stale claim cannot terminate a lease it no longer owns — it silently
     * affects zero rows for those ids, never an error, and never disturbs whichever newer claim (if
     * any) now owns the row. Returns how many rows were actually updated. [claimToken] being a real
     * [ClaimToken] (not a bare `String`) means an empty/blank token can never even be constructed to
     * pass in here — see that class's own invariant.
     */
    suspend fun markProcessed(ids: List<Long>, claimToken: ClaimToken, processedAt: Instant): Int

    /**
     * **The crash-recovery primitive.** Resets every row still stuck in
     * [BufferedObservationProcessingState.PROCESSING] whose [BufferedObservationRecord.processingStartedAt]
     * is at or before [olderThan] back to [BufferedObservationProcessingState.PENDING], **clearing
     * [BufferedObservationRecord.claimToken] and [BufferedObservationRecord.processingStartedAt] to
     * `null` in the same transition** — the signature of a process that crashed (or was killed)
     * after calling [claimPendingObservations] but before calling [markProcessed]. Clearing the old
     * [BufferedObservationRecord.claimToken] is what makes reclaim safe: the previous owner's token
     * no longer matches this row, so a late [markProcessed] call from that stale owner cannot
     * terminate whatever new claim a subsequent [claimPendingObservations] call gives the row. A
     * future consumer calling this once at startup, before calling [claimPendingObservations], is
     * what makes reprocessing safe: no observation is silently lost, and re-running the (future,
     * idempotent-by-design) reconstruction step on a reclaimed row is expected to be safe. Returns
     * how many rows were reclaimed.
     *
     * The exact staleness timeout ([olderThan]'s distance from "now") is CALIBRATION REQUIRED, not
     * decided here.
     */
    suspend fun reclaimStalledProcessing(olderThan: Instant): Int

    /**
     * Applies [policy] **evaluated live against [now]** — deliberately not against any value
     * stored at insert time, so changing [policy] between calls takes effect immediately across
     * every already-buffered row, matching the "injectable/configurable policy" requirement this
     * exists for. Age is measured from [BufferedObservationRecord.receivedAt] (when this app
     * actually captured the observation — the natural buffer-retention clock, deliberately not
     * the device-reported [BufferedObservationRecord] fix time, which is not guaranteed
     * monotonic/trustworthy — see [TrajectoryObservation.observedAt]'s own doc comment).
     *
     * **Never removes a row currently [BufferedObservationProcessingState.PROCESSING]** — an active
     * lease is protected from both the age and the count bound below, regardless of how old
     * [BufferedObservationRecord.receivedAt] is; a stale lease must first go through
     * [reclaimStalledProcessing] (which returns it to [BufferedObservationProcessingState.PENDING])
     * before it becomes eligible for purge. This prevents purge from silently deleting a row a
     * worker still believes it owns and may still call [markProcessed] on.
     *
     * Deletes eligible ([BufferedObservationProcessingState.PENDING]/
     * [BufferedObservationProcessingState.PROCESSED]/[BufferedObservationProcessingState.DISCARDED])
     * rows whose age exceeds [TrajectoryBufferRetentionPolicy.maxAge] (if set) and, if the
     * remaining eligible count still exceeds [TrajectoryBufferRetentionPolicy.maxObservationCount],
     * deletes the oldest excess eligible rows by [BufferedObservationRecord.id] (if set). Both
     * bounds apply independently when both are set — a row violating either is removed. Purge is a
     * genuine `DELETE`, not merely a [BufferedObservationProcessingState.DISCARDED] flag — see
     * `docs/ai-context/LOCATION_TRACKING.md`'s privacy section for what a `DELETE` does and does
     * not guarantee at the SQLite/WAL level. Returns how many rows were removed.
     */
    suspend fun purgeAccordingTo(policy: TrajectoryBufferRetentionPolicy, now: Instant): Int
}
