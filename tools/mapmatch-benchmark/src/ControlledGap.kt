package worlddiscovery.benchmark

import kotlinx.serialization.Serializable

/**
 * Generic controlled-observation-gap infrastructure -- protocol round 2B-5. Distinct from
 * [Degradation]/[DegradationSpec] (which uniformly downsamples an ENTIRE trace to a target
 * cadence): a [ControlledGapSpec] instead removes exactly ONE contiguous window of observations
 * from an otherwise-dense trace, isolating "what happens when observations disappear for X
 * seconds in ONE place" from "what happens when the whole trip is sampled every X seconds"
 * (protocol §8). No Scenario-3-specific coordinates or indices are hardcoded anywhere in this
 * file -- it operates generically on any [ReferenceTrace].
 *
 * Terminology (protocol §2): the removed window's real observations are the REFERENCE_WINDOW --
 * retained privately as observational evidence (never treated as exact geometric truth, never sent
 * to a matcher), while the CONTROLLED_GAP is what the matcher actually receives (the trace with
 * that window hidden).
 */

/** Route-context classes a controlled-gap window may fall in -- protocol §4. Not every trace will
 * contain every class; [PARALLEL_ALTERNATIVES] in particular requires genuine evidence of nearby
 * parallel routable corridors and must never be assigned without it. */
enum class GapContext { SIMPLE_ROAD, JUNCTION_OR_FORK, URBAN_COMPLEX, TRANSITION, PARALLEL_ALTERNATIVES }

/** A frozen, reproducible description of one controlled gap -- protocol §5 ("selection must not
 * cheat"): this spec (and [selectionRationale]) must be written down BEFORE any matcher is run
 * against it, using only signals available from the dense reference trace itself (speed, heading,
 * turn/network structure), never from having already seen a matcher fail there. */
@Serializable
data class ControlledGapSpec(
    val gapId: String,
    val sourceTraceId: String,
    /** First index (inclusive, into [ReferenceTrace.rawObservations]) that is hidden from the matcher. */
    val hiddenStartIndex: Int,
    /** Last index (inclusive) that is hidden from the matcher. */
    val hiddenEndIndex: Int,
    val context: GapContext,
    val selectionRationale: String,
    val targetDurationSeconds: Double,
)

/** The result of applying a [ControlledGapSpec] to a real [ReferenceTrace] -- protocol §7's
 * required invariants are enforced in [ControlledGapGenerator.generate], not just documented here. */
@Serializable
data class ControlledGapVariant(
    val spec: ControlledGapSpec,
    /** What the matcher actually receives -- the dense trace with [ControlledGapSpec]'s window
     * removed, index-renumbered. Contains NO observation from the hidden window. */
    val matcherInputObservations: List<DegradedObservation>,
    /** The real, originally-recorded observations from inside the hidden window -- kept ONLY for
     * private post-hoc evaluation against the matcher's reconstruction, NEVER sent to a matcher. */
    val referenceWindowObservations: List<DegradedObservation>,
)

/** Aggregate, non-authoritative descriptive stats about a candidate/frozen window -- protocol §6
 * ("for every candidate compute: duration; distance; speed; displacement; heading change..."). Pure
 * observational description of the reference window, not a truth claim. */
data class GapWindowStats(
    val durationSeconds: Double,
    val cumulativeObservedDistanceMeters: Double,
    val meanSpeedMps: Double,
    val medianSpeedMps: Double,
    val straightLineDisplacementMeters: Double,
    val headingChangeDegrees: Double,
)

object ControlledGapGenerator {

    /** Computes descriptive stats for a candidate window WITHOUT generating a matcher-input variant
     * -- used during candidate selection (protocol §5/§6), before any window is frozen. */
    fun computeWindowStats(points: List<ReferencePoint>, hiddenStartIndex: Int, hiddenEndIndex: Int): GapWindowStats {
        require(hiddenStartIndex in points.indices && hiddenEndIndex in points.indices) { "indices out of range" }
        require(hiddenStartIndex <= hiddenEndIndex) { "hiddenStartIndex must be <= hiddenEndIndex" }
        val window = points.subList(hiddenStartIndex, hiddenEndIndex + 1)
        val durationSeconds = (window.last().timestampEpochMs - window.first().timestampEpochMs) / 1000.0
        var cumulativeDistance = 0.0
        val stepSpeeds = mutableListOf<Double>()
        for (i in 1 until window.size) {
            val d = GeometryComparison.haversineMeters(LatLon(window[i - 1].lat, window[i - 1].lon), LatLon(window[i].lat, window[i].lon))
            cumulativeDistance += d
            val dt = (window[i].timestampEpochMs - window[i - 1].timestampEpochMs) / 1000.0
            if (dt > 0) stepSpeeds.add(d / dt)
        }
        val sortedSpeeds = stepSpeeds.sorted()
        val medianSpeed = if (sortedSpeeds.isEmpty()) 0.0 else {
            val mid = sortedSpeeds.size / 2
            if (sortedSpeeds.size % 2 == 0 && sortedSpeeds.size > 0) (sortedSpeeds[mid - 1] + sortedSpeeds[mid]) / 2.0 else sortedSpeeds[mid]
        }
        val displacement = GeometryComparison.haversineMeters(LatLon(window.first().lat, window.first().lon), LatLon(window.last().lat, window.last().lon))
        val headingStart = window.first().bearingDegrees
        val headingEnd = window.last().bearingDegrees
        val headingChange = if (headingStart != null && headingEnd != null) {
            var d = (headingEnd - headingStart) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            kotlin.math.abs(d)
        } else {
            0.0
        }
        return GapWindowStats(
            durationSeconds = durationSeconds,
            cumulativeObservedDistanceMeters = cumulativeDistance,
            meanSpeedMps = if (stepSpeeds.isEmpty()) 0.0 else stepSpeeds.average(),
            medianSpeedMps = medianSpeed,
            straightLineDisplacementMeters = displacement,
            headingChangeDegrees = headingChange,
        )
    }

