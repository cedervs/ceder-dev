package worlddiscovery.benchmark

import kotlinx.serialization.Serializable

/**
 * Phase 2B benchmark harness -- documentation/tooling only, never wired into production code.
 * See `docs/ai-context/PHASE_2B_BENCHMARK_PROTOCOL.md` §B for the full rationale behind each shape
 * below. These types are deliberately separate from `core-discovery-engine`'s
 * `TrajectoryReconstructor` contract -- a future correspondence is analyzed, not implemented, in
 * that document's "Correspondance future" section.
 *
 * All types in this file are genuinely `@Serializable` (kotlinx.serialization) -- verified by
 * compiling `tools/mapmatch-benchmark` with the `kotlinx-serialization-json-jvm` classpath entry
 * already used by the JSON-parsing adapters. `IntRange` is not natively serializable, so
 * `EngineMatchResult.splits` uses [SplitRange] instead of `IntRange` for this reason (2B-1B
 * correction -- Phase 2B-1's original `splits: List<IntRange>` was never actually serialized or
 * tested, so this is a genuine fix, not a claim carried over unverified).
 */

@Serializable
data class LatLon(val lat: Double, val lon: Double)

enum class TransportMode { CAR, WALK, BIKE }

enum class CorpusCategory { ROAD, WALK, BIKE, ADVERSARIAL }

enum class CorpusStatus { PLANNED, CAPTURED, VALIDATED, BENCHMARKED }

// =====================================================================================
// Truth model (2B-1B correction §2) -- three distinct concepts, never collapsed into one.
// =====================================================================================

/** A. REFERENCE GPS OBSERVATIONS -- raw high-frequency captured measurements. This is EVIDENCE,
 * never automatically truth (2B-1B correction §2.A). `timestampEpochMs` is a plain epoch
 * millisecond `Long`, not `java.time.Instant` -- the original protocol doc's `Instant` claim in its
 * Kotlin-shaped sketch did not match this field's actual type; corrected here and in the doc. */
@Serializable
data class ReferencePoint(
    val lat: Double,
    val lon: Double,
    val timestampEpochMs: Long,
    val accuracyMeters: Float? = null,
    val bearingDegrees: Float? = null,
    val speedMps: Float? = null,
    val providerOrSource: String? = null,
)

/** How a [VerifiedSegment] or [VerifiedRouteGeometry] was independently confirmed -- 2B-1B
 * correction §10. Never includes a candidate matcher (OSRM/Valhalla/GraphHopper) as a method. */
enum class VerificationMethod {
    MAP_INSPECTION,
    KNOWN_TURN_SEQUENCE,
    VIDEO_OR_DASHCAM,
    SECOND_INDEPENDENT_LOGGER,
    MANUAL_ROUTE_ANNOTATION,
}

/** 2B-1B correction §10 -- only [HIGH] confidence segments/traces are used for the safety-critical
 * H3 false-positive metric initially; [MEDIUM] may be reported separately; [UNUSABLE] is excluded
 * from all quality metrics (kept only for corpus bookkeeping). */
enum class TruthConfidence { HIGH, MEDIUM, UNUSABLE }

/** C. SEGMENT/CORRIDOR ANNOTATIONS (2B-1B correction §2.C) -- structured per-segment truth,
 * replacing the original `ReferenceTrace.manuallyVerifiedPath: String?` free-form field the
 * correction round explicitly rejected ("Do not use one free-form String as the only truth
 * representation"). Indices are into the *reference* observations
 * ([ReferenceTrace.rawObservations]), not into any degraded/matched output. */
@Serializable
data class VerifiedSegment(
    val segmentId: String,
    val startReferenceIndex: Int,
    val endReferenceIndex: Int,
    /** The actual road/path identifier travelled, e.g. an OSM way id, a street name, or a
     * researcher-assigned corridor label ("D12 southbound", "riverside footpath"). */
    val corridorId: String,
    val verificationMethod: VerificationMethod,
    val confidence: TruthConfidence,
    val ambiguityNote: String? = null,
    val roadOrPathDescription: String? = null,
    /** Optional pointer (file path or id) to a manually traced geometry specific to this segment,
     * when the segment's path diverges from the overall [VerifiedRouteGeometry.geometry]. */
    val verifiedGeometryRef: String? = null,
)

