package com.cedervs.worlddiscovery.core.discovery

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — part of the background-tracking calibration
 * diagnostic logger (see `docs/ai-context/LOCATION_TRACKING.md`). This is NOT production
 * analytics/telemetry, NOT a product feature, and NOT a permanent part of the discovery engine —
 * it exists solely so a physical calibration trip can be inspected offline afterward. Remove this
 * file, [SubmitDiscoveryObservation]'s [DiscoveryDiagnosticSink] parameter, and its
 * Android-backed implementation (`AndroidDiscoveryDiagnosticSink` in `:core-location`) once
 * background tracking calibration is complete.
 *
 * What happened when [SubmitDiscoveryObservation] processed one observation — distinguishes a
 * genuinely new H3 cell from a merge into an already-known one (see
 * [DiscoveredCellMerger] — this is exactly the ambiguity that can make dense-but-repeated fixes
 * look identical to a single fix in `discovered_cells` row counts) from a submission that failed
 * before persistence completed.
 */
enum class DiscoveryObservationOutcome {
    /** No [DiscoveredCell] existed yet for this `(cell, trustStatus)` pair. */
    NEW_CELL,

    /** An existing [DiscoveredCell] was found and merged — see [DiscoveredCellMerger]. */
    MERGED_EXISTING,

    /** [H3CellConverter.toCanonicalCell] itself threw, before any H3 cell existed at all —
     * [DiscoveryObservationDiagnostics.h3Cell] is `null` for this outcome, never a guessed or
     * partially-computed value. Codex review round: previously, a conversion failure here would
     * silently produce a `LOCATION_DELIVERED` calibration event with no matching
     * `DISCOVERY_RESULT` at all, since the conversion call sat outside the diagnostic try
     * boundary — this outcome makes that failure mode explicit and diagnosable instead of
     * indistinguishable from "never reached this code." */
    REJECTED_PRE_H3,

    /** [DiscoveredCellRepository.find] or [DiscoveredCellRepository.upsert] threw, *after* H3
     * conversion already succeeded — the observation never reached durable storage.
     * [DiscoveryObservationDiagnostics.rejectionReason] carries the exception's class name only,
     * never its message (which could carry arbitrary, potentially sensitive detail). */
    REJECTED,
}

/**
 * Deliberately never carries a raw [Coordinate] — only [h3Cell] (an already-derived, already-
 * persisted identifier at the same resolution [DiscoveredCell] itself uses), matching this whole
 * diagnostic logger's privacy-minimization requirement. `null` only for
 * [DiscoveryObservationOutcome.REJECTED_PRE_H3], where no cell was ever successfully computed.
 *
 * [deliveryId] lets a background-batch delivery's `LOCATION_DELIVERED` calibration event be
 * explicitly correlated with the matching `DISCOVERY_RESULT` it produced, instead of relying on
 * file ordering alone — see [SubmitDiscoveryObservation.invoke]'s `diagnosticDeliveryId`
 * parameter for where this comes from. `null` for callers that don't supply one (the foreground/
 * one-shot paths — see that parameter's doc comment for why).
 */
data class DiscoveryObservationDiagnostics(
    val h3Cell: String?,
    val trustStatus: TrustStatus,
    val provenance: Provenance,
    val outcome: DiscoveryObservationOutcome,
    val rejectionReason: String?,
    val deliveryId: String? = null,
)

/**
 * Diagnostic-only logging seam for a [DiscoveryObservationDiagnostics] — deliberately a pure
 * Kotlin interface with zero Android dependency, matching every other diagnostic seam in this
 * codebase (see `core-location`'s `LocationDiagnosticLogger`): the real, file-writing
 * implementation lives in `:core-location` as `AndroidDiscoveryDiagnosticSink`, since
 * `:core-discovery-engine` itself must stay a plain-JVM module with no Android dependency.
 *
 * Same non-throwing contract as every other diagnostic seam here — [recordSafely] is the actual
 * enforcement point, so a misbehaving implementation can never affect whether
 * [SubmitDiscoveryObservation] returns normally or rethrows.
 */
interface DiscoveryDiagnosticSink {
    fun record(diagnostics: DiscoveryObservationDiagnostics)
}

/** Default used by [SubmitDiscoveryObservation]'s constructor parameter — logging is opt-in,
 * wired explicitly in production (see `AppContainer`); tests that don't care about it need not
 * pass anything. */
class NoOpDiscoveryDiagnosticSink : DiscoveryDiagnosticSink {
    override fun record(diagnostics: DiscoveryObservationDiagnostics) = Unit
}

/** See [DiscoveryDiagnosticSink]'s doc comment's "non-throwing contract" paragraph. Does not
 * special-case [kotlinx.coroutines.CancellationException]: [record] is never `suspend`, so any
 * such exception here can only be fabricated by a misbehaving implementation, never genuine job
 * cancellation — see `LocationDiagnosticLogger.logSafely`'s doc comment for the identical
 * reasoning applied elsewhere in this codebase. */
fun DiscoveryDiagnosticSink.recordSafely(diagnostics: DiscoveryObservationDiagnostics) {
    try {
        record(diagnostics)
    } catch (t: Throwable) {
        // Diagnostic-only — must never affect whether the caller's submission succeeded/rethrew.
    }
}
