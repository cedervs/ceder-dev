package com.cedervs.worlddiscovery.core.discovery.trajectory

/**
 * Which side of the [ReconstructionSafetyDecision] a [SafetyGateReasonCode] is capable of
 * justifying — the type-level expression of the requirement that a decision can never contradict
 * its own reasons:
 * - [SUPPORT]: only ever appears on [ReconstructionSafetyDecision.AcceptReconstruction].
 * - [UNCERTAINTY]: insufficient to accept, but not itself evidence of invalidity — drives
 *   [ReconstructionSafetyDecision.RequireMoreEvidence]; never appears alone as grounds for
 *   [ReconstructionSafetyDecision.NoReconstruction].
 * - [HARD_FAILURE]: unsafe/contradictory/structurally untrustworthy — the only category that can
 *   justify [ReconstructionSafetyDecision.NoReconstruction], and the only category that can never
 *   appear on [ReconstructionSafetyDecision.AcceptReconstruction] or
 *   [ReconstructionSafetyDecision.RequireMoreEvidence].
 *
 * See [ReconstructionSafetyDecision]'s companion `invoke` factories for where this is enforced.
 */
enum class SafetyGateReasonCategory { HARD_FAILURE, UNCERTAINTY, SUPPORT }

/**
 * Codified, explainable reasons behind a [ReconstructionSafetyDecision] — mirrors
 * [ReconstructionReasonCode]'s own "stable typed codes, never human-readable strings as domain
 * truth" shape and its open-enum-with-an-escape-hatch pattern, but is a genuinely separate
 * vocabulary: [ReconstructionReasonCode] explains why a [TrajectoryReconstructor] did or didn't
 * produce a candidate at all; this enum explains why the Safety Gate did or didn't trust an
 * already-produced [TrajectoryReconstructionResult.AcceptedTrajectory] candidate enough to let it
 * feed later discovery.
 *
 * **PHASE 3A CORRECTION ROUND 1 (Codex's B1 "ACCEPT is fail-open").** The original round's gate
 * reached [ReconstructionSafetyDecision.AcceptReconstruction] whenever no *known* problem was found
 * — an evidence bundle that was almost entirely [EvidenceValue.Unknown] could still accept, which
 * means ACCEPT never actually meant "positively supported", only "nothing complained". This round
 * introduces a **mandatory positive-evidence contract**: [ReconstructionSafetyGate.evaluate] tracks
 * a fixed set of mandatory facts (candidate/evidence association, temporal validity, kinematic
 * plausibility, matcher/candidate structural relationship, matcher accounting coherence, observation
 * support, transport-mode compatibility) and can only reach `AcceptReconstruction` once **every**
 * one of them is positively, *affirmatively* known-good — never merely "not known bad". The `_UNKNOWN`
 * codes below exist specifically to make "this mandatory fact was never established" visible and
 * distinct from "this mandatory fact was established and found unfavorable" in the reason trail.
 * Matcher-native confidence and the other evidence groups explicitly called out as OPTIONAL/enriching
 * (Phase 3A round's §6/§8) remain exempt from this — they may legitimately stay
 * [EvidenceValue.Unknown] without blocking acceptance, per this round's own explicit instruction.
 */
enum class SafetyGateReasonCode(val category: SafetyGateReasonCategory) {
    // ---- SUPPORT: only ever justifies AcceptReconstruction. Every mandatory fact gets its own
    // explicit SUPPORT reason so an accepted decision's reason trail visibly enumerates what was
    // positively established, never merely implies it from the absence of a complaint. ----

    /** PHASE 3A CORRECTION ROUND 2 (Layer A): [OrderedObservationWindowIdentity] recomputed from the
     * evaluated candidate's own observation window matched
     * [ReconstructionSafetyEvidence.observationWindowIdentity] exactly. */
    OBSERVATION_WINDOW_IDENTITY_CONFIRMED(SafetyGateReasonCategory.SUPPORT),

    /** PHASE 3A CORRECTION ROUND 2 (Layer B): the candidate's own `matcherRunIdentity` matched
     * [ReconstructionSafetyEvidence.matcherRunIdentity] exactly. */
    MATCHER_RUN_IDENTITY_CONFIRMED(SafetyGateReasonCategory.SUPPORT),

    /** Layer C: [ReconstructionCandidateIdentity] recomputed from the evaluated candidate's own
     * geometry/provenance matched [ReconstructionSafetyEvidence.associatedCandidateIdentity] exactly. */
    EVIDENCE_CANDIDATE_ASSOCIATION_CONFIRMED(SafetyGateReasonCategory.SUPPORT),

