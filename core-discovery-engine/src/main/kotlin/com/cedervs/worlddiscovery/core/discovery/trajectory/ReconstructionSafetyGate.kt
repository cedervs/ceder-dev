package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.time.Instant

/**
 * PHASE 3A CONTRACT — the Reconstruction Safety Gate. A map matcher/`TrajectoryReconstructor`
 * proposes a reconstruction; it never directly authorizes discovered cells. This is the independent
 * domain decision layer in between: `observations -> reconstruction candidate -> reconstruction
 * evidence -> SAFETY GATE -> decision -> (future) H3/discovery processing`. **Nothing in this phase
 * wires the "later H3/discovery processing" step.**
 *
 * Pure by contract: no Android/Room/MapLibre/H3/network-client/matcher-specific dependency,
 * deterministic, side-effect free, no I/O, **no clock read inside evaluation** — [evaluatedAt] is an
 * explicit parameter the caller supplies, never an internal `Instant.now()` call.
 */
fun interface ReconstructionSafetyGate {
    fun evaluate(
        candidate: TrajectoryReconstructionResult.AcceptedTrajectory,
        evidence: ReconstructionSafetyEvidence,
        policy: ReconstructionSafetyPolicy,
        evaluatedAt: Instant,
    ): ReconstructionSafetyDecision
}

/**
 * The one real [ReconstructionSafetyGate] implementation — deterministic, rule-based, no
 * machine-learned or opaque scoring of any kind.
 *
 * **PHASE 3A CORRECTION ROUND 2 — identity block rewritten around three independent layers**
 * (Codex's remaining B2 finding): a geometry-only fingerprint cannot prove which original ordered
 * observations, or which matcher run, produced a candidate. `evaluate` now checks, in order:
 * 1. **Layer A — observation window** ([OrderedObservationWindowIdentity]): candidate vs. evidence.
 * 2. **Layer B — matcher run** ([MatcherRunIdentity]): candidate vs. evidence.
 * 3. **Layer C — candidate geometry/provenance** ([ReconstructionCandidateIdentity]): recomputed vs.
 *    evidence.
 *
 * A real mismatch at any layer is decisive: `evaluate` returns
 * [ReconstructionSafetyDecision.NoReconstruction] immediately with only the identity-layer reasons
 * collected so far — nothing else in evidence that fails even one identity layer is safe to read or
 * report on. An *unknown* layer (the candidate's own optional identity field is `null`) blocks
 * acceptance without being decisive on its own — evaluation continues so the rest of the evidence can
 * still be read and reported.
 *
 * **PHASE 3A CORRECTION ROUND 2 — edge-based matcher-declined-continuity model** (Codex's remaining
 * B3 finding): see [EdgeRange]'s own doc comment for why vertex-provenance labels
 * (`observedIndices`/`inferredIndices`) are insufficient on their own, and
 * [TrajectoryReconstructionResult.AcceptedTrajectory.bridgedObservationEdges] for the candidate-side
 * counterpart this checks against.
 *
 * **PHASE 3A CORRECTION ROUND 1 — mandatory positive-evidence contract** (Codex's B1 finding, still
 * in force, unchanged this round): acceptance requires every mandatory fact to be affirmatively
 * known-good — never merely "nothing known is wrong". See [ReconstructionAuthorization] for this
 * round's additional fix to how an ACCEPT outcome may be treated downstream.
 */
object DeterministicReconstructionSafetyGate : ReconstructionSafetyGate {

