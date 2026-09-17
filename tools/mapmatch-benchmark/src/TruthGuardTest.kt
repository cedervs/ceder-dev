package worlddiscovery.benchmark

/**
 * Focused tests for `TruthGuard.kt`/`BenchmarkEvaluation.kt` -- protocol correction round 2B-2A
 * §14 (A-D). Same plain-`main()`/`check()` style as the other test files in this tool.
 */
fun main() {
    testA_rawGpsEvidenceCannotBecomeAuthoritativeTruthAutomatically()
    testB_exactGeometryMetricsNotEvaluableWhenPending()
    testC_h3MetricsNotEvaluableWhenGeometryProvisional()
    testD_corridorClassificationRemainsAvailableRegardlessOfExactGeometryStatus()
    testPositiveControl_validatedGeometryProducesRealMetrics()
    println("TruthGuardTest: ALL PASS")
}

private fun fakeSegment(confidence: TruthConfidence) = VerifiedSegment(
    segmentId = "seg-1",
    startReferenceIndex = 0,
    endReferenceIndex = 2,
    corridorId = "test-corridor",
    verificationMethod = VerificationMethod.MANUAL_ROUTE_ANNOTATION,
    confidence = confidence,
    roadOrPathDescription = "single rural road, no alternative visible",
)

private fun fakeGeometry(status: GeometryTruthStatus): VerifiedRouteGeometry {
    val line = listOf(LatLon(48.0, 2.0), LatLon(48.001, 2.0), LatLon(48.002, 2.0))
    return VerifiedRouteGeometry(
        scenarioId = "test-scenario",
        geometry = line,
        segments = listOf(fakeSegment(TruthConfidence.HIGH)),
        corridorConfidence = TruthConfidence.HIGH,
        exactGeometryTruthStatus = status,
        sourceDescription = "test fixture -- dense GPS evidence + annotated-map corridor confirmation only",
    )
}

private fun fakeEngineResult(matched: List<LatLon>) = EngineMatchResult(
    configIdentity = EngineConfigIdentity(
        engine = "fake-engine", engineVersion = "0.0", profileOrCosting = "car",
        benchmarkMode = BenchmarkMode.DEFAULT, graphSource = "test",
        requestFixturePath = "test", requestFixtureSha256 = "0" .repeat(64),
        rawResponsePath = "test", rawResponseSha256 = "0".repeat(64),
    ),
    scenarioId = "test-scenario", variant = "dense", mode = TransportMode.WALK,
    matchedGeometry = matched, perObservation = null, splits = emptyList(),
    engineNativeConfidence = emptyMap(), unsupportedInputFields = emptyList(),
    runtimeMillis = 1, errors = emptyList(),
)

/** A: a geometry corroborated ONLY by raw GPS + corridor annotation (the exact situation this
 * scenario is actually in) must NOT default to being usable as exact truth -- PENDING is the
 * correct default, and the guard must treat it as NOT_EVALUABLE, not silently EVALUABLE. */
private fun testA_rawGpsEvidenceCannotBecomeAuthoritativeTruthAutomatically() {
    val geometry = fakeGeometry(GeometryTruthStatus.PENDING)
    check(geometry.corridorConfidence == TruthConfidence.HIGH) { "corridor confidence should still be HIGH in this fixture" }
    check(TruthGuard.exactGeometryEvaluability(geometry) == MetricEvaluability.NOT_EVALUABLE) {
        "a PENDING exactGeometryTruthStatus, even with HIGH corridor confidence, must be NOT_EVALUABLE for exact metrics"
    }
    val notEvaluable = fakeGeometry(GeometryTruthStatus.NOT_EVALUABLE)
    check(TruthGuard.exactGeometryEvaluability(notEvaluable) == MetricEvaluability.NOT_EVALUABLE)
}

/** B: geometry metrics (Hausdorff/Fréchet/along-track/overlap) must come back null, with
 * geometryEvaluability = NOT_EVALUABLE, whenever exactGeometryTruthStatus != VALIDATED. */
