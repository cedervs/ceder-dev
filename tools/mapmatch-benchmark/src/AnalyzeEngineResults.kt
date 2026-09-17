package worlddiscovery.benchmark

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Consolidated per-variant, per-engine analysis for round 2B-2B ("resume local engine benchmark").
 * Loads the already-built [ReferenceTrace] (truth model, `exactGeometryTruthStatus = PENDING` for
 * this scenario), adapts each engine's raw response for each cadence variant, and:
 *
 * 1. Runs [BenchmarkEvaluation.evaluate] -- proves the [TruthGuard] correctly returns
 *    `NOT_EVALUABLE`/`null` for Hausdorff/Fréchet/overlap/H3 even against REAL engine output (not
 *    just the synthetic fixtures in `TruthGuardTest.kt`).
 * 2. Computes a separately-labeled CORRIDOR SANITY check (max/mean perpendicular distance from
 *    matched geometry to the raw-GPS-evidence polyline) -- explicitly NOT a truth-metric score
 *    (protocol §7: "You MAY evaluate: correct known corridor vs wrong road" -- this is the coarse
 *    geometric signal behind that judgment, not a substitute for [GeometryMetrics]).
 * 3. Reports matched-point count, unmatched-observation count (where the engine provides
 *    per-observation correspondence), splits, native confidence, and any errors.
 *
 * No coordinate is printed to stdout -- only aggregate distances/counts -- so this can be safely
 * captured to a log file under `benchmark-private/` without a secondary coordinate leak.
 */
private val json = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    require(args.size >= 1) { "usage: AnalyzeEngineResultsKt <scenarioRootDir> [transportMode=WALK|CAR|BIKE]" }
    val root = File(args[0])
    // 2B-4: mode is now an explicit argument (default WALK, preserving Scenarios 1-2's behavior
    // unchanged) instead of a hardcoded TransportMode.WALK baked into every adapter call below --
    // generic across scenarios, see EngineProfiles.kt.
    val mode = if (args.size >= 2) TransportMode.valueOf(args[1]) else TransportMode.WALK
    val osrmProfile = EngineProfiles.osrmProfile(mode)
    val valhallaCosting = EngineProfiles.valhallaCosting(mode)
    val graphHopperProfile = EngineProfiles.graphHopperProfile(mode)
    val referenceTrace = json.decodeFromString(ReferenceTrace.serializer(), File(root, "truth/reference-trace.json").readText())
    val verifiedGeometry = requireNotNull(referenceTrace.verifiedGeometry) { "scenario has no verifiedGeometry" }
    println("scenarioId: ${referenceTrace.id}")
    println("transportMode: $mode (osrmProfile=$osrmProfile, valhallaCosting=$valhallaCosting, graphHopperProfile=$graphHopperProfile)")
    println("exactGeometryTruthStatus: ${verifiedGeometry.exactGeometryTruthStatus} (guard should force NOT_EVALUABLE below)")
    println()

    val h3 = H3Comparison()
    val variants = listOf("dense", "7s", "15s", "27s", "45s")

    for (variant in variants) {
        println("=============================== variant=$variant ===============================")

        // --- OSRM ---
        val osrmResponsePath = File(root, "results/osrm/response-$variant.json")
        if (osrmResponsePath.exists()) {
            val osrmResult = OsrmAdapter.adapt(
                rawJson = osrmResponsePath.readText(),
                scenarioId = referenceTrace.id, variant = variant, mode = mode,
                engineVersion = "v5.26.0", profile = osrmProfile, benchmarkMode = BenchmarkMode.DEFAULT,
                graphSource = "limousin-latest.osm.pbf (Geofabrik)",
                requestFixturePath = File(root, "engine-requests/osrm-request-$variant.txt").path,
                rawResponsePath = osrmResponsePath.path, runtimeMillis = -1,
            )
            report("OSRM", osrmResult, verifiedGeometry, h3)
        } else {
            println("[OSRM] no response file for variant=$variant")
        }

        // --- Valhalla ---
        val valhallaResponsePath = File(root, "results/valhalla/response-$variant.json")
        if (valhallaResponsePath.exists()) {
            val valhallaResult = ValhallaAdapter.adapt(
                rawJson = valhallaResponsePath.readText(),
                scenarioId = referenceTrace.id, variant = variant, mode = mode,
                engineVersion = "3.8.3-7f372987b", costing = valhallaCosting, benchmarkMode = BenchmarkMode.DEFAULT,
                graphSource = "limousin-latest.osm.pbf (Geofabrik)",
                requestFixturePath = File(root, "engine-requests/valhalla-request-$variant.json").path,
                rawResponsePath = valhallaResponsePath.path, runtimeMillis = -1,
            )
            report("Valhalla", valhallaResult, verifiedGeometry, h3)
        } else {
            println("[Valhalla] no response file for variant=$variant")
        }

        // --- GraphHopper ---
        val ghResponsePath = File(root, "results/graphhopper/response-$variant.gpx")
        if (ghResponsePath.exists()) {
            val ghResult = GraphHopperAdapter.adapt(
                resultGpxText = ghResponsePath.readText(),
                scenarioId = referenceTrace.id, variant = variant, mode = mode,
                engineVersion = "11.0", profile = graphHopperProfile, benchmarkMode = BenchmarkMode.DEFAULT,
                graphSource = "limousin-latest.osm.pbf (Geofabrik)",
                requestFixturePath = File(root, "engine-requests/graphhopper-request-$variant.gpx").path,
                rawResponsePath = ghResponsePath.path, runtimeMillis = -1,
            )
            report("GraphHopper", ghResult, verifiedGeometry, h3)
            val cliLog = File(root, "results/graphhopper/cli-output-$variant.log")
            if (cliLog.exists()) {
                val matchesLine = cliLog.readLines().firstOrNull { it.trim().startsWith("matches:") }
                println("  [GraphHopper] CLI summary: ${matchesLine?.trim()}")
            }
        } else {
            println("[GraphHopper] no response file for variant=$variant")
        }
        println()
    }
}

