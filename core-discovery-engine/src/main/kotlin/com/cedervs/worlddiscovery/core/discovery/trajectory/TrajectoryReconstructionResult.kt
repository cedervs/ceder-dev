package com.cedervs.worlddiscovery.core.discovery.trajectory

import com.cedervs.worlddiscovery.core.discovery.Coordinate

/**
 * How a reconstructed geometry was produced — an open, generic vocabulary so this contract never
 * has to be reshaped around one specific future engine's own concepts (Valhalla/OSRM/GraphHopper
 * are explicitly not chosen yet — see `docs/ai-context/LOCATION_TRACKING.md`).
 */
enum class ReconstructionStrategyKind {
    /** Pure spatial/temporal continuity reasoning without any road-network graph (the closest
     * analog today is the corridor's own H3-adjacency evidence — see `DiscoveredRoute.kt` — but a
     * future geometric strategy for this system is its own, separate, not-yet-built component). */
    GEOMETRIC_CONTINUITY,

    /** Matching against a real road/rail/path network graph. */
    NETWORK_MATCHING,

    /** A combination of the above. */
    HYBRID,

    /** The strategy that produced this result did not report which kind it used. */
    UNKNOWN,
}

/** [name] is a free-text implementation identifier (e.g. a future engine's own name) — kept
 * separate from [kind] so the generic contract survives even before any specific engine is
 * chosen. */
data class ReconstructionStrategy(
    val kind: ReconstructionStrategyKind,
    val name: String? = null,
)

/** Identifies which network/graph dataset a [ReconstructionStrategyKind.NETWORK_MATCHING] or
 * [ReconstructionStrategyKind.HYBRID] result drew on — always `null` in Phase 1 (no graph exists).
 * Kept intentionally minimal (no engine-specific fields) until a real graph source is chosen. */
data class GraphSourceDescriptor(
    val name: String,
    val version: String,
)

/** One correspondence between an input observation and whatever candidate the strategy matched it
 * to — [candidateDescription] is deliberately a free-text/opaque description rather than a typed
 * network-edge/node reference, since no concrete graph representation is chosen yet; a future
 * strategy integrating a real graph would likely replace or extend this shape, not necessarily
 * reuse it as-is.
 *
 * [observationIndex] indexes into the [ObservationWindow] the reconstructor was given — **not**
 * into [AcceptedTrajectory.geometry]; validating it against the actual window size is
 * [AcceptedTrajectory]'s own responsibility (see [AcceptedTrajectory.inputObservationCount]),
 * since this class alone has no way to know how large that window was. [distanceMeters], when
 * present, is a physical distance and so must be finite and non-negative — `NaN`/±`Infinity`/
 * negative values are rejected rather than silently accepted as "a distance". */
data class ObservationNetworkMatch(
    val observationIndex: Int,
    val candidateDescription: String,
    val distanceMeters: Double? = null,
) {
    init {
        require(observationIndex >= 0) { "observationIndex must not be negative, got $observationIndex" }
        distanceMeters?.let {
            require(it.isFinite() && it >= 0.0) { "distanceMeters must be finite and >= 0 when present, got $it" }
        }
    }
}

/**
 * The outcome of [TrajectoryReconstructor.reconstruct] — every variant carries [reasons]
 * (explainability is a hard requirement, not an afterthought — see `docs/ai-context/LOCATION_TRACKING.md`)
 * and [cadenceRecommendation] (a strategy may ask for a denser future observation stream
 * regardless of whether it could reconstruct anything this time — see
 * [ObservationCadenceRecommendation]'s own doc comment for the contract this expresses).
 *
 * **[NoReconstruction] is an explicit, expected, normal result — never an error** — but per this
 * round's own product framing: on a clearly road-based movement, a future strategy should make a
 * genuine effort to reconstruct via the network before falling back to it; `NoReconstruction`
 * exists as a safety net for real ambiguity/insufficient evidence/incompatibility with known
 * networks, not as a default first response to any gap. Nothing in Phase 1 enforces this
 * preference — no strategy exists yet — this is recorded here so a future strategy's own design
 * inherits the right default posture rather than reopening the question.
 */
sealed interface TrajectoryReconstructionResult {
    val reasons: List<ReconstructionReason>
    val cadenceRecommendation: ObservationCadenceRecommendation?

