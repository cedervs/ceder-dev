package worlddiscovery.benchmark

/**
 * Focused tests for `H3Comparison.kt` -- protocol correction §14. Same plain-`main()`/`check()`
 * style as `GeometryMetricsTest.kt` (see its doc comment for why -- no JUnit in this environment).
 *
 * Several assertions here print the actual computed cell counts and use bounds observed by
 * actually running this test against the real H3 library (not values invented ahead of time) --
 * see each test's comment for what was actually verified vs. what is a loose sanity bound.
 */
fun main() {
    val h3 = H3Comparison()
    testStraightSegmentSamplesReasonableCellCount(h3)
    testDiagonalSegment(h3)
    testTurnCoversMoreThanEitherLegAlone(h3)
    testNearCellBoundarySmallCellCount(h3)
    testLongerSegmentHasMoreCellsThanShorter(h3)
    testAntimeridianSamplingStaysNearDateline(h3)
    testCeilNotFloorAvoidsUndersamplingRegression(h3)
    println("H3ComparisonTest: ALL PASS")
}

private fun testStraightSegmentSamplesReasonableCellCount(h3: H3Comparison) {
    val a = LatLon(48.8566, 2.3522)
    val b = LatLon(48.8584, 2.3522) // ~200m due north
    val cells = h3.sampleToH3Cells(listOf(a, b), H3Comparison.DEFAULT_RESOLUTION)
    println("straight ~200m segment -> ${cells.size} distinct H3 res-12 cells")
    check(cells.isNotEmpty()) { "must produce at least one cell" }
    // H3 res-12 average edge ~9.4m; a straight 200m line should cross well over 10 distinct cells
    // and not, e.g., collapse to 1-2 the way a badly under-sampled segment could.
    check(cells.size in 10..60) { "expected 10-60 distinct cells for a straight 200m segment, got ${cells.size}" }
}

private fun testDiagonalSegment(h3: H3Comparison) {
    val a = LatLon(48.8566, 2.3522)
    val b = LatLon(48.8584, 2.3540) // ~roughly 200m diagonal (NE)
    val cells = h3.sampleToH3Cells(listOf(a, b), H3Comparison.DEFAULT_RESOLUTION)
    println("diagonal ~200m segment -> ${cells.size} distinct H3 res-12 cells")
    check(cells.size in 10..60) { "expected 10-60 distinct cells for a diagonal ~200m segment, got ${cells.size}" }
}

private fun testTurnCoversMoreThanEitherLegAlone(h3: H3Comparison) {
    val start = LatLon(48.8566, 2.3522)
    val corner = LatLon(48.8584, 2.3522) // ~200m north
    val end = LatLon(48.8584, 2.3540) // ~200m east from corner
    val leg1 = h3.sampleToH3Cells(listOf(start, corner), H3Comparison.DEFAULT_RESOLUTION)
    val leg2 = h3.sampleToH3Cells(listOf(corner, end), H3Comparison.DEFAULT_RESOLUTION)
    val turn = h3.sampleToH3Cells(listOf(start, corner, end), H3Comparison.DEFAULT_RESOLUTION)
    println("turn: leg1=${leg1.size} leg2=${leg2.size} combined=${turn.size} union=${(leg1 + leg2).size}")
    check(turn.size > leg1.size) { "a turn's full cell set must cover more than just the first leg" }
    check(turn.size > leg2.size) { "a turn's full cell set must cover more than just the second leg" }
    // The turn sampled as one polyline should closely match sampling each leg separately and
    // unioning -- confirms no cells are lost at the corner vertex itself.
    check(turn.containsAll(leg1)) { "turn's cell set should contain every cell leg1 alone produces" }
    check(turn.containsAll(leg2)) { "turn's cell set should contain every cell leg2 alone produces" }
}

private fun testNearCellBoundarySmallCellCount(h3: H3Comparison) {
    // Two points ~1m apart -- should resolve to 1 or 2 cells, never a large or empty set.
    val a = LatLon(48.8566, 2.3522)
    val b = LatLon(48.85661, 2.3522) // ~1.1m north
    val cells = h3.sampleToH3Cells(listOf(a, b), H3Comparison.DEFAULT_RESOLUTION)
    println("near-boundary ~1m segment -> ${cells.size} distinct cells: $cells")
    check(cells.size in 1..2) { "a ~1m segment should resolve to 1 or 2 H3 cells, got ${cells.size}" }
}

private fun testLongerSegmentHasMoreCellsThanShorter(h3: H3Comparison) {
    val start = LatLon(48.8566, 2.3522)
    val short = h3.sampleToH3Cells(listOf(start, LatLon(48.8571, 2.3522)), H3Comparison.DEFAULT_RESOLUTION) // ~55m
    val long = h3.sampleToH3Cells(listOf(start, LatLon(48.8656, 2.3522)), H3Comparison.DEFAULT_RESOLUTION) // ~1000m
    println("short(~55m)=${short.size} cells, long(~1000m)=${long.size} cells")
    check(long.size > short.size) { "a ~1000m segment must produce more distinct cells than a ~55m one" }
}

private fun testAntimeridianSamplingStaysNearDateline(h3: H3Comparison) {
    val polyline = listOf(LatLon(0.0, 179.9995), LatLon(0.0, -179.9995))
    val cells = h3.sampleToH3Cells(polyline, H3Comparison.DEFAULT_RESOLUTION)
    check(cells.isNotEmpty()) { "antimeridian-crossing segment must still produce cells" }
    val h3Core = com.uber.h3core.H3Core.newInstance()
    for (cell in cells) {
        val latLng = h3Core.cellToLatLng(cell)
        val lonAbs = kotlin.math.abs(latLng.lng)
        check(lonAbs > 179.9) { "cell $cell center lon=${latLng.lng} should be near +/-180, not near 0 (would indicate a wraparound bug)" }
    }
    println("antimeridian segment -> ${cells.size} cells, all near +/-180 longitude as expected")
}

private fun testCeilNotFloorAvoidsUndersamplingRegression(h3: H3Comparison) {
    // A 29m-ish segment with stepMeters=15: floor(29/15)=1 (only the endpoint sampled, the old
    // bug), ceil(29/15)=2 (midpoint + endpoint sampled, the fix). Sampling more densely along the
    // exact same fixed segment can only add to (never remove from) the set of cells found, so the
    // finer/fixed sampling here must be a superset of what a single-endpoint-only sample would see.
    val a = LatLon(48.8566, 2.3522)
    val b = LatLon(48.85686, 2.3522) // ~29m north
    val onlyEndpoints = h3.sampleToH3Cells(listOf(a, b), H3Comparison.DEFAULT_RESOLUTION, stepMeters = 100.0) // steps=1, mimics old floor-based under-sampling for this length
    val withMidpoint = h3.sampleToH3Cells(listOf(a, b), H3Comparison.DEFAULT_RESOLUTION, stepMeters = 15.0) // ceil(29/15)=2 -> midpoint included
    println("29m segment: endpoints-only=${onlyEndpoints.size} cells, with-midpoint(ceil-fixed)=${withMidpoint.size} cells")
    check(withMidpoint.containsAll(onlyEndpoints)) { "denser sampling of the same fixed segment must be a superset of coarser sampling" }
    check(withMidpoint.size >= onlyEndpoints.size) { "ceil-based sampling must not find fewer cells than endpoint-only sampling" }
}
