package worlddiscovery.benchmark

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry comparison metrics -- protocol §G. None of these is used alone; a caller combines all
 * four into one [GeometryMetrics] row. Pure functions, no I/O, no engine-specific knowledge.
 *
 * 2B-1B correction fixes applied here (protocol correction §13):
 * - haversine `h` is clamped to `[0,1]` before `sqrt`/`asin` -- floating-point rounding could
 *   otherwise push it fractionally above 1.0 for near-antipodal or near-identical points, making
 *   `asin` return `NaN`.
 * - the equirectangular local projection used by [distanceToSegmentMeters] now computes longitude
 *   as a wrapped delta from the segment's own reference point ([wrapLonDeltaDegrees]), instead of
 *   projecting absolute longitude directly -- the old code silently produced a ~40,000km distance
 *   for two points a few meters apart on either side of the antimeridian (lon +179.9999 vs
 *   -179.9999), because `Math.toRadians(pt.lon)` does not wrap.
 * - [routeOverlapFraction] is now length-weighted: it densifies the reference polyline to a fixed
 *   spacing ([densify]) before counting coverage, instead of counting raw input vertices. The old
 *   vertex-count version silently over- or under-weighted a route depending on how densely its
 *   *input* polyline happened to be vertexed (e.g. OSRM's ~700-point dense geometry vs Valhalla's
 *   36 per-observation points for the same route) -- a benchmark-invalidating defect, since it
 *   made overlap% incomparable across engines that return different geometry densities for the
 *   same real route.
 * - [hausdorffMetersDensified]/[discreteFrechetMetersDensified] densify both inputs to the SAME
 *   spacing before comparing, for the same reason -- Fréchet distance in particular is sensitive to
 *   point-index alignment, so comparing a 36-point trace against a 722-point trace directly (as
 *   Phase 2B-1's smoke test did, see its own documented caveat) is not a fair comparison. The
 *   un-densified [hausdorffMeters]/[discreteFrechetMeters] are kept as the tested primitives (used
 *   directly by [GeometryMetricsTest]'s exact small synthetic cases); real benchmark runs should
 *   use the densified variants.
 */
object GeometryComparison {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun haversineMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = (sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLon / 2).pow(2)).coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }

    /** Wraps a longitude delta (in degrees, `b - a`) into `(-180, 180]` -- e.g. `358.0 -> -2.0` --
     * so a local planar projection referenced at `a` treats `a` and `b` as 2 degrees apart, not
     * 358. */
    fun wrapLonDeltaDegrees(deltaDeg: Double): Double {
        var d = deltaDeg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /** Normalizes an absolute longitude into `(-180, 180]`. */
    fun normalizeLonDegrees(lonDeg: Double): Double {
        var d = lonDeg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /** Linearly interpolates longitude from `aLon` to `bLon` at `t` in `[0,1]`, wrapping across the
     * antimeridian correctly (e.g. interpolating from 179.9 to -179.9 moves *forward* through 180,
     * not backward across the whole globe). Shared by [densify] and `H3Comparison.sampleToH3Cells`
     * so every place that walks along a segment uses the same antimeridian-safe rule. */
    fun interpolateLon(aLon: Double, bLon: Double, t: Double): Double =
        normalizeLonDegrees(aLon + wrapLonDeltaDegrees(bLon - aLon) * t)

    /** Distance from [point] to the closest point on any segment of [polyline] (perpendicular
     * distance to the nearest segment, not just to the nearest vertex). */
    fun distanceToPolylineMeters(point: LatLon, polyline: List<LatLon>): Double {
        require(polyline.isNotEmpty()) { "polyline must not be empty" }
        if (polyline.size == 1) return haversineMeters(point, polyline.first())
        var best = Double.MAX_VALUE
        for (i in 0 until polyline.size - 1) {
            best = min(best, distanceToSegmentMeters(point, polyline[i], polyline[i + 1]))
        }
        return best
    }

    /** Approximates the segment as locally planar (equirectangular projection centered on [a],
     * with longitude measured as an antimeridian-wrapped delta from [a] -- see class-level doc
     * comment) -- adequate at the scale of a single road segment; not intended for spans of
     * hundreds of kilometers. */
    private fun distanceToSegmentMeters(p: LatLon, a: LatLon, b: LatLon): Double {
        val latRef = Math.toRadians(a.lat)
        fun toXy(pt: LatLon): Pair<Double, Double> {
            val lonDeltaDeg = wrapLonDeltaDegrees(pt.lon - a.lon)
            val x = Math.toRadians(lonDeltaDeg) * cos(latRef) * EARTH_RADIUS_METERS
            val y = Math.toRadians(pt.lat) * EARTH_RADIUS_METERS
            return x to y
        }
        val (px, py) = toXy(p)
        val (ax, ay) = toXy(a)
        val (bx, by) = toXy(b)
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * abx + (py - ay) * aby) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * abx
        val projY = ay + t * aby
        val dx = px - projX
        val dy = py - projY
        return sqrt(dx * dx + dy * dy)
    }

    /** Discrete Hausdorff distance between two polylines: the worst-case nearest-point distance,
     * checked in both directions. Sensitive to a single bad excursion in either trace, and to the
     * vertex density of its inputs -- see [hausdorffMetersDensified] for a density-fair variant. */
    fun hausdorffMeters(a: List<LatLon>, b: List<LatLon>): Double {
        require(a.isNotEmpty() && b.isNotEmpty()) { "both polylines must be non-empty" }
        val aToB = a.maxOf { distanceToPolylineMeters(it, b) }
        val bToA = b.maxOf { distanceToPolylineMeters(it, a) }
        return max(aToB, bToA)
    }

    /** Discrete Fréchet distance (classic dynamic-programming formulation) -- unlike Hausdorff,
     * respects the order points are visited along each curve, not just spatial proximity. O(n*m),
     * and (unlike Hausdorff) sensitive to how densely each input curve is vertexed -- see
     * [discreteFrechetMetersDensified] for a density-fair variant.
     *
     * Computed bottom-up (row by row), NOT via the textbook top-down memoized recursion --
     * 2B-1B correction testing caught the recursive form throwing `StackOverflowError` once inputs
     * are densified to a realistic route length (recursion depth is `O(n+m)`, and the JVM's
     * default stack cannot hold a call chain thousands of frames deep with one frame per DP cell
     * on the critical path). The iterative form computes the exact same recurrence with `O(m)`
     * auxiliary memory (two rolling rows) and no recursion, so it has no depth limit tied to input
     * size. */
    fun discreteFrechetMeters(a: List<LatLon>, b: List<LatLon>): Double {
        require(a.isNotEmpty() && b.isNotEmpty()) { "both polylines must be non-empty" }
        val n = a.size
        val m = b.size
        var prevRow = DoubleArray(m)
        var currRow = DoubleArray(m)
        for (i in 0 until n) {
            for (j in 0 until m) {
                val d = haversineMeters(a[i], b[j])
                currRow[j] = when {
                    i == 0 && j == 0 -> d
                    i > 0 && j == 0 -> max(prevRow[0], d)
                    i == 0 && j > 0 -> max(currRow[j - 1], d)
                    else -> max(minOf(prevRow[j], prevRow[j - 1], currRow[j - 1]), d)
                }
            }
            val tmp = prevRow
            prevRow = currRow
            currRow = tmp
        }
        return prevRow[m - 1]
    }

    /** Resamples [polyline] so consecutive points are at most [spacingMeters] apart along its own
     * length, using [ceil] (never floor/`toInt`) to compute the step count for each source segment
     * -- so the resulting spacing is always `<= spacingMeters`, never larger (the same rounding
     * defect class fixed in `H3Comparison.sampleToH3Cells`, see its doc comment). Original vertices
     * are always preserved; only interpolated points are inserted between them. Longitude is
     * interpolated via [interpolateLon] (antimeridian-safe). Used to put two polylines of different
     * native vertex density on a comparable footing before measuring distance/overlap between them
     * (correction §13.B: "densify geometries using the SAME deterministic spacing policy"). */
    fun densify(polyline: List<LatLon>, spacingMeters: Double): List<LatLon> {
        require(spacingMeters > 0.0) { "spacingMeters must be > 0" }
        if (polyline.size < 2) return polyline
        val result = ArrayList<LatLon>(polyline.size * 4)
        result.add(polyline.first())
        for (i in 0 until polyline.size - 1) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val segmentLength = haversineMeters(a, b)
            val steps = ceil(segmentLength / spacingMeters).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                result.add(LatLon(lat = a.lat + (b.lat - a.lat) * t, lon = interpolateLon(a.lon, b.lon, t)))
            }
        }
        return result
    }

    /** [hausdorffMeters] with both inputs densified to the same [spacingMeters] first -- see
     * class-level doc comment for why this matters when comparing engines whose native output
     * geometry has very different vertex density for the same real route. */
    fun hausdorffMetersDensified(a: List<LatLon>, b: List<LatLon>, spacingMeters: Double): Double =
        hausdorffMeters(densify(a, spacingMeters), densify(b, spacingMeters))

    /** [discreteFrechetMeters] with both inputs densified to the same [spacingMeters] first. Cost
     * is `O(n*m)` in the densified point counts -- for a multi-kilometer route at a few meters of
     * spacing this can be a genuinely large matrix; no optimization attempted here (correction §22:
     * "no premature optimization"), just documented as a real, currently-unaddressed performance
     * cost that a large real corpus run may need to revisit. */
    fun discreteFrechetMetersDensified(a: List<LatLon>, b: List<LatLon>, spacingMeters: Double): Double =
        discreteFrechetMeters(densify(a, spacingMeters), densify(b, spacingMeters))

    /** Mean perpendicular distance from every point of [matched] to the nearest segment of
     * [reference] -- how far, on average, the matched geometry strays from the reference path. */
    fun meanAlongTrackDeviationMeters(matched: List<LatLon>, reference: List<LatLon>): Double {
        require(matched.isNotEmpty()) { "matched geometry must not be empty" }
        return matched.map { distanceToPolylineMeters(it, reference) }.average()
    }

    /** Length-weighted proportion (0.0-1.0) of [reference]'s own length that falls within
     * [thresholdMeters] of the matched geometry -- "how much of the real path, by distance
     * travelled, got covered". [reference] is first densified to [densificationSpacingMeters] so
     * the count of covered sample points approximates covered *length*, regardless of how sparsely
     * or densely [reference]'s own input vertices happen to be placed (2B-1B correction §13.A --
     * see class-level doc comment for why the previous raw-vertex-count version was invalid). */
    fun routeOverlapFraction(
        reference: List<LatLon>,
        matched: List<LatLon>,
        thresholdMeters: Double,
        densificationSpacingMeters: Double = 5.0,
    ): Double {
        if (reference.isEmpty() || matched.isEmpty()) return 0.0
        val densified = densify(reference, densificationSpacingMeters)
        val within = densified.count { distanceToPolylineMeters(it, matched) <= thresholdMeters }
        return within.toDouble() / densified.size
    }
}
