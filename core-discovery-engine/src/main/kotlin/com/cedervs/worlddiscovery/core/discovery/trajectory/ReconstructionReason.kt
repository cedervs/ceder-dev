package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * Codified, explainable reasons a [TrajectoryReconstructionResult] came out the way it did —
 * every result variant (including [TrajectoryReconstructionResult.NoReconstruction]) carries a
 * list of these, never a bare unexplained outcome. Deliberately an open/extensible enum with an
 * [OTHER] escape hatch rather than a closed set tied to one future engine's own vocabulary.
 */
enum class ReconstructionReasonCode {
    /** The gap between two observations exceeds whatever bound the active strategy/configuration
     * considers plausible for continuous travel. */
    GAP_TOO_LONG,

    /** The implied speed/kinematics between two observations are implausible for any supported
     * transport mode. */
    IMPLAUSIBLE_KINEMATICS,

    /** Reported GPS accuracy is too poor for the strategy to trust the observations it has. */
    LOW_GPS_QUALITY,

    /** Multiple candidate paths/networks are plausible with no clear winner. */
    NETWORK_AMBIGUITY,

    /** The transport mode itself could not be determined with enough confidence to proceed. */
    MODE_UNCERTAINTY,

    /** Too few observations in the window to attempt anything. */
    INSUFFICIENT_OBSERVATIONS,

    /** The inferred/likely transport mode is one this strategy does not attempt to reconstruct
     * (e.g. open water, air travel — see `docs/discovery-engine.md` §2). */
    UNSUPPORTED_TRANSPORT_MODE,

    /** No usable network/graph data is available for this area (relevant once a real map-matching
     * graph exists — Phase 1 has none). */
    GRAPH_UNAVAILABLE,

    /** Reconstruction is disabled/not yet implemented for this configuration — the only reason
     * [NoOpTrajectoryReconstructor] ever produces. */
    CONFIGURATION_DISABLED,

    /** Any cause not covered above — see the accompanying [ReconstructionReason.detail] for
     * free-text context. Never used as a substitute for adding a real code once a genuine
     * recurring cause is identified. */
    OTHER,
}

/** One explainable reason, with an optional free-text [detail] for diagnostics — never used to
 * carry anything privacy-sensitive (no raw coordinates, no user-identifying content). */
data class ReconstructionReason(
    val code: ReconstructionReasonCode,
    val detail: String? = null,
)