    /**
     * A trajectory was reconstructed with enough confidence to proceed.
     *
     * [geometry] is the full reconstructed path, ordered. [observedIndices] identifies which
     * positions in [geometry] came directly from an input observation (vs. [inferredIndices] for
     * positions the strategy interpolated/inferred) — kept as index sets against the same
     * [geometry] list rather than two separate point lists, so there is exactly one geometry, never
     * two that could drift apart.
     *
     * This never itself becomes an H3 cell or a `Provenance.RECONSTRUCTED` `DiscoveredCell` —
     * converting an [AcceptedTrajectory] into canonical discovery data is explicitly future work
     * (Phase 1 forbids it — see `docs/ai-context/LOCATION_TRACKING.md`).
     *
     * **Invariants (enforced at construction, not merely documented):**
     * - `geometry.size >= 2` — never a degenerate single-point "trajectory".
     * - every index in [observedIndices] and every index in [inferredIndices] is a valid index
     *   into [geometry].
     * - [observedIndices] and [inferredIndices] never overlap.
     * - every position in [geometry] has a provenance — the union of [observedIndices] and
     *   [inferredIndices] covers all of `geometry.indices`; there is no silently-unaccounted-for
     *   point.
     * - [engineVersion] is a positive version number (`>= 1`) — `0`/negative is never valid.
     * - [inputObservationCount] (the size of the [ObservationWindow] the reconstructor was given)
     *   is positive, and every [ObservationNetworkMatch.observationIndex] in [observationMatches]
     *   is a valid index into that window (`0 until inputObservationCount`) — this is the only
     *   place that validation can happen, since [ObservationNetworkMatch] itself has no visibility
     *   into the window size.
     *
     * [geometry], [observedIndices], [inferredIndices], [parameters], [observationMatches],
     * [bridgedObservationEdges] and [reasons] are all defensively copied at construction — mutating a
     * `MutableList`/`MutableSet`/`MutableMap` passed in by the caller after constructing this object
     * has no effect on the instance held here. The primary constructor is private specifically so
     * every instance goes through the companion [invoke], which performs both this copy and the
     * validation above; `copy()` is therefore also private (Kotlin ties its visibility to the primary
     * constructor's) — this is deliberate, since an unchecked `copy()` could otherwise produce an
     * instance violating the invariants above without re-running this validation. `@ConsistentCopyVisibility`
     * is required for that: without it, Kotlin (as of the language versions this module targets) still
     * generates a *public* `copy()` even for a class with a private primary constructor — a real,
     * easy-to-miss loophole around exactly the validation this class exists to enforce.
     *
     * **PHASE 3A CORRECTION ROUND 2 additions (Codex's remaining B2/B3 findings):**
     * - [observationWindowIdentity] / [matcherRunIdentity] — see [OrderedObservationWindowIdentity]/
     *   [MatcherRunIdentity]'s own doc comments. Both default to `null` ("not yet associated with a
     *   specific window/run") — a safe default: [ReconstructionSafetyGate] treats `null` as blocking
     *   acceptance, never as a false-positive match, so omitting either can never silently authorize
     *   anything.
     * - [bridgedObservationEdges] — **no default, deliberately mandatory.** The set of **observation-
     *   window edge indices** (edge `i` connects original observation `i` to observation `i+1`, valid
     *   range `0..inputObservationCount-2`) this candidate's own reconstruction is claiming continuity
     *   across *without* independently-confirmed direct support — i.e. the edges this candidate itself
     *   is honestly disclosing as bridged/inferred, in observation-window index space. Deliberately
     *   **independent of [observedIndices]/[inferredIndices]** (which describe geometry-*vertex*
     *   rendering provenance, a different concern): two adjacent, both-*observed* geometry vertices can
     *   still correspond to a bridged observation-window edge if the underlying raw observations they
     *   came from were not adjacent in the original window, or if their connecting path was not itself
     *   independently confirmed — labelling a vertex "observed" says nothing about whether the *edge*
     *   leading to it was matcher-confirmed. A default of `emptySet()` would silently under-report
     *   bridging (the exact fail-open shape Codex's B1 finding already required closing elsewhere in
     *   this package), so no default is offered — every caller must state this explicitly.
     */
    @ConsistentCopyVisibility
    data class AcceptedTrajectory private constructor(
        val geometry: List<Coordinate>,
        val observedIndices: Set<Int>,
        val inferredIndices: Set<Int>,
        val strategy: ReconstructionStrategy,
        val transportModeHypothesis: TransportModeHypothesis?,
        val confidence: ReconstructionConfidence,
        val engineVersion: Int,
        val graphSource: GraphSourceDescriptor?,
        val parameters: Map<String, String>,
        val inputObservationCount: Int,
        val observationMatches: List<ObservationNetworkMatch>,
        val observationWindowIdentity: OrderedObservationWindowIdentity?,
        val matcherRunIdentity: MatcherRunIdentity?,
        val bridgedObservationEdges: Set<Int>,
        override val reasons: List<ReconstructionReason>,
        override val cadenceRecommendation: ObservationCadenceRecommendation? = null,
    ) : TrajectoryReconstructionResult {
        companion object {
            operator fun invoke(
                geometry: List<Coordinate>,
                observedIndices: Set<Int>,
                inferredIndices: Set<Int>,
                strategy: ReconstructionStrategy,
                transportModeHypothesis: TransportModeHypothesis?,
                confidence: ReconstructionConfidence,
                engineVersion: Int,
                graphSource: GraphSourceDescriptor?,
                parameters: Map<String, String>,
                inputObservationCount: Int,
                observationMatches: List<ObservationNetworkMatch>,
                bridgedObservationEdges: Set<Int>,
                reasons: List<ReconstructionReason>,
                observationWindowIdentity: OrderedObservationWindowIdentity? = null,
                matcherRunIdentity: MatcherRunIdentity? = null,
                cadenceRecommendation: ObservationCadenceRecommendation? = null,
            ): AcceptedTrajectory {
                val safeGeometry = geometry.toList()
                val safeObserved = observedIndices.toSet()
                val safeInferred = inferredIndices.toSet()
                val safeParameters = parameters.toMap()
                val safeMatches = observationMatches.toList()
                val safeBridgedEdges = bridgedObservationEdges.toSet()
                val safeReasons = reasons.toList()

                require(safeGeometry.size >= 2) {
                    "An accepted trajectory must have at least 2 points, got ${safeGeometry.size}"
                }
                val validGeometryIndices = safeGeometry.indices.toSet()
                require(safeObserved.all { it in validGeometryIndices }) {
                    "observedIndices must all be valid geometry indices (0 until ${safeGeometry.size}), got $safeObserved"
                }
                require(safeInferred.all { it in validGeometryIndices }) {
                    "inferredIndices must all be valid geometry indices (0 until ${safeGeometry.size}), got $safeInferred"
                }
                require(safeObserved.intersect(safeInferred).isEmpty()) {
                    "observedIndices and inferredIndices must not overlap, both contain: ${safeObserved.intersect(safeInferred)}"
                }
                require((safeObserved + safeInferred) == validGeometryIndices) {
                    "every geometry position must have a provenance (observed or inferred) -- " +
                        "unaccounted-for indices: ${validGeometryIndices - safeObserved - safeInferred}"
                }
                require(engineVersion >= 1) { "engineVersion must be >= 1, got $engineVersion" }
                require(inputObservationCount > 0) {
                    "inputObservationCount must be positive, got $inputObservationCount"
                }
                require(safeMatches.all { it.observationIndex in 0 until inputObservationCount }) {
                    "every observationMatches.observationIndex must be a valid index into the input window " +
                        "(0 until $inputObservationCount)"
                }
                val validObservationEdgeIndices = 0..(inputObservationCount - 2)
                require(safeBridgedEdges.all { it in validObservationEdgeIndices }) {
                    "every bridgedObservationEdges entry must be a valid observation-window edge index " +
                        "($validObservationEdgeIndices for inputObservationCount=$inputObservationCount), got $safeBridgedEdges"
                }
                require(observationWindowIdentity == null || observationWindowIdentity.observationCount == inputObservationCount) {
                    "observationWindowIdentity.observationCount (${observationWindowIdentity?.observationCount}) must equal " +
                        "inputObservationCount ($inputObservationCount) when present"
                }

                return AcceptedTrajectory(
                    geometry = safeGeometry,
                    observedIndices = safeObserved,
                    inferredIndices = safeInferred,
                    strategy = strategy,
                    transportModeHypothesis = transportModeHypothesis,
                    confidence = confidence,
                    engineVersion = engineVersion,
                    graphSource = graphSource,
                    parameters = safeParameters,
                    inputObservationCount = inputObservationCount,
                    observationMatches = safeMatches,
                    observationWindowIdentity = observationWindowIdentity,
                    matcherRunIdentity = matcherRunIdentity,
                    bridgedObservationEdges = safeBridgedEdges,
                    reasons = safeReasons,
                    cadenceRecommendation = cadenceRecommendation,
                )
            }
        }
    }

