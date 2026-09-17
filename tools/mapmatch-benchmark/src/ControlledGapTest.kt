package worlddiscovery.benchmark

/**
 * Focused tests for `ControlledGap.kt` -- protocol round 2B-5 §18 (A-G). Uses small FABRICATED
 * synthetic traces (fake coordinates), never reads `benchmark-private/`. Same plain-`main()`/
 * `check()` style as the other test files in this tool.
 */
fun main() {
    testA_hiddenObservationsAbsentFromMatcherInput()
    testB_referenceObservationsRetainedSeparately()
    testC_sourceTraceNotMutated()
    testD_deterministicGapGeneration()
    testE_overlapWithExcludedRealGapRejected()
    testF_stationaryWindowRejectedForMovingGap()
    testG_invalidRangesRejected()
    println("ControlledGapTest: ALL PASS")
}

private fun movingPoint(i: Int) = ReferencePoint(
    lat = 48.0 + i * 0.0001, lon = 2.0, timestampEpochMs = i.toLong() * 1000,
    speedMps = 10.0f, bearingDegrees = 0.0f,
)

private fun stationaryPoint(i: Int) = ReferencePoint(
    lat = 48.0, lon = 2.0, timestampEpochMs = i.toLong() * 1000,
    speedMps = 0.0f, bearingDegrees = null,
)

private fun fakeMovingTrace(n: Int = 100, id: String = "test-trace") = ReferenceTrace(
    id = id, capturedAtEpochMs = 0, device = "test", acquisitionMode = "test",
    rawObservations = (0 until n).map { movingPoint(it) },
    verifiedGeometry = null, corpusCategory = CorpusCategory.ROAD, corpusStatus = CorpusStatus.CAPTURED,
)

private fun fakeSpec(start: Int, end: Int, id: String = "gap-1") = ControlledGapSpec(
    gapId = id, sourceTraceId = "test-trace", hiddenStartIndex = start, hiddenEndIndex = end,
    context = GapContext.SIMPLE_ROAD, selectionRationale = "test fixture", targetDurationSeconds = (end - start).toDouble(),
)

private fun testA_hiddenObservationsAbsentFromMatcherInput() {
    val trace = fakeMovingTrace()
    val variant = ControlledGapGenerator.generate(trace, fakeSpec(40, 59))
    check(variant.matcherInputObservations.size == 100 - 20) { "expected 80 matcher-input observations, got ${variant.matcherInputObservations.size}" }
    val matcherInputTimestamps = variant.matcherInputObservations.map { it.timestampEpochMs }.toSet()
    for (hiddenIdx in 40..59) {
        check((hiddenIdx.toLong() * 1000) !in matcherInputTimestamps) { "hidden observation at index $hiddenIdx leaked into matcher input" }
    }
}

private fun testB_referenceObservationsRetainedSeparately() {
    val trace = fakeMovingTrace()
    val variant = ControlledGapGenerator.generate(trace, fakeSpec(40, 59))
    check(variant.referenceWindowObservations.size == 20) { "expected 20 reference-window observations, got ${variant.referenceWindowObservations.size}" }
    val refTimestamps = variant.referenceWindowObservations.map { it.timestampEpochMs }.sorted()
    val expected = (40..59).map { it.toLong() * 1000 }
    check(refTimestamps == expected) { "reference window should contain exactly the hidden observations' real timestamps" }
}

private fun testC_sourceTraceNotMutated() {
    val trace = fakeMovingTrace()
    val originalSize = trace.rawObservations.size
    val originalFirstTimestamp = trace.rawObservations.first().timestampEpochMs
    ControlledGapGenerator.generate(trace, fakeSpec(40, 59))
    check(trace.rawObservations.size == originalSize) { "source trace size must be unchanged after generating a variant" }
    check(trace.rawObservations.first().timestampEpochMs == originalFirstTimestamp) { "source trace content must be unchanged" }
}

private fun testD_deterministicGapGeneration() {
    val trace = fakeMovingTrace()
    val spec = fakeSpec(40, 59)
    val v1 = ControlledGapGenerator.generate(trace, spec)
    val v2 = ControlledGapGenerator.generate(trace, spec)
    check(v1.matcherInputObservations.map { it.timestampEpochMs } == v2.matcherInputObservations.map { it.timestampEpochMs }) { "same spec must deterministically produce the same matcher input" }
    check(v1.referenceWindowObservations.map { it.timestampEpochMs } == v2.referenceWindowObservations.map { it.timestampEpochMs }) { "same spec must deterministically produce the same reference window" }
}

private fun testE_overlapWithExcludedRealGapRejected() {
    val trace = fakeMovingTrace()
    var threw = false
    try {
        ControlledGapGenerator.generate(trace, fakeSpec(40, 59), excludedRealGapRanges = listOf(50..55))
    } catch (e: IllegalArgumentException) {
        threw = true
    }
    check(threw) { "a controlled gap overlapping an excluded real gap range must be rejected" }

    // A non-overlapping window (clearly outside the excluded range) must still succeed.
    val ok = ControlledGapGenerator.generate(trace, fakeSpec(10, 20), excludedRealGapRanges = listOf(50..55))
    check(ok.referenceWindowObservations.size == 11)
}

private fun testF_stationaryWindowRejectedForMovingGap() {
    val trace = ReferenceTrace(
        id = "stationary-trace", capturedAtEpochMs = 0, device = "test", acquisitionMode = "test",
        rawObservations = (0 until 100).map { stationaryPoint(it) },
        verifiedGeometry = null, corpusCategory = CorpusCategory.ROAD, corpusStatus = CorpusStatus.CAPTURED,
    )
    var threw = false
    try {
        ControlledGapGenerator.generate(trace, fakeSpec(40, 59), requireMovement = true)
    } catch (e: IllegalArgumentException) {
        threw = true
    }
    check(threw) { "a stationary window must be rejected when requireMovement=true" }

    // Explicitly opting out must still succeed (e.g. for a deliberate stationary-gap experiment).
    val ok = ControlledGapGenerator.generate(trace, fakeSpec(40, 59), requireMovement = false)
    check(ok.referenceWindowObservations.size == 20)
}

private fun testG_invalidRangesRejected() {
    val trace = fakeMovingTrace()
    var caught = 0
    val badSpecs = listOf(
        fakeSpec(0, 10), // hiddenStartIndex must be > 0 (no observation before it)
        fakeSpec(90, 99), // hiddenEndIndex must be < lastIndex (no observation after it)
        fakeSpec(60, 40), // start > end
        fakeSpec(-1, 10), // out of range
        fakeSpec(10, 200), // out of range
    )
    for (spec in badSpecs) {
        try {
            ControlledGapGenerator.generate(trace, spec)
        } catch (e: IllegalArgumentException) {
            caught++
        }
    }
    check(caught == badSpecs.size) { "expected all ${badSpecs.size} invalid specs to be rejected, only $caught were" }
}
