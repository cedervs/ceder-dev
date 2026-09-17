package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * A single piece of reconstruction-safety evidence — deliberately not a bare nullable primitive.
 * Mirrors [ConfidenceValue]'s own `Unknown`/`Known` shape (see that type's doc comment for the
 * same underlying reasoning) but generic, since [ReconstructionSafetyEvidence] needs this
 * distinction for counts, durations, distances, booleans and enums, not only confidence-shaped
 * `[0.0, 1.0]` values. [ConfidenceValue] itself is reused as-is (not duplicated) wherever a field
 * genuinely is a confidence-shaped value, e.g. [MatcherEvidence.nativeConfidence].
 *
 * A missing/unmeasured signal must stay explicitly [Unknown] through this whole contract — never
 * silently coerced into a zero, `false`, "perfect", "safe" or "unsafe" default. See
 * `ReconstructionSafetyGate`'s doc comment for how [Unknown] is treated differently from a
 * [Known] value that happens to fail a policy threshold (Phase 3A round's own explicit
 * requirement).
 */
sealed interface EvidenceValue<out T> {
    /** Not measured/available for this reconstruction candidate — absence of evidence, never
     * evidence of an unsafe or safe condition. */
    data object Unknown : EvidenceValue<Nothing>

    /** A genuinely measured/known value. */
    data class Known<T>(val value: T) : EvidenceValue<T>
}

/** `value` when [EvidenceValue] is [EvidenceValue.Known], `null` when [EvidenceValue.Unknown] —
 * a small convenience for read sites that don't need to distinguish "unknown" from "known null"
 * (impossible here, since [EvidenceValue.Known.value] is non-nullable) from "known real value". */
fun <T> EvidenceValue<T>.knownOrNull(): T? = when (this) {
    is EvidenceValue.Known -> value
    EvidenceValue.Unknown -> null
}