    override fun evaluate(
        candidate: TrajectoryReconstructionResult.AcceptedTrajectory,
        evidence: ReconstructionSafetyEvidence,
        policy: ReconstructionSafetyPolicy,
        evaluatedAt: Instant,
    ): ReconstructionSafetyDecision {
        val identityUncertainties = mutableListOf<SafetyGateReason>()

        // ---- Layer A: observation window identity. ----

        val candidateWindowIdentity = candidate.observationWindowIdentity
        if (candidateWindowIdentity == null) {
            identityUncertainties += SafetyGateReason(
                SafetyGateReasonCode.OBSERVATION_WINDOW_IDENTITY_UNKNOWN,
                "candidate.observationWindowIdentity is null -- which ordered observations produced this " +
                    "candidate cannot be confirmed",
            )
        } else if (candidateWindowIdentity != evidence.observationWindowIdentity) {
            return ReconstructionSafetyDecision.NoReconstruction.of(
                listOf(
                    SafetyGateReason(
                        SafetyGateReasonCode.OBSERVATION_WINDOW_MISMATCH,
                        "evidence.observationWindowIdentity does not match candidate.observationWindowIdentity -- " +
                            "this evidence does not describe the same ordered observations that produced this candidate",
                    ),
                ),
            )
        }

        // ---- Layer B: matcher-run identity. ----

        val candidateRunIdentity = candidate.matcherRunIdentity
        if (candidateRunIdentity == null) {
            identityUncertainties += SafetyGateReason(
                SafetyGateReasonCode.MATCHER_RUN_IDENTITY_UNKNOWN,
                "candidate.matcherRunIdentity is null -- which matcher evaluation produced this candidate " +
                    "cannot be confirmed",
            )
        } else if (candidateRunIdentity != evidence.matcherRunIdentity) {
            return ReconstructionSafetyDecision.NoReconstruction.of(
                listOf(
                    SafetyGateReason(
                        SafetyGateReasonCode.MATCHER_RUN_MISMATCH,
                        "evidence.matcherRunIdentity does not match candidate.matcherRunIdentity -- this evidence " +
                            "describes a different matcher evaluation than the one that produced this candidate",
                    ),
                ),
            )
        }

        // ---- Layer C: candidate geometry/provenance identity. Always known on the candidate side
        // (recomputed directly from the candidate), so only a real mismatch is possible here, never
        // "unknown". ----

        val actualCandidateIdentity = ReconstructionCandidateIdentity.of(candidate)
        if (actualCandidateIdentity != evidence.associatedCandidateIdentity) {
            return ReconstructionSafetyDecision.NoReconstruction.of(
                listOf(
                    SafetyGateReason(
                        SafetyGateReasonCode.EVIDENCE_CANDIDATE_MISMATCH,
                        "evidence.associatedCandidateIdentity does not match the geometry/provenance identity " +
                            "recomputed from the evaluated candidate",
                    ),
                ),
            )
        }

        val hardFailures = mutableListOf<SafetyGateReason>()
        val uncertainties = mutableListOf<SafetyGateReason>()
        val supportReasons = mutableListOf<SafetyGateReason>()

        uncertainties += identityUncertainties
        if (candidateWindowIdentity != null) {
            supportReasons += SafetyGateReason(SafetyGateReasonCode.OBSERVATION_WINDOW_IDENTITY_CONFIRMED)
        }
        if (candidateRunIdentity != null) {
            supportReasons += SafetyGateReason(SafetyGateReasonCode.MATCHER_RUN_IDENTITY_CONFIRMED)
        }
        supportReasons += SafetyGateReason(SafetyGateReasonCode.EVIDENCE_CANDIDATE_ASSOCIATION_CONFIRMED)

        // ---- Matcher accounting coherence against the candidate's authoritative total (Codex's B4)
        // -- now a four-way partition (matched/interpolated/unmatched/unknown-classification). ----

        val totalObservations = candidate.inputObservationCount.toLong()
        val matched = (evidence.matcher.matchedObservationCount as? EvidenceValue.Known)?.value
        val interpolated = (evidence.matcher.interpolatedObservationCount as? EvidenceValue.Known)?.value
        val unmatched = (evidence.matcher.unmatchedObservationCount as? EvidenceValue.Known)?.value
        val unclassified = (evidence.matcher.unknownClassificationObservationCount as? EvidenceValue.Known)?.value
        val knownCounts = listOfNotNull(matched, interpolated, unmatched, unclassified)
        val knownSum = knownCounts.fold(0L) { acc, v -> acc + v.toLong() }

        var accountingCoherent = false
        if (knownSum > totalObservations) {
            hardFailures += SafetyGateReason(
                SafetyGateReasonCode.MATCHER_ACCOUNTING_INCOHERENT,
                "known matcher counts (matched=$matched interpolated=$interpolated unmatched=$unmatched " +
                    "unknownClassification=$unclassified, sum=$knownSum) exceed candidate.inputObservationCount=$totalObservations",
            )
        } else if (matched != null && interpolated != null && unmatched != null && unclassified != null) {
            if (knownSum != totalObservations) {
                hardFailures += SafetyGateReason(
                    SafetyGateReasonCode.MATCHER_ACCOUNTING_INCOHERENT,
                    "matcher counts (matched=$matched interpolated=$interpolated unmatched=$unmatched " +
                        "unknownClassification=$unclassified, sum=$knownSum) do not partition " +
                        "candidate.inputObservationCount=$totalObservations",
                )
            } else {
                accountingCoherent = true
                supportReasons += SafetyGateReason(SafetyGateReasonCode.MATCHER_ACCOUNTING_COHERENT)
            }
        } else {
            uncertainties += SafetyGateReason(
                SafetyGateReasonCode.MATCHER_ACCOUNTING_UNKNOWN,
                "matched/interpolated/unmatched/unknownClassification are not all known (matched=$matched " +
                    "interpolated=$interpolated unmatched=$unmatched unknownClassification=$unclassified) -- " +
                    "accounting coherence cannot be confirmed",
            )
        }
        if (unclassified != null && unclassified > 0) {
            uncertainties += SafetyGateReason(
                SafetyGateReasonCode.UNCLASSIFIED_OBSERVATIONS_PRESENT,
                "unknownClassificationObservationCount=$unclassified -- never treated as supporting acceptance",
            )
        }

        // ---- Temporal structure (mandatory). ----

        when (val monotonic = evidence.observation.temporallyMonotonic) {
            is EvidenceValue.Known -> if (monotonic.value) {
                supportReasons += SafetyGateReason(SafetyGateReasonCode.TEMPORAL_STRUCTURE_VALID)
            } else {
                hardFailures += SafetyGateReason(
                    SafetyGateReasonCode.INVALID_TEMPORAL_STRUCTURE,
                    "observation ordering evidence reports non-monotonic temporal structure",
                )
            }
            EvidenceValue.Unknown -> uncertainties += SafetyGateReason(
                SafetyGateReasonCode.TEMPORAL_STRUCTURE_UNKNOWN,
                "temporal monotonicity was not reported",
            )
        }

        // ---- Kinematic plausibility (mandatory). ----

        when (val speed = evidence.observation.impliedAverageSpeedMetersPerSecond) {
            is EvidenceValue.Known -> if (speed.value in policy.plausibleSpeedMetersPerSecond) {
                supportReasons += SafetyGateReason(SafetyGateReasonCode.KINEMATICS_PLAUSIBLE)
            } else {
                hardFailures += SafetyGateReason(
                    SafetyGateReasonCode.PHYSICALLY_IMPLAUSIBLE_KINEMATICS,
                    "impliedAverageSpeedMetersPerSecond=${speed.value} outside plausible range " +
                        "${policy.plausibleSpeedMetersPerSecond} for ${policy.transportMode}",
                )
            }
            EvidenceValue.Unknown -> uncertainties += SafetyGateReason(
                SafetyGateReasonCode.KINEMATICS_UNKNOWN,
                "impliedAverageSpeedMetersPerSecond was not reported",
            )
        }

        // ---- Matcher/candidate structural relationship -- edge-based, no invented bridge (mandatory,
        // Codex's B3 remaining finding). A declined interval is checked for real overlap against the
        // candidate's own bridgedObservationEdges (observation-window edge space), never against
        // geometry-vertex provenance labels -- see EdgeRange's own doc comment. Each declined
        // interval's upper bound is also validated against the candidate's actual edge count, since
        // only now (with the candidate available) is that authoritative bound known. ----

        val validEdgeIndices = 0..(candidate.inputObservationCount - 2)
        when (val declined = evidence.matcher.declinedIntervals) {
            is EvidenceValue.Known -> {
                val outOfBounds = declined.value.filter { interval ->
                    interval.endIndexBeyond(validEdgeIndices)
                }
                if (outOfBounds.isNotEmpty()) {
                    hardFailures += SafetyGateReason(
                        SafetyGateReasonCode.DECLINED_EDGE_OUT_OF_BOUNDS,
                        "declined interval(s) ${outOfBounds.map { it.range }} exceed the candidate's valid " +
                            "observation-window edge range ($validEdgeIndices for inputObservationCount=" +
                            "${candidate.inputObservationCount})",
                    )
                } else {
                    val invented = declined.value.filter { interval ->
                        candidate.bridgedObservationEdges.any { it in interval.range.indices }
                    }
                    if (invented.isNotEmpty()) {
                        hardFailures += SafetyGateReason(
                            SafetyGateReasonCode.UNSUPPORTED_BRIDGE,
                            "declined interval(s) ${invented.map { it.range }} overlap candidate.bridgedObservationEdges " +
                                "${candidate.bridgedObservationEdges}",
                        )
                    } else {
                        supportReasons += SafetyGateReason(SafetyGateReasonCode.NO_INVENTED_BRIDGE)
                        if (declined.value.isEmpty()) {
                            supportReasons += SafetyGateReason(SafetyGateReasonCode.ROUTE_STRUCTURALLY_CONTINUOUS)
                        }
                    }
                }
            }
            EvidenceValue.Unknown -> uncertainties += SafetyGateReason(
                SafetyGateReasonCode.STRUCTURAL_RELATIONSHIP_UNKNOWN,
                "matcher declined-edge evidence was not reported",
            )
        }

        // ---- Transport-mode compatibility (mandatory, Codex's B5). ----

        val hypothesis = candidate.transportModeHypothesis
        if (hypothesis == null) {
            uncertainties += SafetyGateReason(
                SafetyGateReasonCode.TRANSPORT_COMPATIBILITY_UNKNOWN,
                "candidate carries no transportModeHypothesis -- compatibility with " +
                    "policy.transportMode=${policy.transportMode} cannot be confirmed",
            )
        } else if (hypothesis.mode != policy.transportMode) {
            hardFailures += SafetyGateReason(
                SafetyGateReasonCode.TRANSPORT_MODE_MISMATCH,
                "candidate.transportModeHypothesis.mode=${hypothesis.mode} does not match " +
                    "policy.transportMode=${policy.transportMode}",
            )
        } else {
            supportReasons += SafetyGateReason(SafetyGateReasonCode.TRANSPORT_MODE_COMPATIBLE)
        }

        // ---- Observation support (mandatory, known-insufficient is uncertainty, never a hard failure
        // -- the candidate's own count is always known, never "unknown"). ----

        if (candidate.inputObservationCount >= policy.minimumObservationSupport) {
            supportReasons += SafetyGateReason(SafetyGateReasonCode.SUFFICIENT_OBSERVATION_SUPPORT)
        } else {
            uncertainties += SafetyGateReason(
                SafetyGateReasonCode.SPARSE_OBSERVATIONS,
                "candidate.inputObservationCount=${candidate.inputObservationCount} below policy minimum " +
                    "${policy.minimumObservationSupport}",
            )
        }

        if (hardFailures.isNotEmpty()) {
            return ReconstructionSafetyDecision.NoReconstruction.of(hardFailures + uncertainties)
        }

        // ---- OPTIONAL/enriching evidence -- may only ever add uncertainty, never a hard failure. ----

        (evidence.networkRoute.plausibleAlternativeRouteCount as? EvidenceValue.Known)?.let {
            if (it.value > policy.maximumToleratedAlternativeRoutes) {
                uncertainties += SafetyGateReason(
                    SafetyGateReasonCode.HIGH_NETWORK_AMBIGUITY,
                    "plausibleAlternativeRouteCount=${it.value} exceeds policy tolerance " +
                        "${policy.maximumToleratedAlternativeRoutes}",
                )
            } else {
                supportReasons += SafetyGateReason(SafetyGateReasonCode.LOW_NETWORK_AMBIGUITY)
            }
        }

        (evidence.networkRoute.endpointPlausible as? EvidenceValue.Known)?.let {
            if (!it.value) {
                uncertainties += SafetyGateReason(
                    SafetyGateReasonCode.ENDPOINT_IMPLAUSIBLE,
                    "reported endpoint evidenced as implausible relative to the known network",
                )
            }
        }

        if (accountingCoherent && unmatched != null && unmatched > 0) {
            uncertainties += SafetyGateReason(
                SafetyGateReasonCode.PARTIAL_UNMATCHED_OBSERVATIONS,
                "unmatchedObservationCount=$unmatched",
            )
        }
        if (accountingCoherent && matched != null && interpolated != null) {
            val total = matched + interpolated + (unmatched ?: 0) + (unclassified ?: 0)
            if (total > 0) {
                val interpolatedFraction = interpolated.toDouble() / total
                if (interpolatedFraction > policy.maximumInterpolatedObservationFraction) {
                    uncertainties += SafetyGateReason(
                        SafetyGateReasonCode.INTERPOLATION_HEAVY,
                        "interpolated fraction=${"%.3f".format(java.util.Locale.ROOT, interpolatedFraction)} " +
                            "exceeds policy maximum ${policy.maximumInterpolatedObservationFraction}",
                    )
                }
            }
        }

        when (val confidence = evidence.matcher.nativeConfidence) {
            is ConfidenceValue.Known -> {
                val threshold = policy.minimumMatcherConfidenceWhenKnown
                if (threshold != null && confidence.value < threshold) {
                    uncertainties += SafetyGateReason(
                        SafetyGateReasonCode.MATCHER_CONFIDENCE_WEAK,
                        "nativeConfidence=${confidence.value} below policy minimum $threshold",
                    )
                } else {
                    supportReasons += SafetyGateReason(SafetyGateReasonCode.MATCHER_CONFIDENCE_ADEQUATE)
                }
            }
            ConfidenceValue.Unknown -> if (!policy.acceptWhenMatcherConfidenceUnknown) {
                uncertainties += SafetyGateReason(
                    SafetyGateReasonCode.MATCHER_CONFIDENCE_UNAVAILABLE,
                    "matcher-native confidence not reported and policy does not accept unknown confidence",
                )
            }
        }

        val knownConsistencySignals = listOf(
            evidence.consistency.impliedSpeedConsistent,
            evidence.consistency.headingConsistent,
            evidence.consistency.observationToRouteConsistent,
        ).filterIsInstance<EvidenceValue.Known<Boolean>>()
        if (knownConsistencySignals.isNotEmpty()) {
            if (knownConsistencySignals.any { !it.value }) {
                uncertainties += SafetyGateReason(
                    SafetyGateReasonCode.CONSISTENCY_SIGNAL_WEAK,
                    "speed=${evidence.consistency.impliedSpeedConsistent} heading=${evidence.consistency.headingConsistent} " +
                        "route=${evidence.consistency.observationToRouteConsistent}",
                )
            } else {
                supportReasons += SafetyGateReason(SafetyGateReasonCode.CONSISTENT_KINEMATICS)
            }
        }

        evidence.crossMatcherAgreement?.let { cross ->
            (cross.agreeingEngineCount as? EvidenceValue.Known)?.let {
                if (it.value < cross.evaluatedEngineCount) {
                    uncertainties += SafetyGateReason(
                        SafetyGateReasonCode.MATCHER_DISAGREEMENT,
                        "agreeingEngineCount=${it.value} of evaluatedEngineCount=${cross.evaluatedEngineCount} " +
                            "-- never resolved by majority vote",
                    )
                }
            }
        }

        if (uncertainties.isNotEmpty()) {
            return ReconstructionSafetyDecision.RequireMoreEvidence.of(
                reasons = uncertainties,
                cadenceRecommendation = deriveCadenceRecommendation(uncertainties, evaluatedAt),
            )
        }

        return ReconstructionSafetyDecision.AcceptReconstruction.of(supportReasons)
    }

