package worlddiscovery.benchmark

import java.io.File

/**
 * Focused tests for `ValhallaAdapter.kt` -- round 2B-2C §1/§2, a Codex-flagged real correctness bug:
 * `matchedGeometry` previously included UNMATCHED/UNKNOWN `matched_points[]` entries whenever they
 * carried non-null lat/lon (which Valhalla's raw response can do even for a point it failed to
 * match), silently treating a failed match as if it were a real one. Uses small FABRICATED
 * synthetic `trace_attributes`-shaped JSON (fake coordinates), never reads `benchmark-private/`.
 * Same plain-`main()`/`check()` style as the other test files in this tool.
 */
fun main() {
    testA_matchedPointEntersMatchedGeometry()
    testB_unmatchedPointWithCoordinatesExcluded()
    testC_unknownPointWithCoordinatesExcluded()
    testD_unmatchedUnknownStillRepresentedInPerObservation()
    testE_mixedInputPreservesOrderOfMatchedGeometry()
    println("ValhallaAdapterTest: ALL PASS")
}

private fun tempRequestFile(): File {
    val f = File.createTempFile("valhalla-adapter-test-request", ".json")
    f.deleteOnExit()
    f.writeText("{}")
    return f
}

private fun tempResponseFile(json: String): File {
    val f = File.createTempFile("valhalla-adapter-test-response", ".json")
    f.deleteOnExit()
    f.writeText(json)
    return f
}

private fun adapt(matchedPointsJson: String): EngineMatchResult {
    val rawJson = """{"units":"kilometers","edges":[],"matched_points":[$matchedPointsJson]}"""
    val responseFile = tempResponseFile(rawJson)
    return ValhallaAdapter.adapt(
        rawJson = rawJson,
        scenarioId = "test", variant = "test-variant", mode = TransportMode.WALK,
        engineVersion = "test", costing = "pedestrian", benchmarkMode = BenchmarkMode.DEFAULT,
        graphSource = "test", requestFixturePath = tempRequestFile().path, rawResponsePath = responseFile.path,
        runtimeMillis = 1,
    )
}

/** A: a MATCHED point with coordinates enters matchedGeometry. */
private fun testA_matchedPointEntersMatchedGeometry() {
    val result = adapt("""{"type":"matched","lat":48.1,"lon":2.2,"distance_from_trace_point":1.5,"edge_index":0}""")
    check(result.matchedGeometry.size == 1) { "expected 1 matched point, got ${result.matchedGeometry.size}" }
    check(result.matchedGeometry[0] == LatLon(48.1, 2.2)) { "matched point coordinates mismatch: ${result.matchedGeometry[0]}" }
}

/** B: an UNMATCHED point WITH coordinates must NOT enter matchedGeometry -- the exact bug fixed. */
private fun testB_unmatchedPointWithCoordinatesExcluded() {
    val result = adapt("""{"type":"unmatched","lat":48.9,"lon":2.9,"distance_from_trace_point":500.0}""")
    check(result.matchedGeometry.isEmpty()) { "an UNMATCHED point with coordinates must not appear in matchedGeometry, got ${result.matchedGeometry}" }
}

/** C: an UNKNOWN-type point (unrecognized `type` string) WITH coordinates must NOT enter matchedGeometry either. */
private fun testC_unknownPointWithCoordinatesExcluded() {
    val result = adapt("""{"type":"something_new_and_unrecognized","lat":48.9,"lon":2.9}""")
    val perObs = requireNotNull(result.perObservation)
    check(perObs[0].outcome == MatchOutcome.UNKNOWN) { "expected UNKNOWN outcome for an unrecognized type string, got ${perObs[0].outcome}" }
    check(result.matchedGeometry.isEmpty()) { "an UNKNOWN point with coordinates must not appear in matchedGeometry, got ${result.matchedGeometry}" }
}

/** D: UNMATCHED/UNKNOWN entries remain fully represented in perObservation (never dropped or
 * fabricated as matched) even though they're excluded from matchedGeometry. */
private fun testD_unmatchedUnknownStillRepresentedInPerObservation() {
    val result = adapt(
        """{"type":"matched","lat":48.1,"lon":2.2,"distance_from_trace_point":1.0},""" +
            """{"type":"unmatched","lat":48.2,"lon":2.3,"distance_from_trace_point":300.0}""",
    )
    val perObs = requireNotNull(result.perObservation)
    check(perObs.size == 2) { "both entries must remain in perObservation, got ${perObs.size}" }
    check(perObs[1].outcome == MatchOutcome.UNMATCHED) { "second entry must be tagged UNMATCHED, got ${perObs[1].outcome}" }
    check(perObs[1].matchedPoint == LatLon(48.2, 2.3)) { "UNMATCHED entry's own coordinate must still be preserved in perObservation (not nulled out), got ${perObs[1].matchedPoint}" }
    check(perObs[1].distanceToObservationMeters == 300.0) { "UNMATCHED entry's distance_from_trace_point must still be preserved" }
    check(result.matchedGeometry.size == 1) { "only the matched entry should appear in matchedGeometry, got ${result.matchedGeometry.size}" }
}

/** E: a mixed matched/unmatched/matched input preserves the relative ORDER of matched points in
 * matchedGeometry (filtering must not reorder). */
private fun testE_mixedInputPreservesOrderOfMatchedGeometry() {
    val result = adapt(
        """{"type":"matched","lat":48.10,"lon":2.20,"distance_from_trace_point":1.0},""" +
            """{"type":"unmatched","lat":48.15,"lon":2.25,"distance_from_trace_point":400.0},""" +
            """{"type":"interpolated","lat":48.20,"lon":2.30,"distance_from_trace_point":2.0},""" +
            """{"type":"unmatched","lat":48.25,"lon":2.35,"distance_from_trace_point":410.0},""" +
            """{"type":"matched","lat":48.30,"lon":2.40,"distance_from_trace_point":0.5}""",
    )
    val expected = listOf(LatLon(48.10, 2.20), LatLon(48.20, 2.30), LatLon(48.30, 2.40))
    check(result.matchedGeometry == expected) { "expected matched+interpolated points in original order $expected, got ${result.matchedGeometry}" }
    val perObs = requireNotNull(result.perObservation)
    check(perObs.size == 5) { "all 5 input entries must remain in perObservation, got ${perObs.size}" }
}
