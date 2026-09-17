package worlddiscovery.benchmark

/**
 * Focused tests for `TurnSegmentation.kt` -- round 2B-3 (needed because Scenario 2 is a one-way
 * urban walk with real turns, unlike Scenario 1's out-and-back). Uses small FABRICATED synthetic
 * point sequences (an L-shape, a straight line, a noisy straight line), never reads
 * `benchmark-private/`. Same plain-`main()`/`check()` style as the other test files.
 */
fun main() {
    testStraightLineDetectsNoTurns()
    testLShapeDetectsOneTurn()
    testNoisyStraightLineDoesNotFalseTrigger()
    testBuildSegmentsProducesTwoLegsForOneTurn()
    testShortTrailingSegmentIsMerged()
    testLargeTimeGapDetected()
    testNoLargeTimeGapWhenAllIntervalsNormal()
    testMultipleLargeTimeGapsAllDetected()
    println("TurnSegmentationTest: ALL PASS")
}

private fun pt(lat: Double, lon: Double, tSec: Long) = ReferencePoint(lat = lat, lon = lon, timestampEpochMs = tSec * 1000)

/** ~1m of latitude per 0.000009 degrees near the equator-ish scale used here; these fixtures use
 * simple straight-line synthetic coordinates, not real-world geodesy-critical precision. */
private fun straightLine(startLat: Double, startLon: Double, headingIsNorth: Boolean, steps: Int, stepMeters: Double = 2.0): List<ReferencePoint> {
    val degPerMeter = 1.0 / 111_320.0
    return (0..steps).map { i ->
        val d = i * stepMeters * degPerMeter
        if (headingIsNorth) pt(startLat + d, startLon, i.toLong()) else pt(startLat, startLon + d, i.toLong())
    }
}

private fun testStraightLineDetectsNoTurns() {
    val points = straightLine(48.0, 2.0, headingIsNorth = true, steps = 60)
    val turns = TurnSegmentation.detectTurnIndices(points)
    check(turns.isEmpty()) { "a perfectly straight line must have 0 detected turns, got $turns" }
}

private fun testLShapeDetectsOneTurn() {
    val northLeg = straightLine(48.0, 2.0, headingIsNorth = true, steps = 40) // 80m north
    val turnPoint = northLeg.last()
    val degPerMeter = 1.0 / 111_320.0
    val eastLeg = (1..40).map { i ->
        pt(turnPoint.lat, turnPoint.lon + i * 2.0 * degPerMeter, 40L + i)
    }
    val points = northLeg + eastLeg
    val turns = TurnSegmentation.detectTurnIndices(points)
    check(turns.size == 1) { "an L-shaped path must detect exactly 1 turn, got ${turns.size}: $turns" }
    // The turn should be near index 40 (the corner), within a reasonable tolerance.
    check(turns[0] in 30..50) { "expected turn index near 40 (the corner), got ${turns[0]}" }
}

private fun testNoisyStraightLineDoesNotFalseTrigger() {
    // Small lateral jitter (~1-2m) superimposed on an otherwise straight northward line -- must not
    // be mistaken for a sustained turn.
    val degPerMeter = 1.0 / 111_320.0
    val points = (0..80).map { i ->
        val jitter = if (i % 3 == 0) 1.5 * degPerMeter else 0.0
        pt(48.0 + i * 2.0 * degPerMeter, 2.0 + jitter, i.toLong())
    }
    val turns = TurnSegmentation.detectTurnIndices(points)
    check(turns.isEmpty()) { "small lateral GPS jitter on a straight line must not be detected as a turn, got $turns" }
}

private fun testBuildSegmentsProducesTwoLegsForOneTurn() {
    val segments = TurnSegmentation.buildSegments(
        totalPoints = 80, turnIndices = listOf(40),
        corridorIdPrefix = "test", confidence = TruthConfidence.HIGH,
        verificationMethod = VerificationMethod.MANUAL_ROUTE_ANNOTATION,
    )
    check(segments.size == 2) { "one turn should produce 2 segments, got ${segments.size}" }
    check(segments[0].startReferenceIndex == 0 && segments[0].endReferenceIndex == 40)
    check(segments[1].startReferenceIndex == 40 && segments[1].endReferenceIndex == 79)
    check(segments[0].corridorId != segments[1].corridorId) { "each leg must get a distinct corridorId" }
}

private fun testShortTrailingSegmentIsMerged() {
    // A turn detected very close to the end of the trace should not produce a tiny 2-point trailing
    // "segment" -- it should merge into the previous leg.
    val segments = TurnSegmentation.buildSegments(
        totalPoints = 100, turnIndices = listOf(97),
        corridorIdPrefix = "test", confidence = TruthConfidence.HIGH,
        verificationMethod = VerificationMethod.MANUAL_ROUTE_ANNOTATION,
        minSegmentPoints = 5,
    )
    check(segments.size == 1) { "a too-short trailing leg must be merged into the previous one, got ${segments.size} segments: $segments" }
    check(segments[0].endReferenceIndex == 99)
}

/** 2B-4: a real ~389s-style gap must be detected as its own boundary, independent of spatial
 * bearing -- fabricated here as a near-stationary gap (near-zero displacement, like the real
 * scenario 3 gap turned out to be), which a pure bearing-based detector would never catch. */
private fun testLargeTimeGapDetected() {
    val points = listOf(
        pt(48.0, 2.0, 0), pt(48.0, 2.0, 1), pt(48.0, 2.0, 2),
        pt(48.0, 2.0, 402), // 400 second gap from the previous point (t=2 -> t=402)
        pt(48.0, 2.0, 403), pt(48.0, 2.0, 404),
    )
    val gaps = TurnSegmentation.detectLargeTimeGapIndices(points, thresholdSeconds = 60.0)
    check(gaps == listOf(3)) { "expected a single gap boundary at index 3 (the point right after the gap), got $gaps" }
}

private fun testNoLargeTimeGapWhenAllIntervalsNormal() {
    val points = (0..20).map { pt(48.0 + it * 0.0001, 2.0, it.toLong()) }
    val gaps = TurnSegmentation.detectLargeTimeGapIndices(points, thresholdSeconds = 60.0)
    check(gaps.isEmpty()) { "uniform 1s intervals must not produce any detected gap, got $gaps" }
}

private fun testMultipleLargeTimeGapsAllDetected() {
    val points = listOf(
        pt(48.0, 2.0, 0), pt(48.0, 2.0, 1),
        pt(48.0, 2.0, 101), // gap 1: 100s
        pt(48.0, 2.0, 102),
        pt(48.0, 2.0, 300), // gap 2: 198s
        pt(48.0, 2.0, 301),
    )
    val gaps = TurnSegmentation.detectLargeTimeGapIndices(points, thresholdSeconds = 60.0)
    check(gaps == listOf(2, 4)) { "expected both gap boundaries detected in order, got $gaps" }
}
