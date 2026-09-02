package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell

/**
 * The outcome of [ForegroundReconstructionScheduler.evaluateTransition] — never persisted by the
 * scheduler itself. A future caller (not built in this increment) would decide whether/how to
 * submit [Candidate.intermediateCells] through `SubmitDiscoveryObservation` with
 * `Provenance.RECONSTRUCTED`.
 */
sealed interface ReconstructionCandidate {
    /** The eligibility policy denied this transition — no path was even attempted. */
    data object NotEligible : ReconstructionCandidate

    /** Eligible, but H3 could not compute a path — see `H3GridTraversal`'s doc comment. */
    data object NoPath : ReconstructionCandidate

    /**
     * Eligible and a path was computed. [intermediateCells] deliberately excludes both the origin
     * and destination cells — those came from real observations and must never be presented to a
     * future caller as `RECONSTRUCTED`; they stay conceptually `OBSERVED`. Ordered origin ->
     * destination, excluding both endpoints:
     * - origin and destination in the same cell, or grid-adjacent cells, produce an **empty**
     *   list — there is nothing strictly between them to reconstruct;
     * - a longer path produces only the cells strictly between origin and destination.
     */
    data class Candidate(val intermediateCells: List<CanonicalCell>) : ReconstructionCandidate
}