private fun testB_exactGeometryMetricsNotEvaluableWhenPending() {
    val geometry = fakeGeometry(GeometryTruthStatus.PENDING)
    val engineResult = fakeEngineResult(listOf(LatLon(48.0001, 2.0001), LatLon(48.0011, 2.0001)))
    val result = BenchmarkEvaluation.evaluate(engineResult, geometry, H3Comparison())
    check(result.geometryEvaluability == MetricEvaluability.NOT_EVALUABLE) { "expected NOT_EVALUABLE, got ${result.geometryEvaluability}" }
    check(result.geometryMetrics == null) { "geometryMetrics must be null when NOT_EVALUABLE, got ${result.geometryMetrics}" }
}

/** C: H3 TP/FP/FN/precision/recall must come back null, with h3Evaluability = NOT_EVALUABLE,
 * whenever the underlying geometry truth is provisional. */
private fun testC_h3MetricsNotEvaluableWhenGeometryProvisional() {
    val geometry = fakeGeometry(GeometryTruthStatus.PENDING)
    val engineResult = fakeEngineResult(listOf(LatLon(48.0001, 2.0001), LatLon(48.0011, 2.0001)))
    val result = BenchmarkEvaluation.evaluate(engineResult, geometry, H3Comparison())
    check(result.h3Evaluability == MetricEvaluability.NOT_EVALUABLE) { "expected NOT_EVALUABLE, got ${result.h3Evaluability}" }
    check(result.h3Metrics == null) { "h3Metrics must be null when NOT_EVALUABLE, got ${result.h3Metrics}" }
}

/** D: corridor-level information (which road, confidence, verification method) remains fully
 * available even when exact geometry truth is PENDING/NOT_EVALUABLE -- the guard only blocks
 * metre-scale metrics, not corridor-identity reasoning (protocol §I wrong-road classification). */
private fun testD_corridorClassificationRemainsAvailableRegardlessOfExactGeometryStatus() {
    for (status in listOf(GeometryTruthStatus.PENDING, GeometryTruthStatus.NOT_EVALUABLE, GeometryTruthStatus.VALIDATED)) {
        val geometry = fakeGeometry(status)
        check(geometry.segments.isNotEmpty()) { "segments must remain available regardless of exactGeometryTruthStatus=$status" }
        check(geometry.segments.first().corridorId == "test-corridor") { "corridorId must remain readable regardless of exactGeometryTruthStatus=$status" }
        check(geometry.segments.first().confidence == TruthConfidence.HIGH) { "segment confidence must remain readable regardless of exactGeometryTruthStatus=$status" }
        check(geometry.corridorConfidence == TruthConfidence.HIGH) { "corridorConfidence must remain readable regardless of exactGeometryTruthStatus=$status" }
    }
}

/** Positive control: the guard is not a permanent block -- when exactGeometryTruthStatus IS
 * VALIDATED, real metrics must actually be computed (proves the guard discriminates correctly,
 * not just "always off"). */
private fun testPositiveControl_validatedGeometryProducesRealMetrics() {
    val geometry = fakeGeometry(GeometryTruthStatus.VALIDATED)
    val engineResult = fakeEngineResult(listOf(LatLon(48.0001, 2.0001), LatLon(48.0011, 2.0001), LatLon(48.0021, 2.0001)))
    val result = BenchmarkEvaluation.evaluate(engineResult, geometry, H3Comparison())
    check(result.geometryEvaluability == MetricEvaluability.EVALUABLE) { "expected EVALUABLE, got ${result.geometryEvaluability}" }
    check(result.geometryMetrics != null) { "geometryMetrics must be non-null when VALIDATED" }
    check(result.h3Evaluability == MetricEvaluability.EVALUABLE) { "expected EVALUABLE, got ${result.h3Evaluability}" }
    check(result.h3Metrics != null) { "h3Metrics must be non-null when VALIDATED" }
}
