package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant

/**
 * Lifecycle state of one buffered observation — see `docs/ai-context/LOCATION_TRACKING.md`'s
 * "Durability / crash recovery" section for the full contract.
 *
 * `PENDING -> PROCESSING -> PROCESSED` is the expected path once a future consumer exists;
 * `PENDING -> DISCARDED` is retention/purge acting directly (see
 * [TrajectoryBufferRetentionPolicy]). Nothing in this codebase transitions a record to
 * `PROCESSING`/`PROCESSED` yet — Phase 1 has no consumer — but the states and the crash-recovery
 * primitive built around them ([TrajectoryObservationBufferRepository.reclaimStalledProcessing])
 * are real and tested now, ready for that future consumer.
 */
enum class BufferedObservationProcessingState {
    PENDING,
    PROCESSING,
    PROCESSED,
    DISCARDED,
}

/**
 * The persisted shape of one buffered raw observation — the domain-level mirror of `core-database`'s
 * Room entity (kept as a plain, Room-free data class here so it stays testable without Room/
 * Robolectric, matching this module's existing `DiscoveredCell`/`DiscoveredCellEntity` split).
 *
 * [id] is `0` for a not-yet-persisted record (the caller lets the storage layer assign the real
 * id on insert) and the real row id once read back — **doubling as the always-available,
 * always-monotonic retrieval order** (a Room `AUTOINCREMENT` primary key, which SQLite never
 * reuses even across a purge that empties the table entirely — see the `:core-database` Room
 * entity's own doc comment). This is valid across process/boot boundaries where
 * [elapsedRealtimeNanos] is not, at the cost of only reflecting *when this app learned about the
 * fix*, not the fix's own real-world timing. **This is insertion/retrieval order, never a claim
 * about the physical order of the underlying trip** — see [TrajectoryObservation]'s own doc
 * comment for the ordering rules a future window-normalizer must actually apply.
 *
 * See `docs/ai-context/LOCATION_TRACKING.md` for the full field-by-field rationale; in summary:
 * - [dedupKey] is the idempotency boundary (see [buildObservationDedupKey]) — insertion must be a
 *   no-op, not an error, for an already-buffered observation. It is derived **only** from the
 *   *fix identity* fields ([elapsedRealtimeClockDomainId]/[elapsedRealtimeNanos], or the weaker
 *   fallback) — never from [source]/[processSessionId]/[batchId]/[indexInBatch], which are
 *   *delivery metadata*: they describe how/where a fix arrived, not which physical fix it is. See
 *   [buildObservationDedupKey]'s own doc comment for why conflating the two was a real bug this
 *   round fixes.
 * - [claimToken]/[processingStartedAt] together are the crash-recovery/multi-worker-safety
 *   boundary — see [TrajectoryObservationBufferRepository.claimPendingObservations]'s doc comment.
 */