    /** Generates a [ControlledGapVariant] from a real [ReferenceTrace] and a frozen [ControlledGapSpec].
     * Enforces protocol §7's required invariants:
     * - start/end observations exist (hiddenStartIndex > 0, hiddenEndIndex < lastIndex)
     * - hiddenStartIndex <= hiddenEndIndex (hidden reference observations non-empty)
     * - matcher input contains NO hidden observation (filtered out entirely, not just marked)
     * - [referenceTrace] itself is never mutated (only read; a new independent list is built)
     * - real source timestamps are preserved verbatim in both output lists
     * - [excludedRealGapRanges] (e.g. Scenario 3's real ~389s stationary gap) must not overlap the
     *   requested hidden window -- throws if it does, rather than silently allowing it
     * - a minimum-movement check rejects a window that is not genuinely a MOVING-gap experiment
     *   (protocol §6: "stationary window rejected for moving-gap experiment") unless the caller
     *   explicitly opts out via [requireMovement] = false
     */
    fun generate(
        referenceTrace: ReferenceTrace,
        spec: ControlledGapSpec,
        excludedRealGapRanges: List<IntRange> = emptyList(),
        requireMovement: Boolean = true,
        minimumMeanSpeedMpsForMovement: Double = 1.0,
    ): ControlledGapVariant {
        val points = referenceTrace.rawObservations
        require(spec.hiddenStartIndex in points.indices) { "hiddenStartIndex ${spec.hiddenStartIndex} out of range" }
        require(spec.hiddenEndIndex in points.indices) { "hiddenEndIndex ${spec.hiddenEndIndex} out of range" }
        require(spec.hiddenStartIndex <= spec.hiddenEndIndex) { "hiddenStartIndex must be <= hiddenEndIndex -- hidden reference observations must be non-empty" }
        require(spec.hiddenStartIndex > 0) { "a real observation must exist immediately before the hidden window (hiddenStartIndex must be > 0)" }
        require(spec.hiddenEndIndex < points.size - 1) { "a real observation must exist immediately after the hidden window (hiddenEndIndex must be < last index)" }

        val hiddenRange = spec.hiddenStartIndex..spec.hiddenEndIndex
        for (excluded in excludedRealGapRanges) {
            require(hiddenRange.first > excluded.last || hiddenRange.last < excluded.first) {
                "requested hidden window $hiddenRange overlaps an excluded real gap range $excluded -- controlled gaps must not overlap the real observation gap"
            }
        }

        if (requireMovement) {
            val stats = computeWindowStats(points, spec.hiddenStartIndex, spec.hiddenEndIndex)
            require(stats.meanSpeedMps >= minimumMeanSpeedMpsForMovement) {
                "window mean speed ${stats.meanSpeedMps} m/s is below the movement threshold $minimumMeanSpeedMpsForMovement m/s -- this window is not suitable as a MOVING-gap experiment (use requireMovement=false only if a stationary-gap experiment is genuinely intended)"
            }
        }

        fun toDegradedObservation(p: ReferencePoint, index: Int) = DegradedObservation(
            index = index, lat = p.lat, lon = p.lon, timestampEpochMs = p.timestampEpochMs,
            accuracyMeters = p.accuracyMeters, bearingDegrees = p.bearingDegrees, speedMps = p.speedMps,
            intentLabel = "NORMAL",
        )

        val matcherInput = points.filterIndexed { i, _ -> i !in hiddenRange }
            .mapIndexed { newIndex, p -> toDegradedObservation(p, newIndex) }
        val referenceWindow = points.filterIndexed { i, _ -> i in hiddenRange }
            .mapIndexed { newIndex, p -> toDegradedObservation(p, newIndex) }

        return ControlledGapVariant(
            spec = spec,
            matcherInputObservations = matcherInput,
            referenceWindowObservations = referenceWindow,
        )
    }
}
