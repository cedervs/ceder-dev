package com.cedervs.worlddiscovery.core.discovery.trajectory

import java.time.Duration
import kotlin.math.abs
import kotlin.math.max

/**
 * Evidence about the input observations themselves. Every field is independently
 * [EvidenceValue.Unknown]-capable (Phase 3A round's §7): a caller that didn't measure a signal must
 * say so explicitly, never guess a value that could be misread as measured.
 *
 * **PHASE 3A CORRECTION ROUND 1 — [observationCount] removed.** The original round carried a
 * separate, independently-suppliable observation count here, compared against
 * [TrajectoryReconstructionResult.AcceptedTrajectory.inputObservationCount] by the gate — Codex's B2
 * finding was that a mere count match is a weak association signal two different candidates can
 * share. Observation count is now read **only** from the candidate itself (authoritative, always
 * known, never independently re-stated evidence that could silently drift from it) — see
 * [ReconstructionCandidateIdentity] for the real, structural association mechanism this evidence
 * type now relies on instead ([ReconstructionSafetyEvidence.associatedCandidateIdentity]).
 *
 * **PHASE 3A CORRECTION ROUND 1 — relational coherence enforced at construction (Codex's B4).**
 * [elapsedDuration], [endpointDisplacementMeters] and [impliedAverageSpeedMetersPerSecond] are no
 * longer validated only individually: a zero [elapsedDuration] together with a positive
 * [endpointDisplacementMeters] is physically incoherent and now rejected at construction, and a
 * supplied [impliedAverageSpeedMetersPerSecond] that contradicts the duration/displacement-derived
 * speed beyond a fixed, documented tolerance is rejected the same way — contradictory evidence can
 * no longer be constructed at all, let alone reach [ReconstructionSafetyGate.evaluate].
 *
 * **PHASE 3A CORRECTION ROUND 2 — numeric-extreme safety fix (Codex's remaining B4 finding).** Round
 * 1's own coherence check had a real bug: `Duration.toNanos()` throws `ArithmeticException` for an
 * otherwise perfectly constructible extreme [Duration] (beyond ~292 years in nanoseconds), and even
 * when it didn't throw, a derived speed could legitimately evaluate to `Infinity` (e.g. a 1-nanosecond
 * [elapsedDuration] against a `Double.MAX_VALUE` [endpointDisplacementMeters]) — and `Infinity <= Infinity`
 * is `true` in IEEE-754, so the old tolerance check could silently *validate* a contradiction instead
 * of rejecting it. The derived-speed computation below now uses [Duration.getSeconds]/[Duration.getNano]
 * instead of [Duration.toNanos] (never throws, even for the largest constructible [Duration]), and
 * explicitly requires the derived speed itself to be finite before any tolerance comparison is even
 * attempted — a non-finite derived quantity is unconditionally treated as incoherent, never as
 * something a tolerance check could accidentally wave through.
 */