data class BufferedObservationRecord(
    val id: Long,
    val dedupKey: String,
    val source: TrajectoryObservationSource,
    val processSessionId: String,
    val elapsedRealtimeClockDomainId: String,
    val elapsedRealtimeNanos: Long,
    val coordinate: Coordinate,
    val providerTimeEpochMillis: Long,
    val receivedAt: Instant,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val bearingAccuracyDegrees: Float?,
    val provider: String?,
    val isMockLocation: Boolean,
    val batchId: String?,
    val indexInBatch: Int?,
    val processingState: BufferedObservationProcessingState,
    /** Owner token of the current [BufferedObservationProcessingState.PROCESSING] lease (or the
     * lease that completed a [BufferedObservationProcessingState.PROCESSED] row — see the state
     * invariants below), or `null` when never leased. */
    val claimToken: ClaimToken?,
    val processingStartedAt: Instant?,
    val processedAt: Instant?,
) {
    /**
     * **State invariants (Codex review round 2 — enforced at construction, previously only
     * documented):** a record used to be constructible with impossible combinations such as
     * `PROCESSING` with a `null` [claimToken], or `PENDING` with a non-null [claimToken] left over
     * from careless test/call-site construction. The rule, chosen for internal consistency with
     * this buffer's own transitions:
     * - [BufferedObservationProcessingState.PENDING]: [claimToken] and [processingStartedAt] must
     *   both be `null` — never leased, or a lease that was cleared by
     *   [TrajectoryObservationBufferRepository.reclaimStalledProcessing].
     * - [BufferedObservationProcessingState.PROCESSING]: [claimToken] and [processingStartedAt]
     *   must both be non-null — a row is never actively leased without knowing who holds the lease
     *   or when it started.
     * - [BufferedObservationProcessingState.PROCESSED]: [claimToken] and [processingStartedAt] must
     *   both be non-null (the completed lease's history is retained, never erased), and
     *   [processedAt] must be non-null.
     * - [BufferedObservationProcessingState.DISCARDED]: [claimToken] and [processingStartedAt] must
     *   both be `null` — nothing in this codebase ever transitions a row to `DISCARDED` from
     *   `PROCESSING` (purge/retention, the only thing that could conceptually produce this state,
     *   never acts on an actively-leased row — see
     *   [TrajectoryObservationBufferRepository.purgeAccordingTo]), so a `DISCARDED` row never has
     *   lease history to retain.
     * - [processedAt] must be `null` for every state other than
     *   [BufferedObservationProcessingState.PROCESSED].
     */
    init {
        when (processingState) {
            BufferedObservationProcessingState.PENDING -> {
                require(claimToken == null) { "PENDING must not carry a claimToken, got $claimToken" }
                require(processingStartedAt == null) { "PENDING must not carry a processingStartedAt, got $processingStartedAt" }
            }
            BufferedObservationProcessingState.PROCESSING -> {
                require(claimToken != null) { "PROCESSING must carry a non-null claimToken" }
                require(processingStartedAt != null) { "PROCESSING must carry a non-null processingStartedAt" }
            }
            BufferedObservationProcessingState.PROCESSED -> {
                require(claimToken != null) { "PROCESSED must retain the claimToken of the lease that completed it" }
                require(processingStartedAt != null) { "PROCESSED must retain processingStartedAt" }
                require(processedAt != null) { "PROCESSED must carry a non-null processedAt" }
            }
            BufferedObservationProcessingState.DISCARDED -> {
                require(claimToken == null) { "DISCARDED must not carry a claimToken -- purge never discards an actively-leased row" }
                require(processingStartedAt == null) { "DISCARDED must not carry a processingStartedAt" }
            }
        }
        if (processingState != BufferedObservationProcessingState.PROCESSED) {
            require(processedAt == null) { "only PROCESSED may carry a non-null processedAt, got $processingState with processedAt=$processedAt" }
        }
    }

    /** Pure projection into the shape a future [TrajectoryReconstructor] actually consumes —
     * drops storage-only bookkeeping ([id], [dedupKey], [claimToken], processing state/
     * timestamps) that has no meaning to reconstruction logic itself. */
    fun toTrajectoryObservation(): TrajectoryObservation = TrajectoryObservation(
        coordinate = coordinate,
        observedAt = Instant.ofEpochMilli(providerTimeEpochMillis),
        receivedAt = receivedAt,
        source = source,
        processSessionId = processSessionId,
        elapsedRealtimeClockDomainId = elapsedRealtimeClockDomainId,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
        bearingDegrees = bearingDegrees,
        bearingAccuracyDegrees = bearingAccuracyDegrees,
        provider = provider,
        isMockLocation = isMockLocation,
        batchId = batchId,
        indexInBatch = indexInBatch,
    )
}

/**
 * How strongly [buildObservationDedupKey] can vouch for the identity it produced.
 * [STRONG] means the key is derived from a real `elapsedRealtimeNanos` reading scoped to a clock
 * domain — essentially collision-free for two distinct real fixes. [WEAK] means
 * `elapsedRealtimeNanos` was unavailable (`0L`) and the key falls back to timestamp/coordinate/
 * accuracy — deterministic and still not a bare `(timestamp, lat, lon)` triple, but *not* immune
 * to a genuine coincidence (two different real fixes reported with identical rounded fields).
 * Never mix a [STRONG] and a [WEAK] key silently: [buildObservationDedupKey] prefixes its output
 * so the two guarantee levels can never collide with each other by construction.
 */
