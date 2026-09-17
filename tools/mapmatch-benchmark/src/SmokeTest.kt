package worlddiscovery.benchmark

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Smoke test for the Phase 2B benchmark harness -- proves the pipeline (load -> adapt ->
 * normalize -> compare geometry -> H3 -> metrics -> summary) runs end to end against real, already
 * -captured Phase 2A/2B fixture files.
 *
 * =====================================================================================
 * THIS IS A SMOKE TEST ONLY -- NOT A BENCHMARK RESULT.
 * The "reference" geometry used here is the Isle of Man synthetic self-referential route
 * (derived from an OSRM /route query -- see docs/ai-context/map-matching-spike/README.md's
 * "SYNTHETIC SELF-REFERENTIAL REFERENCE" warning). It is NOT independent ground truth, and it is
 * NOT a `VerifiedRouteGeometry` (no verification method was applied to it -- it is literally an
 * OSRM route). These numbers say nothing about which engine reconstructs a real trajectory
 * correctly -- they only prove the harness code itself computes real numbers on real (if
 * self-referential) inputs. A real benchmark run loads an actual `ReferenceTrace.verifiedGeometry`
 * instead of this file.
 * =====================================================================================
 *
 * 2B-1B correction: now uses the density-fair `hausdorffMetersDensified`/
 * `discreteFrechetMetersDensified` (both inputs densified to the same spacing before comparing)
 * instead of comparing OSRM's ~722-point geometry against Valhalla's 36 sparse points directly --
 * see `GeometryMetrics.kt`'s class-level doc comment. This does not fix the underlying "Valhalla
 * row only has 36 matched_points, not a denser polyline" limitation (still documented below and in
 * the README) -- it only removes the *comparison-fairness* defect the raw density mismatch caused.
 */
fun main() {
    println("=====================================================================")
    println("PHASE 2B BENCHMARK HARNESS -- SMOKE TEST ONLY -- NOT A BENCHMARK RESULT")
    println("Reference geometry is self-referential (derived from OSRM's own /route).")
    println("These numbers prove the pipeline works; they measure nothing about engine quality.")
    println("=====================================================================")
    println()

    val fixtureRoot = File("docs/ai-context/map-matching-spike")
    val osrmRequestPath = File(fixtureRoot, "osrm/request.txt")
    val osrmResponsePath = File(fixtureRoot, "osrm/response.json")
    val valhallaRequestPath = File(fixtureRoot, "valhalla/request.json")
    val valhallaResponsePath = File(fixtureRoot, "valhalla/response.json")
    val referenceRoutePath = File(fixtureRoot, "shared/synthetic-self-referential-route.json")

    val referenceGeometry = loadReferenceGeometry(referenceRoutePath)
    println("Loaded self-referential 'reference' geometry: ${referenceGeometry.size} points from ${referenceRoutePath.path}")

    val osrmResult = OsrmAdapter.adapt(
        rawJson = osrmResponsePath.readText(),
        scenarioId = "phase2a-isle-of-man-smoke-test",
        variant = "27s-with-gap-and-outlier",
        mode = TransportMode.CAR,
        engineVersion = "v5.26.0",
        profile = "car",
        benchmarkMode = BenchmarkMode.DEFAULT,
        graphSource = "isle-of-man-latest.osm.pbf (2026-09-04 Geofabrik)",
        requestFixturePath = osrmRequestPath.path,
        rawResponsePath = osrmResponsePath.path,
        runtimeMillis = -1,
    )
    val valhallaResult = ValhallaAdapter.adapt(
        rawJson = valhallaResponsePath.readText(),
        scenarioId = "phase2a-isle-of-man-smoke-test",
        variant = "27s-with-gap-and-outlier",
        mode = TransportMode.CAR,
        engineVersion = "3.8.3-7f372987b",
        costing = "auto",
        benchmarkMode = BenchmarkMode.DEFAULT,
        graphSource = "isle-of-man-latest.osm.pbf (2026-09-04 Geofabrik)",
        requestFixturePath = valhallaRequestPath.path,
        rawResponsePath = valhallaResponsePath.path,
        runtimeMillis = -1,
    )

    val h3 = H3Comparison()
    val densificationSpacingMeters = 5.0
    val results = listOf(osrmResult, valhallaResult).map { engineResult ->
        computeBenchmarkResult(engineResult, referenceGeometry, h3, densificationSpacingMeters)
    }

    println()
    println(
        String.format(
            Locale.ROOT, "%-10s %-10s %14s %14s %14s %10s %8s %8s %8s",
            "engine", "points", "hausdorff(m)", "frechet(m)", "alongTrack(m)", "overlap%", "H3-TP", "H3-FP", "H3-FN",
        ),
    )
    for (r in results) {
        println(
            String.format(
                Locale.ROOT,
                "%-10s %-10d %14.1f %14.1f %14.1f %10.1f %8d %8d %8d",
                r.engine,
                r.engineMatchResult.matchedGeometry.size,
                r.geometryMetrics?.hausdorffMeters ?: Double.NaN,
                r.geometryMetrics?.discreteFrechetMeters ?: Double.NaN,
                r.geometryMetrics?.meanAlongTrackDeviationMeters ?: Double.NaN,
                r.geometryMetrics?.routeOverlapPercent ?: Double.NaN,
                r.h3Metrics?.truePositiveCells ?: -1,
                r.h3Metrics?.falsePositiveCells ?: -1,
                r.h3Metrics?.falseNegativeCells ?: -1,
            ),
        )
    }
    println()
    println("geometry densification spacing used for hausdorff/frechet/overlap: ${densificationSpacingMeters}m (both inputs, same spacing)")
    println("H3 sampling step used: ${H3Comparison.DEFAULT_STEP_METERS}m at resolution ${H3Comparison.DEFAULT_RESOLUTION}")
    println()
    println("perObservation entries -- osrm: ${osrmResult.perObservation?.size}, valhalla: ${valhallaResult.perObservation?.size}")
    println("osrm null (unmatched) count: ${osrmResult.perObservation?.count { it.outcome == MatchOutcome.UNMATCHED }}")
    println("valhalla unmatched count: ${valhallaResult.perObservation?.count { it.outcome == MatchOutcome.UNMATCHED }}")
    println()
    println("configIdentity.requestFixtureSha256 (osrm): ${osrmResult.configIdentity.requestFixtureSha256}")
    println("configIdentity.rawResponseSha256 (osrm): ${osrmResult.configIdentity.rawResponseSha256}")
    println("configIdentity.requestFixtureSha256 (valhalla): ${valhallaResult.configIdentity.requestFixtureSha256}")
    println("configIdentity.rawResponseSha256 (valhalla): ${valhallaResult.configIdentity.rawResponseSha256}")
    println()
    println("SMOKE TEST COMPLETE -- pipeline executed load -> adapt -> normalize -> geometry -> H3 -> summary successfully.")
    println("Reminder: none of the above is a benchmark result (see warning at top).")
}

