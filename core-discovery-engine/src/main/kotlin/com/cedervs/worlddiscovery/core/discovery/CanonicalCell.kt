package com.cedervs.worlddiscovery.core.discovery

/**
 * A cell of the canonical H3 discovery grid. [resolution] is carried explicitly rather than
 * assumed, so this type stays valid for a future engine version that changes
 * [DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION] or for a later Easy/Standard/Hard
 * aggregation layer built from cells at other resolutions (not implemented yet).
 */
data class CanonicalCell(val h3Index: String, val resolution: Int) {
    init {
        require(h3Index.isNotBlank()) { "h3Index must not be blank" }
        require(resolution in 0..15) { "Invalid H3 resolution: $resolution" }
    }
}
