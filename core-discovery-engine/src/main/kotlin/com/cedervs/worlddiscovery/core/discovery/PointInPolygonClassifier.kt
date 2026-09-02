package com.cedervs.worlddiscovery.core.discovery

/**
 * Pure ray-casting (even-odd rule) point-in-polygon test — no external geometry library, no
 * Android/MapLibre dependency, directly testable with plain coordinates.
 *
 * **Antimeridian note**: a *naive* ray-cast (comparing raw stored longitudes directly) breaks for
 * a ring that crosses ±180° — e.g. an edge from `179.9°` to `-179.9°` is geometrically `0.2°` wide,
 * but a naive interpolation between the two raw values spans it the *long* way, through `0°`
 * longitude, `358°` wide, silently corrupting every crossing test involving that edge. This is
 * fixed in two phases, applied identically to the outer ring and every hole ring (both go through
 * the same [containsInRing]):
 *
 * 1. [unwrapRingLongitudes] makes the *ring itself* internally consistent — each vertex is
 *    cumulatively re-expressed relative to the previous one so no single edge is ever represented
 *    as spanning more than 180° (mirrors [computeGeographicBounds]'s own per-ring unwrap). A real,
 *    simple (non-self-intersecting), single-antimeridian-crossing ring's cumulative offset always
 *    returns to a multiple of 360° by its last vertex — the same vertex the closing edge (last
 *    back to first) reconnects to — so that closing edge stays short too, with no extra handling
 *    needed.
 * 2. [point]'s own longitude is unwrapped *once*, against that now-internally-consistent ring's own
 *    frame (anchored on its first vertex) — not per edge. Anchoring per edge instead (an earlier,
 *    incorrect version of this function did exactly that) breaks down as soon as a ring has *two or
 *    more* antimeridian-adjacent edges: each edge would independently decide "the point is on my
 *    east/west side" using a different local frame, and those per-edge verdicts no longer add up to
 *    a globally consistent crossing count. Anchoring once, against the whole ring, keeps every
 *    edge's crossing test comparable to every other edge's.
 *
 * This is invariant to ring winding direction (reversing a ring's vertex order changes which vertex
 * [unwrapRingLongitudes] starts from and which endpoint of each edge is "previous", but the *set*
 * of edges — and the true shape they describe — is unchanged, so a genuinely interior/exterior
 * point classifies the same way regardless of orientation; only exact-boundary points can differ,
 * per the contract below, as they can in any ray-casting implementation).
 *
 * **Boundary-point contract**: a point exactly on a ring edge or vertex has no single "correct"
 * inside/outside answer geometrically, but this implementation is fully deterministic — the same
 * coordinates, ring, and orientation always produce the same result on every call, using strict
 * `<`/`>` throughout (the standard half-open ray-casting convention, which is also what avoids
 * double-counting a ray that passes exactly through a vertex shared by two edges).
 */
object PointInPolygonClassifier {

    /** True if [point] lies inside [multiPolygon] — inside *any* of its constituent polygons
     * (never requires all of them; a MultiPolygon's parts are independent alternatives, not a
     * shape you must be inside every part of). */
    fun contains(multiPolygon: GeographicMultiPolygon, point: Coordinate): Boolean =
        multiPolygon.polygons.any { polygon -> contains(polygon, point) }

    /** True if [point] lies inside [polygon]'s outer ring and outside every hole ring. */
    fun contains(polygon: GeographicPolygon, point: Coordinate): Boolean {
        if (!containsInRing(polygon.outerRing, point)) return false
        return polygon.holes.none { hole -> containsInRing(hole, point) }
    }

    /**
     * The standard even-odd ray-casting test: count how many edges of [ring] a ray extending
     * eastward from [point] (at [point]'s own latitude) crosses. An odd count means [point] is
     * inside; even means outside. [ring] need not be closed (the first vertex need not repeat at
     * the end) — the wraparound edge (last vertex back to first) is included explicitly below.
     */
    private fun containsInRing(ring: GeographicRing, point: Coordinate): Boolean {
        if (ring.size < 3) return false

        val unwrappedRing = unwrapRingLongitudes(ring)
        val pointLongitude = unwrapLongitudeNearAnchor(point.longitude, unwrappedRing.first().longitude)

        var inside = false
        var previous = unwrappedRing.last()
        for (current in unwrappedRing) {
            val crossesLatitude = (current.latitude > point.latitude) != (previous.latitude > point.latitude)
            if (crossesLatitude) {
                val intersectionLongitude = current.longitude +
                    (point.latitude - current.latitude) /
                    (previous.latitude - current.latitude) *
                    (previous.longitude - current.longitude)
                if (pointLongitude < intersectionLongitude) {
                    inside = !inside
                }
            }
            previous = current
        }
        return inside
    }

    /** An unwrapped ring vertex — deliberately **not** [Coordinate]: [Coordinate] enforces
     * `longitude in [-180, 180]`, but an unwrapped longitude legitimately falls outside that range
     * by design (e.g. `181°`, representing the same point as raw `-179°` in a ring's own
     * consistently-unwrapped frame) — that's the entire point of unwrapping. */
    private data class UnwrappedPoint(val longitude: Double, val latitude: Double)

    /** Phase 1 — see the class doc comment. Cumulatively re-expresses [ring]'s longitudes (latitude
     * untouched) so consecutive vertices are never more than 180° apart, exactly like
     * [computeGeographicBounds]'s own per-ring unwrap; an independent, self-contained pass over
     * [ring] alone, with no dependency on any test point. */
    private fun unwrapRingLongitudes(ring: GeographicRing): List<UnwrappedPoint> {
        val unwrapped = ArrayList<UnwrappedPoint>(ring.size)
        var offset = 0.0
        var previousRawLongitude = ring.first().longitude
        for (coordinate in ring) {
            val rawLongitude = coordinate.longitude
            val delta = rawLongitude - previousRawLongitude
            if (delta > 180.0) offset -= 360.0 else if (delta < -180.0) offset += 360.0
            previousRawLongitude = rawLongitude
            unwrapped.add(UnwrappedPoint(latitude = coordinate.latitude, longitude = rawLongitude + offset))
        }
        return unwrapped
    }

    /** Phase 2 — see the class doc comment. Re-expresses [longitude] as the representative within
     * `[anchor - 180, anchor + 180)`, regardless of how far apart the two raw values are
     * numerically. */
    private fun unwrapLongitudeNearAnchor(longitude: Double, anchor: Double): Double {
        val delta = ((longitude - anchor + 180.0).mod(360.0)) - 180.0
        return anchor + delta
    }
}
