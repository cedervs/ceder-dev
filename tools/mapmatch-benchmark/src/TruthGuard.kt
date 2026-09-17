package worlddiscovery.benchmark

/**
 * Enforces the truth-semantics correction from round 2B-2A: raw dense GPS evidence, even when
 * independently corroborated for corridor/road identity (an annotated map, a known turn sequence),
 * must never silently become authoritative EXACT geometric truth. A [VerifiedRouteGeometry] can
 * have `corridorConfidence = HIGH` (we know which road was used) while its
 * `exactGeometryTruthStatus` stays `PENDING` or `NOT_EVALUABLE` (we do NOT know the exact lateral
 * position/every wobble is physically accurate) -- these are deliberately independent axes.
 *
 * This object is the single place that decides whether exact-geometry-dependent metrics
 * (Hausdorff/Fréchet/along-track/overlap, and H3 TP/FP/FN/precision/recall, since H3 truth cells
 * are sampled from the same geometry) may be computed. `BenchmarkEvaluation.evaluate` is the only
 * intended caller -- no other code in this tool should construct a [GeometryMetrics] or
 * [H3Metrics] against a [VerifiedRouteGeometry] without going through here first.
 */
object TruthGuard {

    fun exactGeometryEvaluability(geometry: VerifiedRouteGeometry): MetricEvaluability =
        if (geometry.exactGeometryTruthStatus == GeometryTruthStatus.VALIDATED) {
            MetricEvaluability.EVALUABLE
        } else {
            MetricEvaluability.NOT_EVALUABLE
        }

    /** H3 truth cells are sampled from [VerifiedRouteGeometry.geometry] -- the same polyline exact
     * geometry metrics depend on -- so H3 TP/FP/FN/precision/recall inherit the same gate. Kept as
     * a separate function (not just an alias) because a future, genuinely different H3-specific
     * verification path is plausible and should not require touching every call site. */
    fun h3Evaluability(geometry: VerifiedRouteGeometry): MetricEvaluability =
        exactGeometryEvaluability(geometry)
}