    /** [ObservationEvidence.temporallyMonotonic] is known `true`. */
    TEMPORAL_STRUCTURE_VALID(SafetyGateReasonCategory.SUPPORT),

    /** [ObservationEvidence.impliedAverageSpeedMetersPerSecond] is known and within
     * [ReconstructionSafetyPolicy.plausibleSpeedMetersPerSecond] for [ReconstructionSafetyPolicy.transportMode]. */
    KINEMATICS_PLAUSIBLE(SafetyGateReasonCategory.SUPPORT),

    /** [MatcherEvidence.declinedIntervals] is known, and no declined edge range overlaps the
     * candidate's own `bridgedObservationEdges` — no bridge was invented across anything the matcher
     * declined (PHASE 3A CORRECTION ROUND 2: edge-based, see [EdgeRange]'s own doc comment). */
    NO_INVENTED_BRIDGE(SafetyGateReasonCategory.SUPPORT),

    /** [MatcherEvidence.declinedIntervals] is known **empty** — the matcher affirmatively reported
     * full continuity across the whole candidate window (the strongest structural-continuity
     * signal available, distinct from merely "no invented bridge"). */
    ROUTE_STRUCTURALLY_CONTINUOUS(SafetyGateReasonCategory.SUPPORT),

    /** Matched/interpolated/unmatched counts are all known and exactly partition the candidate's
     * own `inputObservationCount`. */
    MATCHER_ACCOUNTING_COHERENT(SafetyGateReasonCategory.SUPPORT),

    /** The candidate's own `inputObservationCount` meets or exceeds
     * [ReconstructionSafetyPolicy.minimumObservationSupport]. */
    SUFFICIENT_OBSERVATION_SUPPORT(SafetyGateReasonCategory.SUPPORT),

    /** The candidate's own `transportModeHypothesis.mode` matches [ReconstructionSafetyPolicy.transportMode]. */
    TRANSPORT_MODE_COMPATIBLE(SafetyGateReasonCategory.SUPPORT),

    /** OPTIONAL/enriching positive signal: the number of plausible alternative routes stayed within
     * [ReconstructionSafetyPolicy.maximumToleratedAlternativeRoutes] (when known). */
    LOW_NETWORK_AMBIGUITY(SafetyGateReasonCategory.SUPPORT),

    /** OPTIONAL/enriching positive signal: matcher-native confidence was known and met
     * [ReconstructionSafetyPolicy.minimumMatcherConfidenceWhenKnown]. */
    MATCHER_CONFIDENCE_ADEQUATE(SafetyGateReasonCategory.SUPPORT),

    /** OPTIONAL/enriching positive signal: every known consistency signal (implied-speed / heading /
     * observation-to-route) agreed. */
    CONSISTENT_KINEMATICS(SafetyGateReasonCategory.SUPPORT),

    // ---- UNCERTAINTY (mandatory-fact-unknown flavor): a required fact for acceptance was never
    // established. Always blocks AcceptReconstruction; never itself a HARD_FAILURE, since "unknown"
    // is not evidence of invalidity. ----

    /** PHASE 3A CORRECTION ROUND 2 (Layer A): the candidate's own `observationWindowIdentity` is
     * `null` — which ordered raw observations produced this candidate cannot be confirmed. */
    OBSERVATION_WINDOW_IDENTITY_UNKNOWN(SafetyGateReasonCategory.UNCERTAINTY),

    /** PHASE 3A CORRECTION ROUND 2 (Layer B): the candidate's own `matcherRunIdentity` is `null` —
     * which matcher evaluation produced this candidate cannot be confirmed. */
    MATCHER_RUN_IDENTITY_UNKNOWN(SafetyGateReasonCategory.UNCERTAINTY),

    /** [ObservationEvidence.temporallyMonotonic] was not reported. */
    TEMPORAL_STRUCTURE_UNKNOWN(SafetyGateReasonCategory.UNCERTAINTY),

    /** [ObservationEvidence.impliedAverageSpeedMetersPerSecond] was not reported. */
    KINEMATICS_UNKNOWN(SafetyGateReasonCategory.UNCERTAINTY),

    /** [MatcherEvidence.declinedIntervals] was not reported — whether/where the matcher declined
     * anything is unknown, so the candidate/matcher structural relationship cannot be confirmed
     * safe. */
    STRUCTURAL_RELATIONSHIP_UNKNOWN(SafetyGateReasonCategory.UNCERTAINTY),

