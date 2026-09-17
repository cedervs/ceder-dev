package worlddiscovery.benchmark

import com.uber.h3core.H3Core
import kotlin.math.ceil

/**
 * H3 coverage comparison -- protocol §H. This is the safety-critical comparison for World
 * Discovery: false-positive H3 cells (matched territory the reference trace never actually
 * covered) are the single most important number this harness produces (protocol §H, restating
 * Phase 2A's own "false accepted reconstruction is the worst outcome" principle).
 *
 * Uses the exact same `com.uber.h3core.H3Core`/`latLngToCellAddress` API already used by
 * `core-discovery-engine`'s `H3JavaCellConverter` (see that file), so this benchmark's H3
 * semantics match production's, not a reinvented approximation.
 *
 * Truth H3 cells MUST be derived from [ReferenceTrace.verifiedGeometry] (correction §14), never
 * from raw [ReferencePoint] observations -- this class only samples whatever polyline it is given,
 * so enforcing that rule is the *caller's* responsibility (see `SmokeTest.kt` and the protocol
 * doc's §H for the explicit warning); there is deliberately no `ReferenceTrace`-typed overload here
 * that could make the wrong source look equally valid.
 *
 * 2B-1B correction fixes (protocol correction §14):
 * - [sampleToH3Cells]'s per-segment step count now uses [ceil], not `toInt()` (which truncates
 *   towards zero, i.e. floors for positive inputs). The old code computed
 *   `(segmentLength / stepMeters).toInt()`, so a 29m segment with `stepMeters = 15.0` produced
 *   `steps = 1` (`29/15 = 1.93 -> 1`), i.e. only ONE sample at the segment's far endpoint --
 *   silently exceeding the requested 15m spacing and risking skipping over an H3 cell the segment
 *   actually crosses. `ceil(29.0/15.0) = 2` correctly bounds the resulting spacing to `<= 15m`.
 * - the interpolation itself now uses `GeometryComparison.interpolateLon` (antimeridian-safe)
 *   instead of raw linear longitude interpolation, which broke the same way described in
 *   `GeometryMetrics.kt`'s doc comment for a segment crossing the dateline.
 * - [DEFAULT_STEP_METERS] is lowered from 15.0m to 3.0m and documented against H3 resolution 12's
 *   actual published cell dimensions (see its own doc comment) -- 15m was not defensible at
 *   resolution 12 (see below).
 */
class H3Comparison(private val h3Core: H3Core = H3Core.newInstance()) {

    /** Samples [polyline] at approximately every [stepMeters] of its own length and converts each
     * sampled point to an H3 cell address at [resolution] -- the same sampling policy must be
     * applied to both the truth geometry and the matched geometry for the comparison to be fair
     * (protocol §H step 2's explicit requirement).
     *
     * This is dense point-sampling, not a topological line/grid traversal: H3's own
     * `gridPathCells` only connects cell *centers* and is not defined for arbitrary continuous
     * geometry, so it is not a drop-in replacement here (protocol correction §14 asked for "a
     * defensible traversal strategy", weighing traversal-API options against dense interpolation
     * -- dense interpolation at a spacing well below a resolution-12 cell's own size was judged the
     * more directly applicable option). Sampling at [stepMeters] well under a cell's size makes
     * skipping a traversed cell unlikely but does not *formally* guarantee it can never happen at a
     * hexagon corner; see [DEFAULT_STEP_METERS]'s doc comment for the numbers behind that judgment.
     */
    fun sampleToH3Cells(polyline: List<LatLon>, resolution: Int, stepMeters: Double = DEFAULT_STEP_METERS): Set<String> {
        require(stepMeters > 0.0) { "stepMeters must be > 0" }
        if (polyline.isEmpty()) return emptySet()
        if (polyline.size == 1) {
            return setOf(h3Core.latLngToCellAddress(polyline[0].lat, polyline[0].lon, resolution))
        }
        val cells = LinkedHashSet<String>()
        cells.add(h3Core.latLngToCellAddress(polyline[0].lat, polyline[0].lon, resolution))
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val segmentLength = GeometryComparison.haversineMeters(a, b)
            val steps = ceil(segmentLength / stepMeters).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val lat = a.lat + (b.lat - a.lat) * t
                val lon = GeometryComparison.interpolateLon(a.lon, b.lon, t)
                cells.add(h3Core.latLngToCellAddress(lat, lon, resolution))
            }
        }
        return cells
    }

    /** Compares a truth H3 set against a matched H3 set -- see [H3Metrics]'s own doc comment for
     * why the raw false-positive/false-negative counts, not just precision/recall, are the
     * headline numbers. */
    fun compare(truthCells: Set<String>, matchedCells: Set<String>, resolution: Int, samplingStepMeters: Double = DEFAULT_STEP_METERS): H3Metrics {
        val truePositives = truthCells.intersect(matchedCells)
        val falsePositives = matchedCells - truthCells
        val falseNegatives = truthCells - matchedCells
        val precision = if (matchedCells.isEmpty()) 0.0 else truePositives.size.toDouble() / matchedCells.size
        val recall = if (truthCells.isEmpty()) 0.0 else truePositives.size.toDouble() / truthCells.size
        return H3Metrics(
            truePositiveCells = truePositives.size,
            falsePositiveCells = falsePositives.size,
            falseNegativeCells = falseNegatives.size,
            precision = precision,
            recall = recall,
            resolution = resolution,
            samplingStepMeters = samplingStepMeters,
        )
    }

    companion object {
        /** Same value as `core-discovery-engine`'s `DiscoveryEngineVersion.CANONICAL_H3_RESOLUTION`
         * -- reused deliberately so benchmark results stay directly interpretable against real
         * production discovery data, not because this document invents a new calibration decision.
         * Kept at 12 per 2B-1B correction §15 ("do not redesign production resolution in this
         * phase"). */
        const val DEFAULT_RESOLUTION = 12

        /** H3's own published average-cell-size table gives resolution 12 an average edge length of
         * ~9.415526m (and average hexagon area ~307.09 m²) -- so a hexagon's own flat-to-flat span
         * is on the order of ~16-19m. Sampling every 3m is comfortably under an order of magnitude
         * below that span, which is the judgment call behind this default: dense enough that a
         * nearly-straight travelled segment is very unlikely to pass through a whole cell between
         * two consecutive samples without landing inside it at least once, while remaining cheap
         * enough to run over a real multi-kilometer route. This is a documented ENGINEERING DESIGN
         * choice (protocol correction §14), not a formal topological guarantee -- see
         * [sampleToH3Cells]'s doc comment. */
        const val DEFAULT_STEP_METERS = 3.0
    }
}