/** 2B-2A correction: two genuinely different questions were previously collapsed into one
 * `overallConfidence: TruthConfidence` field on [VerifiedRouteGeometry] -- "which corridor/road was
 * travelled" (a discrete, human-verifiable fact an annotated map can establish with real
 * confidence) and "is this exact polyline metre-accurate ground truth" (a claim raw consumer GPS,
 * even corroborated by a map annotation, does NOT support). [GeometryTruthStatus] makes the second
 * question explicit and separate: [VALIDATED] means the geometry itself has been independently
 * confirmed accurate enough for metre-scale metrics (e.g. by manual tracing from imagery, not by
 * trusting raw GPS wobble); [PENDING] means no such stronger verification has been done yet, but
 * could be added later; [NOT_EVALUABLE] means no defensible exact geometry exists and none is
 * expected. `TruthGuard.kt` enforces that exact-geometry-dependent metrics are only computed when
 * this is [VALIDATED] -- see its own doc comment. */
enum class GeometryTruthStatus { VALIDATED, PENDING, NOT_EVALUABLE }

/** B. VERIFIED TRAVEL GEOMETRY (2B-1B correction §2.B) -- the independently verified path actually
 * travelled. MUST NOT be produced by OSRM/Valhalla/GraphHopper -- [sourceDescription] must name a
 * non-matcher method.
 *
 * **2B-2A correction (do not repeat the mistake this fixes):** [geometry] is observational evidence
 * -- typically a dense raw GPS polyline -- NOT automatically exact ground truth just because
 * [segments] independently confirm which corridor/road it represents. [corridorConfidence] (renamed
 * from the old, overloaded `overallConfidence`) is ONLY a statement about corridor/road identity --
 * "the walker used this one specific road, confirmed by an annotated map" -- and says nothing about
 * whether [geometry]'s exact lateral position, every GPS wobble, or exact H3 cell membership is
 * physically accurate. That second, separate claim is [exactGeometryTruthStatus] -- it defaults to
 * [GeometryTruthStatus.PENDING] and must be explicitly set to [GeometryTruthStatus.VALIDATED]
 * (by a stronger method than raw GPS + corridor annotation, e.g. manual tracing from independent
 * imagery) before any Hausdorff/Fréchet/overlap/H3-TP/FP/FN metric may be computed against
 * [geometry] as truth -- enforced in code by `TruthGuard.kt`, never left to a caller's discretion. */
@Serializable
data class VerifiedRouteGeometry(
    val scenarioId: String,
    /** Raw-GPS-evidence-derived polyline (or, when [exactGeometryTruthStatus] is `VALIDATED`, an
     * independently traced one) -- see the class-level doc comment for why this is not
     * automatically exact truth. Never call this "the verified geometry" in prose without also
     * stating [exactGeometryTruthStatus]. */
    val geometry: List<LatLon>,
    val segments: List<VerifiedSegment>,
    /** Confidence in corridor/road IDENTITY only (which road was used) -- see class doc comment.
     * Renamed from `overallConfidence` (2B-1B) because that name invited exactly the conflation
     * this correction fixes. */
    val corridorConfidence: TruthConfidence,
    /** Whether [geometry] itself may be treated as exact ground truth for metre-scale metrics.
     * Defaults to [GeometryTruthStatus.PENDING] -- callers must not assume `VALIDATED`. */
    val exactGeometryTruthStatus: GeometryTruthStatus = GeometryTruthStatus.PENDING,
    /** e.g. "manual OSM/imagery trace of the known turn sequence, cross-checked against raw GPS
     * evidence" -- must describe a verification method, never "OSRM /match output" or equivalent. */
    val sourceDescription: String,
)

/** The independent ground truth for one scenario -- see protocol §C for how this must be captured.
 * 2B-1B correction: renamed `points` to [rawObservations] to make explicit these are evidence, not
 * truth (§2.A), and replaced the free-form `manuallyVerifiedPath: String?` with a structured,
 * nullable [verifiedGeometry] (§2.B/§2.C) -- null is valid and expected for a trace that is
 * `CAPTURED` but not yet `VALIDATED` (see [CorpusStatus]). **2B-2A correction**: a non-null
 * [verifiedGeometry] with [VerifiedRouteGeometry.corridorConfidence] `HIGH` establishes corridor
 * identity only -- it does NOT by itself authorize the safety-critical H3 false-positive metric or
 * any exact-geometry metric; those additionally require
 * [VerifiedRouteGeometry.exactGeometryTruthStatus] `VALIDATED` (see `TruthGuard.kt`). */
@Serializable
data class ReferenceTrace(
    val id: String,
    val capturedAtEpochMs: Long,
    val device: String,
    val acquisitionMode: String,
    val rawObservations: List<ReferencePoint>,
    val verifiedGeometry: VerifiedRouteGeometry?,
    val corpusCategory: CorpusCategory,
    val corpusStatus: CorpusStatus,
)

