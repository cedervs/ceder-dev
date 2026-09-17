package worlddiscovery.benchmark

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generic, non-out-and-back-assuming turn detection for building [VerifiedSegment]s from a dense
 * [ReferencePoint] trace -- protocol round 2B-3 (`walk-urban-parallel-corridors-002`). Scenario 1's
 * `IngestAndBuildScenario.kt` assumes a simple there-and-back-the-same-way shape (turnaround =
 * point of maximum distance from start) -- not applicable to a one-way urban walk with real turns.
 * This module detects turns algorithmically from the GPS evidence itself (bearing between
 * distance-spaced samples, not adjacent 1s samples which are too noisy over ~1-2m spacing), never
 * from the screenshot -- the screenshot is corroborating evidence for corridor identity (§4 of the
 * round), not a source of exact turn indices.
 */
object TurnSegmentation {

    /**
     * Resamples [points] to a minimum spacing of [minSpacingMeters] (keeping original points,
     * simply skipping ones too close to the last kept one -- no interpolation, this is meant to
     * suppress bearing noise between near-duplicate consecutive fixes, not to synthesize data),
     * computes the bearing of each resulting leg, and returns the [points] index (in the ORIGINAL,
     * un-resampled list) closest to each detected sustained turn.
     *
     * A turn is "sustained" when [minSustainedLegs] consecutive resampled legs all differ from the
     * leg before the turn by more than [turnThresholdDegrees] in the same rough direction -- a
     * single noisy leg is not enough to declare a turn.
     */
    fun detectTurnIndices(
        points: List<ReferencePoint>,
        minSpacingMeters: Double = 8.0,
        turnThresholdDegrees: Double = 40.0,
        minSustainedLegs: Int = 2,
    ): List<Int> {
        if (points.size < 3) return emptyList()

        // 1. Resample to reduce noise, keeping original-list indices alongside.
        val resampledIndices = mutableListOf(0)
        for (i in 1 until points.size) {
            val last = points[resampledIndices.last()]
            val d = haversine(last.lat, last.lon, points[i].lat, points[i].lon)
            if (d >= minSpacingMeters) resampledIndices.add(i)
        }
        if (resampledIndices.size < 3) return emptyList()

        // 2. Bearing of each leg between consecutive resampled points.
        val legBearings = (0 until resampledIndices.size - 1).map { i ->
            bearingDegrees(
                points[resampledIndices[i]].lat, points[resampledIndices[i]].lon,
                points[resampledIndices[i + 1]].lat, points[resampledIndices[i + 1]].lon,
            )
        }

        // 3. Find sustained bearing changes.
        val turnResampledPositions = mutableListOf<Int>()
        var i = 1
        while (i < legBearings.size) {
            val delta = angularDifference(legBearings[i - 1], legBearings[i])
            if (abs(delta) >= turnThresholdDegrees) {
                // Check the next (minSustainedLegs - 1) legs stay turned (don't immediately revert).
                val sustained = (0 until minSustainedLegs).all { k ->
                    i + k < legBearings.size && abs(angularDifference(legBearings[i - 1], legBearings[i + k])) >= turnThresholdDegrees * 0.6
                }
                if (sustained) {
                    // The turn happens AT the resampled vertex between leg i-1 and leg i, i.e. at
                    // resampledIndices[i].
                    turnResampledPositions.add(i)
                    i += minSustainedLegs // skip past this turn's confirming legs to avoid re-detecting it
                    continue
                }
            }
            i++
        }

        return turnResampledPositions.map { resampledIndices[it] }
    }

    /** Builds one [VerifiedSegment] per leg between consecutive turn indices (and the start/end),
     * with a generic, non-identifying `corridorId` (`"$corridorIdPrefix-N"`) per leg -- never a
     * real street name. Segments shorter than [minSegmentPoints] observations are merged into the
     * following segment (too short to be a meaningfully distinct corridor claim on their own). */
    fun buildSegments(
        totalPoints: Int,
        turnIndices: List<Int>,
        corridorIdPrefix: String,
        confidence: TruthConfidence,
        verificationMethod: VerificationMethod,
        minSegmentPoints: Int = 5,
    ): List<VerifiedSegment> {
        val boundaries = (listOf(0) + turnIndices.filter { it in 1 until totalPoints - 1 } + listOf(totalPoints - 1)).distinct().sorted()
        val rawRanges = (0 until boundaries.size - 1).map { boundaries[it] to boundaries[it + 1] }

        // Merge too-short ranges: a short leading/middle range absorbs forward into the next range
        // (extends that range's start backward); a short TRAILING range instead merges backward
        // into whatever was most recently finalized, since there is no "next" range to extend into.
        val merged = mutableListOf<Pair<Int, Int>>()
        var pendingStart = rawRanges.first().first
        for ((idx, range) in rawRanges.withIndex()) {
            val (_, end) = range
            val effectiveStart = pendingStart
            val length = end - effectiveStart
            val isLast = idx == rawRanges.lastIndex
            when {
                length < minSegmentPoints && !isLast -> {
                    pendingStart = effectiveStart // keep accumulating into the next range
                }
                length < minSegmentPoints && isLast && merged.isNotEmpty() -> {
                    val (prevStart, _) = merged.removeAt(merged.lastIndex)
                    merged.add(prevStart to end) // extend the previous finalized range to cover this short tail
                }
                else -> {
                    merged.add(effectiveStart to end)
                    pendingStart = end
                }
            }
        }

        return merged.mapIndexed { idx, (start, end) ->
            VerifiedSegment(
                segmentId = "$corridorIdPrefix-segment-${idx + 1}",
                startReferenceIndex = start,
                endReferenceIndex = end,
                corridorId = "$corridorIdPrefix-corridor-${idx + 1}",
                verificationMethod = verificationMethod,
                confidence = confidence,
                ambiguityNote = null,
                roadOrPathDescription = "urban leg ${idx + 1} of ${merged.size}, bounded by an algorithmically detected turn (or the trace start/end)",
                verifiedGeometryRef = null,
            )
        }
    }

    /** Detects indices where a real observation-timestamp gap of more than [thresholdSeconds]
     * occurs between two consecutive raw observations -- protocol round 2B-4 §5/§6: a large real
     * gap (e.g. a logger/app pause) must produce an explicit segment boundary, never be silently
     * absorbed into a normal turn-based leg (which only looks at spatial bearing, never time). The
     * returned index is that of the observation immediately AFTER the gap, using the same
     * boundary-index convention as [detectTurnIndices]. Generic -- not specific to any one
     * scenario's actual gap. */
    fun detectLargeTimeGapIndices(points: List<ReferencePoint>, thresholdSeconds: Double = 60.0): List<Int> {
        if (points.size < 2) return emptyList()
        val result = mutableListOf<Int>()
        for (i in 1 until points.size) {
            val dtSeconds = (points[i].timestampEpochMs - points[i - 1].timestampEpochMs) / 1000.0
            if (dtSeconds > thresholdSeconds) result.add(i)
        }
        return result
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        GeometryComparison.haversineMeters(LatLon(lat1, lon1), LatLon(lat2, lon2))

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360.0) % 360.0
    }

    /** Smallest signed angular difference `b - a` in degrees, in `(-180, 180]`. */
    private fun angularDifference(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
