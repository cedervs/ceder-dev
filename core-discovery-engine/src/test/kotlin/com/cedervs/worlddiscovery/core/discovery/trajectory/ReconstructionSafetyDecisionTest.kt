package com.cedervs.worlddiscovery.core.discovery.trajectory

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **PHASE 3A CORRECTION ROUND 1 note (Codex's M2 "decisions can be fabricated outside the gate").**
 * Every companion `of` factory exercised directly below is now `internal` (see
 * [ReconstructionSafetyDecision]'s own doc comment) — constructible here only because this test
 * source set compiles as part of the same Gradle module (`core-discovery-engine`) as the main
 * source, which is exactly the access this fix intentionally preserves (so these invariant-
 * enforcement tests can keep running as plain unit tests without reflection). A consuming module
 * (`app`, `core-location`, `feature-map`, ...) has no such access — the Kotlin compiler rejects the
 * same calls from outside this module at compile time, which is the actual fabrication threat this
 * round closes. This project has no separate consuming module wired up yet to compile a "this must
 * fail" negative-compilation test against, so that boundary is verified by inspection of the
 * `internal` modifier itself rather than by an executable cross-module test — see this round's own
 * final report for the honest scope of what `internal` does and does not prevent.
 */
class ReconstructionSafetyDecisionTest {

    private val support = SafetyGateReason(SafetyGateReasonCode.SUFFICIENT_OBSERVATION_SUPPORT)
    private val uncertainty = SafetyGateReason(SafetyGateReasonCode.SPARSE_OBSERVATIONS)
    private val hardFailure = SafetyGateReason(SafetyGateReasonCode.INVALID_TEMPORAL_STRUCTURE)

    // ==============================================================================================
    // AcceptReconstruction -- only SUPPORT-category reasons allowed.
    // ==============================================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `AcceptReconstruction with empty reasons is rejected`() {
        ReconstructionSafetyDecision.AcceptReconstruction.of(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `AcceptReconstruction carrying a HARD_FAILURE reason is rejected`() {
        ReconstructionSafetyDecision.AcceptReconstruction.of(listOf(support, hardFailure))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `AcceptReconstruction carrying an UNCERTAINTY reason is rejected`() {
        ReconstructionSafetyDecision.AcceptReconstruction.of(listOf(support, uncertainty))
    }

    @Test
    fun `AcceptReconstruction with only SUPPORT reasons is allowed`() {
        val result = ReconstructionSafetyDecision.AcceptReconstruction.of(listOf(support))

        assertEquals(listOf(support), result.reasons)
    }

    @Test
    fun `mutating the caller's list after constructing AcceptReconstruction does not affect it`() {
        val mutableReasons = mutableListOf(support)
        val result = ReconstructionSafetyDecision.AcceptReconstruction.of(mutableReasons)

        mutableReasons.add(SafetyGateReason(SafetyGateReasonCode.LOW_NETWORK_AMBIGUITY))

        assertEquals(1, result.reasons.size)
    }

    // ==============================================================================================
    // RequireMoreEvidence -- at least one UNCERTAINTY reason, no HARD_FAILURE reason.
    // ==============================================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `RequireMoreEvidence with empty reasons is rejected`() {
        ReconstructionSafetyDecision.RequireMoreEvidence.of(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `RequireMoreEvidence carrying a HARD_FAILURE reason is rejected`() {
        ReconstructionSafetyDecision.RequireMoreEvidence.of(listOf(uncertainty, hardFailure))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `RequireMoreEvidence with only SUPPORT reasons is rejected`() {
        ReconstructionSafetyDecision.RequireMoreEvidence.of(listOf(support))
    }

    @Test
    fun `RequireMoreEvidence with at least one UNCERTAINTY reason is allowed`() {
        val result = ReconstructionSafetyDecision.RequireMoreEvidence.of(listOf(uncertainty))

        assertEquals(listOf(uncertainty), result.reasons)
        assertEquals(null, result.cadenceRecommendation)
    }

    @Test
    fun `mutating the caller's list after constructing RequireMoreEvidence does not affect it`() {
        val mutableReasons = mutableListOf(uncertainty)
        val result = ReconstructionSafetyDecision.RequireMoreEvidence.of(mutableReasons)

        mutableReasons.add(SafetyGateReason(SafetyGateReasonCode.MATCHER_DISAGREEMENT))

        assertEquals(1, result.reasons.size)
    }

    // ==============================================================================================
    // NoReconstruction -- at least one HARD_FAILURE reason required.
    // ==============================================================================================

    @Test(expected = IllegalArgumentException::class)
    fun `NoReconstruction with empty reasons is rejected`() {
        ReconstructionSafetyDecision.NoReconstruction.of(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NoReconstruction with only UNCERTAINTY reasons is rejected`() {
        ReconstructionSafetyDecision.NoReconstruction.of(listOf(uncertainty))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NoReconstruction with only SUPPORT reasons is rejected`() {
        ReconstructionSafetyDecision.NoReconstruction.of(listOf(support))
    }

    @Test
    fun `NoReconstruction with at least one HARD_FAILURE reason is allowed, alongside uncertainty`() {
        val result = ReconstructionSafetyDecision.NoReconstruction.of(listOf(hardFailure, uncertainty))

        assertEquals(listOf(hardFailure, uncertainty), result.reasons)
    }

    @Test
    fun `mutating the caller's list after constructing NoReconstruction does not affect it`() {
        val mutableReasons = mutableListOf(hardFailure)
        val result = ReconstructionSafetyDecision.NoReconstruction.of(mutableReasons)

        mutableReasons.add(SafetyGateReason(SafetyGateReasonCode.UNSUPPORTED_BRIDGE))

        assertEquals(1, result.reasons.size)
    }

    // ==============================================================================================
    // Correction round §11: duplicate reasons if still constructible -- documents current behavior
    // rather than asserting a rejection that was never required. Nothing in this round's scope asked
    // for reason deduplication, so this is intentionally still allowed.
    // ==============================================================================================

    @Test
    fun `duplicate reasons are still constructible -- no deduplication is performed`() {
        val result = ReconstructionSafetyDecision.NoReconstruction.of(listOf(hardFailure, hardFailure))

        assertEquals(2, result.reasons.size)
        assertEquals(hardFailure, result.reasons[0])
        assertEquals(hardFailure, result.reasons[1])
    }
}
