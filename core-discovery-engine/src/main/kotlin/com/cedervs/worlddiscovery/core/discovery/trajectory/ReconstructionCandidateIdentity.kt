package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate

/**
 * PHASE 3A CORRECTION ROUND 1 (introduced) / ROUND 2 (scope narrowed and accurately documented) —
 * originally fixed Codex finding B2 ("candidate/evidence identity is not established"). **Round 2
 * correction:** a Codex re-review found this type's original doc comment overclaimed what it
 * actually proves — a geometry/provenance fingerprint says nothing about *which original ordered
 * observations* produced that geometry (two different observation windows can honestly reconstruct
 * to structurally identical geometry/indices/endpoints), and nothing about *which matcher run*
 * produced it either. Association between a candidate and its evidence is now **three independent
 * layers**, each with its own identity type and its own gate check:
 * - **Layer A — which ordered raw observations**: [OrderedObservationWindowIdentity]
 *   ([TrajectoryReconstructionResult.AcceptedTrajectory.observationWindowIdentity]).
 * - **Layer B — which matcher evaluation/run**: [MatcherRunIdentity]
 *   ([TrajectoryReconstructionResult.AcceptedTrajectory.matcherRunIdentity]).
 * - **Layer C — this type**: the reconstructed candidate's own **geometry/provenance shape**
 *   (points, endpoints, observed/inferred index sets). Useful as an independent sanity check (e.g.
 *   catching a non-deterministic matcher that reconstructs different geometry from the same window
 *   and run), but **does not by itself prove anything about layers A or B** — a checksum over
 *   geometry alone cannot know which raw observations or which run produced that geometry. Treat all
 *   three as independent; do not treat any single one (this type included) as sufficient proof of
 *   the other two.
 *
 * A deterministic, immutable fingerprint of the **exact** [TrajectoryReconstructionResult.AcceptedTrajectory]
 * geometry/provenance shape a piece of [ReconstructionSafetyEvidence] claims to describe — derived
 * **only** from fields already present on that existing Phase 1 type ([of]).
 * [ReconstructionSafetyEvidence.associatedCandidateIdentity] carries the identity the evidence's own
 * producer computed against whatever candidate *they* evaluated; [ReconstructionSafetyGate]
 * independently recomputes [of] against the candidate it was actually given and requires exact
 * equality (a mismatch is [SafetyGateReasonCode.EVIDENCE_CANDIDATE_MISMATCH], a hard failure) — this
 * check runs *in addition to*, never instead of, the Layer A/B checks above.
 *
 * **[geometryChecksum] is deliberately never the sole discriminator** — equality is over the full
 * structured tuple below ([inputObservationCount], [geometrySize], [firstCoordinate],
 * [lastCoordinate], [observedIndices], [inferredIndices], and only then [geometryChecksum]), so an
 * accidental checksum collision alone could never make two genuinely different geometries compare
 * equal. [geometryChecksum] is a deterministic, order-sensitive rolling hash over every geometry
 * point's IEEE-754 bit pattern ([Double.toRawBits]) — the same [Coordinate] sequence always produces
 * the same checksum on any JVM, and reordering, inserting, or removing any point changes it.
 */
data class ReconstructionCandidateIdentity(
    val inputObservationCount: Int,
    val geometrySize: Int,
    val firstCoordinate: Coordinate,
    val lastCoordinate: Coordinate,
    val observedIndices: Set<Int>,
    val inferredIndices: Set<Int>,
    val geometryChecksum: Long,
) {
    companion object {
        /** Arbitrary odd 64-bit seed for the rolling checksum — any fixed odd constant works;
         * chosen only to avoid an all-zero initial state. */
        private const val CHECKSUM_SEED = 1_125_899_906_842_597L
        private const val CHECKSUM_MULTIPLIER = 31L

        fun of(candidate: TrajectoryReconstructionResult.AcceptedTrajectory): ReconstructionCandidateIdentity {
            var checksum = CHECKSUM_SEED
            for (point in candidate.geometry) {
                checksum = checksum * CHECKSUM_MULTIPLIER + point.latitude.toRawBits()
                checksum = checksum * CHECKSUM_MULTIPLIER + point.longitude.toRawBits()
            }
            return ReconstructionCandidateIdentity(
                inputObservationCount = candidate.inputObservationCount,
                geometrySize = candidate.geometry.size,
                firstCoordinate = candidate.geometry.first(),
                lastCoordinate = candidate.geometry.last(),
                observedIndices = candidate.observedIndices,
                inferredIndices = candidate.inferredIndices,
                geometryChecksum = checksum,
            )
        }
    }
}