    /** Matched/interpolated/unmatched counts are not all known, so matcher accounting coherence
     * cannot be confirmed. */
    MATCHER_ACCOUNTING_UNKNOWN(SafetyGateReasonCategory.UNCERTAINTY),

    /** The candidate's own `transportModeHypothesis` is absent — a transport-mode hypothesis exists
     * on [TrajectoryReconstructionResult.AcceptedTrajectory] for exactly this purpose; without it,
     * compatibility with [ReconstructionSafetyPolicy.transportMode] cannot be confirmed. */
    TRANSPORT_COMPATIBILITY_UNKNOWN(SafetyGateReasonCategory.UNCERTAINTY),

    // ---- UNCERTAINTY (mandatory-fact-known-but-insufficient, or optional-evidence-unfavorable
    // flavor): drives RequireMoreEvidence; never a HARD_FAILURE on its own. ----

    /** The candidate's own `inputObservationCount` is below [ReconstructionSafetyPolicy.minimumObservationSupport]. */
    SPARSE_OBSERVATIONS(SafetyGateReasonCategory.UNCERTAINTY),

    /** More plausible alternative routes than [ReconstructionSafetyPolicy.maximumToleratedAlternativeRoutes]. */
    HIGH_NETWORK_AMBIGUITY(SafetyGateReasonCategory.UNCERTAINTY),

    /** A reported endpoint is evidenced as implausible (e.g. off any known network) — 2B-4's own
     * real finding: a real, non-fatal ambiguity signal, not a wrong-road contradiction. */
    ENDPOINT_IMPLAUSIBLE(SafetyGateReasonCategory.UNCERTAINTY),

    /** Matcher-native confidence was known but below [ReconstructionSafetyPolicy.minimumMatcherConfidenceWhenKnown].
     * Deliberately [UNCERTAINTY], never [HARD_FAILURE] on its own: weak native confidence alone must
     * never force [ReconstructionSafetyDecision.NoReconstruction]. */
    MATCHER_CONFIDENCE_WEAK(SafetyGateReasonCategory.UNCERTAINTY),

    /** Matcher-native confidence was not reported at all, and
     * [ReconstructionSafetyPolicy.acceptWhenMatcherConfidenceUnknown] is `false`. Kept distinct from
     * [MATCHER_CONFIDENCE_WEAK] so "known weak" and "never reported" stay observably different. */
    MATCHER_CONFIDENCE_UNAVAILABLE(SafetyGateReasonCategory.UNCERTAINTY),

    /** The interpolated fraction of matched+interpolated+unmatched observations exceeded
     * [ReconstructionSafetyPolicy.maximumInterpolatedObservationFraction]. Only ever computed once
     * accounting is already known coherent — see [MATCHER_ACCOUNTING_UNKNOWN]/[MATCHER_ACCOUNTING_INCOHERENT]. */
    INTERPOLATION_HEAVY(SafetyGateReasonCategory.UNCERTAINTY),

    /** At least one observation in the matcher's own (coherent) accounting was reported unmatched —
     * an additional signal on top of, not instead of, coherent accounting. */
    PARTIAL_UNMATCHED_OBSERVATIONS(SafetyGateReasonCategory.UNCERTAINTY),

    /** Multiple matchers were evaluated ([ReconstructionSafetyEvidence.crossMatcherAgreement] is
     * present) and they did not all agree. Never resolved by majority vote — its only effect is to
     * add uncertainty. */
    MATCHER_DISAGREEMENT(SafetyGateReasonCategory.UNCERTAINTY),

    /** At least one known consistency signal (implied-speed / heading / observation-to-route)
     * disagreed. Deliberately [UNCERTAINTY]: a single inconsistent signal alone is not proof of an
     * invalid candidate the way [INVALID_TEMPORAL_STRUCTURE]/[PHYSICALLY_IMPLAUSIBLE_KINEMATICS] are. */
    CONSISTENCY_SIGNAL_WEAK(SafetyGateReasonCategory.UNCERTAINTY),

    /** PHASE 3A CORRECTION ROUND 2 (Codex's minor finding): [MatcherEvidence.unknownClassificationObservationCount]
     * is known and greater than zero — some observations were neither matched, interpolated, nor
     * unmatched. Never silently dropped from accounting, and never treated as though it supports
     * acceptance on its own. */
    UNCLASSIFIED_OBSERVATIONS_PRESENT(SafetyGateReasonCategory.UNCERTAINTY),

    // ---- HARD_FAILURE: the only category that can justify NoReconstruction. ----