// =====================================================================================
// Degradation specification (2B-1B correction §5/§16) -- structured, not implicit in code alone.
// =====================================================================================

enum class DegradationSelectionPolicy {
    /** For each target timestamp `t0 + k*targetCadence`, select the first not-yet-selected
     * observation with `timestampEpochMs >= target` -- the deterministic rule implemented in
     * `Degradation.kt` and specified in correction §16. */
    FIRST_AT_OR_AFTER_TARGET,
}

enum class OutlierInjectionRule { NONE, ISOLATED_OUTLIER, CONSECUTIVE_OUTLIERS }

/** Structured degradation specification (correction §5) -- a [DegradedObservationSet] must
 * reference the exact spec that produced it ([DegradedObservationSet.spec]), never just an
 * opaque variant label. Only [DegradationSelectionPolicy.FIRST_AT_OR_AFTER_TARGET] is implemented
 * this round (see `Degradation.kt`); the other fields describe planned-but-not-yet-implemented
 * perturbations (correction §16: "do not implement unless needed now"). */
@Serializable
data class DegradationSpec(
    val targetCadenceSeconds: Double,
    val selectionPolicy: DegradationSelectionPolicy = DegradationSelectionPolicy.FIRST_AT_OR_AFTER_TARGET,
    val gapInjectionSeconds: Double? = null,
    val accuracyPerturbationMeters: Double? = null,
    val coordinateNoiseMeters: Double? = null,
    val outlierRule: OutlierInjectionRule = OutlierInjectionRule.NONE,
    val randomSeed: Long? = null,
)

/** One observation as degraded/downsampled from a [ReferenceTrace] -- this is what actually gets
 * sent (in a per-engine adapted shape) to a matcher. See protocol §D.5 for generation rules. */
@Serializable
data class DegradedObservation(
    val index: Int,
    val lat: Double,
    val lon: Double,
    val timestampEpochMs: Long,
    val accuracyMeters: Float? = null,
    val bearingDegrees: Float? = null,
    val speedMps: Float? = null,
    val intentLabel: String = "NORMAL",
)

/** A full degraded input set -- identical, byte-for-byte, across every engine adapter (protocol
 * §B.2's "identical input rule"). [spec] makes the generation reproducible and auditable
 * (correction §5 -- "DegradedObservationSet must reference the exact specification that produced
 * it"). */
@Serializable
data class DegradedObservationSet(
    val referenceTraceId: String,
    val variant: String,
    val mode: TransportMode,
    val spec: DegradationSpec,
    val observations: List<DegradedObservation>,
)

enum class MatchOutcome { MATCHED, INTERPOLATED, UNMATCHED, UNKNOWN }

/** Per-observation outcome, when the engine's own output makes this derivable at all -- see
 * [EngineMatchResult.perObservation]'s own doc comment for why this is nullable by design. */
@Serializable
data class ObservationOutcome(
    val observationIndex: Int,
    val outcome: MatchOutcome,
    val matchedPoint: LatLon?,
    val distanceToObservationMeters: Double?,
    val edgeOrCandidateId: String?,
)

/** A contiguous sub-range `[startIndex, endIndex]` of matched geometry/observation indices,
 * representing one piece of a matcher's trace split. Replaces the original `List<IntRange>` (see
 * file header) so [EngineMatchResult] is actually serializable end to end. */
@Serializable
data class SplitRange(val startIndex: Int, val endIndex: Int)

enum class BenchmarkMode { DEFAULT, NORMALIZED }

/**
 * Engine configuration identity (2B-1B correction §4) -- makes a benchmark result auditable:
 * given only this record, another person should be able to point at exactly which engine build,
 * profile, config mode, and input/output fixture produced a given [EngineMatchResult].
 *
 * Hashes are SHA-256 hex digests computed by `Sha256.kt` over file bytes -- plain content hashing,
 * no cryptographic-signing infrastructure, per the correction's own "do not require cryptographic
 * infrastructure if simple SHA-256/path metadata is enough".
 */
@Serializable
data class EngineConfigIdentity(
    val engine: String,
    val engineVersion: String,
    val profileOrCosting: String,
    val benchmarkMode: BenchmarkMode,
    val graphSource: String,
    val requestFixturePath: String,
    val requestFixtureSha256: String,
    val rawResponsePath: String,
    val rawResponseSha256: String,
)

