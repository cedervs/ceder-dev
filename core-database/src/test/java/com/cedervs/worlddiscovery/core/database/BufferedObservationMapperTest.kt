package com.cedervs.worlddiscovery.core.database

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationProcessingState
import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationRecord
import com.cedervs.worlddiscovery.core.discovery.trajectory.ClaimToken
import com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class BufferedObservationMapperTest {

    private fun record(
        id: Long = 7L,
        processingState: BufferedObservationProcessingState = BufferedObservationProcessingState.PENDING,
        claimToken: ClaimToken? = null,
        processingStartedAt: Instant? = null,
        processedAt: Instant? = null,
    ) = BufferedObservationRecord(
        id = id,
        dedupKey = "strong|domain:session-1|ert:123",
        source = TrajectoryObservationSource.FOREGROUND,
        processSessionId = "session-1",
        elapsedRealtimeClockDomainId = "session-1",
        elapsedRealtimeNanos = 123L,
        coordinate = Coordinate(latitude = 48.8566, longitude = 2.3522),
        providerTimeEpochMillis = 1700000000000L,
        receivedAt = Instant.parse("2026-01-01T10:00:05Z"),
        accuracyMeters = 12.5f,
        speedMetersPerSecond = 1.4f,
        speedAccuracyMetersPerSecond = 0.3f,
        bearingDegrees = 87f,
        bearingAccuracyDegrees = 15f,
        provider = "fused",
        isMockLocation = false,
        batchId = "batch-1",
        indexInBatch = 2,
        processingState = processingState,
        claimToken = claimToken,
        processingStartedAt = processingStartedAt,
        processedAt = processedAt,
    )

    @Test
    fun `domain to entity to domain round-trips without loss -- PENDING`() {
        val original = record()

        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `domain to entity to domain round-trips without loss -- PROCESSING with a claimToken and started timestamp`() {
        val original = record(
            processingState = BufferedObservationProcessingState.PROCESSING,
            claimToken = ClaimToken("token-1"),
            processingStartedAt = Instant.parse("2026-01-01T10:05:00Z"),
        )

        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `domain to entity to domain round-trips without loss -- PROCESSED retains claimToken and both timestamps`() {
        val original = record(
            processingState = BufferedObservationProcessingState.PROCESSED,
            claimToken = ClaimToken("token-1"),
            processingStartedAt = Instant.parse("2026-01-01T10:05:00Z"),
            processedAt = Instant.parse("2026-01-01T10:05:02Z"),
        )

        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `domain to entity to domain round-trips without loss -- every optional metadata field null`() {
        val original = record().copy(
            accuracyMeters = null,
            speedMetersPerSecond = null,
            speedAccuracyMetersPerSecond = null,
            bearingDegrees = null,
            bearingAccuracyDegrees = null,
            provider = null,
            batchId = null,
            indexInBatch = null,
        )

        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `entity stores stable string codes for source and processing state`() {
        // DISCARDED, like PENDING, never carries claimToken/processingStartedAt (see the
        // BufferedObservationRecord state invariants) -- the default null/null/null fixture already
        // satisfies that, no need to reach for PROCESSING/PROCESSED here.
        val entity = record(processingState = BufferedObservationProcessingState.DISCARDED).toEntity()

        assertEquals("FOREGROUND", entity.source)
        assertEquals("DISCARDED", entity.processingState)
    }
}