    /** Multiple candidate trajectories were plausible with no clear winner. [leadingCandidateCount]
     * is how many distinct plausible candidates were considered, when known (`null` if the
     * strategy didn't report a count). */
    data class Ambiguous(
        val leadingCandidateCount: Int?,
        val confidence: ReconstructionConfidence = ReconstructionConfidence.UNKNOWN,
        override val reasons: List<ReconstructionReason>,
        override val cadenceRecommendation: ObservationCadenceRecommendation? = null,
    ) : TrajectoryReconstructionResult

    /** The inferred/likely transport mode is one this strategy does not attempt to reconstruct
     * (e.g. air, open water — see `docs/discovery-engine.md` §2/§11). */
    data class UnsupportedMode(
        val hypothesis: TransportModeHypothesis?,
        override val reasons: List<ReconstructionReason>,
        override val cadenceRecommendation: ObservationCadenceRecommendation? = null,
    ) : TrajectoryReconstructionResult

    /** Too little usable evidence to attempt anything (too few observations, all low-quality,
     * window too short, ...). */
    data class InsufficientEvidence(
        override val reasons: List<ReconstructionReason>,
        override val cadenceRecommendation: ObservationCadenceRecommendation? = null,
    ) : TrajectoryReconstructionResult

    /** No reconstruction was produced — see the interface doc comment for the product nuance this
     * carries: a normal, explicit, expected outcome, but a safety net, not a default. */
    data class NoReconstruction(
        override val reasons: List<ReconstructionReason>,
        override val cadenceRecommendation: ObservationCadenceRecommendation? = null,
    ) : TrajectoryReconstructionResult
}
