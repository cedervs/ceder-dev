package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.trajectory.BufferedObservationProcessingState
import com.cedervs.worlddiscovery.core.discovery.trajectory.TrajectoryObservationSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers [buildBufferedObservationRecord] -- the bridge from this module's own [LocationObservation]
 * into the pure `core-discovery-engine.trajectory` domain. Nothing here exercises a real
 * `android.location.Location` (that boundary is [LocationObservationConversionTest]'s job); this
 * file only proves the bridge itself is correct and total. */
class TrajectoryObservationCaptureTest {

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)

    private fun observation(
        elapsedRealtimeNanos: Long = 1_000_000_000L,
        accuracyMeters: Float? = 12.5f,
    ) = LocationObservation(
        coordinate = paris,
        observedAt = Instant.parse("2026-01-01T10:00:00Z"),
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = 1.4f,
        provider = "fused",
        bearingDegrees = 87f,
        bearingAccuracyDegrees = 15f,
        speedAccuracyMetersPerSecond = 0.3f,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        isMockLocation = false,
    )

    @Test
    fun `every LocationObservation field is preserved in the built record`() {
        val record = buildBufferedObservationRecord(
            observation = observation(),
            source = TrajectoryObservationSource.FOREGROUND,
            processSessionId = "session-1",
            elapsedRealtimeClockDomainId = "session-1",
            receivedAt = Instant.parse("2026-01-01T10:00:02Z"),
        )

        assertEquals(paris, record.coordinate)
        assertEquals(Instant.parse("2026-01-01T10:00:00Z").toEpochMilli(), record.providerTimeEpochMillis)
        assertEquals(Instant.parse("2026-01-01T10:00:02Z"), record.receivedAt)
        assertEquals(12.5f, record.accuracyMeters)
        assertEquals(1.4f, record.speedMetersPerSecond)
        assertEquals(0.3f, record.speedAccuracyMetersPerSecond)
        assertEquals(87f, record.bearingDegrees)
        assertEquals(15f, record.bearingAccuracyDegrees)
        assertEquals("fused", record.provider)
        assertEquals(false, record.isMockLocation)
        assertEquals(1_000_000_000L, record.elapsedRealtimeNanos)
    }

    @Test
    fun `a freshly built record is always id 0 and PENDING, never pre-processed`() {
        val record = buildBufferedObservationRecord(
            observation = observation(),
            source = TrajectoryObservationSource.BACKGROUND,
            processSessionId = "session-1",
            elapsedRealtimeClockDomainId = "session-1",
            receivedAt = Instant.now(),
        )

        assertEquals(0L, record.id)
        assertEquals(BufferedObservationProcessingState.PENDING, record.processingState)
        assertNull(record.processingStartedAt)
        assertNull(record.processedAt)
    }

    @Test
    fun `batchId and indexInBatch pass through unchanged, defaulting to null for a singleton delivery`() {
        val singleton = buildBufferedObservationRecord(
            observation = observation(),
            source = TrajectoryObservationSource.FOREGROUND,
            processSessionId = "session-1",
            elapsedRealtimeClockDomainId = "session-1",
            receivedAt = Instant.now(),
        )
        assertNull(singleton.batchId)
        assertNull(singleton.indexInBatch)

        val batched = buildBufferedObservationRecord(
            observation = observation(),
            source = TrajectoryObservationSource.BACKGROUND,
            processSessionId = "session-1",
            elapsedRealtimeClockDomainId = "session-1",
            receivedAt = Instant.now(),
            batchId = "batch-42",
            indexInBatch = 3,
        )
        assertEquals("batch-42", batched.batchId)
        assertEquals(3, batched.indexInBatch)
    }

    @Test
    fun `the built record's dedupKey matches buildObservationDedupKey's own output for the same inputs`() {
        val record = buildBufferedObservationRecord(
            observation = observation(elapsedRealtimeNanos = 555L),
            source = TrajectoryObservationSource.ONE_SHOT,
            processSessionId = "session-9",
            elapsedRealtimeClockDomainId = "session-9",
            receivedAt = Instant.now(),
        )

        val expectedKey = com.cedervs.worlddiscovery.core.discovery.trajectory.buildObservationDedupKey(
            elapsedRealtimeClockDomainId = "session-9",
            elapsedRealtimeNanos = 555L,
            providerTimeEpochMillis = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli(),
            coordinate = paris,
            accuracyMeters = 12.5f,
        )
        assertEquals(expectedKey, record.dedupKey)
    }

    @Test
    fun `two observations with different elapsedRealtimeNanos build records with different dedupKeys`() {
        val first = buildBufferedObservationRecord(
            observation = observation(elapsedRealtimeNanos = 1L),
            source = TrajectoryObservationSource.FOREGROUND,
            processSessionId = "session-1",
            elapsedRealtimeClockDomainId = "session-1",
            receivedAt = Instant.now(),
        )
        val second = buildBufferedObservationRecord(
            observation = observation(elapsedRealtimeNanos = 2L),
            source = TrajectoryObservationSource.FOREGROUND,
            processSessionId = "session-1",
            elapsedRealtimeClockDomainId = "session-1",
            receivedAt = Instant.now(),
        )

        assertNotEquals(first.dedupKey, second.dedupKey)
    }

    @Test
    fun `the same fix redelivered under a different source and a different process session builds the same dedupKey`() {
        // Regression coverage at the capture-bridge level for the blocking Codex dedup fix: source
        // and processSessionId are delivery metadata, never fix identity.
        val first = buildBufferedObservationRecord(
            observation = observation(elapsedRealtimeNanos = 999L),
            source = TrajectoryObservationSource.FOREGROUND,
            processSessionId = "session-A",
            elapsedRealtimeClockDomainId = "domain-1",
            receivedAt = Instant.now(),
        )
        val second = buildBufferedObservationRecord(
            observation = observation(elapsedRealtimeNanos = 999L),
            source = TrajectoryObservationSource.BACKGROUND,
            processSessionId = "session-B",
            elapsedRealtimeClockDomainId = "domain-1",
            receivedAt = Instant.now(),
        )

        assertEquals(first.dedupKey, second.dedupKey)
    }

    @Test
    fun `the same elapsedRealtimeNanos under a different clock domain builds a different dedupKey`() {
        val first = buildBufferedObservationRecord(
            observation = observation(elapsedRealtimeNanos = 999L),
            source = TrajectoryObservationSource.FOREGROUND,
            processSessionId = "session-A",
            elapsedRealtimeClockDomainId = "domain-1",
            receivedAt = Instant.now(),
        )
        val second = buildBufferedObservationRecord(
            observation = observation(elapsedRealtimeNanos = 999L),
            source = TrajectoryObservationSource.FOREGROUND,
            processSessionId = "session-A",
            elapsedRealtimeClockDomainId = "domain-2",
            receivedAt = Instant.now(),
        )

        assertNotEquals(first.dedupKey, second.dedupKey)
    }

}
