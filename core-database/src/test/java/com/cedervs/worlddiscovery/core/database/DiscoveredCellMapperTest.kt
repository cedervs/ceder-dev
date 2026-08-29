package com.cedervs.worlddiscovery.core.database

import com.cedervs.worlddiscovery.core.discovery.CanonicalCell
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCell
import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveredCellMapperTest {

    @Test
    fun `domain to entity to domain round-trips without loss`() {
        val original = DiscoveredCell(
            cell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12),
            trustStatus = TrustStatus.CERTIFIED,
            firstDiscoveredAt = Instant.parse("2026-01-01T10:00:00Z"),
            lastObservedAt = Instant.parse("2026-01-05T12:34:56Z"),
            provenance = Provenance.RECONSTRUCTED,
            engineVersion = 1,
            h3Resolution = 12,
        )

        val roundTripped = original.toEntity().toDomain()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `entity stores stable string codes for provenance and trust status`() {
        val entity = DiscoveredCell(
            cell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12),
            trustStatus = TrustStatus.NON_CERTIFIED,
            firstDiscoveredAt = Instant.EPOCH,
            lastObservedAt = Instant.EPOCH,
            provenance = Provenance.MANUAL_NON_CERTIFIED,
            engineVersion = 1,
            h3Resolution = 12,
        ).toEntity()

        assertEquals("NON_CERTIFIED", entity.trustStatus)
        assertEquals("MANUAL_NON_CERTIFIED", entity.provenance)
    }
}
