package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.util.UUID

/**
 * PHASE 3A CORRECTION ROUND 2 — fixes Codex's remaining B2 finding on the matcher-run axis: Round
 * 1's `matcherEvaluationId: String` was free-form caller convention with nothing on the gate side to
 * independently compare against — a caller could invent any unrelated string. A typed opaque
 * identity, produced **once per matcher evaluation at the reconstruction/matching boundary** and
 * propagated into both [TrajectoryReconstructionResult.AcceptedTrajectory.matcherRunIdentity] and
 * [ReconstructionSafetyEvidence.matcherRunIdentity], is what actually lets
 * [ReconstructionSafetyGate.evaluate] compare "the run the candidate came from" against "the run this
 * evidence describes" — mirrors [ClaimToken]/[ClaimTokenGenerator]'s own established pattern in this
 * exact package (an opaque, repository/producer-generated identity a caller can never fabricate
 * in-place) rather than a bare `String` API.
 *
 * Deliberately never reveals matcher vendor/version — [value] is opaque by contract, only ever
 * compared for equality, never parsed or inspected.
 */
@JvmInline
value class MatcherRunIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "MatcherRunIdentity must not be blank" }
    }
}

/** Produces a fresh [MatcherRunIdentity] for one matcher evaluation — an injectable seam so tests can
 * supply a deterministic generator without needing real randomness, matching
 * [ClaimTokenGenerator]'s own established convention. Generation belongs at the producer boundary (a
 * future [TrajectoryReconstructor]/matcher-integration point) — [ReconstructionSafetyGate.evaluate]
 * itself never generates one; it only ever compares immutable identities it is handed. */
fun interface MatcherRunIdentityGenerator {
    fun generate(): MatcherRunIdentity
}

/** The real, production [MatcherRunIdentityGenerator] — mirrors [UuidClaimTokenGenerator]'s own
 * "a random UUID is unique in practice for this purpose" standard used elsewhere in this project. */
object UuidMatcherRunIdentityGenerator : MatcherRunIdentityGenerator {
    override fun generate(): MatcherRunIdentity = MatcherRunIdentity(UUID.randomUUID().toString())
}
