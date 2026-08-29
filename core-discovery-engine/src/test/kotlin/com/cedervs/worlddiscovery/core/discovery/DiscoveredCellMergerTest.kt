package com.cedervs.worlddiscovery.core.discovery

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DiscoveredCellMergerTest {

    private val cell = CanonicalCell(h3Index = "8c1fb46625551ff", resolution = 12)

    private fun event(
        timestamp: Instant,
        provenance: Provenance = Provenance.OBSERVED,
        trustStatus: TrustStatus = TrustStatus.NON_CERTIFIED,
        engineVersion: Int = DiscoveryEngineVersion.CURRENT,
        cell: CanonicalCell = this.cell,
    ) = DiscoveryEvent(
        cell = cell,
        timestamp = timestamp,
        provenance = provenance,
        trustStatus = trustStatus,
        engineVersion = engineVersion,
    )

    @Test
    fun `first observation of a cell creates a discovered cell matching the event`() {
        val timestamp = Instant.parse("2026-01-01T10:00:00Z")

        val result = DiscoveredCellMerger.merge(existing = null, event = event(timestamp))

        assertEquals(cell, result.cell)
        assertEquals(timestamp, result.firstDiscoveredAt)
        assertEquals(timestamp, result.lastObservedAt)
        assertEquals(Provenance.OBSERVED, result.provenance)
        assertEquals(TrustStatus.NON_CERTIFIED, result.trustStatus)
    }

    @Test
    fun `a later observation of the same cell does not create a second record and advances lastObservedAt`() {
        val first = DiscoveredCellMerger.merge(null, event(Instant.parse("2026-01-01T10:00:00Z")))

        val merged = DiscoveredCellMerger.merge(first, event(Instant.parse("2026-01-02T10:00:00Z")))

        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), merged.firstDiscoveredAt)
        assertEquals(Instant.parse("2026-01-02T10:00:00Z"), merged.lastObservedAt)
    }

    @Test
    fun `an out-of-order earlier observation still corrects firstDiscoveredAt backwards`() {
        val first = DiscoveredCellMerger.merge(null, event(Instant.parse("2026-01-05T10:00:00Z")))

        val merged = DiscoveredCellMerger.merge(first, event(Instant.parse("2026-01-01T10:00:00Z")))

        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), merged.firstDiscoveredAt)
        // lastObservedAt must not regress just because an older event arrived late.
        assertEquals(Instant.parse("2026-01-05T10:00:00Z"), merged.lastObservedAt)
    }

    @Test
    fun `provenance reflects the chronologically most recent observation, not arrival order`() {
        val first = DiscoveredCellMerger.merge(
            null,
            event(Instant.parse("2026-01-05T10:00:00Z"), provenance = Provenance.OBSERVED),
        )

        // A late-arriving but chronologically *older* imported event must not overwrite the
        // provenance of the already-known more recent observation.
        val merged = DiscoveredCellMerger.merge(
            first,
            event(Instant.parse("2026-01-01T10:00:00Z"), provenance = Provenance.IMPORTED),
        )

        assertEquals(Provenance.OBSERVED, merged.provenance)
    }

    @Test
    fun `a newer observation does update provenance`() {
        val first = DiscoveredCellMerger.merge(
            null,
            event(Instant.parse("2026-01-01T10:00:00Z"), provenance = Provenance.OBSERVED),
        )

        val merged = DiscoveredCellMerger.merge(
            first,
            event(Instant.parse("2026-01-05T10:00:00Z"), provenance = Provenance.RECONSTRUCTED),
        )

        assertEquals(Provenance.RECONSTRUCTED, merged.provenance)
    }

    @Test
    fun `engineVersion and h3Resolution are fixed at first creation and never overwritten by a later merge`() {
        val first = DiscoveredCellMerger.merge(
            null,
            event(Instant.parse("2026-01-01T10:00:00Z"), engineVersion = 1),
        )

        val merged = DiscoveredCellMerger.merge(
            first,
            event(Instant.parse("2026-01-02T10:00:00Z"), engineVersion = 2),
        )

        assertEquals(1, merged.engineVersion)
    }

    @Test
    fun `merging Certified and Non-certified events for the same cell is refused`() {
        val certified = DiscoveredCellMerger.merge(
            null,
            event(Instant.parse("2026-01-01T10:00:00Z"), trustStatus = TrustStatus.CERTIFIED),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DiscoveredCellMerger.merge(
                certified,
                event(Instant.parse("2026-01-02T10:00:00Z"), trustStatus = TrustStatus.NON_CERTIFIED),
            )
        }
    }

    @Test
    fun `merging an event for a different cell is refused`() {
        val existing = DiscoveredCellMerger.merge(null, event(Instant.parse("2026-01-01T10:00:00Z")))
        val otherCell = CanonicalCell(h3Index = "8c2a107289061ff", resolution = 12)

        assertThrows(IllegalArgumentException::class.java) {
            DiscoveredCellMerger.merge(existing, event(Instant.parse("2026-01-02T10:00:00Z"), cell = otherCell))
        }
    }
}