    /** `true` when [EdgeRange.endIndex] falls outside [validEdgeIndices] -- the only bound that
     * needs the candidate's own authoritative edge count to check (the range's own internal
     * `startIndex <= endIndex` well-formedness is already enforced by [EdgeRange]'s own constructor). */
    private fun MatcherDeclinedInterval.endIndexBeyond(validEdgeIndices: IntRange): Boolean =
        range.endIndex !in validEdgeIndices || range.startIndex !in validEdgeIndices

    /**
     * Derives a future-cadence-intention hook from the uncertainty reasons this evaluation found —
     * reuses [ObservationCadenceRecommendation] exactly as-is. [evaluatedAt] is passed straight
     * through as [ObservationCadenceRecommendation.recommendedAt] rather than read from a clock
     * here, keeping [evaluate] itself free of any clock access. **No consumer of this recommendation
     * exists anywhere in this phase.**
     */
    private fun deriveCadenceRecommendation(
        uncertainties: List<SafetyGateReason>,
        evaluatedAt: Instant,
    ): ObservationCadenceRecommendation {
        val cadenceReasons = uncertainties.map { reason ->
            val code = when (reason.code) {
                SafetyGateReasonCode.HIGH_NETWORK_AMBIGUITY,
                SafetyGateReasonCode.STRUCTURAL_RELATIONSHIP_UNKNOWN,
                SafetyGateReasonCode.ENDPOINT_IMPLAUSIBLE,
                -> ObservationCadenceReasonCode.NETWORK_AMBIGUITY
                else -> ObservationCadenceReasonCode.OTHER
            }
            ObservationCadenceReason(code, reason.detail)
        }
        val level = if (uncertainties.any {
                it.code == SafetyGateReasonCode.HIGH_NETWORK_AMBIGUITY ||
                    it.code == SafetyGateReasonCode.STRUCTURAL_RELATIONSHIP_UNKNOWN
            }
        ) {
            ObservationCadenceLevel.HIGH
        } else {
            ObservationCadenceLevel.INCREASED
        }
        return ObservationCadenceRecommendation(
            level = level,
            reasons = cadenceReasons,
            recommendedAt = evaluatedAt,
        )
    }

