package com.cedervs.worlddiscovery.core.discovery.trajectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClaimTokenTest {

    @Test
    fun `a non-blank value is accepted`() {
        assertEquals("token-1", ClaimToken("token-1").value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty string is rejected`() {
        ClaimToken("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank (whitespace-only) string is rejected`() {
        ClaimToken("   ")
    }

    @Test
    fun `UuidClaimTokenGenerator produces a non-blank token`() {
        val token = UuidClaimTokenGenerator.generate()
        assertEquals(false, token.value.isBlank())
    }

    @Test
    fun `UuidClaimTokenGenerator produces a different token on each call`() {
        val first = UuidClaimTokenGenerator.generate()
        val second = UuidClaimTokenGenerator.generate()

        assertNotEquals(first, second)
    }

    @Test
    fun `ClaimedObservationBatch carries the token and its observations together`() {
        val token = ClaimToken("token-1")
        val batch = ClaimedObservationBatch(claimToken = token, observations = emptyList())

        assertEquals(token, batch.claimToken)
        assertEquals(emptyList<BufferedObservationRecord>(), batch.observations)
    }
}
