package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * The outcome of [ReconstructionSafetyGate.evaluate] — the independent domain decision layer
 * between reconstruction evidence and any later use of a reconstructed candidate. A map matcher/
 * `TrajectoryReconstructor` proposes; this decision is what would, in a future increment, actually
 * gate whether that proposal may ever feed discovery — **nothing in this phase wires that later
 * step**.
 *
 * Every variant carries [reasons] and is only constructible through its own companion factory
 * function (`of`), which both defensively copies the caller's list and enforces that **the decision
 * can never contradict its own reason structure**: see each variant's own validation below and
 * [SafetyGateReasonCategory]'s doc comment for the categorization rule this enforces.
 *
 * **PHASE 3A CORRECTION ROUND 1 (Codex's M2 "decisions can be fabricated outside the gate").** The
 * original round's companion factories were `public operator fun invoke`, so any caller anywhere
 * could construct a category-valid `AcceptReconstruction` directly — trivially satisfying the
 * reason-category invariant (e.g. `AcceptReconstruction(listOf(SafetyGateReason(SUFFICIENT_OBSERVATION_SUPPORT)))`)
 * without ever running [ReconstructionSafetyGate.evaluate] at all. Every companion factory below is
 * now `internal fun of(...)` — constructible only from within `core-discovery-engine` (which is
 * exactly where [DeterministicReconstructionSafetyGate] itself, the only real evaluator, lives),
 * never from a consuming module. **Deliberately a plain named function, not `operator fun invoke`**:
 * with an `internal operator fun invoke` whose parameter list exactly matches the private primary
 * constructor's own, this module's manual dual-kotlinc-invocation build (main compiled separately
 * from test, joined only via `-Xfriend-paths` for `internal` visibility — real Gradle's single
 * associated main+test compilation does not hit this) resolves `ClassName(args)` call syntax back to
 * the private, inaccessible primary constructor rather than falling through to the accessible
 * `internal` companion `invoke`, and fails to compile; a plain differently-named function sidesteps
 * that call-syntax ambiguity entirely and is exactly as safe. This is the standard, idiomatic Kotlin
 * mechanism for "usable within our module, not exposed as public API surface" and is deliberately
 * the *smallest* structural fix: a stricter design (constructible only from inside
 * [DeterministicReconstructionSafetyGate] itself, e.g. via a private nested factory) was considered
 * and rejected because it would make this type's own category-invariant enforcement untestable as a
 * unit in isolation, without reflection, from this module's own test source — see
 * `ReconstructionSafetyDecisionTest`'s own note and this round's final report for the honest
 * limitation this still leaves (nothing prevents another file *within* `core-discovery-engine` from
 * also calling `of`; only cross-module fabrication, the actual threat model for anything that would
 * consume a decision as real authorization, is closed).
 *
 * **PHASE 3A CORRECTION ROUND 2 (Codex's §11 MAJOR finding, on this exact remaining gap): an
 * `AcceptReconstruction` value alone should not be treated as sufficient proof of authorization** —
 * see [DeterministicReconstructionSafetyGate.ReconstructionAuthorization] for the separate type a
 * future consumer should require instead, and that type's own doc comment for the honest statement
 * of what Kotlin's visibility system does and does not actually enforce there (module-wide `internal`
 * remains the real ceiling for both types — Kotlin has no single-caller-only construction guarantee
 * without a custom compiler plugin or a separate module boundary). This type remains the
 * freely-inspectable *diagnostic* decision (logging, testing, explainability);
 * `ReconstructionAuthorization` is the separate type a consumer should specifically ask for instead.
 */
sealed interface ReconstructionSafetyDecision {
    val reasons: List<SafetyGateReason>

    /**
     * Sufficient evidence exists to proceed to later `NON_CERTIFIED` discovery processing — this
     * does **not** mean Certified (Certified reconstruction policy remains entirely a future server/
     * audit problem, not implemented here or implied by this decision).
     *
     * Construction requires at least one reason and forbids any [SafetyGateReasonCategory.HARD_FAILURE]
     * or [SafetyGateReasonCategory.UNCERTAINTY] reason — an accept can only ever be justified by
     * [SafetyGateReasonCategory.SUPPORT] reasons.
     */
    @ConsistentCopyVisibility
    data class AcceptReconstruction private constructor(
        override val reasons: List<SafetyGateReason>,
    ) : ReconstructionSafetyDecision {
        companion object {
            internal fun of(reasons: List<SafetyGateReason>): AcceptReconstruction {
                val safeReasons = reasons.toList()
                require(safeReasons.isNotEmpty()) { "AcceptReconstruction must carry at least one reason" }
                val disqualifying = safeReasons.filter { it.code.category != SafetyGateReasonCategory.SUPPORT }
                require(disqualifying.isEmpty()) {
                    "AcceptReconstruction must carry only SUPPORT-category reasons, found: $disqualifying"
                }
                return AcceptReconstruction(safeReasons)
            }
        }
    }

    /**
     * Insufficient evidence for safe acceptance, but nothing here establishes invalidity.
     * [cadenceRecommendation], when present, reuses the existing [ObservationCadenceRecommendation]
     * contract as-is so a future adaptive-cadence consumer could someday act on it — **no such
     * consumer exists or is wired anywhere in this phase.**
     *
     * Construction requires at least one [SafetyGateReasonCategory.UNCERTAINTY] reason and forbids
     * any [SafetyGateReasonCategory.HARD_FAILURE] reason (a genuine hard failure is always
     * [NoReconstruction], never merely "more evidence needed").
     */
    @ConsistentCopyVisibility
    data class RequireMoreEvidence private constructor(
        override val reasons: List<SafetyGateReason>,
        val cadenceRecommendation: ObservationCadenceRecommendation?,
    ) : ReconstructionSafetyDecision {
        companion object {
            internal fun of(
                reasons: List<SafetyGateReason>,
                cadenceRecommendation: ObservationCadenceRecommendation? = null,
            ): RequireMoreEvidence {
                val safeReasons = reasons.toList()
                require(safeReasons.isNotEmpty()) { "RequireMoreEvidence must carry at least one reason" }
                val hardFailures = safeReasons.filter { it.code.category == SafetyGateReasonCategory.HARD_FAILURE }
                require(hardFailures.isEmpty()) {
                    "RequireMoreEvidence must not carry a HARD_FAILURE-category reason, found: $hardFailures"
                }
                require(safeReasons.any { it.code.category == SafetyGateReasonCategory.UNCERTAINTY }) {
                    "RequireMoreEvidence must carry at least one UNCERTAINTY-category reason"
                }
                return RequireMoreEvidence(safeReasons, cadenceRecommendation)
            }
        }
    }

    /**
     * Unsafe/contradictory/invalid/structurally untrustworthy — only actually observed discovery
     * remains authoritative for this window (this decision never demotes or removes any
     * `Provenance.OBSERVED` data; it only withholds this reconstructed candidate).
     *
     * Construction requires at least one [SafetyGateReasonCategory.HARD_FAILURE] reason — the only
     * category capable of justifying this outcome (an uncertainty signal alone must never
     * automatically produce this decision).
     */
    @ConsistentCopyVisibility
    data class NoReconstruction private constructor(
        override val reasons: List<SafetyGateReason>,
    ) : ReconstructionSafetyDecision {
        companion object {
            internal fun of(reasons: List<SafetyGateReason>): NoReconstruction {
                val safeReasons = reasons.toList()
                require(safeReasons.isNotEmpty()) { "NoReconstruction must carry at least one reason" }
                require(safeReasons.any { it.code.category == SafetyGateReasonCategory.HARD_FAILURE }) {
                    "NoReconstruction must carry at least one HARD_FAILURE-category reason"
                }
                return NoReconstruction(safeReasons)
            }
        }
    }
}
