package worlddiscovery.benchmark

/**
 * Focused tests for `Degradation.kt` -- protocol correction §16. Same plain-`main()`/`check()`
 * style as the other test files in this tool (see `GeometryMetricsTest.kt`'s doc comment for why).
 */
fun main() {
    testIrregularSourceIntervals()
    testStopsProduceNoDuplicateSelection()
    testGapLargerThanCadenceIsNotFilled()
    testNoDuplicateSelectedPointEver()
    testFinalPointAlwaysIncluded()
    testPureDownsamplingNeverSynthesizesAPoint()
    println("DegradationTest: ALL PASS")
}

private fun trace(points: List<ReferencePoint>): ReferenceTrace = ReferenceTrace(
    id = "test-trace",
    capturedAtEpochMs = points.first().timestampEpochMs,
    device = "test",
    acquisitionMode = "test",
    rawObservations = points,
    verifiedGeometry = null,
    corpusCategory = CorpusCategory.ROAD,
    corpusStatus = CorpusStatus.CAPTURED,
)

private fun pt(tSec: Long, lat: Double = 48.0, lon: Double = 2.0) =
    ReferencePoint(lat = lat, lon = lon, timestampEpochMs = tSec * 1000)

private fun testIrregularSourceIntervals() {
    // Source at t=0,1,2,3,9,10,11,20 (irregular) -- target cadence 5s -> expect targets 0,5,10,15,20
    // -> selections: t=0 (idx0), first at-or-after 5 is t=9 (idx4), first at-or-after 10 is t=10
    // (idx5), first at-or-after 15 is t=20 (idx7), first at-or-after 20 is already idx7 but
    // searchFrom has moved past it so loop ends; final-point policy adds idx7 (already last).
    val points = listOf(0L, 1L, 2L, 3L, 9L, 10L, 11L, 20L).map { pt(it) }
    val spec = DegradationSpec(targetCadenceSeconds = 5.0)
    val result = Degradation.generate(trace(points), spec, "5s-irregular", TransportMode.CAR)
    val selectedTimestampsSec = result.observations.map { it.timestampEpochMs / 1000 }
    println("irregular intervals -> selected timestamps(s): $selectedTimestampsSec")
    check(selectedTimestampsSec == listOf(0L, 9L, 10L, 20L)) {
        "expected [0,9,10,20], got $selectedTimestampsSec"
    }
}

private fun testStopsProduceNoDuplicateSelection() {
    // A "stop": several observations at nearly the same timestamp (0,1,1,1,1,2) then resuming.
    // Target cadence 2s -> targets 0,2,4 -> selections: idx0 (t=0), first at-or-after 2 is t=2
    // (idx5), first at-or-after 4 is none (trace ends at t=2) -> final point policy re-confirms
    // idx5 as last (no duplicate added).
    val points = listOf(0L, 1L, 1L, 1L, 1L, 2L).map { pt(it) }
    val spec = DegradationSpec(targetCadenceSeconds = 2.0)
    val result = Degradation.generate(trace(points), spec, "2s-stop", TransportMode.WALK)
    val selectedTimestampsSec = result.observations.map { it.timestampEpochMs / 1000 }
    println("stop scenario -> selected timestamps(s): $selectedTimestampsSec")
    check(selectedTimestampsSec == listOf(0L, 2L)) { "expected [0,2], got $selectedTimestampsSec" }
    check(result.observations.map { it.index } == listOf(0, 1)) { "indices must be re-numbered 0..n-1 in the degraded set" }
}

private fun testGapLargerThanCadenceIsNotFilled() {
    // A large real gap: t=0, then nothing until t=100. Target cadence 5s -> targets 0,5,10,...,95
    // all land in the gap and get skipped forward to the first real point at-or-after each target,
    // which is t=100 for all of them until searchFrom passes it -- so only t=0 and t=100 get
    // selected, never a synthesized intermediate point.
    val points = listOf(0L, 100L).map { pt(it) }
    val spec = DegradationSpec(targetCadenceSeconds = 5.0)
    val result = Degradation.generate(trace(points), spec, "5s-biggap", TransportMode.CAR)
    val selectedTimestampsSec = result.observations.map { it.timestampEpochMs / 1000 }
    println("large gap scenario -> selected timestamps(s): $selectedTimestampsSec")
    check(selectedTimestampsSec == listOf(0L, 100L)) { "expected [0,100] with no synthesized fill point, got $selectedTimestampsSec" }
}

private fun testNoDuplicateSelectedPointEver() {
    // Dense source (every 1s for 60s), cadence 27s -- verify strictly increasing indices/timestamps
    // and no repeats, across a case that doesn't line up evenly with the cadence.
    val points = (0L..60L).map { pt(it) }
    val spec = DegradationSpec(targetCadenceSeconds = 27.0)
    val result = Degradation.generate(trace(points), spec, "27s", TransportMode.CAR)
    val timestamps = result.observations.map { it.timestampEpochMs }
    check(timestamps == timestamps.distinct()) { "no duplicate timestamps/points should ever be selected, got $timestamps" }
    check(timestamps == timestamps.sorted()) { "selected points must be strictly time-ordered, got $timestamps" }
    println("27s over 60s dense trace -> ${timestamps.size} points at seconds ${timestamps.map { it / 1000 }}")
}

private fun testFinalPointAlwaysIncluded() {
    // Cadence chosen so the natural target grid (0,10,20,30,...) never reaches as far as the
    // trace's last timestamp (t=25 < next target 30) -- without the explicit final-point fallback,
    // t=25 would never be selected and the trip's true end would be silently dropped.
    val points = listOf(0L, 10L, 20L, 25L).map { pt(it) } // last point at t=25, before the t=30 target
    val spec = DegradationSpec(targetCadenceSeconds = 10.0)
    val result = Degradation.generate(trace(points), spec, "10s", TransportMode.CAR)
    val selectedTimestampsSec = result.observations.map { it.timestampEpochMs / 1000 }
    println("final-point-policy scenario -> selected timestamps(s): $selectedTimestampsSec")
    check(selectedTimestampsSec == listOf(0L, 10L, 20L, 25L)) {
        "expected [0,10,20,25] with the fallback adding t=25, got $selectedTimestampsSec"
    }
    check(selectedTimestampsSec.last() == 25L) { "the reference trace's final observation must always be included, got last=${selectedTimestampsSec.last()}" }
}

private fun testPureDownsamplingNeverSynthesizesAPoint() {
    val points = listOf(0L, 7L, 19L, 41L, 68L).map { i -> pt(i, lat = 48.0 + i * 0.0001, lon = 2.0 + i * 0.0001) }
    val spec = DegradationSpec(targetCadenceSeconds = 15.0)
    val result = Degradation.generate(trace(points), spec, "15s", TransportMode.CAR)
    val sourceCoords = points.map { it.lat to it.lon }.toSet()
    for (obs in result.observations) {
        check((obs.lat to obs.lon) in sourceCoords) { "degraded observation (${obs.lat},${obs.lon}) does not match any real source coordinate -- a point was synthesized" }
    }
    println("pure downsampling -> every one of ${result.observations.size} degraded points matches a real source coordinate")
}
