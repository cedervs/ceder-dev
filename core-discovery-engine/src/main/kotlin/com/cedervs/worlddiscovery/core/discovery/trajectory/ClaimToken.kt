package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.util.UUID

/**
 * An opaque ownership credential for one [TrajectoryObservationBufferRepository.claimPendingObservations]
 * call — never a caller-supplied arbitrary string. See that method's own doc comment for the full
 * claim/lease contract this exists for.
 *
 * **Correction (second Codex review round):** an earlier version of this contract let the *caller*
 * pass in an arbitrary `String` as the claim's identity, relying on a documented convention ("always
 * generate a fresh unique value") rather than making uniqueness a property the repository itself
 * guarantees. That is unsafe in practice: nothing prevented a caller from accidentally reusing a
 * token across two calls, and the DAO's own read-back query (`rowsForToken`) had no way to tell a
 * genuine single-call claim apart from an accidental multi-call reuse of the same token — it would
 * silently return the union of everything ever claimed under that token. [ClaimToken] is now always
 * produced by [ClaimTokenGenerator] (never typed in by a caller), and the DAO-level fix (see
 * `:core-database`'s `BufferedObservationDao.claimPending`) additionally scopes the read-back to the
 * exact ids selected in the current call, so the contract no longer depends on token uniqueness
 * alone even as defense in depth.
 */
@JvmInline
value class ClaimToken(val value: String) {
    init {
        require(value.isNotBlank()) { "ClaimToken must not be blank" }
    }
}

/** Produces a fresh [ClaimToken] for one claim call — an injectable seam so tests can supply a
 * deterministic generator without needing real randomness, matching this codebase's existing
 * testability conventions. [UuidClaimTokenGenerator] is the only real implementation. */
fun interface ClaimTokenGenerator {
    fun generate(): ClaimToken
}

/** The real, production [ClaimTokenGenerator] — a random UUID has a collision probability low
 * enough that this codebase treats it as "unique in practice" for a single claim call, the same
 * standard used elsewhere for opaque identifiers in this project. */
object UuidClaimTokenGenerator : ClaimTokenGenerator {
    override fun generate(): ClaimToken = ClaimToken(UUID.randomUUID().toString())
}

/**
 * The result of one [TrajectoryObservationBufferRepository.claimPendingObservations] call:
 * [claimToken] is the credential this exact call now owns, and [observations] are exactly the rows
 * it claimed — never rows from any other claim, past or concurrent. See that method's own doc
 * comment for the full contract. Only ever constructed by a [TrajectoryObservationBufferRepository]
 * implementation itself from a freshly-mapped, not-externally-aliased list, so (unlike
 * `AcceptedTrajectory`) no defensive copy is needed here — there is no caller-supplied mutable
 * collection this could alias.
 */
data class ClaimedObservationBatch(
    val claimToken: ClaimToken,
    val observations: List<BufferedObservationRecord>,
)
