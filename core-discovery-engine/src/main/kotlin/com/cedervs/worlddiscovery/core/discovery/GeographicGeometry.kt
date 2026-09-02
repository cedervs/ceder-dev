package com.cedervs.worlddiscovery.core.discovery

/**
 * One polygon ring — an ordered list of vertices. Not required to be closed (first == last);
 * callers close it themselves if their consumer requires it, matching [H3CellConverter.cellBoundary]'s
 * existing convention in this module.
 */
typealias GeographicRing = List<Coordinate>

/**
 * A single polygon: [rings].first() is the outer boundary; any further rings are holes. France's
 * real Natural-Earth-derived geometry has no holes (verified directly against the source data),
 * but the shape supports them from the start rather than assuming a single-ring polygon
 * structurally — the same "don't overbuild, but don't paint into a corner" balance already applied
 * elsewhere in this module.
 */
data class GeographicPolygon(val rings: List<GeographicRing>) {
    init {
        require(rings.isNotEmpty()) { "A polygon must have at least one ring (the outer boundary)" }
        rings.forEachIndexed { index, ring ->
            require(ring.size >= 3) {
                "Ring $index has only ${ring.size} point(s); a polygon ring needs at least 3 to enclose an area"
            }
        }
    }

    val outerRing: GeographicRing get() = rings[0]
    val holes: List<GeographicRing> get() = rings.drop(1)
}

/**
 * A geographic area's geometry — genuinely a *multi*-polygon from the start, never assumed to be
 * a single contiguous shape. France's own real geometry already forces this: Natural Earth's
 * `ADMIN=France` feature is three disjoint polygons (mainland Europe, Corsica, and French Guiana
 * in South America — confirmed by inspecting the real source data, not assumed), so this type is
 * exercised by real data immediately, not merely future-proofed against a hypothetical case.
 */
data class GeographicMultiPolygon(val polygons: List<GeographicPolygon>) {
    init {
        require(polygons.isNotEmpty()) { "A GeographicMultiPolygon must have at least one polygon" }
    }
}

/**
 * A simple lat/lon bounding box — south-west and north-east corners — suitable for a map
 * "fit bounds" camera operation. Deliberately **not** computed via naive per-coordinate
 * longitude min/max across all rings: see [computeGeographicBounds]'s doc comment for why that
 * breaks for any polygon crossing the antimeridian (±180°), even though France's own geometry
 * happens not to.
 */
data class GeographicBounds(
    val southWestLatitude: Double,
    val southWestLongitude: Double,
    val northEastLatitude: Double,
    val northEastLongitude: Double,
) {
    init {
        require(southWestLatitude.isFinite() && southWestLatitude in -90.0..90.0) {
            "Invalid southWestLatitude: $southWestLatitude"
        }
        require(northEastLatitude.isFinite() && northEastLatitude in -90.0..90.0) {
            "Invalid northEastLatitude: $northEastLatitude"
        }
        require(southWestLatitude <= northEastLatitude) {
            "southWestLatitude ($southWestLatitude) must not exceed northEastLatitude ($northEastLatitude)"
        }
        require(southWestLongitude.isFinite()) { "Invalid southWestLongitude: $southWestLongitude" }
        require(northEastLongitude.isFinite()) { "Invalid northEastLongitude: $northEastLongitude" }
        require(southWestLongitude <= northEastLongitude) {
            "southWestLongitude ($southWestLongitude) must not exceed northEastLongitude " +
                "($northEastLongitude) -- computeGeographicBounds always produces a single " +
                "consistently-unwrapped frame where this holds; a caller constructing GeographicBounds " +
                "directly must do the same"
        }
    }
}

private const val FULL_CIRCLE_DEGREES = 360.0

