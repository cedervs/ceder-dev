package com.cedervs.worlddiscovery.core.discovery

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius in meters (WGS84 mean radius) — a physical constant, not a product/
 * calibration value. */
private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * Great-circle distance between two [Coordinate]s in meters, via the standard haversine formula.
 * Deliberately a plain geometric utility, independent of H3 — it operates purely on raw
 * latitude/longitude, not on any H3 concept, so it does not belong in [H3CellConverter] or
 * [H3GridTraversal] (both stay narrowly scoped to their own H3 responsibilities). Exists to
 * support debug-only transition diagnostics (delta distance/implied speed between two consecutive
 * observations) without depending on H3 for a computation that has nothing to do with the H3 grid.
 */
fun haversineDistanceMeters(from: Coordinate, to: Coordinate): Double {
    val fromLatRad = Math.toRadians(from.latitude)
    val toLatRad = Math.toRadians(to.latitude)
    val deltaLatRad = Math.toRadians(to.latitude - from.latitude)
    val deltaLngRad = Math.toRadians(to.longitude - from.longitude)

    val rawA = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
        cos(fromLatRad) * cos(toLatRad) * sin(deltaLngRad / 2) * sin(deltaLngRad / 2)
    // Mathematically a lies in [0, 1], but floating-point rounding can push it a hair outside
    // that range for near-antipodal or antimeridian-crossing pairs, which would otherwise hand
    // sqrt(1 - a) a negative input and produce NaN. Clamp before either sqrt, not after.
    val a = rawA.coerceIn(0.0, 1.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

/**
 * The point reached by travelling [distanceMeters] from [from] along initial great-circle
 * [bearingDegrees] (clockwise from true north; any real value, not restricted to `[0, 360)` —
 * periodic by construction since only its `sin`/`cos` are used). The direct/forward counterpart
 * to [haversineDistanceMeters]'s inverse problem: given a start point, direction, and distance,
 * where do you end up. Deliberately generic geographic math, not tied to any particular caller —
 * same rationale as [haversineDistanceMeters] for staying independent of H3/[H3CellConverter].
 *
 * `distanceMeters = 0` returns [from] unchanged (mathematically exact, not special-cased). At an
 * exact pole, initial bearing is not well-defined (every direction points toward the equator) —
 * the formula still resolves to a determinate, non-crashing result (never `NaN`, never throws),
 * it just doesn't carry a meaningful bearing at that singular point; this is an inherent property
 * of the spherical direct-geodesic formula, not a defect. The returned [Coordinate]'s longitude is
 * always normalized into `[-180, 180]` before construction (`Coordinate`'s own validation would
 * otherwise reject an unnormalized value crossing the antimeridian), and latitude is always within
 * `[-90, 90]` by construction (`asin`'s range), so this can never throw for any finite, real input.
 */
fun destinationPoint(from: Coordinate, bearingDegrees: Double, distanceMeters: Double): Coordinate {
    val angularDistance = distanceMeters / EARTH_RADIUS_METERS
    val bearingRad = Math.toRadians(bearingDegrees)
    val fromLatRad = Math.toRadians(from.latitude)
    val fromLngRad = Math.toRadians(from.longitude)

    val toLatRad = asin(
        sin(fromLatRad) * cos(angularDistance) +
            cos(fromLatRad) * sin(angularDistance) * cos(bearingRad),
    )
    val toLngRad = fromLngRad + atan2(
        sin(bearingRad) * sin(angularDistance) * cos(fromLatRad),
        cos(angularDistance) - sin(fromLatRad) * sin(toLatRad),
    )

    return Coordinate(
        latitude = Math.toDegrees(toLatRad),
        longitude = normalizeLongitudeDegrees(Math.toDegrees(toLngRad)),
    )
}

/** Wraps an arbitrary-magnitude longitude in degrees into `(-180, 180]`, handling the
 * antimeridian crossing that a raw [destinationPoint] computation can produce. */
private fun normalizeLongitudeDegrees(longitudeDegrees: Double): Double {
    var normalized = longitudeDegrees % 360.0
    if (normalized > 180.0) normalized -= 360.0
    if (normalized <= -180.0) normalized += 360.0
    return normalized
}
