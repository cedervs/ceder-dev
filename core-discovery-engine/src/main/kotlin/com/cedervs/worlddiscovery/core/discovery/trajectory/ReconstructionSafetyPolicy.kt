package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * What World Discovery considers sufficient evidence to trust a reconstructed candidate —
 * deliberately separate from [ReconstructionSafetyEvidence] (what happened). **No field here
 * carries a real production default.** Every value must be supplied explicitly by the caller
 * (mirrors [TrajectoryBufferRetentionPolicy]'s own "no implicit unbounded/default construction"
 * convention) — this file invents no production numbers; the real calibrated values for each field
 * are `CALIBRATION REQUIRED`. Test code in this module builds its own explicitly-named placeholder
 * instances local to the test file rather than relying on any default exposed here.
 *
 * **PHASE 3A CORRECTION ROUND 1 (Codex's M1 "policy permits structurally unsafe configurations").**
 * Two categories of change:
 * 1. **`requireStructuralContinuity` removed entirely.** The original round let this policy field
 *    disable the very check that protects against an unsupported/invented bridge — exactly the
 *    structural safety protection Codex's B3 found bypassable. Matcher-declined-bridge protection
 *    is now a non-negotiable rule inside [ReconstructionSafetyGate.evaluate] itself; no policy field
 *    controls whether it runs. The same is true of candidate/evidence identity association (B2),
 *    transport-mode compatibility (B5), and evidence relational coherence (B4) — none of these are
 *    policy-configurable; only [ReconstructionSafetyGate]'s own code decides whether they run, and
 *    they always do.
 * 2. **Numeric validation tightened** so a nonsensical configuration cannot even be constructed:
 *    infinite/non-finite speed bounds are rejected (not merely a non-empty range), and
 *    [minimumObservationSupport] of `0` is rejected — an accepted reconstruction must always be
 *    grounded in at least one real supporting observation; zero has no legitimate safety meaning.
 */
data class ReconstructionSafetyPolicy(
    val transportMode: TransportMode,

    /** Physically plausible implied-speed bounds for [transportMode], in meters/second. Both bounds
     * must be finite (an unbounded upper limit would make [SafetyGateReasonCode.PHYSICALLY_IMPLAUSIBLE_KINEMATICS]
     * structurally unreachable, silently disabling a hard-failure check). `CALIBRATION REQUIRED`. */
    val plausibleSpeedMetersPerSecond: ClosedFloatingPointRange<Double>,

    /** Minimum candidate `inputObservationCount` considered sufficient support. Must be `>= 1` — a
     * reconstruction grounded in zero observations has no legitimate safety meaning and is rejected
     * at construction. `CALIBRATION REQUIRED`. */
    val minimumObservationSupport: Int,

    /** Maximum tolerated fraction (`[0.0, 1.0]`) of matched+interpolated+unmatched observations that
     * may be interpolated before [SafetyGateReasonCode.INTERPOLATION_HEAVY] applies.
     * `CALIBRATION REQUIRED`. */
    val maximumInterpolatedObservationFraction: Double,

    /** Maximum [NetworkRouteEvidence.plausibleAlternativeRouteCount] tolerated before
     * [SafetyGateReasonCode.HIGH_NETWORK_AMBIGUITY] applies. `CALIBRATION REQUIRED`. */
    val maximumToleratedAlternativeRoutes: Int,

    /** Minimum [MatcherEvidence.nativeConfidence], when known, considered adequate. `null` means no
     * threshold is applied even when the confidence value is known (native confidence is
     * informative, never a calibrated correctness probability, so a policy may legitimately choose
     * not to gate on it at all). `CALIBRATION REQUIRED` when non-null. */
    val minimumMatcherConfidenceWhenKnown: Double?,

    /** Whether the gate may still proceed toward [ReconstructionSafetyDecision.AcceptReconstruction]
     * when [MatcherEvidence.nativeConfidence] is [ConfidenceValue.Unknown] (never silently treated as
     * zero). `CALIBRATION REQUIRED`. */
    val acceptWhenMatcherConfidenceUnknown: Boolean,
) {
    init {
        require(plausibleSpeedMetersPerSecond.start.isFinite()) {
            "plausibleSpeedMetersPerSecond.start must be finite, got ${plausibleSpeedMetersPerSecond.start}"
        }
        require(plausibleSpeedMetersPerSecond.endInclusive.isFinite()) {
            "plausibleSpeedMetersPerSecond.endInclusive must be finite (an unbounded upper limit would make " +
                "PHYSICALLY_IMPLAUSIBLE_KINEMATICS structurally unreachable), got ${plausibleSpeedMetersPerSecond.endInclusive}"
        }
        require(!plausibleSpeedMetersPerSecond.isEmpty()) {
            "plausibleSpeedMetersPerSecond must not be an empty range, got $plausibleSpeedMetersPerSecond"
        }
        require(plausibleSpeedMetersPerSecond.start >= 0.0) {
            "plausibleSpeedMetersPerSecond must not start below 0.0, got $plausibleSpeedMetersPerSecond"
        }
        require(minimumObservationSupport >= 1) {
            "minimumObservationSupport must be >= 1 -- zero has no legitimate safety meaning, got $minimumObservationSupport"
        }
        require(maximumInterpolatedObservationFraction in 0.0..1.0) {
            "maximumInterpolatedObservationFraction must be within [0.0, 1.0], got $maximumInterpolatedObservationFraction"
        }
        require(maximumToleratedAlternativeRoutes >= 0) {
            "maximumToleratedAlternativeRoutes must be >= 0, got $maximumToleratedAlternativeRoutes"
        }
        minimumMatcherConfidenceWhenKnown?.let {
            require(it.isFinite() && it in 0.0..1.0) {
                "minimumMatcherConfidenceWhenKnown must be finite and within [0.0, 1.0] when non-null, got $it"
            }
        }
    }
}