/**
 * Computes the smallest lat/lon bounding box covering every polygon in [multiPolygon] —
 * genuinely antimeridian-safe for the *whole* MultiPolygon at once, not per-ring independently.
 *
 * **Why naive min/max breaks**: a ring that crosses ±180° (e.g. a Fiji- or Alaska/Russia-adjacent
 * shape) has vertices near `+179.9` and `-179.9` that are geometrically a fraction of a degree
 * apart, but a plain numeric `min`/`max` across the raw values would compute a bounding box
 * spanning nearly the entire globe (`[-179.9, 179.9]`) instead of the true, narrow box actually
 * containing the ring.
 *
 * **Why a per-ring-only unwrap (this function's previous implementation) also breaks**: two
 * *separate* polygon components can each independently unwrap "cleanly" (no single ring individually
 * crosses ±180°) yet still, together, straddle the antimeridian — e.g. one component's vertices sit
 * at +170..+179 and another's sit at -179..-170: neither ring needs any internal unwrap, but naively
 * unioning their raw `min`/`max` still produces the wrong, nearly-global `[-179, 179]` box instead of
 * the true ~20°-wide box spanning the dateline. A per-ring fix cannot catch this, because the
 * problem is between rings, not within one.
 *
 * **The actual fix — smallest enclosing circular arc**: every ring's outer-boundary vertices
 * (holes are strictly interior to their own outer ring and never extend the overall box, so they're
 * excluded) are pooled into one flat set of longitudes, normalized into `[-180, 180)`. Longitudes on
 * a circle of circumference 360° are sorted, and the single largest circular gap between consecutive
 * values (wrapping from the largest back to the smallest) is found — this gap is the widest stretch
 * of the globe containing none of the input points. Its complement (going forward from right after
 * the gap to right before it) is, by construction, the smallest arc containing every point — this is
 * the standard, mathematically correct solution to "smallest enclosing arc on a circle", and it
 * naturally subsumes the single-ring-crossing case (that's just one specific input configuration)
 * without needing any separate per-ring pass. The result is expressed as a single consistently
 * unwrapped `[west, east]` frame with `east >= west` (an `east` past `180°`, e.g. `181°`, is a
 * genuinely wider-than-normal but still valid, directly `LatLngBounds`-usable value — see
 * `CountryOverlayCameraFit.kt`).
 *
 * Latitude has no such wraparound concept (the poles are hard bounds, not a cycle), so it stays a
 * plain `min`/`max` over the same pooled outer-ring vertices.
 */
fun computeGeographicBounds(multiPolygon: GeographicMultiPolygon): GeographicBounds {
    val outerRingVertices = multiPolygon.polygons.flatMap { polygon -> polygon.outerRing }
    require(outerRingVertices.isNotEmpty()) { "Cannot compute bounds: every polygon's outer ring is empty" }

    val minLat = outerRingVertices.minOf { it.latitude }
    val maxLat = outerRingVertices.maxOf { it.latitude }

    val normalizedLongitudes = outerRingVertices.map { normalizeLongitude(it.longitude) }.sorted()
    val (west, east) = smallestEnclosingLongitudeArc(normalizedLongitudes)

    return GeographicBounds(
        southWestLatitude = minLat,
        southWestLongitude = west,
        northEastLatitude = maxLat,
        northEastLongitude = east,
    )
}

/** Maps any finite longitude onto its representative in `[-180, 180)`. */
private fun normalizeLongitude(longitude: Double): Double {
    val normalized = (longitude + 180.0).mod(FULL_CIRCLE_DEGREES) - 180.0
    // Guards the exact boundary: (180.0).mod(360.0) == 0.0, so an input of exactly -180.0 would
    // otherwise map to -180.0 correctly, but floating point on inputs like 180.0 itself must land
    // on -180.0 (the canonical representative), never drift to +180.0 (outside the half-open range).
    return if (normalized >= 180.0) normalized - FULL_CIRCLE_DEGREES else normalized
}

/**
 * Given at least one longitude already normalized into `[-180, 180)`, returns `(west, east)` for
 * the smallest circular arc enclosing all of them, in one consistently unwrapped frame
 * (`east >= west`, `east` possibly `> 180`). See [computeGeographicBounds]'s doc comment for the
 * "largest gap" reasoning this implements.
 */
internal fun smallestEnclosingLongitudeArc(sortedNormalizedLongitudes: List<Double>): Pair<Double, Double> {
    require(sortedNormalizedLongitudes.isNotEmpty()) { "Need at least one longitude" }
    if (sortedNormalizedLongitudes.size == 1) {
        val only = sortedNormalizedLongitudes.single()
        return only to only
    }

    var largestGap = Double.NEGATIVE_INFINITY
    var largestGapEndsBeforeIndex = -1 // the gap sits between this index and the next (circularly)
    for (i in sortedNormalizedLongitudes.indices) {
        val current = sortedNormalizedLongitudes[i]
        val next = if (i == sortedNormalizedLongitudes.lastIndex) {
            sortedNormalizedLongitudes[0] + FULL_CIRCLE_DEGREES
        } else {
            sortedNormalizedLongitudes[i + 1]
        }
        val gap = next - current
        if (gap > largestGap) {
            largestGap = gap
            largestGapEndsBeforeIndex = i
        }
    }

    val westIndex = (largestGapEndsBeforeIndex + 1) % sortedNormalizedLongitudes.size
    val west = sortedNormalizedLongitudes[westIndex]
    val eastRaw = sortedNormalizedLongitudes[largestGapEndsBeforeIndex]
    val east = if (eastRaw < west) eastRaw + FULL_CIRCLE_DEGREES else eastRaw
    return west to east
}
