package com.cedervs.worlddiscovery.core.discovery

/**
 * A deterministic, **verified** point-on-surface / interior-point helper — replaces an earlier,
 * unsafe implementation that used the raw arithmetic average of an outer ring's vertices as a
 * "representative point" and trusted it without checking. That is not a guaranteed interior point:
 * it can fall outside the ring for a concave polygon, inside a hole, or (for a naively-averaged
 * antimeridian-crossing ring) somewhere wildly wrong on the opposite side of the globe. A Codex
 * review caught this — see `PromoteAncestorPresence.kt`'s own doc comment for where the result is
 * consumed.
 *
 * **The contract this file follows, and asks every caller to trust:** [findVerifiedInteriorPoint]
 * never returns an unverified guess. Every candidate point it considers is checked against
 * [PointInPolygonClassifier] — the same authoritative containment test used everywhere else in this
 * module — before being returned; a candidate that fails verification is simply discarded, never
 * "close enough." If [area] has no polygon component for which any candidate strategy below finds a
 * verified interior point, this returns `null` — **never a guess** — and callers must treat that as
 * "no representative point could be determined for this area," not as an error to work around with a
 * fallback value.
 *
 * **Searches every polygon component, in order — never assumes the largest is correct.** A prior
 * version of this idea (superseded before it ever shipped) assumed `polygons[0]` (the generator's own
 * "largest first" ordering — see `tools/geo/GenerateFranceAdministrativeReference.kt`) was always the
 * right component to use. That is true for every one of this app's own bundled data today (see
 * `InteriorPointFinderRealDataTest`'s own regression proof for all 13 metropolitan France regions),
 * but is a **verified France-prototype property, not a worldwide guarantee** — nothing here assumes
 * it. [findVerifiedInteriorPoint] tries `area.geometry.polygons` in their existing deterministic
 * order and returns the first verified interior point found in *any* of them.
 *
 * **Candidate strategies, tried per polygon in increasing cost/generality, each independently
 * verified:**
 * 1. [boundsCenterCandidate] — the polygon's own antimeridian-safe bounding-box midpoint
 *    ([computeGeographicBounds] applied to just this one polygon, then re-normalized back into a
 *    valid [Coordinate] longitude via [normalizeLongitude]). Cheap, and correct for the common case
 *    (a roughly convex, hole-free shape) — including antimeridian-crossing ones, since
 *    [computeGeographicBounds] is itself antimeridian-safe.
 * 2. [polygonAreaCentroid] — the true signed-area centroid of the outer ring (the standard polygon
 *    centroid formula, genuinely different from and more robust than a plain vertex average, which is
 *    biased by vertex density rather than weighted by area) — still not *guaranteed* inside for a
 *    strongly concave shape, but verified before use regardless, exactly like every other candidate
 *    here. Deliberately not antimeridian-aware on its own (raw longitude differencing across the
 *    dateline would be meaningless) — safe anyway, because an antimeridian-crossing polygon's
 *    candidate #1 already succeeds and this is never reached, and any genuinely bad candidate this
 *    formula could produce simply fails verification like any other.
 * 3. [scanlineInteriorCandidates] — the robust fallback for concave shapes and holes: several
 *    horizontal scan rows through the outer ring's own latitude span, each ray-cast against every
 *    edge to find alternating inside/outside longitude spans (the same principle
 *    [PointInPolygonClassifier] itself uses), yielding the midpoint of each span as a candidate. A
 *    midpoint of a genuine inside-span of a *simple* (non-self-intersecting) ring is geometrically
 *    guaranteed to be inside that ring — but this fallback only ever tests the *outer* ring, so a
 *    span midpoint can still legitimately land inside a *hole* (holes are not considered when
 *    generating scanline candidates) — which is exactly why every candidate, from every strategy,
 *    still goes through the same verification step before being trusted: a hole-interior candidate
 *    simply fails verification and the search continues to the next candidate/row/polygon.
 */
fun findVerifiedInteriorPoint(area: GeographicArea): Coordinate? {
    for (polygon in area.geometry.polygons) {
        val candidate = findVerifiedInteriorPoint(polygon)
        if (candidate != null) return candidate
    }
    return null
}

/** Same contract as [findVerifiedInteriorPoint], scoped to a single [GeographicPolygon] — exposed
 * separately so the interior-point search over one polygon's own candidates is independently
 * testable without needing a full [GeographicArea] wrapper. */
