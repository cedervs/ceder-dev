package com.cedervs.worlddiscovery.core.discovery

/**
 * Certified vs Non-certified — the only trust distinction ever exposed to the user
 * (docs/discovery-engine.md §27, certified-mode.md §11). Never expand this into a
 * visible Personal/Verified/Certified hierarchy; richer confidence/provenance stays
 * internal via [Provenance].
 *
 * This phase never sets [CERTIFIED] itself — no server validation exists yet — but the
 * model must be able to represent it so storage/merge logic is correct once it does.
 *
 * [code] is the stable value persisted to storage.
 */
enum class TrustStatus(val code: String) {
    CERTIFIED("CERTIFIED"),
    NON_CERTIFIED("NON_CERTIFIED"),
    ;

    companion object {
        fun fromCode(code: String): TrustStatus =
            entries.firstOrNull { it.code == code }
                ?: error("Unknown TrustStatus code: $code")
    }
}
