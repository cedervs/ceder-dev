package worlddiscovery.benchmark

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Round 2B-5 analysis: for each frozen [ControlledGapSpec], loads the REAL (never-sent-to-a-
 * matcher) reference-window observations and each engine's raw response, adapts the response, and
 * reports how closely the engine's reconstruction passes near where the vehicle actually was during
 * the hidden interval -- the primary signal behind SUPPORTED_CORRIDOR/AMBIGUOUS/WRONG_ROAD/
 * FORCED_UNSUPPORTED/SPLIT_OR_REJECTED/NOT_EVALUABLE classification (done by a human reading this
 * output, not automated here -- protocol §11/§12 explicitly reject deriving WRONG_ROAD from
 * distance alone).
 *
 * Distance-to-reference-window is explicitly a NON-AUTHORITATIVE diagnostic (protocol §12/§15 of
 * this round and the standing TruthGuard contract) -- dense GPS, including this reference window,
 * is observational evidence, not exact geometric truth.
 *
 * Usage: `AnalyzeControlledGapResultsKt <scenarioRootDir> <gapId> [gapId...]`
 */
private val json = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: AnalyzeControlledGapResultsKt <scenarioRootDir> <gapId> [gapId...]" }
    val root = File(args[0])
    val h3 = H3Comparison() // instantiated only because BenchmarkEvaluation requires it; H3 stays guarded/unused for authoritative output
    val mode = TransportMode.CAR

    for (gapId in args.drop(1)) {
        val spec = json.decodeFromString(ControlledGapSpec.serializer(), File(root, "generated/gap-$gapId-controlled-gap-spec.json").readText())
        val referenceWindow = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(DegradedObservation.serializer()),
            File(root, "generated/gap-$gapId-reference-window.json").readText(),
        )
        val referenceWindowGeometry = referenceWindow.map { LatLon(it.lat, it.lon) }
        val referenceWindowDistance = (1 until referenceWindowGeometry.size).sumOf {
            GeometryComparison.haversineMeters(referenceWindowGeometry[it - 1], referenceWindowGeometry[it])
        }

        println("=============================== gap=$gapId context=${spec.context} targetDurationSec=${spec.targetDurationSeconds} ===============================")
        println("  referenceWindow: ${referenceWindow.size} hidden real observations, own traveled distance=${"%.1f".format(java.util.Locale.ROOT, referenceWindowDistance)}m")

        // --- OSRM ---
        val osrmPath = File(root, "results/osrm/response-$gapId.json")
        if (osrmPath.exists()) {
            val result = OsrmAdapter.adapt(
                rawJson = osrmPath.readText(), scenarioId = "controlled-gap-study-005", variant = gapId, mode = mode,
                engineVersion = "v5.26.0", profile = "car", benchmarkMode = BenchmarkMode.DEFAULT,
                graphSource = "limousin-latest.osm.pbf (Geofabrik)",
                requestFixturePath = File(root, "engine-requests/osrm-request-$gapId.txt").path,
                rawResponsePath = osrmPath.path, runtimeMillis = -1,
            )
            reportGap("OSRM", result, referenceWindowGeometry, referenceWindowDistance)
        }

        // --- Valhalla ---
        val valhallaPath = File(root, "results/valhalla/response-$gapId.json")
        if (valhallaPath.exists()) {
            val result = ValhallaAdapter.adapt(
                rawJson = valhallaPath.readText(), scenarioId = "controlled-gap-study-005", variant = gapId, mode = mode,
                engineVersion = "3.8.3-7f372987b", costing = "auto", benchmarkMode = BenchmarkMode.DEFAULT,
                graphSource = "limousin-latest.osm.pbf (Geofabrik)",
                requestFixturePath = File(root, "engine-requests/valhalla-request-$gapId.json").path,
                rawResponsePath = valhallaPath.path, runtimeMillis = -1,
            )
            reportGap("Valhalla", result, referenceWindowGeometry, referenceWindowDistance)
        }

        // --- GraphHopper ---
        val ghPath = File(root, "results/graphhopper/response-$gapId.gpx")
        if (ghPath.exists()) {
            val result = GraphHopperAdapter.adapt(
                resultGpxText = ghPath.readText(), scenarioId = "controlled-gap-study-005", variant = gapId, mode = mode,
                engineVersion = "11.0", profile = "car", benchmarkMode = BenchmarkMode.DEFAULT,
                graphSource = "limousin-latest.osm.pbf (Geofabrik)",
                requestFixturePath = File(root, "engine-requests/graphhopper-request-$gapId.gpx").path,
                rawResponsePath = ghPath.path, runtimeMillis = -1,
            )
            reportGap("GraphHopper", result, referenceWindowGeometry, referenceWindowDistance)
        }
        println()
    }
}

private fun reportGap(engineLabel: String, result: EngineMatchResult, referenceWindowGeometry: List<LatLon>, referenceWindowDistance: Double) {
    println("  --- $engineLabel ---")
    println("    matchedGeometry points: ${result.matchedGeometry.size}")
    if (result.perObservation != null) {
        val unmatched = result.perObservation.count { it.outcome == MatchOutcome.UNMATCHED }
        println("    perObservation entries: ${result.perObservation.size}, unmatched: $unmatched")
    }
    println("    splits: ${result.splits.size}")
    if (result.engineNativeConfidence.isNotEmpty()) {
        val sampleKey = result.engineNativeConfidence.keys.first()
        println("    engineNativeConfidence sample: $sampleKey = ${result.engineNativeConfidence[sampleKey]} (${result.engineNativeConfidence.size} entries)")
    }
    if (result.errors.isNotEmpty()) println("    errors: ${result.errors}")

    if (result.matchedGeometry.isEmpty()) {
        println("    [NOT_EVALUABLE -- empty matchedGeometry]")
        return
    }
    val distances = referenceWindowGeometry.map { GeometryComparison.distanceToPolylineMeters(it, result.matchedGeometry) }
    val meanDist = distances.average()
    val maxDist = distances.max()
    println("    [NON-AUTHORITATIVE diagnostic] distance from each REAL hidden observation to the matcher's reconstruction: mean=${"%.1f".format(java.util.Locale.ROOT, meanDist)}m max=${"%.1f".format(java.util.Locale.ROOT, maxDist)}m")

    // Coverage: what fraction of the real hidden observations have the matcher's reconstruction
    // passing within a generous tolerance (25m -- GPS accuracy scale) of them at all?
    val within25m = distances.count { it <= 25.0 }
    println("    [NON-AUTHORITATIVE diagnostic] hidden observations within 25m of reconstruction: $within25m/${referenceWindowGeometry.size}")
}
