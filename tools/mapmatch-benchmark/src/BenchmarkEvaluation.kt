package worlddiscovery.benchmark

/**
 * The single, guarded path from (engine result, verified truth) to a [BenchmarkResult] row.
 * Always consults [TruthGuard] before computing any exact-geometry-dependent metric -- see its
 * doc comment and round 2B-2A's correction report for why this exists (raw GPS evidence must
 * never silently become authoritative exact truth just because a corridor was independently
 * confirmed).
 *
 * Corridor-level information ([VerifiedSegment.corridorId]/`confidence`) remains available
 * regardless of [GeometryTruthStatus] -- callers doing wrong-road classification (protocol §I)
 * read `verifiedGeometry.segments` directly, not through this guard, since that classification
 * does not depend on metre-level geometric accuracy.
 */
object BenchmarkEvaluation {

    fun evaluate(
        engineResult: EngineMatchResult,
        verifiedGeometry: VerifiedRouteGeometry,
        h3: H3Comparison,
        densificationSpacingMeters: Double = 5.0,
    ): BenchmarkResult {
        val geometryEvaluability = TruthGuard.exactGeometryEvaluability(verifiedGeometry)
        val h3Evaluability = TruthGuard.h3Evaluability(verifiedGeometry)
        val matched = engineResult.matchedGeometry

        val geometryMetrics = if (geometryEvaluability == MetricEvaluability.EVALUABLE && matched.isNotEmpty()) {
            GeometryMetrics(
                hausdorffMeters = GeometryComparison.hausdorffMetersDensified(verifiedGeometry.geometry, matched, densificationSpacingMeters),
                discreteFrechetMeters = GeometryComparison.discreteFrechetMetersDensified(verifiedGeometry.geometry, matched, densificationSpacingMeters),
                meanAlongTrackDeviationMeters = GeometryComparison.meanAlongTrackDeviationMeters(matched, verifiedGeometry.geometry),
                routeOverlapPercent = GeometryComparison.routeOverlapFraction(verifiedGeometry.geometry, matched, thresholdMeters = 25.0, densificationSpacingMeters = densificationSpacingMeters) * 100.0,
                densificationSpacingMeters = densificationSpacingMeters,
            )
        } else {
            null
        }

        val h3Metrics = if (h3Evaluability == MetricEvaluability.EVALUABLE && matched.isNotEmpty()) {
            val truthCells = h3.sampleToH3Cells(verifiedGeometry.geometry, H3Comparison.DEFAULT_RESOLUTION)
            val matchedCells = h3.sampleToH3Cells(matched, H3Comparison.DEFAULT_RESOLUTION)
            h3.compare(truthCells, matchedCells, H3Comparison.DEFAULT_RESOLUTION)
        } else {
            null
        }

        return BenchmarkResult(
            scenarioId = engineResult.scenarioId,
            engine = engineResult.engine,
            variant = engineResult.variant,
            mode = engineResult.mode,
            geometryEvaluability = geometryEvaluability,
            geometryMetrics = geometryMetrics,
            h3Evaluability = h3Evaluability,
            h3Metrics = h3Metrics,
            acceptance = null,
            wrongRoadClassification = null,
            engineMatchResult = engineResult,
        )
    }
}