    /** PHASE 3A CORRECTION ROUND 2 (Layer A): [OrderedObservationWindowIdentity] recomputed from the
     * evaluated candidate's own observation window does not match
     * [ReconstructionSafetyEvidence.observationWindowIdentity] — this evidence cannot safely be
     * associated with which observations actually produced this candidate. Checked first, before any
     * other rule; makes the Round 1 "same geometry, different underlying observations" stale-evidence
     * case structurally impossible to authorize. */
    OBSERVATION_WINDOW_MISMATCH(SafetyGateReasonCategory.HARD_FAILURE),

    /** PHASE 3A CORRECTION ROUND 2 (Layer B): the candidate's own `matcherRunIdentity` does not match
     * [ReconstructionSafetyEvidence.matcherRunIdentity] — this evidence describes a different matcher
     * evaluation than the one that produced this candidate. Checked immediately after Layer A. */
    MATCHER_RUN_MISMATCH(SafetyGateReasonCategory.HARD_FAILURE),

    /** Layer C: [ReconstructionCandidateIdentity] recomputed from the evaluated candidate's own
     * geometry/provenance does not match [ReconstructionSafetyEvidence.associatedCandidateIdentity]
     * (Codex's original B2). Checked after Layers A and B. */
    EVIDENCE_CANDIDATE_MISMATCH(SafetyGateReasonCategory.HARD_FAILURE),

    /** [ObservationEvidence.temporallyMonotonic] is known `false` — observation ordering contradicts
     * itself; nothing downstream can be trusted. */
    INVALID_TEMPORAL_STRUCTURE(SafetyGateReasonCategory.HARD_FAILURE),

    /** [ObservationEvidence.impliedAverageSpeedMetersPerSecond] is known and falls outside
     * [ReconstructionSafetyPolicy.plausibleSpeedMetersPerSecond] for [ReconstructionSafetyPolicy.transportMode]. */
    PHYSICALLY_IMPLAUSIBLE_KINEMATICS(SafetyGateReasonCategory.HARD_FAILURE),

    /** A [MatcherDeclinedInterval] the matcher reported overlaps the candidate's own
     * `bridgedObservationEdges` — a direct structural contradiction between what the matcher was
     * willing to claim and what the candidate claims (2B-5's central safety finding, Codex's B3).
     * PHASE 3A CORRECTION ROUND 2: edge-based (see [EdgeRange]'s own doc comment) — never inferred
     * from vertex-provenance labels, and never dependent on whether an inferred geometry vertex
     * happens to exist. */
    UNSUPPORTED_BRIDGE(SafetyGateReasonCategory.HARD_FAILURE),

    /** PHASE 3A CORRECTION ROUND 2: a [MatcherDeclinedInterval]'s [EdgeRange] references an
     * observation-window edge index beyond the evaluated candidate's own valid edge range
     * (`0..inputObservationCount-2`) — the evidence cannot structurally describe this candidate's
     * observation window at all. */
    DECLINED_EDGE_OUT_OF_BOUNDS(SafetyGateReasonCategory.HARD_FAILURE),

    /** Matched/interpolated/unmatched/unknown-classification counts are all known but do not
     * coherently relate to the candidate's own `inputObservationCount` — either their sum exceeds it,
     * or (when all four are known) their sum does not exactly partition it (Codex's B4). */
    MATCHER_ACCOUNTING_INCOHERENT(SafetyGateReasonCategory.HARD_FAILURE),

    /** The candidate's own `transportModeHypothesis.mode` is known and does not match
     * [ReconstructionSafetyPolicy.transportMode] (Codex's B5) — a WALK candidate must never be
     * silently evaluated under ROAD_VEHICLE limits, or vice versa. */
    TRANSPORT_MODE_MISMATCH(SafetyGateReasonCategory.HARD_FAILURE),

    /** Any cause not covered above — see the accompanying [SafetyGateReason.detail]. Conservatively
     * categorized [UNCERTAINTY] (never silently a hard failure or an accept) so an uninspected
     * escape-hatch reason can never itself force either extreme outcome. Never used as a substitute
     * for adding a real code once a genuine recurring cause is identified. */
    OTHER(SafetyGateReasonCategory.UNCERTAINTY),
}

/** One explainable Safety Gate reason, with an optional free-text [detail] for diagnostics —
 * mirrors [ReconstructionReason]'s shape exactly. Never carries anything privacy-sensitive (no raw
 * coordinates, no user-identifying content) — only aggregate/structural descriptions. */
data class SafetyGateReason(val code: SafetyGateReasonCode, val detail: String? = null)
