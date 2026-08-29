package com.cedervs.worlddiscovery.core.discovery

/**
 * Single source of truth for the discovery engine's version and its canonical H3 resolution.
 *
 * Validated in docs/discovery-engine.md §8 (2026-08-29): H3 resolution 12 is the canonical
 * discovery resolution for v1. That decision is explicitly provisional/calibratable and must
 * stay versioned — never hardcode the literal 12 (or this engine version) anywhere else;
 * reference these constants instead so a future engine/resolution change is a single-point
 * edit, not a silent, undocumented drift across the codebase.
 *
 * Changing either value here defines a new engine version. Per docs/architecture.md principle 6
 * and docs/discovery-engine.md §16, such a change must never silently reinterpret already
 * persisted [com.cedervs.worlddiscovery.core.discovery.DiscoveryEvent]s — a version bump only
 * affects newly submitted observations; recomputing historical data under a new version is a
 * separate, explicit, future job, not a side effect of bumping this constant.
 */
object DiscoveryEngineVersion {
    const val CURRENT: Int = 1
    const val CANONICAL_H3_RESOLUTION: Int = 12
}
