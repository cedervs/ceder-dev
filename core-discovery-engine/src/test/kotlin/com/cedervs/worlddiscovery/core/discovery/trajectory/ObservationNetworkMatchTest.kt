package com.cedervs.worlddiscovery.core.discovery.trajectory

import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationNetworkMatchTest {

    @Test
    fun `a null distanceMeters is allowed -- distance not always known`() {
        val match = ObservationNetworkMatch(observationIndex = 0, candidateDescription = "edge-1", distanceMeters = null)

        assertEquals(null, match.distanceMeters)
    }

    @Test
    fun `a zero distanceMeters is allowed`() {
        val match = ObservationNetworkMatch(observationIndex = 0, candidateDescription = "edge-1", distanceMeters = 0.0)

        assertEquals(0.0, match.distanceMeters)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative distanceMeters is rejected`() {
        ObservationNetworkMatch(observationIndex = 0, candidateDescription = "edge-1", distanceMeters = -1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NaN distanceMeters is rejected`() {
        ObservationNetworkMatch(observationIndex = 0, candidateDescription = "edge-1", distanceMeters = Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `positive Infinity distanceMeters is rejected`() {
        ObservationNetworkMatch(observationIndex = 0, candidateDescription = "edge-1", distanceMeters = Double.POSITIVE_INFINITY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative Infinity distanceMeters is rejected`() {
        ObservationNetworkMatch(observationIndex = 0, candidateDescription = "edge-1", distanceMeters = Double.NEGATIVE_INFINITY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative observationIndex is rejected`() {
        ObservationNetworkMatch(observationIndex = -1, candidateDescription = "edge-1")
    }
}
