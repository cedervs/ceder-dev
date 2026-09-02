package com.cedervs.worlddiscovery.core.location

/**
 * A pure, injectable decision of whether a transition between two consecutive
 * [LocationObservation]s is eligible to become a reconstruction candidate — see
 * [ForegroundReconstructionScheduler]. No numeric threshold is decided anywhere in this module;
 * see `docs/ai-context/LOCATION_TRACKING.md` for what remains CALIBRATION REQUIRED before any
 * real policy can replace [DenyAllReconstructionEligibilityPolicy].
 *
 * Deliberately does not receive or infer any notion of "trusted anchor": until a real trust/
 * calibration policy exists, no observation is treated as validated ground truth here.
 */
interface ReconstructionEligibilityPolicy {
    fun isEligible(from: LocationObservation, to: LocationObservation): Boolean
}

/**
 * The only policy wired anywhere today — denies every transition, unconditionally. Reconstruction
 * stays entirely inert (no [ReconstructionCandidate.Candidate] can ever be produced in production)
 * until a real, calibrated policy replaces this.
 */
class DenyAllReconstructionEligibilityPolicy : ReconstructionEligibilityPolicy {
    override fun isEligible(from: LocationObservation, to: LocationObservation): Boolean = false
}
