package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BufferedObservationRecordTest {

    private val paris = Coordinate(latitude = 48.8566, longitude = 2.3522)
    private val lyon = Coordinate(latitude = 45.7640, longitude = 4.8357)

    // ==============================================================================================
    // buildObservationDedupKey -- fix identity vs. delivery metadata (Codex review correction).
    // ==============================================================================================

    @Test
    fun `two calls with identical clock domain and elapsedRealtimeNanos produce the same key -- a genuine redelivery dedupes`() {
        val first = buildObservationDedupKey("domain-1", 1_000_000_000L, 1700000000000L, paris, 10f)
        val second = buildObservationDedupKey("domain-1", 1_000_000_000L, 1700000000000L, paris, 10f)

        assertEquals(first, second)
    }

    @Test
    fun `the same fix delivered as FOREGROUND then BACKGROUND is the same identity -- source is delivery metadata, not identity`() {
        // Regression test for the blocking Codex finding: the dedup key must never depend on
        // `source`, since a foreground/background transition can redeliver the exact same
        // underlying Android fix under a different source.
        val foreground = buildObservationDedupKey("domain-1", 1_000_000_000L, 1700000000000L, paris, 10f)
        val background = buildObservationDedupKey("domain-1", 1_000_000_000L, 1700000000000L, paris, 10f)

        assertEquals(
            "the same physical fix redelivered under a different source must be recognized as the same identity",
            foreground,
            background,
        )
    }

    @Test
    fun `the same fix delivered under a new process session in the same clock domain is the same identity`() {
        // Regression test for the blocking Codex finding: the dedup key must never depend on
        // `processSessionId` either -- a process restart (swipe, crash, cold start) that still
        // shares the same clock domain must not fabricate a new physical observation.
        val sessionA = buildObservationDedupKey("domain-1", 1_000_000_000L, 1700000000000L, paris, 10f)
        val sessionB = buildObservationDedupKey("domain-1", 1_000_000_000L, 1700000000000L, paris, 10f)

        assertEquals(
            "a new process session within the same clock domain must not change the fix identity",
            sessionA,
            sessionB,
        )
    }

    @Test
    fun `the same elapsedRealtimeNanos from a different clock domain is a distinct identity`() {
        val domainA = buildObservationDedupKey("domain-A", 1_000_000_000L, 1700000000000L, paris, 10f)
        val domainB = buildObservationDedupKey("domain-B", 1_000_000_000L, 1700000000000L, paris, 10f)

        assertNotEquals(
            "elapsedRealtimeNanos is only comparable within the same clock domain -- a different domain must never be assumed to be the same fix",
            domainA,
            domainB,
        )
    }

    @Test
    fun `two different real fixes in the same clock domain produce different keys, even with identical timestamp and coordinate`() {
        val first = buildObservationDedupKey("domain-1", 1_000_000_000L, 1700000000000L, paris, 10f)
        val second = buildObservationDedupKey("domain-1", 2_000_000_000L, 1700000000000L, paris, 10f)

        assertNotEquals(
            "distinct elapsedRealtimeNanos in the same clock domain must never collapse to the same key",
            first,
            second,
        )
    }

    @Test
    fun `a zero elapsedRealtimeNanos falls back to the weak key, never a bare timestamp-lat-lon triple`() {
        val key = buildObservationDedupKey("domain-1", 0L, 1700000000000L, paris, 10f)

        // The fallback key must still incorporate more than just (timestamp, lat, lon) -- accuracy
        // is included too, so two fixes differing only in accuracy are not silently conflated.
        val keyWithDifferentAccuracy = buildObservationDedupKey("domain-1", 0L, 1700000000000L, paris, 25f)
        assertNotEquals(key, keyWithDifferentAccuracy)
    }

    @Test
    fun `the zero-elapsedRealtimeNanos fallback still distinguishes different coordinates`() {
        val atParis = buildObservationDedupKey("domain-1", 0L, 1700000000000L, paris, 10f)
        val atLyon = buildObservationDedupKey("domain-1", 0L, 1700000000000L, lyon, 10f)

        assertNotEquals(atParis, atLyon)
    }

    @Test
    fun `a STRONG key and a WEAK key never collide, even if their raw fields could otherwise coincide`() {
        // fixIdentityGuarantee(0L) == WEAK; a STRONG key always carries a non-zero elapsedRealtimeNanos
        // and a distinct "strong|" prefix, so the two families are structurally disjoint.
        val weak = buildObservationDedupKey("domain-1", 0L, 1700000000000L, paris, 10f)
        val strong = buildObservationDedupKey("domain-1", 1700000000000L, 1700000000000L, paris, 10f)

        assertNotEquals(weak, strong)
    }

    // ==============================================================================================
    // fixIdentityGuarantee
    // ==============================================================================================

    @Test
    fun `fixIdentityGuarantee is STRONG for a non-zero elapsedRealtimeNanos and WEAK for zero`() {
        assertEquals(FixIdentityGuarantee.STRONG, fixIdentityGuarantee(1L))
        assertEquals(FixIdentityGuarantee.WEAK, fixIdentityGuarantee(0L))
    }

    // ==============================================================================================
    // BufferedObservationRecord.toTrajectoryObservation -- pure projection.
    // ==============================================================================================

    @Test
    fun `toTrajectoryObservation preserves every field the reconstruction domain actually needs`() {
        val record = BufferedObservationRecord(
            id = 42L,
            dedupKey = "irrelevant-to-this-test",
            source = TrajectoryObservationSource.FOREGROUND,
            processSessionId = "session-1",
            elapsedRealtimeClockDomainId = "session-1",
            elapsedRealtimeNanos = 123_456L,
            coordinate = paris,
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
            processingState = BufferedObservationProcessingState.PENDING,
            claimToken = null,
            processingStartedAt = null,
            processedAt = null,
        )

        val observation = record.toTrajectoryObservation()

        assertEquals(paris, observation.coordinate)
        assertEquals(Instant.ofEpochMilli(1700000000000L), observation.observedAt)
        assertEquals(Instant.parse("2026-01-01T10:00:05Z"), observation.receivedAt)
        assertEquals(TrajectoryObservationSource.FOREGROUND, observation.source)
        assertEquals("session-1", observation.processSessionId)
        assertEquals(123_456L, observation.elapsedRealtimeNanos)
        assertEquals(12.5f, observation.accuracyMeters)
        assertEquals(1.4f, observation.speedMetersPerSecond)
        assertEquals(0.3f, observation.speedAccuracyMetersPerSecond)
        assertEquals(87f, observation.bearingDegrees)
        assertEquals(15f, observation.bearingAccuracyDegrees)
        assertEquals("fused", observation.provider)
        assertEquals(false, observation.isMockLocation)
        assertEquals("batch-1", observation.batchId)
        assertEquals(2, observation.indexInBatch)
    }

    // ==============================================================================================
    // State invariants (Codex review round 2) -- previously only documented, now enforced.
    // ==============================================================================================

    private fun record(
        processingState: BufferedObservationProcessingState,
        claimToken: ClaimToken? = null,
        processingStartedAt: Instant? = null,
        processedAt: Instant? = null,
    ) = BufferedObservationRecord(
        id = 1L,
        dedupKey = "irrelevant-to-this-test",
        source = TrajectoryObservationSource.FOREGROUND,
        processSessionId = "session-1",
        elapsedRealtimeClockDomainId = "session-1",
        elapsedRealtimeNanos = 1L,
        coordinate = paris,
        providerTimeEpochMillis = 1700000000000L,
        receivedAt = Instant.parse("2026-01-01T10:00:05Z"),
        accuracyMeters = null,
        speedMetersPerSecond = null,
        speedAccuracyMetersPerSecond = null,
        bearingDegrees = null,
        bearingAccuracyDegrees = null,
        provider = null,
        isMockLocation = false,
        batchId = null,
        indexInBatch = null,
        processingState = processingState,
        claimToken = claimToken,
        processingStartedAt = processingStartedAt,
        processedAt = processedAt,
    )

    @Test
    fun `PENDING with no lease fields is allowed`() {
        val r = record(BufferedObservationProcessingState.PENDING)
        assertEquals(BufferedObservationProcessingState.PENDING, r.processingState)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PENDING with a non-null claimToken is rejected`() {
        record(BufferedObservationProcessingState.PENDING, claimToken = ClaimToken("token-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PENDING with a non-null processingStartedAt is rejected`() {
        record(BufferedObservationProcessingState.PENDING, processingStartedAt = Instant.now())
    }

    @Test
    fun `PROCESSING with both lease fields is allowed`() {
        val r = record(
            BufferedObservationProcessingState.PROCESSING,
            claimToken = ClaimToken("token-1"),
            processingStartedAt = Instant.now(),
        )
        assertEquals(BufferedObservationProcessingState.PROCESSING, r.processingState)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PROCESSING with a null claimToken is rejected`() {
        record(BufferedObservationProcessingState.PROCESSING, claimToken = null, processingStartedAt = Instant.now())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PROCESSING with a null processingStartedAt is rejected`() {
        record(BufferedObservationProcessingState.PROCESSING, claimToken = ClaimToken("token-1"), processingStartedAt = null)
    }

    @Test
    fun `PROCESSED retaining claimToken and both timestamps is allowed`() {
        val r = record(
            BufferedObservationProcessingState.PROCESSED,
            claimToken = ClaimToken("token-1"),
            processingStartedAt = Instant.parse("2026-01-01T10:00:00Z"),
            processedAt = Instant.parse("2026-01-01T10:00:01Z"),
        )
        assertEquals(BufferedObservationProcessingState.PROCESSED, r.processingState)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PROCESSED with a null claimToken is rejected`() {
        record(
            BufferedObservationProcessingState.PROCESSED,
            claimToken = null,
            processingStartedAt = Instant.now(),
            processedAt = Instant.now(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PROCESSED with a null processedAt is rejected`() {
        record(
            BufferedObservationProcessingState.PROCESSED,
            claimToken = ClaimToken("token-1"),
            processingStartedAt = Instant.now(),
            processedAt = null,
        )
    }

    @Test
    fun `DISCARDED with no lease fields is allowed`() {
        val r = record(BufferedObservationProcessingState.DISCARDED)
        assertEquals(BufferedObservationProcessingState.DISCARDED, r.processingState)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `DISCARDED with a non-null claimToken is rejected`() {
        record(BufferedObservationProcessingState.DISCARDED, claimToken = ClaimToken("token-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-PROCESSED state with a non-null processedAt is rejected`() {
        record(BufferedObservationProcessingState.PENDING, processedAt = Instant.now())
    }
}
