package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.H3GridTraversal

/**
 * Debug-only, diagnostic-only computation of a transition between two consecutive foreground
 * [LocationObservation]s — for field calibration (see `docs/ai-context/LOCATION_TRACKING.md`),
 * completely independent of [ReconstructionEligibilityPolicy] and entirely separate from
 * [ForegroundReconstructionScheduler] (the only thing that can ever produce a
 * [ReconstructionCandidate]; it stays exactly as before, still gated by its policy, still unused
 * in production).
 *
 * This class:
 * - never evaluates eligibility (there is no policy involved at all);
 * - always computes the H3 path between the two observations' cells, purely to report a cell
 *   count / success-failure via [TransitionDiagnosticLogger] — never to seed a reconstruction;
 * - never returns a [ReconstructionCandidate];
 * - never persists anything.
 */
class ForegroundTransitionDiagnostics(
    private val cellConverter: H3CellConverter,
    private val gridTraversal: H3GridTraversal,
    private val diagnosticLogger: TransitionDiagnosticLogger = NoOpTransitionDiagnosticLogger(),
) {
    fun record(from: LocationObservation, to: LocationObservation) {
        val originCell = cellConverter.toCanonicalCell(from.coordinate)
        val destinationCell = cellConverter.toCanonicalCell(to.coordinate)
        val path = gridTraversal.pathBetween(originCell, destinationCell)

        diagnosticLogger.logSafely(
            buildReconstructionTransitionDiagnostics(from, to, eligible = false, pathCellCount = path?.size),
        )
    }
}