enum class FixIdentityGuarantee { STRONG, WEAK }

/** The guarantee level [buildObservationDedupKey] would use for a given [elapsedRealtimeNanos]
 * reading — exposed so a caller/consumer can decide how much to trust a given
 * [BufferedObservationRecord] without recomputing the key itself. */
fun fixIdentityGuarantee(elapsedRealtimeNanos: Long): FixIdentityGuarantee =
    if (elapsedRealtimeNanos != 0L) FixIdentityGuarantee.STRONG else FixIdentityGuarantee.WEAK

/**
 * Builds the deterministic deduplication key for one observation — **deliberately never just
 * `(timestamp, latitude, longitude)`**, per this round's own explicit requirement: a redelivered
 * PendingIntent batch, a retried delivery, or two genuinely distinct fixes that happen to share a
 * millisecond timestamp and a rounded coordinate must be told apart correctly.
 *
 * **Identity vs. delivery metadata — the bug this signature fixes:** an earlier version of this
 * key included `source` and `processSessionId`, which are *delivery metadata* (how/where a fix was
 * delivered), not *fix identity* (which physical GPS fix it is). That conflation meant the exact
 * same underlying Android `Location` redelivered under a different source (a foreground/background
 * transition), or received by a different process session (a process restart, a swiped-away app
 * relaunched, a redelivered `PendingIntent` picked up by a fresh process) was wrongly treated as a
 * *new* physical observation. Fix identity must depend only on:
 * - [elapsedRealtimeClockDomainId] + [elapsedRealtimeNanos] when the latter is genuinely available
 *   (non-zero) — `elapsedRealtimeNanos` is a monotonic nanosecond counter Android assigns once, at
 *   the moment a real fix is computed, and is only ever comparable to another reading that shares
 *   the same clock domain (conventionally, the same Android boot — see
 *   [TrajectoryObservation.elapsedRealtimeClockDomainId]'s own doc comment for why a *real* boot
 *   identity is not fabricated here and remains ENGINEERING DESIGN REQUIRED). Two observations
 *   with the same clock domain and the same `elapsedRealtimeNanos` are the same physical fix,
 *   regardless of `source`/`processSessionId`. Two observations with the same `elapsedRealtimeNanos`
 *   but a *different* clock domain are **not** assumed to be the same fix — a different clock
 *   domain means the nanosecond counters are not known to be comparable at all.
 * - a weaker, explicitly lower-guarantee fallback (see [FixIdentityGuarantee]) when
 *   `elapsedRealtimeNanos == 0L` (Android's own documented default for a `Location` that never had
 *   it set, e.g. a hand-constructed test fixture): `(providerTimeEpochMillis, latitude, longitude,
 *   accuracyMeters)` — still never a bare `(timestamp, lat, lon)` triple, but without the
 *   collision-free guarantee the strong path has.
 *
 * [source]/[processSessionId]/[batchId]/[indexInBatch] remain real, useful fields on
 * [BufferedObservationRecord] — they are just never part of *this* key.
 */
fun buildObservationDedupKey(
    elapsedRealtimeClockDomainId: String,
    elapsedRealtimeNanos: Long,
    providerTimeEpochMillis: Long,
    coordinate: Coordinate,
    accuracyMeters: Float?,
): String = when (fixIdentityGuarantee(elapsedRealtimeNanos)) {
    FixIdentityGuarantee.STRONG ->
        "strong|domain:$elapsedRealtimeClockDomainId|ert:$elapsedRealtimeNanos"
    FixIdentityGuarantee.WEAK ->
        "weak|t:$providerTimeEpochMillis|lat:${coordinate.latitude}|lon:${coordinate.longitude}|acc:$accuracyMeters"
}
