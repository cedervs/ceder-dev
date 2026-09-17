package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * A single confidence component — deliberately not a bare `Double`/`Float`: a component a future
 * reconstruction strategy hasn't computed (Phase 1: none are computed by anything real yet) must
 * be representable as genuinely **unknown**, not as a guessed/default numeric value that could be
 * silently misread as "computed and low". See [ReconstructionConfidence] for why the overall
 * contract stays multi-component rather than a single collapsed score.
 */
sealed interface ConfidenceValue {
    /** Not computed by the strategy that produced this result — absence of evidence, not evidence
     * of low confidence. */
    data object Unknown : ConfidenceValue

    /** A genuinely computed component, normalized to `[0.0, 1.0]` (0 = no confidence, 1 = full
     * confidence) — the normalization itself is a Phase-1 modeling choice, not a claim about how
     * any future strategy should derive the number. */
    data class Known(val value: Double) : ConfidenceValue {
        init {
            require(value in 0.0..1.0) { "ConfidenceValue.Known must be within [0.0, 1.0], got $value" }
        }
    }
}

/**
 * The confidence contract for a [TrajectoryReconstructionResult] — **deliberately not a single
 * collapsed `Float`/`Double`**. A future map-matching strategy (HMM/Viterbi or otherwise) draws on
 * many independent signals; collapsing them into one number this early would throw away exactly
 * the explainability this whole system exists to provide (see `docs/ai-context/` product studies
 * this round is grounded in). Every component beyond [overall] defaults to
 * [ConfidenceValue.Unknown] — Phase 1 computes none of them; a future strategy fills in only the
 * ones it actually evaluates, leaving the rest genuinely unknown rather than guessed.
 *
 * Component list matches the round's own product study one-to-one, so nothing here is invented
 * beyond what was already scoped: GPS quality, temporal coherence, gap duration/distance,
 * kinematic plausibility, observation-to-candidate distance, topological continuity, best-vs-
 * second-best path ambiguity, network coverage, transport-mode confidence, bearing coherence,
 * speed coherence, mode-change likelihood, and graph freshness/version.
 */
data class ReconstructionConfidence(
    val overall: ConfidenceValue,
    val gpsQuality: ConfidenceValue = ConfidenceValue.Unknown,
    val temporalCoherence: ConfidenceValue = ConfidenceValue.Unknown,
    val gapDurationAndDistance: ConfidenceValue = ConfidenceValue.Unknown,
    val kinematicPlausibility: ConfidenceValue = ConfidenceValue.Unknown,
    val observationToCandidateDistance: ConfidenceValue = ConfidenceValue.Unknown,
    val topologicalContinuity: ConfidenceValue = ConfidenceValue.Unknown,
    val pathAmbiguity: ConfidenceValue = ConfidenceValue.Unknown,
    val networkCoverage: ConfidenceValue = ConfidenceValue.Unknown,
    val transportModeConfidence: ConfidenceValue = ConfidenceValue.Unknown,
    val bearingCoherence: ConfidenceValue = ConfidenceValue.Unknown,
    val speedCoherence: ConfidenceValue = ConfidenceValue.Unknown,
    val modeChangeLikelihood: ConfidenceValue = ConfidenceValue.Unknown,
    val graphFreshness: ConfidenceValue = ConfidenceValue.Unknown,
) {
    companion object {
        /** Convenience for a result that computed nothing at all — every component, including
         * [overall], is [ConfidenceValue.Unknown]. Distinct from a low *known* confidence: this
         * says "not evaluated", never "evaluated and rejected". */
        val UNKNOWN = ReconstructionConfidence(overall = ConfidenceValue.Unknown)
    }
}
