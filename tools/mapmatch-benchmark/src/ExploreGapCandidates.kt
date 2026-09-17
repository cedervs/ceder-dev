package worlddiscovery.benchmark

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Private candidate-window exploration tool for round 2B-5 §5/§6 -- prints ONLY aggregate
 * duration/distance/speed/heading stats for caller-supplied candidate windows (no coordinates),
 * so window selection can be made BEFORE any matcher is run. Not itself scenario-specific (takes
 * the reference trace path and candidate ranges as arguments); the actual candidate index ranges
 * explored for Scenario 3 are supplied at the call site (private script), not hardcoded here.
 *
 * Usage: `ExploreGapCandidatesKt <referenceTraceJsonPath> <label:start:end> [<label:start:end> ...]`
 */
private val json = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: ExploreGapCandidatesKt <referenceTraceJsonPath> <label:start:end> [...]" }
    val trace = json.decodeFromString(ReferenceTrace.serializer(), File(args[0]).readText())
    val points = trace.rawObservations
    println("total points: ${points.size}")
    for (arg in args.drop(1)) {
        val parts = arg.split(":")
        val label = parts[0]
        val start = parts[1].toInt()
        val end = parts[2].toInt()
        val stats = ControlledGapGenerator.computeWindowStats(points, start, end)
        println(
            "[$label] indices=[$start,$end] durationSec=${"%.1f".format(java.util.Locale.ROOT, stats.durationSeconds)} " +
                "distanceMeters=${"%.1f".format(java.util.Locale.ROOT, stats.cumulativeObservedDistanceMeters)} " +
                "meanSpeedMps=${"%.2f".format(java.util.Locale.ROOT, stats.meanSpeedMps)} " +
                "medianSpeedMps=${"%.2f".format(java.util.Locale.ROOT, stats.medianSpeedMps)} " +
                "displacementMeters=${"%.1f".format(java.util.Locale.ROOT, stats.straightLineDisplacementMeters)} " +
                "headingChangeDeg=${"%.1f".format(java.util.Locale.ROOT, stats.headingChangeDegrees)}",
        )
    }
}