/**
 * The engine-neutral normalized output of one matcher run — protocol §B.3.
 *
 * [perObservation] is `null` when the engine's own output does not preserve a usable one-to-one
 * correspondence with the input observations (confirmed true for GraphHopper's CLI GPX output in
 * this round — see `docs/ai-context/map-matching-spike/graphhopper/commands.md` §4). Never
 * fabricated when absent.
 *
 * [engineNativeConfidence] preserves each engine's own raw signals (e.g. OSRM's `confidence`,
 * Valhalla's `distance_from_trace_point`) verbatim as strings — never coerced into one fake
 * common score (protocol §26's explicit rule).
 */
@Serializable
data class EngineMatchResult(
    val configIdentity: EngineConfigIdentity,
    val scenarioId: String,
    val variant: String,
    val mode: TransportMode,
    val matchedGeometry: List<LatLon>,
    val perObservation: List<ObservationOutcome>?,
    val splits: List<SplitRange>,
    val engineNativeConfidence: Map<String, String>,
    val unsupportedInputFields: List<String>,
    val runtimeMillis: Long,
    val errors: List<String>,
) {
    // Convenience accessors so call sites reading "which engine" don't need to reach through
    // configIdentity every time; not stored twice, just delegated.
    val engine: String get() = configIdentity.engine
    val rawResponsePath: String get() = configIdentity.rawResponsePath
}

@Serializable
data class GeometryMetrics(
    val hausdorffMeters: Double,
    val discreteFrechetMeters: Double,
    val meanAlongTrackDeviationMeters: Double,
    val routeOverlapPercent: Double,
    val densificationSpacingMeters: Double,
    val wrongRoadSegmentPercent: Double? = null,
)

@Serializable
data class H3Metrics(
    val truePositiveCells: Int,
    val falsePositiveCells: Int,
    val falseNegativeCells: Int,
    val precision: Double,
    val recall: Double,
    val resolution: Int,
    val samplingStepMeters: Double,
)

enum class AcceptanceClassification {
    ACCEPTED_CORRECT, ACCEPTED_WRONG, REJECTED_CORRECT, REJECTED_BAD,
    AMBIGUOUS_DETECTED, SPLIT, UNMATCHED_CORRECT, UNMATCHED_WRONG,
}

/** Operational wrong-road rule (correction §12): a matcher result is WRONG_ROAD when its matched
 * geometry follows a different verified corridor/path than the one actually travelled, even if
 * geographic distance is small (parallel street, adjacent carriageway, wrong bridge/stacked
 * road/interchange ramp, road instead of parallel trail). Deliberately NOT derived only from
 * Hausdorff/Fréchet distance -- classifying a result requires comparing against
 * [VerifiedSegment.corridorId], which requires a real verified corpus. This enum and
 * [BenchmarkResult.wrongRoadClassification] exist so the *type* is ready; no automatic classifier
 * is implemented this round (would need a real corpus to test against -- see protocol §14/§I). */
enum class WrongRoadClassification { CORRECT, WRONG_ROAD, AMBIGUOUS, NOT_EVALUABLE }

/** Whether an exact-geometry-dependent metric could be authoritatively computed for a given
 * [BenchmarkResult] row -- 2B-2A correction §6 ("metric safety guard"). See `TruthGuard.kt`. */
enum class MetricEvaluability { EVALUABLE, NOT_EVALUABLE }

/** One row of the final SCENARIO × ENGINE × CADENCE result table -- protocol §B.4/§26.
 *
 * **2B-2A correction**: [geometryMetrics] and [h3Metrics] are `null` whenever their respective
 * [geometryEvaluability]/[h3Evaluability] is [MetricEvaluability.NOT_EVALUABLE] -- i.e. whenever
 * the scenario's [VerifiedRouteGeometry.exactGeometryTruthStatus] is not `VALIDATED`. A `null`
 * here must never be read as "zero" or silently defaulted; it means the underlying ground truth
 * does not support this metric yet. Always constructed via `TruthGuard`/`BenchmarkEvaluation`,
 * never by directly setting these fields from unguarded code. */
@Serializable
data class BenchmarkResult(
    val scenarioId: String,
    val engine: String,
    val variant: String,
    val mode: TransportMode,
    val geometryEvaluability: MetricEvaluability,
    val geometryMetrics: GeometryMetrics?,
    val h3Evaluability: MetricEvaluability,
    val h3Metrics: H3Metrics?,
    val acceptance: AcceptanceClassification?,
    val wrongRoadClassification: WrongRoadClassification?,
    val engineMatchResult: EngineMatchResult,
)
