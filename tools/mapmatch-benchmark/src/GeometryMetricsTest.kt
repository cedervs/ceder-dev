package worlddiscovery.benchmark

/**
 * Focused tests for `GeometryMetrics.kt` -- protocol correction §13.E. Plain `main()` with
 * `check()` assertions, run through the same manual kotlinc/JBR-java toolchain as the rest of this
 * tool (no JUnit -- this environment's JVM cannot start a Gradle daemon or any JVM server-mode
 * process, documented extensively elsewhere in this project's history; a plain assertion-based
 * test runner is the established workaround for benchmark-only tooling). Prints `ALL PASS` and
 * exits normally on success; a failed `check()` throws and the process exits non-zero, so a runner
 * script can detect failure from the exit code alone, not just by eyeballing output.
 */
fun main() {
    testIdenticalLine()
    testParallelLine()
    testDifferentVertexDensities()
    testShortSegment()
    testAntimeridianCrossing()
    testDegenerateSinglePoint()
    testRouteOverlapIsLengthWeightedNotVertexWeighted()
    testHaversineClampNearAntipodal()
    println("GeometryMetricsTest: ALL PASS")
}

private fun testIdenticalLine() {
    val line = listOf(LatLon(48.85, 2.35), LatLon(48.86, 2.36), LatLon(48.87, 2.37))
    val h = GeometryComparison.hausdorffMeters(line, line)
    val f = GeometryComparison.discreteFrechetMeters(line, line)
    check(h < 1e-6) { "identical line hausdorff should be ~0, got $h" }
    check(f < 1e-6) { "identical line frechet should be ~0, got $f" }
    val overlap = GeometryComparison.routeOverlapFraction(line, line, thresholdMeters = 1.0)
    check(overlap > 0.999) { "identical line should fully overlap itself, got $overlap" }
}

private fun testParallelLine() {
    // Two straight north-south lines ~20m apart (roughly 0.00018 deg longitude at this latitude).
    val a = listOf(LatLon(48.85, 2.35), LatLon(48.86, 2.35))
    val b = listOf(LatLon(48.85, 2.35018), LatLon(48.86, 2.35018))
    val h = GeometryComparison.hausdorffMeters(a, b)
    check(h in 10.0..30.0) { "parallel line ~20m apart should have hausdorff in [10,30]m, got $h" }
    val overlapTight = GeometryComparison.routeOverlapFraction(a, b, thresholdMeters = 5.0)
    check(overlapTight < 0.01) { "parallel line 20m apart should NOT overlap at a 5m threshold, got $overlapTight" }
    val overlapLoose = GeometryComparison.routeOverlapFraction(a, b, thresholdMeters = 25.0)
    check(overlapLoose > 0.99) { "parallel line 20m apart SHOULD overlap at a 25m threshold, got $overlapLoose" }
}

private fun testDifferentVertexDensities() {
    // Same physical straight line, represented once with 2 vertices and once with 50 -- densified
    // comparison must agree closely with the analytically expected distance to itself (0), proving
    // that vertex count alone does not distort the densified comparison the way the old
    // vertex-count-based routeOverlapFraction did.
    val sparse = listOf(LatLon(48.80, 2.30), LatLon(48.90, 2.30))
    val dense = GeometryComparison.densify(sparse, spacingMeters = 20.0)
    check(dense.size > 40) { "densify at 20m spacing over ~11km should yield 40+ points, got ${dense.size}" }
    val h = GeometryComparison.hausdorffMetersDensified(sparse, dense, spacingMeters = 5.0)
    check(h < 5.0) { "a line and its own densified version should be ~coincident, got hausdorff=$h" }
    val overlapSparseAsRef = GeometryComparison.routeOverlapFraction(sparse, dense, thresholdMeters = 5.0)
    val overlapDenseAsRef = GeometryComparison.routeOverlapFraction(dense, sparse, thresholdMeters = 5.0)
    check(overlapSparseAsRef > 0.99) { "sparse-as-reference overlap should be ~1.0, got $overlapSparseAsRef" }
    check(overlapDenseAsRef > 0.99) { "dense-as-reference overlap should be ~1.0, got $overlapDenseAsRef" }
}

private fun testShortSegment() {
    // Two points ~5m apart -- must not blow up or return 0 due to precision issues.
    val a = LatLon(48.8566, 2.3522)
    val b = LatLon(48.85664, 2.3522) // ~4.4m north
    val d = GeometryComparison.haversineMeters(a, b)
    check(d in 2.0..8.0) { "expected ~4-5m for a short segment, got $d" }
}