data class ObservationEvidence(
    val elapsedDuration: EvidenceValue<Duration> = EvidenceValue.Unknown,
    val endpointDisplacementMeters: EvidenceValue<Double> = EvidenceValue.Unknown,
    val impliedAverageSpeedMetersPerSecond: EvidenceValue<Double> = EvidenceValue.Unknown,
    val meanAccuracyMeters: EvidenceValue<Double> = EvidenceValue.Unknown,
    /** `false` when the observations' trustworthy ordering signal (see
     * [TrajectoryObservation.elapsedRealtimeNanos]'s own doc comment for why wall-clock time alone
     * is not trustworthy) contradicts itself — a genuine [SafetyGateReasonCode.INVALID_TEMPORAL_STRUCTURE]
     * hard failure, never merely an uncertainty signal. */
    val temporallyMonotonic: EvidenceValue<Boolean> = EvidenceValue.Unknown,
) {
    init {
        val duration = (elapsedDuration as? EvidenceValue.Known)?.value
        val displacement = (endpointDisplacementMeters as? EvidenceValue.Known)?.value
        val suppliedSpeed = (impliedAverageSpeedMetersPerSecond as? EvidenceValue.Known)?.value

        duration?.let {
            require(!it.isNegative) { "elapsedDuration must not be negative when known, got $it" }
        }
        displacement?.let {
            require(it.isFinite() && it >= 0.0) {
                "endpointDisplacementMeters must be finite and >= 0 when known, got $it"
            }
        }
        suppliedSpeed?.let {
            require(it.isFinite() && it >= 0.0) {
                "impliedAverageSpeedMetersPerSecond must be finite and >= 0 when known, got $it"
            }
        }
        (meanAccuracyMeters as? EvidenceValue.Known)?.let {
            require(it.value.isFinite() && it.value >= 0.0) {
                "meanAccuracyMeters must be finite and >= 0 when known, got ${it.value}"
            }
        }

        // Codex B4: zero elapsed time with genuine displacement is physically incoherent.
        if (duration != null && displacement != null) {
            require(!(duration.isZero && displacement > 0.0)) {
                "physically incoherent evidence: elapsedDuration=$duration (zero) with " +
                    "endpointDisplacementMeters=$displacement (positive)"
            }
        }

        // Codex B4 (Round 2 fix): a supplied speed must agree with the duration/displacement-derived
        // speed, within a fixed, documented, deterministic tolerance -- never silently allowed to
        // diverge, and never validated using a non-finite derived quantity (Infinity <= Infinity is
        // `true` in IEEE-754 -- the exact bug this round fixes).
        if (duration != null && displacement != null && suppliedSpeed != null && !duration.isZero) {
            // Duration.getSeconds()/getNano() never throw, unlike Duration.toNanos() (which can
            // overflow for an otherwise perfectly constructible extreme Duration) -- see this class's
            // own Round 2 doc comment.
            val durationSeconds = duration.seconds.toDouble() + duration.nano / NANOS_PER_SECOND
            val derivedSpeed = displacement / durationSeconds
            require(derivedSpeed.isFinite()) {
                "physically incoherent evidence: elapsedDuration=$duration and " +
                    "endpointDisplacementMeters=$displacement imply a non-finite derived speed " +
                    "($derivedSpeed) -- cannot be safely validated against suppliedSpeed=$suppliedSpeed"
            }
            val tolerance = max(MINIMUM_SPEED_TOLERANCE_MPS, derivedSpeed * RELATIVE_SPEED_TOLERANCE)
            require(tolerance.isFinite() && abs(derivedSpeed - suppliedSpeed) <= tolerance) {
                "physically incoherent evidence: suppliedSpeed=$suppliedSpeed but " +
                    "duration/displacement imply speed=$derivedSpeed (tolerance=$tolerance)"
            }
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Deterministic tolerance for the duration/displacement-vs-supplied-speed consistency check
         * above: the greater of a flat 0.5 m/s or 10% relative to the derived speed. A fixed,
         * documented constant, not a policy value — this checks internal evidence coherence, not a
         * product-calibratable threshold (Codex's M1: calibration must never control relational
         * coherence enforcement). */
        const val MINIMUM_SPEED_TOLERANCE_MPS = 0.5
        const val RELATIVE_SPEED_TOLERANCE = 0.1
    }
}

/**
 * A coarse, qualitative network-complexity label — informational context only. Deliberately never
 * wired into a [ReconstructionSafetyGate] decision rule directly: Phase 2B-5's own evidence found
 * that a complex network alone did not, by itself, distinguish safe from unsafe reconstructions. The
 * gate instead reasons about [NetworkRouteEvidence.plausibleAlternativeRouteCount] — a concrete,
 * measurable ambiguity signal — and keeps this label around only for diagnostics/explainability and
 * possible future calibration.
 */
enum class NetworkComplexity { LOW, MODERATE, HIGH }

/** Evidence about the road/path network the candidate was matched against — all OPTIONAL/enriching
 * evidence (Phase 3A correction round's §2): none of these fields gate acceptance on their own being
 * unknown, though a known-unfavorable value still contributes an uncertainty signal. */
data class NetworkRouteEvidence(
    val networkComplexity: EvidenceValue<NetworkComplexity> = EvidenceValue.Unknown,
    val plausibleAlternativeRouteCount: EvidenceValue<Int> = EvidenceValue.Unknown,
    /** Whether the candidate's reported endpoint(s) plausibly sit on/near a real network element.
     * `false` here is an [SafetyGateReasonCode.ENDPOINT_IMPLAUSIBLE] uncertainty signal, never a
     * hard failure by itself — 2B-4's own real off-network-endpoint finding produced `AMBIGUOUS`,
     * not a wrong-road contradiction. */
    val endpointPlausible: EvidenceValue<Boolean> = EvidenceValue.Unknown,
    /** A structural-continuity magnitude proxy (e.g. a network edge-index gap, when the matcher
     * exposes one) — collected but **not currently thresholded by the gate**: 2B-5 explicitly found
     * this signal unvalidated against any genuinely wrong case, so any concrete cutoff here would be
     * an invented, uncalibrated number (`CALIBRATION REQUIRED` before this can drive a decision
     * rule). */
    val pathContinuityMagnitude: EvidenceValue<Int> = EvidenceValue.Unknown,
    val reconstructedRouteLengthMeters: EvidenceValue<Double> = EvidenceValue.Unknown,
) {
    init {
        (plausibleAlternativeRouteCount as? EvidenceValue.Known)?.let {
            require(it.value >= 0) { "plausibleAlternativeRouteCount must be >= 0 when known, got ${it.value}" }
        }
        (pathContinuityMagnitude as? EvidenceValue.Known)?.let {
            require(it.value >= 0) { "pathContinuityMagnitude must be >= 0 when known, got ${it.value}" }
        }
        (reconstructedRouteLengthMeters as? EvidenceValue.Known)?.let {
            require(it.value.isFinite() && it.value >= 0.0) {
                "reconstructedRouteLengthMeters must be finite and >= 0 when known, got ${it.value}"
            }
        }
    }
}

/** Which kind of decline a [MatcherDeclinedInterval] represents. Deliberately excludes a "not
 * split" member (PHASE 3A CORRECTION ROUND 1, Codex's B3) — a matcher that did **not** decline a
 * given interval is expressed by that interval's simple *absence* from
 * [MatcherEvidence.declinedIntervals], never by a member of this enum, so there is no redundant
 * "both mean the same thing" representation to fall out of sync. */
enum class MatcherSplitOutcome {
    /** The matcher explicitly split its output into independently-evaluable segments, declining to
     * make a claim about this specific interval. */
    SPLIT_INTO_SEGMENTS,

    /** The matcher declined to produce any claim for this specific interval (up to and including the
     * whole candidate window). */
    WHOLE_CANDIDATE_REJECTED,
}

/**
 * PHASE 3A CORRECTION ROUND 2 — replaces Round 1's vertex-indexed `GeometryIndexRange` (Codex's
 * remaining B3 finding: "model edges, not only vertices"). A candidate path is a sequence of
 * **edges** between consecutive positions, not a bag of vertices — a matcher can decline to support
 * continuity between two positions that both happen to be labelled `observed` in the candidate's own
 * geometry (e.g. two real, independently-matched fixes with nothing confirming the path *between*
 * them), and Round 1's "does any inferred vertex fall in this range" check could not detect that case
 * at all, since no inferred vertex needs to exist for an edge to be structurally unsupported.
 *
 * **Canonical index space, stated explicitly: observation-window edges, not candidate geometry
 * indices.** For an observation window of `inputObservationCount` original observations, edge `i`
 * connects original observation `i` to observation `i+1`; valid edge indices are exactly
 * `0..inputObservationCount-2` (no edges exist when `inputObservationCount <= 1`). Declined
 * continuity is fundamentally a statement about the *raw observation sequence's* connectivity — it is
 * defined in the same index space [OrderedObservationWindowIdentity] already formalizes, **not** in
 * candidate-geometry-vertex space (which can compress, reorder for rendering, or otherwise not have a
 * 1:1 relationship with the original observations at all). See
 * [TrajectoryReconstructionResult.AcceptedTrajectory.bridgedObservationEdges] for the candidate-side
 * counterpart this is compared against.
 *
 * **Endpoint semantics: inclusive/inclusive**, matching this package's existing `IntRange` convention
 * ([indices]). `startIndex == endIndex` denotes a single declined edge.
 *
 * **Multiple/overlapping/adjacent/duplicate ranges in one [MatcherEvidence.declinedIntervals] list
 * are all valid and require no normalization**: the only safety-relevant question
 * [ReconstructionSafetyGate.evaluate] asks is "does *any* declined range overlap
 * [TrajectoryReconstructionResult.AcceptedTrajectory.bridgedObservationEdges]", which is correct and
 * unambiguous per-range regardless of how the declined ranges relate to *each other* — normalizing/
 * merging them first would add complexity without changing that answer.
 */
data class EdgeRange(val startIndex: Int, val endIndex: Int) {
    init {
        require(startIndex >= 0) { "startIndex must be >= 0, got $startIndex" }
        require(endIndex >= startIndex) { "endIndex ($endIndex) must be >= startIndex ($startIndex) -- a reversed range is invalid" }
    }

    val indices: IntRange get() = startIndex..endIndex
}

/**
 * PHASE 3A CORRECTION ROUND 1 — fixes Codex's B3 ("unsupported bridge protection can be bypassed").
 * PHASE 3A CORRECTION ROUND 2 — [range] is now an [EdgeRange] in observation-window edge space (see
 * that type's own doc comment for why vertex indices were structurally insufficient).
 * [ReconstructionSafetyGate.evaluate] checks real interval overlap between a declined [range] and the
 * candidate's own `bridgedObservationEdges` — the precise structural relationship Codex's finding
 * required, not an aggregate proxy for it.
 */
data class MatcherDeclinedInterval(
    val range: EdgeRange,
    val outcome: MatcherSplitOutcome,
)

/**
 * Evidence reported by (or derived from) the matcher(s) that produced this candidate.
 *
 * **PHASE 3A CORRECTION ROUND 1 — [declinedIntervals] replaces the original round's aggregate
 * `splitOutcome` field** (Codex's B3 — see [MatcherDeclinedInterval]'s own doc comment).
 * [EvidenceValue.Unknown] here means "whether/where the matcher declined anything is not known" —
 * this is one of the [ReconstructionSafetyGate]'s **mandatory** acceptance facts (Codex's B1): it is
 * never treated as "no decline", and it blocks [ReconstructionSafetyDecision.AcceptReconstruction]
 * exactly like every other unresolved mandatory fact. [EvidenceValue.Known] with an empty list means
 * the matcher affirmatively reported full continuity (no decline anywhere) — the strongest possible
 * continuity signal, distinct from simply not having asked.
 *
 * **PHASE 3A CORRECTION ROUND 2 — [unknownClassificationObservationCount] added (Codex's minor
 * finding).** Real benchmark matcher output can classify some observations as neither matched,
 * interpolated, nor unmatched — a genuine fourth outcome, not a defect in the accounting model. Round
 * 1's three-way accounting silently had no bucket for this; it is now an explicit fourth partition
 * member, included in [ReconstructionSafetyGate.evaluate]'s exact-partition check, and any known
 * positive count on its own is treated as its own uncertainty signal
 * ([SafetyGateReasonCode.UNCLASSIFIED_OBSERVATIONS_PRESENT]) — unclassified observations are never
 * silently dropped, and never contribute toward acceptance.
 */
@ConsistentCopyVisibility
data class MatcherEvidence private constructor(
    val matchedObservationCount: EvidenceValue<Int>,
    val interpolatedObservationCount: EvidenceValue<Int>,
    val unmatchedObservationCount: EvidenceValue<Int>,
    val unknownClassificationObservationCount: EvidenceValue<Int>,
    val declinedIntervals: EvidenceValue<List<MatcherDeclinedInterval>>,
    val nativeConfidence: ConfidenceValue,
) {
    companion object {
        operator fun invoke(
            matchedObservationCount: EvidenceValue<Int> = EvidenceValue.Unknown,
            interpolatedObservationCount: EvidenceValue<Int> = EvidenceValue.Unknown,
            unmatchedObservationCount: EvidenceValue<Int> = EvidenceValue.Unknown,
            unknownClassificationObservationCount: EvidenceValue<Int> = EvidenceValue.Unknown,
            declinedIntervals: EvidenceValue<List<MatcherDeclinedInterval>> = EvidenceValue.Unknown,
            /** Reuses [ConfidenceValue] as-is (not duplicated) — genuinely the same
             * "normalized-confidence-or-explicitly-unknown" shape [ReconstructionConfidence]'s own
             * components already use. OPTIONAL/enriching evidence (Phase 3A round's §1/§8 and this
             * correction round's §2): informative, never a calibrated correctness probability, and may
             * legitimately stay [ConfidenceValue.Unknown] even when every mandatory fact is established. */
            nativeConfidence: ConfidenceValue = ConfidenceValue.Unknown,
        ): MatcherEvidence {
            (matchedObservationCount as? EvidenceValue.Known)?.let {
                require(it.value >= 0) { "matchedObservationCount must be >= 0 when known, got ${it.value}" }
            }
            (interpolatedObservationCount as? EvidenceValue.Known)?.let {
                require(it.value >= 0) { "interpolatedObservationCount must be >= 0 when known, got ${it.value}" }
            }
            (unmatchedObservationCount as? EvidenceValue.Known)?.let {
                require(it.value >= 0) { "unmatchedObservationCount must be >= 0 when known, got ${it.value}" }
            }
            (unknownClassificationObservationCount as? EvidenceValue.Known)?.let {
                require(it.value >= 0) { "unknownClassificationObservationCount must be >= 0 when known, got ${it.value}" }
            }
            // Defensive copy: a caller's later mutation of an original MutableList must never
            // retroactively change an already-constructed MatcherEvidence, matching this package's
            // established AcceptedTrajectory/ReconstructionSafetyDecision convention.
            val safeDeclinedIntervals = when (declinedIntervals) {
                is EvidenceValue.Known -> EvidenceValue.Known(declinedIntervals.value.toList())
                EvidenceValue.Unknown -> EvidenceValue.Unknown
            }
            return MatcherEvidence(
                matchedObservationCount = matchedObservationCount,
                interpolatedObservationCount = interpolatedObservationCount,
                unmatchedObservationCount = unmatchedObservationCount,
                unknownClassificationObservationCount = unknownClassificationObservationCount,
                declinedIntervals = safeDeclinedIntervals,
                nativeConfidence = nativeConfidence,
            )
        }
    }
}

/**
 * Cross-signal agreement evidence — deliberately separate from [ObservationEvidence.temporallyMonotonic]
 * (a hard structural check on the observations alone), covering §6's "implied speed consistency,
 * heading consistency ... observation-to-route consistency" instead: whether independently-derived
 * signals about the *same* candidate agree with each other. OPTIONAL/enriching evidence: a single
 * known `false` here is only ever an [SafetyGateReasonCode.CONSISTENCY_SIGNAL_WEAK] uncertainty
 * signal, never a hard failure and never mandatory for acceptance.
 */
data class ConsistencyEvidence(
    val impliedSpeedConsistent: EvidenceValue<Boolean> = EvidenceValue.Unknown,
    val headingConsistent: EvidenceValue<Boolean> = EvidenceValue.Unknown,
    val observationToRouteConsistent: EvidenceValue<Boolean> = EvidenceValue.Unknown,
)

/**
 * Optional cross-matcher evidence (Phase 3A round's §6: "the gate MUST work with one matcher;
 * multiple matchers must NOT be mandatory"). `null` on [ReconstructionSafetyEvidence.crossMatcherAgreement]
 * means exactly one matcher was evaluated (or cross-matcher comparison wasn't attempted) — never
 * treated as disagreement, and never required for [ReconstructionSafetyDecision.AcceptReconstruction].
 * Disagreement is evidence of *uncertainty*, never resolved by majority vote as truth — this type
 * carries only the agreement count, never "which candidate is correct".
 */
data class CrossMatcherAgreementEvidence(
    val evaluatedEngineCount: Int,
    val agreeingEngineCount: EvidenceValue<Int> = EvidenceValue.Unknown,
) {
    init {
        require(evaluatedEngineCount >= 1) { "evaluatedEngineCount must be >= 1, got $evaluatedEngineCount" }
        (agreeingEngineCount as? EvidenceValue.Known)?.let {
            require(it.value in 0..evaluatedEngineCount) {
                "agreeingEngineCount must be within [0, $evaluatedEngineCount] when known, got ${it.value}"
            }
        }
    }
}

/**
 * The full, generic reconstruction-safety evidence bundle a [ReconstructionSafetyGate] reasons over.
 *
 * **PHASE 3A CORRECTION ROUND 1 additions (Codex's B2/B4):**
 * - [associatedCandidateIdentity] — **mandatory, not an [EvidenceValue]**: which exact candidate
 *   *geometry/provenance shape* this evidence bundle describes (Layer C — see
 *   [ReconstructionCandidateIdentity]'s own doc comment for the three-layer model). Bookkeeping about
 *   the evidence object itself, not a measured fact about the world, so it is never legitimately
 *   "unknown" the way e.g. matcher confidence can be.
 * - The [init] block cross-validates [observation]/[matcher] together (Codex's B4 "matcher accounting
 *   must be coherent"): when [MatcherEvidence.matchedObservationCount]/
 *   [MatcherEvidence.interpolatedObservationCount]/[MatcherEvidence.unmatchedObservationCount]/
 *   [MatcherEvidence.unknownClassificationObservationCount] are all known, their sum can never exceed
 *   the *evidence-known* observation count context available at construction time. The authoritative
 *   total (the evaluated candidate's own `inputObservationCount`) is only available once a candidate
 *   is supplied, so the exact-partition check against that authoritative total is enforced by
 *   [ReconstructionSafetyGate.evaluate] itself, immediately after identity association succeeds and
 *   before any acceptance-path evidence is read.
 *
 * **PHASE 3A CORRECTION ROUND 2 additions (Codex's remaining B2 finding — Layers A and B):**
 * - [observationWindowIdentity] — **mandatory, not an [EvidenceValue]**: which exact ordered raw
 *   observation sequence (Layer A) this evidence describes — see
 *   [OrderedObservationWindowIdentity]'s own doc comment. [ReconstructionSafetyGate.evaluate] compares
 *   this against [TrajectoryReconstructionResult.AcceptedTrajectory.observationWindowIdentity]
 *   (`null` there blocks acceptance without being a hard failure; a real mismatch is a hard failure).
 * - [matcherRunIdentity] — **mandatory, replaces Round 1's free-form `matcherEvaluationId: String`**:
 *   which exact matcher evaluation (Layer B) this evidence describes — see [MatcherRunIdentity]'s own
 *   doc comment for why a typed opaque identity, produced once at the reconstruction/matching
 *   boundary and propagated into both the candidate and this evidence, replaces an unconstrained
 *   caller-supplied string the gate had nothing to independently compare against.
 */
data class ReconstructionSafetyEvidence(
    val associatedCandidateIdentity: ReconstructionCandidateIdentity,
    val observationWindowIdentity: OrderedObservationWindowIdentity,
    val matcherRunIdentity: MatcherRunIdentity,
    val observation: ObservationEvidence = ObservationEvidence(),
    val networkRoute: NetworkRouteEvidence = NetworkRouteEvidence(),
    val matcher: MatcherEvidence = MatcherEvidence(),
    val consistency: ConsistencyEvidence = ConsistencyEvidence(),
    val crossMatcherAgreement: CrossMatcherAgreementEvidence? = null,
)
