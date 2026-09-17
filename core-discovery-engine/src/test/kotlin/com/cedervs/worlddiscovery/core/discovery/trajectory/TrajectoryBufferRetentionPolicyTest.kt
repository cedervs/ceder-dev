package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrajectoryBufferRetentionPolicyTest {

    @Test
    fun `boundedByAge sets only the age bound`() {
        val ageOnly = TrajectoryBufferRetentionPolicy.boundedByAge(Duration.ofHours(6))
        assertEquals(Duration.ofHours(6), ageOnly.maxAge)
        assertNull(ageOnly.maxObservationCount)
    }

    @Test
    fun `boundedByCount sets only the count bound`() {
        val countOnly = TrajectoryBufferRetentionPolicy.boundedByCount(5000)
        assertNull(countOnly.maxAge)
        assertEquals(5000, countOnly.maxObservationCount)
    }

    @Test
    fun `bounded sets both bounds`() {
        val both = TrajectoryBufferRetentionPolicy.bounded(Duration.ofHours(6), 5000)
        assertEquals(Duration.ofHours(6), both.maxAge)
        assertEquals(5000, both.maxObservationCount)
    }

    @Test
    fun `unboundedForTestingOnly has no bound on either axis`() {
        val unbounded = TrajectoryBufferRetentionPolicy.unboundedForTestingOnly()
        assertNull(unbounded.maxAge)
        assertNull(unbounded.maxObservationCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `boundedByAge rejects a zero maxAge`() {
        TrajectoryBufferRetentionPolicy.boundedByAge(Duration.ZERO)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `boundedByAge rejects a negative maxAge`() {
        TrajectoryBufferRetentionPolicy.boundedByAge(Duration.ofHours(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `boundedByCount rejects a zero maxObservationCount`() {
        TrajectoryBufferRetentionPolicy.boundedByCount(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `boundedByCount rejects a negative maxObservationCount`() {
        TrajectoryBufferRetentionPolicy.boundedByCount(-10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bounded rejects a zero maxAge even when maxObservationCount is valid`() {
        TrajectoryBufferRetentionPolicy.bounded(Duration.ZERO, 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bounded rejects a zero maxObservationCount even when maxAge is valid`() {
        TrajectoryBufferRetentionPolicy.bounded(Duration.ofHours(6), 0)
    }
}