private fun report(engineLabel: String, result: EngineMatchResult, verifiedGeometry: VerifiedRouteGeometry, h3: H3Comparison) {
    println("--- $engineLabel ---")
    println("  matchedGeometry points: ${result.matchedGeometry.size}")
    if (result.perObservation != null) {
        val unmatched = result.perObservation.count { it.outcome == MatchOutcome.UNMATCHED }
        println("  perObservation entries: ${result.perObservation.size}, unmatched: $unmatched")
    } else {
        println("  perObservation: null (engine does not preserve per-observation correspondence)")
    }
    println("  splits: ${result.splits.size}")
    if (result.engineNativeConfidence.isNotEmpty()) {
        val sampleKey = result.engineNativeConfidence.keys.first()
        println("  engineNativeConfidence sample: $sampleKey = ${result.engineNativeConfidence[sampleKey]} (${result.engineNativeConfidence.size} entries total)")
    }
    if (result.unsupportedInputFields.isNotEmpty()) {
        println("  unsupportedInputFields: ${result.unsupportedInputFields}")
    }
    if (result.errors.isNotEmpty()) {
        println("  errors: ${result.errors}")
    }

    // Guarded benchmark evaluation -- proves TruthGuard fires on real engine output too.
    val evaluation = BenchmarkEvaluation.evaluate(result, verifiedGeometry, h3)
    println("  geometryEvaluability: ${evaluation.geometryEvaluability} (geometryMetrics=${evaluation.geometryMetrics})")
    println("  h3Evaluability: ${evaluation.h3Evaluability} (h3Metrics=${evaluation.h3Metrics})")

    // Corridor sanity check -- explicitly NOT a truth-metric score, just a coarse geometric signal
    // for CORRECT/WRONG_ROAD/AMBIGUOUS/NOT_EVALUABLE judgment (protocol §7).
    if (result.matchedGeometry.isNotEmpty()) {
        val distances = result.matchedGeometry.map { GeometryComparison.distanceToPolylineMeters(it, verifiedGeometry.geometry) }
        val meanDist = distances.average()
        val maxDist = distances.max()
        println("  [corridor sanity, NOT a truth score] distance-to-raw-evidence-geometry (whole route): mean=${"%.1f".format(java.util.Locale.ROOT, meanDist)}m max=${"%.1f".format(java.util.Locale.ROOT, maxDist)}m")

        // Per-segment breakdown (round 2B-3 §13 "parallel-road false snap analysis"): an aggregate
        // mean/max over the whole route can mask a localized wrong-road excursion in ONE segment.
        // For each VerifiedSegment, check how far the ENGINE's matched geometry strays from THAT
        // segment's own raw-evidence sub-polyline -- a segment-specific spike flags exactly where a
        // false snap may have happened, even if the whole-route aggregate looks fine.
        if (verifiedGeometry.segments.size > 1) {
            for (seg in verifiedGeometry.segments) {
                val segGeometry = verifiedGeometry.geometry.subList(seg.startReferenceIndex, (seg.endReferenceIndex + 1).coerceAtMost(verifiedGeometry.geometry.size))
                if (segGeometry.size < 2) continue
                val segDistances = segGeometry.map { GeometryComparison.distanceToPolylineMeters(it, result.matchedGeometry) }
                println("    [segment sanity] ${seg.segmentId} (n=${segGeometry.size}): matched-route distance mean=${"%.1f".format(java.util.Locale.ROOT, segDistances.average())}m max=${"%.1f".format(java.util.Locale.ROOT, segDistances.max())}m")
            }
        }
    }
}
