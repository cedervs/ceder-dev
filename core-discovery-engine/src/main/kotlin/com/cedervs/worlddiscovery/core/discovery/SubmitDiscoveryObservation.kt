package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * The discovery engine's public entry point (docs/discovery-engine.md §7 "Discovery Core API"):
 *
 * ```
 * coordinate -> canonical H3 cell -> versioned DiscoveryEvent -> local discovered-cell persistence
 * ```
 *
 * This is deliberately the *only* thing a future location-tracking component needs to call.
 * It has no opinion on where [Coordinate] came from — GNSS, fused location, route
 * reconstruction, or imported evidence are all just a [Provenance] value supplied by the
 * caller. GPS acquisition, permissions, signal fusion/filtering, and background tracking are
 * explicitly out of scope for this phase and for this class.
 *
 * [diagnosticSink] is TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE (see [DiscoveryDiagnosticSink]'s
 * doc comment) — best-effort, never affects the return value or which exception (if any)
 * propagates; see [recordSafely].
 */
class SubmitDiscoveryObservation(
    private val cellConverter: H3CellConverter,
    private val repository: DiscoveredCellRepository,
    private val diagnosticSink: DiscoveryDiagnosticSink = NoOpDiscoveryDiagnosticSink(),
) {
    /**
     * [diagnosticDeliveryId] is TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE, purely additive and
     * diagnostic-only — an opaque caller-supplied string threaded through unchanged into
     * [DiscoveryObservationDiagnostics.deliveryId], so a background batch's `LOCATION_DELIVERED`
     * calibration event can be explicitly correlated with the matching `DISCOVERY_RESULT` it
     * produced (see [SubmitBackgroundLocationObservations], which passes `"$batchId:$index"`),
     * instead of an analyst having to infer that pairing from file ordering alone. Left `null` by
     * every foreground/one-shot caller ([LocationTrackingSession], `SubmitCurrentLocationUseCase`):
     * correlating those would require threading a new field through
     * `LocationAcquisitionResult.Success` — a core, widely-used domain type — purely for
     * diagnostic purposes, which is more invasive than this temporary logger justifies. Those
     * paths are single-fix-per-delivery by construction, so `DISCOVERY_RESULT` immediately follows
     * its own `LOCATION_DELIVERED` in the file with no interleaving possible (foreground and
     * background tracking are never simultaneously active — see
     * `AppForegroundTrackingController`), making file-adjacency alone reliable enough there.
     */
    suspend operator fun invoke(
        coordinate: Coordinate,
        timestamp: Instant,
        provenance: Provenance,
        trustStatus: TrustStatus,
        diagnosticDeliveryId: String? = null,
    ): DiscoveredCell {
        val cell = try {
            cellConverter.toCanonicalCell(coordinate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately catches only Exception, never Throwable/Error — a fatal JVM Error
            // (OutOfMemoryError, StackOverflowError, ...) must propagate completely untouched, not
            // be turned into an ordinary domain rejection. See DiscoveryObservationOutcome
            // .REJECTED_PRE_H3's doc comment for why this branch exists at all: previously, a
            // conversion failure here (outside any diagnostic boundary) could produce a
            // LOCATION_DELIVERED calibration event with no matching DISCOVERY_RESULT.
            diagnosticSink.recordSafely(
                DiscoveryObservationDiagnostics(
                    h3Cell = null,
                    trustStatus = trustStatus,
                    provenance = provenance,
                    outcome = DiscoveryObservationOutcome.REJECTED_PRE_H3,
                    rejectionReason = e::class.simpleName,
                    deliveryId = diagnosticDeliveryId,
                ),
            )
            throw e
        }

        val event = DiscoveryEvent(
            cell = cell,
            timestamp = timestamp,
            provenance = provenance,
            trustStatus = trustStatus,
            engineVersion = DiscoveryEngineVersion.CURRENT,
        )

        try {
            val existing = repository.find(cell, trustStatus)
            val merged = DiscoveredCellMerger.merge(existing, event)
            repository.upsert(merged)
            diagnosticSink.recordSafely(
                DiscoveryObservationDiagnostics(
                    h3Cell = cell.h3Index,
                    trustStatus = trustStatus,
                    provenance = provenance,
                    outcome = if (existing == null) {
                        DiscoveryObservationOutcome.NEW_CELL
                    } else {
                        DiscoveryObservationOutcome.MERGED_EXISTING
                    },
                    rejectionReason = null,
                    deliveryId = diagnosticDeliveryId,
                ),
            )
            return merged
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Diagnostic-only recording of a genuine submission failure — the exception itself is
            // always rethrown unchanged, exactly as before this diagnostic seam was added; only
            // the exception's class name is recorded, never its message (see
            // DiscoveryObservationOutcome.REJECTED's doc comment).
            diagnosticSink.recordSafely(
                DiscoveryObservationDiagnostics(
                    h3Cell = cell.h3Index,
                    trustStatus = trustStatus,
                    provenance = provenance,
                    outcome = DiscoveryObservationOutcome.REJECTED,
                    rejectionReason = e::class.simpleName,
                    deliveryId = diagnosticDeliveryId,
                ),
            )
            throw e
        }
    }
}
