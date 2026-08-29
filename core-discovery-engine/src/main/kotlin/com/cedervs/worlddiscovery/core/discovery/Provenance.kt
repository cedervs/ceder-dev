package com.cedervs.worlddiscovery.core.discovery

/**
 * How a [DiscoveryEvent]'s coordinate was obtained. Modeled now so the architecture can later
 * distinguish these cases (docs/discovery-engine.md §16/§24/§25); the workflows that actually
 * produce RECONSTRUCTED/IMPORTED/MANUAL_NON_CERTIFIED events are future work, not built here.
 *
 * [code] is the stable value persisted to storage — never rename it when renaming the enum
 * constant, and never reuse a retired code for a different meaning.
 */
enum class Provenance(val code: String) {
    /** Directly observed from live location tracking (not implemented in this phase). */
    OBSERVED("OBSERVED"),

    /** Automatically reconstructed between two reliable points (docs §24, not implemented yet). */
    RECONSTRUCTED("RECONSTRUCTED"),

    /** Derived from imported evidence such as photos/GPX (docs §25, not implemented yet). */
    IMPORTED("IMPORTED"),

    /** Manually added by the user without certifying evidence (docs §25, not implemented yet). */
    MANUAL_NON_CERTIFIED("MANUAL_NON_CERTIFIED"),
    ;

    companion object {
        fun fromCode(code: String): Provenance =
            entries.firstOrNull { it.code == code }
                ?: error("Unknown Provenance code: $code")
    }
}