    /**
     * PHASE 3A CORRECTION ROUND 2 (Codex's §11 MAJOR finding: "internal factory does NOT prevent
     * another production file in the same module from constructing `AcceptReconstruction.of(...)`").
     *
     * **Honest statement of the actual Kotlin guarantee here — corrected from this class's own first
     * draft, which incorrectly claimed a stronger one.** The first draft nested this class inside
     * [DeterministicReconstructionSafetyGate] and gave it a `private` constructor, intending for only
     * [authorize] to be able to construct one — but this does not compile: Kotlin's `private` visibility
     * for a class member is scoped to *that class's own body* (its own nested/inner classes and
     * companion may reach it), and **does not extend to the enclosing class** the way Java's mutual
     * nested/outer private access does. [authorize] itself, a member of the *outer*
     * [DeterministicReconstructionSafetyGate], cannot call [ReconstructionAuthorization]'s private
     * constructor directly — verified by the compiler, not assumed. Kotlin has no "friend class"
     * feature, so **"constructible only from inside this one other specific class" is not directly
     * expressible in Kotlin's type/visibility system at all**, short of a custom compiler plugin.
     *
     * **What is actually implemented, and what it actually guarantees:** the private constructor is
     * called from [ReconstructionAuthorization]'s own companion object (ordinary, well-established
     * Kotlin idiom — a class's companion shares private access with the class body), and that
     * companion factory ([Companion.grantedByGateEvaluation]) is `internal` — visible module-wide,
     * the same ceiling Round 1's `internal fun of(...)` already sits at. This is **not** a stronger
     * compile-time guarantee than Round 1's fix; it is a **separate type with a deliberately narrow,
     * non-`operator`, explicitly-named factory** that a casual or accidental caller elsewhere in this
     * module is unlikely to reach for, so that a future consumer's own code visibly and specifically
     * asks for a [ReconstructionAuthorization] (never merely `decision is AcceptReconstruction`) —
     * a real, useful convention-and-structure improvement, but a convention backed by naming and
     * indirection, not by a compiler-enforced single-caller guarantee. Anything genuinely stronger
     * would require moving construction outside this module's own Kotlin visibility model entirely
     * (e.g. a build-time check, or restructuring this authorization type into its own Gradle module
     * with `internal` visibility relative to *that* module boundary) — out of scope for this round.
     */
    class ReconstructionAuthorization private constructor(
        val decision: ReconstructionSafetyDecision.AcceptReconstruction,
    ) {
        companion object {
            /** The only call site for this in the entire codebase is [DeterministicReconstructionSafetyGate.authorize]
             * — intentionally, by convention, not by a compiler-enforced restriction (see this class's
             * own doc comment for exactly what Kotlin does and does not enforce here). Deliberately
             * *not* `operator fun invoke` and deliberately verbosely named, so a direct call from
             * elsewhere in this module reads as an obvious, auditable deviation from intended usage. */
            internal fun grantedByGateEvaluation(
                decision: ReconstructionSafetyDecision.AcceptReconstruction,
            ): ReconstructionAuthorization = ReconstructionAuthorization(decision)
        }
    }

