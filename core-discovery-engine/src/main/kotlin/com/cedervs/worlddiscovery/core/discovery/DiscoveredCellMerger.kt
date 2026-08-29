package com.cedervs.worlddiscovery.core.discovery

/**
 * Deterministic rule for folding one [DiscoveryEvent] into an existing (nullable)
 * [DiscoveredCell] for the same `(cell, trustStatus)` key. Repeated observations of the same
 * canonical cell must never create duplicate discoveries, but must never silently lose
 * temporal/provenance information either. The rule:
 *
 * 1. No existing record → create one from the event as-is.
 * 2. `firstDiscoveredAt` = min(existing, event timestamp) — order-independent: an
 *    out-of-order/replayed older event can only push this earlier, never later.
 * 3. `lastObservedAt` = max(existing, event timestamp) — order-independent, symmetric to (2).
 * 4. `provenance` is taken from whichever of the two observations is chronologically the most
 *    recent (ties broken in favor of the incoming event) — it always reflects "how was the
 *    latest known observation of this cell obtained", not the first one.
 * 5. `engineVersion` and `h3Resolution` are fixed at first creation and never overwritten by a
 *    later merge. Per docs/discovery-engine.md §16, a version/resolution change is only ever
 *    applied by a future, explicit, versioned recompute job — never as an implicit side effect
 *    of processing a new observation.
 * 6. Normal (NON_CERTIFIED) and Certified are never merged into each other (see
 *    [DiscoveredCell]): merging an event whose `trustStatus` differs from the existing
 *    record's is a programming error, not a data-modeling case to be resolved silently.
 */
object DiscoveredCellMerger {

    fun merge(existing: DiscoveredCell?, event: DiscoveryEvent): DiscoveredCell {
        if (existing == null) {
            return DiscoveredCell(
                cell = event.cell,
                trustStatus = event.trustStatus,
                firstDiscoveredAt = event.timestamp,
                lastObservedAt = event.timestamp,
                provenance = event.provenance,
                engineVersion = event.engineVersion,
                h3Resolution = event.h3Resolution,
            )
        }

        require(existing.cell == event.cell) {
            "Cannot merge an event for ${event.cell} into a record for ${existing.cell}"
        }
        require(existing.trustStatus == event.trustStatus) {
            "Refusing to merge ${existing.trustStatus} with ${event.trustStatus} for the same " +
                "cell: Normal and Certified must never be mixed (see docs/certified-mode.md §1)"
        }

        val eventIsAtLeastAsRecent = !event.timestamp.isBefore(existing.lastObservedAt)

        return existing.copy(
            firstDiscoveredAt = minOf(existing.firstDiscoveredAt, event.timestamp),
            lastObservedAt = maxOf(existing.lastObservedAt, event.timestamp),
            provenance = if (eventIsAtLeastAsRecent) event.provenance else existing.provenance,
        )
    }
}
