package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * A coarse transport-mode vocabulary for trajectory reconstruction — deliberately mirrors
 * `docs/discovery-engine.md` §1's own list (immobile/marche/vélo/voiture/train/bateau/avion)
 * rather than inventing a new one. No classification logic exists anywhere yet; this is only the
 * shared vocabulary a future strategy would report a hypothesis in. `AIR` is included for
 * completeness of the vocabulary even though `docs/discovery-engine.md` §2 already establishes
 * that flight never contributes to ground discovery — a reconstruction strategy detecting `AIR`
 * would be expected to produce [TrajectoryReconstructionResult.UnsupportedMode], never a
 * geometry.
 */
enum class TransportMode {
    STATIONARY,
    WALK,
    BICYCLE,
    ROAD_VEHICLE,
    RAIL,
    WATERBORNE,
    AIR,
    UNKNOWN,
}

/** A transport-mode guess with its own confidence — never asserted as fact. [confidence] uses the
 * same [ConfidenceValue] contract as [ReconstructionConfidence]'s components, for the same reason:
 * "not yet evaluated" must be representable, not guessed. */
data class TransportModeHypothesis(
    val mode: TransportMode,
    val confidence: ConfidenceValue = ConfidenceValue.Unknown,
)
