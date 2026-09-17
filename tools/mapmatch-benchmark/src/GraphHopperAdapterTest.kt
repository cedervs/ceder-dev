package worlddiscovery.benchmark

import java.io.File

/**
 * Focused test for `GraphHopperAdapter.kt` -- round 2B-2B §10/§14 ("if benchmark-only code changes:
 * add focused tests"). Uses a small FABRICATED synthetic `.res.gpx` snippet (fake coordinates),
 * never reads `benchmark-private/`. Same plain-`main()`/`check()` style as the other tests.
 */
fun main() {
    testParsesTrkptsAndMarksUnsupportedFields()
    testEmptyGpxYieldsEmptyGeometry()
    println("GraphHopperAdapterTest: ALL PASS")
}

private fun tempFixtureFile(content: String, suffix: String): File {
    val f = File.createTempFile("gh-adapter-test", suffix)
    f.deleteOnExit()
    f.writeText(content)
    return f
}

private fun testParsesTrkptsAndMarksUnsupportedFields() {
    val gpx = """
        <?xml version="1.0" encoding="UTF-8" standalone="no" ?><gpx version="1.1">
        <trk><name></name><trkseg>
        <trkpt lat="48.100000" lon="2.200000"><time>2026-01-01T10:00:00Z</time></trkpt>
        <trkpt lat="48.100500" lon="2.200200"></trkpt>
        <trkpt lat="48.101000" lon="2.200400"><time>2026-01-01T10:00:05Z</time></trkpt>
        </trkseg></trk></gpx>
    """.trimIndent()
    val responseFile = tempFixtureFile(gpx, ".res.gpx")
    val requestFile = tempFixtureFile("<gpx></gpx>", ".gpx")
    val result = GraphHopperAdapter.adapt(
        resultGpxText = gpx,
        scenarioId = "test", variant = "test-variant", mode = TransportMode.WALK,
        engineVersion = "11.0", profile = "foot", benchmarkMode = BenchmarkMode.DEFAULT,
        graphSource = "test", requestFixturePath = requestFile.path, rawResponsePath = responseFile.path,
        runtimeMillis = 1,
    )
    check(result.matchedGeometry.size == 3) { "expected 3 trkpt points parsed, got ${result.matchedGeometry.size}" }
    check(result.matchedGeometry[0] == LatLon(48.100000, 2.200000)) { "first point mismatch: ${result.matchedGeometry[0]}" }
    check(result.perObservation == null) { "perObservation must be null -- GraphHopper GPX has no reliable per-observation correspondence" }
    check("accuracyMeters" in result.unsupportedInputFields) { "accuracyMeters must be listed as unsupported (GPX has no such field)" }
}

private fun testEmptyGpxYieldsEmptyGeometry() {
    val gpx = """<?xml version="1.0"?><gpx version="1.1"><trk><trkseg></trkseg></trk></gpx>"""
    val responseFile = tempFixtureFile(gpx, ".res.gpx")
    val requestFile = tempFixtureFile("<gpx></gpx>", ".gpx")
    val result = GraphHopperAdapter.adapt(
        resultGpxText = gpx,
        scenarioId = "test", variant = "empty", mode = TransportMode.WALK,
        engineVersion = "11.0", profile = "foot", benchmarkMode = BenchmarkMode.DEFAULT,
        graphSource = "test", requestFixturePath = requestFile.path, rawResponsePath = responseFile.path,
        runtimeMillis = 1,
    )
    check(result.matchedGeometry.isEmpty()) { "expected empty geometry for a trackless GPX, got ${result.matchedGeometry.size} points" }
}
