package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.time.Instant

/**
 * PHASE 1 CONTRACT ONLY — no adaptive-cadence machinery exists or runs yet. This is the pure
 * expression of an *intention* a future [TrajectoryReconstructor] (or a future windowing
 * component) could emit — never a command aimed at any specific location API. The domain must
 * never say "ask Android for updates every 5 seconds"; it says "I would benefit from a denser
 * observation stream right now, and here is why" — a future Android-side component (not built in
 * this phase) would be the only place that ever translates this into real
 * `LocationRequest`/`FusedLocationProviderClient` parameters. See
 * `docs/ai-context/LOCATION_TRACKING.md` for the full status and the explicit list of what this
 * phase does **not** touch (current foreground/background intervals, `maxUpdateDelay`, priority).
 */
enum class ObservationCadenceLevel {
    /** The current observation density is sufficient; no change requested. */
    NORMAL,

    /** A moderately denser stream would help (e.g. approaching a known-ambiguous area). */
    INCREASED,

    /** A significantly denser stream is wanted right now — e.g. genuine real-time ambiguity that
     * only more observations can resolve. Expected to be requested sparingly and briefly; see the
     * class doc comment's hysteresis note. */
    HIGH,
}

/** Codified, explainable reasons behind an [ObservationCadenceRecommendation] — mirrors
 * [ReconstructionReasonCode]'s "open enum with an escape hatch" shape for the same reason. */
enum class ObservationCadenceReasonCode {
    /** Multiple plausible paths/networks with no clear winner — more observations may resolve it. */
    NETWORK_AMBIGUITY,

    /** Approaching or inside an area with many closely-spaced intersections/parallel routes. */
    DENSE_INTERSECTIONS,

    /** Recent observations report poor accuracy, making the existing cadence less useful than it
     * would otherwise be. */
    LOW_GPS_QUALITY,

    /** The transport mode itself is uncertain (e.g. walk vs. bicycle at similar speeds). */
    MODE_UNCERTAINTY,

    /** Any cause not covered above — see the accompanying [ObservationCadenceReason.detail]. */
    OTHER,
}

data class ObservationCadenceReason(
    val code: ObservationCadenceReasonCode,
    val detail: String? = null,
)

/**
 * One recommendation snapshot. [recommendedAt] is when it was produced (not itself an expiry —
 * this phase defines no lifetime/hysteresis policy at all; see the class doc comment). A future
 * `ObservationCadenceAdvisor`-shaped component would be responsible for **debouncing/hysteresis**
 * between levels (never flipping `HIGH`/`NORMAL` on every single observation) — that state machine
 * is explicitly not designed or built in this phase, only anticipated here in documentation so its
 * eventual shape doesn't have to retrofit this contract.
 */
data class ObservationCadenceRecommendation(
    val level: ObservationCadenceLevel,
    val reasons: List<ObservationCadenceReason>,
    val recommendedAt: Instant,
)
