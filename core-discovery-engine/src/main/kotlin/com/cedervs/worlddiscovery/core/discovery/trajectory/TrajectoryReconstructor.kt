package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * Configuration/environment passed alongside an [ObservationWindow] — kept intentionally minimal
 * in Phase 1 (just [engineVersion], mirroring `DiscoveryEngineVersion`'s own existing versioning
 * discipline for the canonical H3 pipeline). A future strategy will likely need more (graph
 * source/version, calibration parameters) — deliberately not modeled yet, to avoid guessing a
 * shape around an engine that isn't chosen (`docs/ai-context/LOCATION_TRACKING.md`).
 */
data class TrajectoryReconstructionContext(
    val engineVersion: Int,
)

/**
 * The future trajectory-reconstruction/map-matching entry point — **no implementation of this
 * exists yet beyond [NoOpTrajectoryReconstructor]**. Pure by contract: an implementation must not
 * perform I/O, must not depend on Android/Room/MapLibre, and must not assume any specific graph
 * engine. See `docs/ai-context/LOCATION_TRACKING.md` for the full Phase 1 status and explicit
 * list of what is deliberately not built yet (H3 conversion of a result, `Provenance.RECONSTRUCTED`
 * writes, any real geometric or road-network matching logic).
 */
fun interface TrajectoryReconstructor {
    fun reconstruct(window: ObservationWindow, context: TrajectoryReconstructionContext): TrajectoryReconstructionResult
}

/**
 * The only [TrajectoryReconstructor] wired anywhere today — always reports
 * [TrajectoryReconstructionResult.NoReconstruction] with
 * [ReconstructionReasonCode.CONFIGURATION_DISABLED], mirroring
 * `core-location`'s existing `DenyAllReconstructionEligibilityPolicy` pattern for the same
 * "infrastructure exists, nothing has activated it yet" posture. Reconstruction stays entirely
 * inert until a real implementation replaces this.
 */
class NoOpTrajectoryReconstructor : TrajectoryReconstructor {
    override fun reconstruct(
        window: ObservationWindow,
        context: TrajectoryReconstructionContext,
    ): TrajectoryReconstructionResult = TrajectoryReconstructionResult.NoReconstruction(
        reasons = listOf(
            ReconstructionReason(
                code = ReconstructionReasonCode.CONFIGURATION_DISABLED,
                detail = "No TrajectoryReconstructor implementation exists yet -- Phase 1 foundations only.",
            ),
        ),
    )
}
