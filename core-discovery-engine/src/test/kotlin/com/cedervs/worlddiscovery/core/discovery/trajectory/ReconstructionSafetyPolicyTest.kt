package com.cedervs.worlddiscovery.core.discovery.trajectory

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 3A Correction Round 1 — Codex's M1 ("policy permits structurally unsafe configurations").
 * `requireStructuralContinuity` was removed entirely (bridge protection is now non-negotiable, never
 * policy-controlled — see [ReconstructionSafetyGateTest]'s `B3 - policyCannotDisableUnsupportedBridgeProtection`).
 * Numeric validation is tightened here: infinite/non-finite speed bounds and a zero
 * [ReconstructionSafetyPolicy.minimumObservationSupport] are now both rejected at construction.
 */
class ReconstructionSafetyPolicyTest {

    private fun policy(
        plausibleSpeedMetersPerSecond: ClosedFloatingPointRange<Double> = 0.0..55.0,
        minimumObservationSupport: Int = 5,
        maximumInterpolatedObservationFraction: Double = 0.5,
        maximumToleratedAlternativeRoutes: Int = 2,
        minimumMatcherConfidenceWhenKnown: Double? = 0.6,
    ) = ReconstructionSafetyPolicy(
        transportMode = TransportMode.ROAD_VEHICLE,
        plausibleSpeedMetersPerSecond = plausibleSpeedMetersPerSecond,
        minimumObservationSupport = minimumObservationSupport,
        maximumInterpolatedObservationFraction = maximumInterpolatedObservationFraction,
        maximumToleratedAlternativeRoutes = maximumToleratedAlternativeRoutes,
        minimumMatcherConfidenceWhenKnown = minimumMatcherConfidenceWhenKnown,
        acceptWhenMatcherConfidenceUnknown = false,
    )

    @Test
    fun `a valid policy is constructed with its own field values`() {
        val result = policy()

        assertEquals(TransportMode.ROAD_VEHICLE, result.transportMode)
        assertEquals(5, result.minimumObservationSupport)
    }

    @Test
    fun `ReconstructionSafetyPolicy has no field capable of disabling bridge protection`() {
        val fields = ReconstructionSafetyPolicy::class.java.declaredFields.map { it.name }
        assertEquals(
            emptyList<String>(),
            fields.filter { it.contains("structuralContinuity", ignoreCase = true) || it.contains("bridge", ignoreCase = true) },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty speed range is rejected`() {
        policy(plausibleSpeedMetersPerSecond = 10.0..5.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a speed range starting below zero is rejected`() {
        policy(plausibleSpeedMetersPerSecond = (-1.0)..10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an infinite upper speed bound is rejected -- it would make PHYSICALLY_IMPLAUSIBLE_KINEMATICS unreachable`() {
        policy(plausibleSpeedMetersPerSecond = 0.0..Double.POSITIVE_INFINITY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a NaN lower speed bound is rejected`() {
        policy(plausibleSpeedMetersPerSecond = Double.NaN..10.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero minimumObservationSupport is rejected -- it has no legitimate safety meaning`() {
        policy(minimumObservationSupport = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative minimumObservationSupport is rejected`() {
        policy(minimumObservationSupport = -1)
    }

    @Test
    fun `minimumObservationSupport of exactly 1 is allowed`() {
        val result = policy(minimumObservationSupport = 1)

        assertEquals(1, result.minimumObservationSupport)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an interpolated fraction above 1_0 is rejected`() {
        policy(maximumInterpolatedObservationFraction = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative interpolated fraction is rejected`() {
        policy(maximumInterpolatedObservationFraction = -0.1)
    }

    @Test
    fun `an interpolated fraction of exactly 0_0 or 1_0 is allowed (boundary)`() {
        assertEquals(0.0, policy(maximumInterpolatedObservationFraction = 0.0).maximumInterpolatedObservationFraction, 0.0)
        assertEquals(1.0, policy(maximumInterpolatedObservationFraction = 1.0).maximumInterpolatedObservationFraction, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative maximumToleratedAlternativeRoutes is rejected`() {
        policy(maximumToleratedAlternativeRoutes = -1)
    }

    @Test
    fun `maximumToleratedAlternativeRoutes of exactly 0 is allowed (boundary)`() {
        val result = policy(maximumToleratedAlternativeRoutes = 0)

        assertEquals(0, result.maximumToleratedAlternativeRoutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a minimumMatcherConfidenceWhenKnown above 1_0 is rejected`() {
        policy(minimumMatcherConfidenceWhenKnown = 1.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative minimumMatcherConfidenceWhenKnown is rejected`() {
        policy(minimumMatcherConfidenceWhenKnown = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a NaN minimumMatcherConfidenceWhenKnown is rejected`() {
        policy(minimumMatcherConfidenceWhenKnown = Double.NaN)
    }

    @Test
    fun `minimumMatcherConfidenceWhenKnown of exactly 0_0 or 1_0 is allowed (boundary)`() {
        assertEquals(0.0, policy(minimumMatcherConfidenceWhenKnown = 0.0).minimumMatcherConfidenceWhenKnown!!, 0.0)
        assertEquals(1.0, policy(minimumMatcherConfidenceWhenKnown = 1.0).minimumMatcherConfidenceWhenKnown!!, 0.0)
    }

    @Test
    fun `a null minimumMatcherConfidenceWhenKnown means no threshold is applied`() {
        val result = policy(minimumMatcherConfidenceWhenKnown = null)

        assertEquals(null, result.minimumMatcherConfidenceWhenKnown)
    }
}