    /** The full result of one [authorize] call: [decision] is always present (the same freely
     * inspectable diagnostic value [evaluate] alone already returns — for logging/testing/
     * explainability); [authorization] is non-null **only** when [decision] is
     * [ReconstructionSafetyDecision.AcceptReconstruction], and is the object a future consumer should
     * prefer to require before acting, per [ReconstructionAuthorization]'s own doc comment. */
    data class EvaluationResult(
        val decision: ReconstructionSafetyDecision,
        val authorization: ReconstructionAuthorization?,
    )

    /** The authorization-producing entry point — identical evaluation to [evaluate], but also returns
     * an [EvaluationResult.authorization]. Prefer this over calling [evaluate] directly whenever the
     * caller intends to *act* on an ACCEPT outcome, not merely inspect/log/test it. */
    fun authorize(
        candidate: TrajectoryReconstructionResult.AcceptedTrajectory,
        evidence: ReconstructionSafetyEvidence,
        policy: ReconstructionSafetyPolicy,
        evaluatedAt: Instant,
    ): EvaluationResult {
        val decision = evaluate(candidate, evidence, policy, evaluatedAt)
        val authorization = (decision as? ReconstructionSafetyDecision.AcceptReconstruction)?.let {
            ReconstructionAuthorization.grantedByGateEvaluation(it)
        }
        return EvaluationResult(decision, authorization)
    }
}