private fun testAntimeridianCrossing() {
    // Two points a few meters apart straddling the antimeridian (lon +179.9999 vs -179.9999).
    val a = LatLon(0.0, 179.9999)
    val b = LatLon(0.0, -179.9999)
    val haversine = GeometryComparison.haversineMeters(a, b)
    check(haversine < 50.0) { "antimeridian-straddling points ~2m apart should NOT be ~40,000km via haversine, got ${haversine}m" }

    // distanceToPolylineMeters uses the local equirectangular projection fixed in this round --
    // must also stay small, not the ~20000km bug the old unwrapped projection produced.
    val polyline = listOf(LatLon(0.0, 179.9998), LatLon(0.0, -179.9998))
    val dist = GeometryComparison.distanceToPolylineMeters(LatLon(0.0, 180.0), polyline)
    check(dist < 50.0) { "point near the antimeridian should be close to a segment crossing it, got ${dist}m" }

    // interpolateLon must move forward through 180, not backward across the whole globe.
    val midLon = GeometryComparison.interpolateLon(179.9999, -179.9999, 0.5)
    val midDistFrom180 = GeometryComparison.wrapLonDeltaDegrees(midLon - 180.0)
    check(kotlin.math.abs(midDistFrom180) < 0.001) { "midpoint across antimeridian should land near lon=180/-180, got $midLon" }

    // densify across an antimeridian-crossing segment must not insert points on the wrong side of
    // the planet.
    val densified = GeometryComparison.densify(polyline, spacingMeters = 5.0)
    for (p in densified) {
        check(p.lon > 179.9 || p.lon < -179.9) { "densified point over the antimeridian strayed to lon=${p.lon}" }
    }
}

private fun testDegenerateSinglePoint() {
    val single = listOf(LatLon(48.85, 2.35))
    val other = listOf(LatLon(48.86, 2.36))
    val d = GeometryComparison.distanceToPolylineMeters(other[0], single)
    check(d > 0.0) { "distance to a single-point polyline should just be the point-to-point distance" }
    val densifiedSingle = GeometryComparison.densify(single, spacingMeters = 5.0)
    check(densifiedSingle == single) { "densify of a single-point polyline should be a no-op" }
    val h = GeometryComparison.hausdorffMeters(single, single)
    check(h < 1e-6) { "a single point compared to itself should have hausdorff ~0, got $h" }
}

private fun testRouteOverlapIsLengthWeightedNotVertexWeighted() {
    // Reference: a long straight segment (2 vertices, ~11km) fully covered by matched.
    // A vertex-count implementation would report 2 points checked (both covered => 100%, same as
    // length-weighted here) -- so instead we construct a reference where 90% of its VERTICES sit on
    // a short covered sub-section and only 10% of vertices sit on a long uncovered sub-section, but
    // by LENGTH it's the opposite (most of the length is uncovered). A vertex-weighted result and a
    // length-weighted result must disagree here, proving the fix changed behavior meaningfully.
    val coveredShort = GeometryComparison.densify(listOf(LatLon(48.0, 2.0), LatLon(48.001, 2.0)), spacingMeters = 2.0) // ~111m, many vertices
    val uncoveredLong = listOf(LatLon(48.001, 2.0), LatLon(48.1, 2.0)) // ~11km, 2 vertices only, far from `matched`
    val reference = coveredShort + uncoveredLong.drop(1)
    val matched = coveredShort // matcher only covers the short covered part

    val vertexWeighted = reference.count { GeometryComparison.distanceToPolylineMeters(it, matched) <= 5.0 }.toDouble() / reference.size
    val lengthWeighted = GeometryComparison.routeOverlapFraction(reference, matched, thresholdMeters = 5.0)

    check(vertexWeighted > 0.5) { "naive vertex-count overlap should be misleadingly high here (most VERTICES are on the covered short part), got $vertexWeighted" }
    check(lengthWeighted < 0.05) { "length-weighted overlap should correctly show most of the route's LENGTH is uncovered, got $lengthWeighted" }
}

private fun testHaversineClampNearAntipodal() {
    // Antipodal-ish points where floating point rounding could push h fractionally above 1.0.
    val a = LatLon(0.0, 0.0)
    val b = LatLon(0.0, 180.0)
    val d = GeometryComparison.haversineMeters(a, b)
    check(!d.isNaN()) { "haversine for antipodal points must not be NaN" }
    check(d > 19_000_000 && d < 20_100_000) { "antipodal points should be ~ half earth circumference (~20015km), got ${d}m" }
}