private fun computeBenchmarkResult(
    engineResult: EngineMatchResult,
    reference: List<LatLon>,
    h3: H3Comparison,
    densificationSpacingMeters: Double,
): BenchmarkResult {
    val matched = engineResult.matchedGeometry
    val geometryMetrics = if (matched.isEmpty()) {
        GeometryMetrics(Double.NaN, Double.NaN, Double.NaN, 0.0, densificationSpacingMeters)
    } else {
        GeometryMetrics(
            hausdorffMeters = GeometryComparison.hausdorffMetersDensified(reference, matched, densificationSpacingMeters),
            discreteFrechetMeters = GeometryComparison.discreteFrechetMetersDensified(reference, matched, densificationSpacingMeters),
            meanAlongTrackDeviationMeters = GeometryComparison.meanAlongTrackDeviationMeters(matched, reference),
            routeOverlapPercent = GeometryComparison.routeOverlapFraction(reference, matched, thresholdMeters = 25.0, densificationSpacingMeters = densificationSpacingMeters) * 100.0,
            densificationSpacingMeters = densificationSpacingMeters,
        )
    }
    val h3Metrics = if (matched.isEmpty()) null else {
        val truthCells = h3.sampleToH3Cells(reference, H3Comparison.DEFAULT_RESOLUTION)
        val matchedCells = h3.sampleToH3Cells(matched, H3Comparison.DEFAULT_RESOLUTION)
        h3.compare(truthCells, matchedCells, H3Comparison.DEFAULT_RESOLUTION)
    }
    // 2B-2A correction: this smoke test deliberately does NOT go through TruthGuard/
    // BenchmarkEvaluation.evaluate -- its "reference" is a plain self-referential List<LatLon>
    // (an OSRM /route output, see the file-level warning), never a VerifiedRouteGeometry with a
    // real GeometryTruthStatus. Routing it through the real-scenario guard would either require
    // fabricating a fake VerifiedRouteGeometry (dishonest) or silently forcing these numbers to
    // null (defeating this file's actual purpose: proving the metric-computation code paths
    // themselves execute correctly on real, if self-referential, data). The evaluability fields
    // below reflect only "was a computation attempted", NOT any claim about ground-truth validity
    // -- that claim was never made for this fixture and remains explicitly disclaimed throughout.
    val evaluability = if (matched.isEmpty()) MetricEvaluability.NOT_EVALUABLE else MetricEvaluability.EVALUABLE
    return BenchmarkResult(
        scenarioId = engineResult.scenarioId,
        engine = engineResult.engine,
        variant = engineResult.variant,
        mode = engineResult.mode,
        geometryEvaluability = evaluability,
        geometryMetrics = geometryMetrics,
        h3Evaluability = evaluability,
        h3Metrics = h3Metrics,
        acceptance = null, // requires real ground truth + manual verification, not available in a smoke test
        wrongRoadClassification = null, // requires VerifiedSegment.corridorId data, not available in a smoke test
        engineMatchResult = engineResult,
    )
}

/** Parses the OSRM /route geojson response used as the Phase 2A self-referential source geometry
 * -- see the file-level warning above for why this is never treated as ground truth. */
private fun loadReferenceGeometry(path: File): List<LatLon> {
    val root = Json.parseToJsonElement(path.readText()).jsonObject
    val route = root["routes"]?.jsonArray?.get(0)?.jsonObject ?: error("no routes[0] in $path")
    val coords = route["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: error("no geometry.coordinates in $path")
    return coords.map {
        val p = it.jsonArray
        LatLon(lat = p[1].jsonPrimitive.content.toDouble(), lon = p[0].jsonPrimitive.content.toDouble())
    }
}
