package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.H3GridTraversal

/**
 * Evaluates whether a transition between two [LocationObservation]s is eligible to become a
 * reconstruction candidate, and if so, computes the candidate path — WITHOUT persisting anything
 * and WITHOUT ever calling `SubmitDiscoveryObservation` itself. Pure/testable: no I/O beyond the
 * injected [diagnosticLogger], which is best-effort and cannot affect the result.
 *
 * **Foreground-only, and not wired into [LocationTrackingSession] yet.** No functional notion of
 * "last accepted observation" exists anywhere in this codebase today — see
 * `docs/ai-context/LOCATION_TRACKING.md` — so nothing calls this during real tracking. Callers
 * supply candidate transitions explicitly; this class never asserts that either endpoint is a
 * validated anchor, and never will until a real trust/calibration policy exists.
 *
 * The eligibility policy is injected; the production default
 * ([DenyAllReconstructionEligibilityPolicy]) denies every transition — no numeric threshold
 * (accuracy, speed, freshness, distance) is decided here or anywhere in this increment.
 *
 * A produced [ReconstructionCandidate.Candidate] never includes `from`/`to`'s own cells — see its
 * doc comment: the H3 path is inclusive of both endpoints, but those came from real observations
 * and are stripped here before the candidate is returned, not left for a future caller to
 * remember to exclude.
 */
class ForegroundReconstructionScheduler(
    private val cellConverter: H3CellConverter,
    private val gridTraversal: H3GridTraversal,
    private val eligibilityPolicy: ReconstructionEligibilityPolicy = DenyAllReconstructionEligibilityPolicy(),
    private val diagnosticLogger: TransitionDiagnosticLogger = NoOpTransitionDiagnosticLogger(),
) {
    fun evaluateTransition(from: LocationObservation, to: LocationObservation): ReconstructionCandidate {
        val eligible = eligibilityPolicy.isEligible(from, to)

        if (!eligible) {
            diagnosticLogger.logSafely(buildReconstructionTransitionDiagnostics(from, to, eligible = false, pathCellCount = null))
            return ReconstructionCandidate.NotEligible
        }

        val originCell = cellConverter.toCanonicalCell(from.coordinate)
        val destinationCell = cellConverter.toCanonicalCell(to.coordinate)
        val path = gridTraversal.pathBetween(originCell, destinationCell)

        diagnosticLogger.logSafely(buildReconstructionTransitionDiagnostics(from, to, eligible = true, pathCellCount = path?.size))

        if (path == null) return ReconstructionCandidate.NoPath

        // path is inclusive of both endpoints (see H3GridTraversal's contract) — origin and
        // destination came from real observations and must never be presented to a future caller
        // as RECONSTRUCTED (see ReconstructionCandidate.Candidate's doc comment), so they are
        // stripped here, not left for the caller to remember to exclude.
        val intermediateCells = path.drop(1).dropLast(1)
        return ReconstructionCandidate.Candidate(intermediateCells)
    }
}