internal fun findVerifiedInteriorPoint(polygon: GeographicPolygon): Coordinate? {
    val candidates = buildList {
        boundsCenterCandidate(polygon)?.let(::add)
        polygonAreaCentroid(polygon.outerRing)?.let(::add)
        addAll(scanlineInteriorCandidates(polygon.outerRing))
    }
    return candidates.firstOrNull { candidate -> PointInPolygonClassifier.contains(polygon, candidate) }
}

/** Candidate 1 — see [findVerifiedInteriorPoint]'s own doc comment. `null` only in the defensive,
 * should-never-happen case where the computed midpoint itself falls outside a valid
 * [Coordinate]'s own range (a genuinely degenerate input). */
private fun boundsCenterCandidate(polygon: GeographicPolygon): Coordinate? {
    val outerOnly = GeographicMultiPolygon(listOf(GeographicPolygon(rings = listOf(polygon.outerRing))))
    val bounds = computeGeographicBounds(outerOnly)
    val midLatitude = (bounds.southWestLatitude + bounds.northEastLatitude) / 2.0
    val midLongitude = normalizeLongitude((bounds.southWestLongitude + bounds.northEastLongitude) / 2.0)
    if (midLatitude !in -90.0..90.0 || midLongitude !in -180.0..180.0) return null
    return Coordinate(latitude = midLatitude, longitude = midLongitude)
}

/** Candidate 2 — see [findVerifiedInteriorPoint]'s own doc comment. The standard signed-area
 * polygon centroid formula (treats [ring] as implicitly closed, matching
 * [PointInPolygonClassifier]'s own convention for an unclosed ring). `null` for a degenerate
 * (zero-area, non-finite, or out-of-[Coordinate]-range) result — never a value [Coordinate] itself
 * would reject. */
private fun polygonAreaCentroid(ring: GeographicRing): Coordinate? {
    if (ring.size < 3) return null
    var signedAreaTwice = 0.0
    var centroidXSum = 0.0
    var centroidYSum = 0.0
    for (i in ring.indices) {
        val current = ring[i]
        val next = ring[(i + 1) % ring.size]
        val cross = current.longitude * next.latitude - next.longitude * current.latitude
        signedAreaTwice += cross
        centroidXSum += (current.longitude + next.longitude) * cross
        centroidYSum += (current.latitude + next.latitude) * cross
    }
    val signedArea = signedAreaTwice / 2.0
    if (signedArea == 0.0 || !signedArea.isFinite()) return null
    val centroidLongitude = centroidXSum / (6.0 * signedArea)
    val centroidLatitude = centroidYSum / (6.0 * signedArea)
    if (!centroidLongitude.isFinite() || !centroidLatitude.isFinite()) return null
    if (centroidLongitude !in -180.0..180.0 || centroidLatitude !in -90.0..90.0) return null
    return Coordinate(latitude = centroidLatitude, longitude = centroidLongitude)
}

/** Fractional positions (of the ring's own latitude span) scanned by [scanlineInteriorCandidates] —
 * deliberately more than one row, and deliberately not just the midpoint, so a concave notch or a
 * centered hole at one row still leaves other rows free to find a genuine interior span. */
private val SCANLINE_ROW_FRACTIONS = listOf(0.5, 0.25, 0.75, 0.1, 0.9, 0.33, 0.66)

/** Candidate 3 — see [findVerifiedInteriorPoint]'s own doc comment. */
private fun scanlineInteriorCandidates(ring: GeographicRing): List<Coordinate> {
    if (ring.size < 3) return emptyList()
    val minLatitude = ring.minOf { it.latitude }
    val maxLatitude = ring.maxOf { it.latitude }
    if (minLatitude == maxLatitude) return emptyList()

    val candidates = mutableListOf<Coordinate>()
    for (fraction in SCANLINE_ROW_FRACTIONS) {
        val scanLatitude = minLatitude + (maxLatitude - minLatitude) * fraction
        val crossingLongitudes = mutableListOf<Double>()
        for (i in ring.indices) {
            val current = ring[i]
            val next = ring[(i + 1) % ring.size]
            val crossesScanLine = (current.latitude > scanLatitude) != (next.latitude > scanLatitude)
            if (crossesScanLine) {
                val t = (scanLatitude - current.latitude) / (next.latitude - current.latitude)
                crossingLongitudes += current.longitude + t * (next.longitude - current.longitude)
            }
        }
        crossingLongitudes.sort()
        var i = 0
        while (i + 1 < crossingLongitudes.size) {
            val midLongitude = (crossingLongitudes[i] + crossingLongitudes[i + 1]) / 2.0
            if (midLongitude in -180.0..180.0 && scanLatitude in -90.0..90.0) {
                candidates += Coordinate(latitude = scanLatitude, longitude = midLongitude)
            }
            i += 2
        }
    }
    return candidates
}
