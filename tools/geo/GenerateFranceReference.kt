import com.cedervs.worlddiscovery.core.discovery.GeographicAreaReferenceJson
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private typealias RawPoint = List<Double> // [lon, lat]
private typealias RawRing = List<RawPoint>

/** One polygon as parsed from source GeoJSON: `rings[0]` is the outer boundary, any further rings
 * are holes — matches [com.cedervs.worlddiscovery.core.discovery.GeographicPolygon]'s own
 * convention, kept as a plain type alias here since this tool is compiled standalone, outside
 * `core-discovery-engine`'s own module. */
private typealias RawPolygon = List<RawRing>

/**
 * **PRODUCT CALIBRATION REQUIRED** — the area (in the same crude flat-degree approximation used
 * throughout this generator, `deltaLon * deltaLat * 111km²`, never geodesically exact and never
 * used for anything user-facing) below which a polygon or hole is treated as raster/classification
 * noise rather than real geography, and dropped. Chosen from real measurement, not guessed: a
 * `geoBoundaries` gbOpen ADM0 spike against France (FRA) and French Guiana (GUF) found real
 * landmasses at 11,694–790,041 km² (this approximation's own scale, not true km²) and every
 * noise/artifact polygon or hole strictly below 46 km² — see `tools/geo/README.md` for the exact
 * measured distribution this threshold was picked from. `50.0` sits with a wide margin on both
 * sides of that real gap, not a value tuned to produce a particular polygon count.
 */
private const val MIN_COMPONENT_AREA_KM2 = 50.0

/**
 * **PRODUCT CALIBRATION REQUIRED** — Douglas-Peucker simplification tolerance, in degrees,
 * applied identically to every component (mainland, Corsica, French Guiana) so the whole France
 * reference is one consistent precision class rather than each component picking its own number.
 * Validated in the geoBoundaries spike: at this tolerance France's own real border stretches
 * (Franco-Swiss/Italian border) keep the overwhelming majority of their vertices (86 -> 79 points
 * in a representative bounding box), while the overall vertex count still drops meaningfully from
 * the untouched source.
 */
private const val SIMPLIFY_TOLERANCE_DEGREES = 0.001

/** Output coordinate precision — 6 decimal degrees is ~11cm, vastly finer than anything visually
 * or classification-relevant at country-map/H3-resolution-12 scale; source coordinates carry far
 * more (sometimes 15+) significant digits that exist only as floating-point noise from upstream
 * processing, never real surveyed precision, and would otherwise inflate the bundled artifact for
 * no visual benefit. */
private const val OUTPUT_COORDINATE_DECIMALS = 6

/**
 * Regenerates `core-discovery-engine/src/main/resources/geo/france-reference.json` from local
 * copies of the pinned `geoBoundaries` gbOpen ADM0 source files for France (`FRA`) and French
 * Guiana (`GUF`) — see `tools/geo/README.md` for the exact reproducible steps (source URLs, pinned
 * commit, licensing per component, how to run this).
 *
 * **Why two source files for one country.** `FRA`'s own gbOpen ADM0 geometry is genuinely only
 * metropolitan France + Corsica (2 polygons; confirmed by direct inspection, not assumed) — it
 * does not include French Guiana at all, unlike the Natural-Earth-based artifact this tool
 * previously generated. `GUF`'s own ADM0 geometry supplies French Guiana instead, at comparable
 * precision, once filtered (see below).
 *
 * **Component identity is derived geometrically, never from upstream polygon position.** `FRA`'s 2
 * significant polygons are told apart by *area* (mainland is roughly 70x Corsica's size in this
 * tool's own approximation — an enormous, unambiguous gap, not a close call), and the final
 * `polygons` list is assembled in an explicit, deliberately chosen order — mainland, then Corsica,
 * then French Guiana — regardless of what order either source file happens to iterate its own
 * features/polygons in. This order is a contract of *this generated artifact*, not a claim that
 * positional indices are a stable, permanent, worldwide component identity — see
 * `GeographicAreaComponent.kt`'s own doc comment.
 *
 * **`GUF` is not safe to use raw.** Its source is not a cartographic/administrative boundary at
 * all — it is `raster2polygon` output from Sentinel-2 10m land-cover classification, dissolved
 * after excluding water pixels (see the `boundarySource` field recorded in this tool's own
 * `sourceVersion`/license text). Direct inspection found 1,812 exterior polygons and 3,815
 * interior holes, of which exactly one exterior polygon (the real landmass, ~99.8% of total polygon
 * area, matching French Guiana's known real area to within 0.5%) and zero holes exceed
 * [MIN_COMPONENT_AREA_KM2] — every hole measured strictly below 46 in this approximation's own
 * units, comfortably under the 50 threshold. [filterSignificantPolygons]/[filterSignificantHoles]
 * apply the exact same threshold to both exterior polygons and interior holes, deliberately one
 * shared calibration value rather than two independently guessed ones — this is not "silently
 * delete all holes": if a future regeneration's largest hole ever exceeds the threshold, it
 * survives.
 */
fun main(args: Array<String>) {
    require(args.size == 3) {
        "Usage: GenerateFranceReference <path-to-geoBoundaries-FRA-ADM0.geojson> " +
            "<path-to-geoBoundaries-GUF-ADM0.geojson> <output-path>"
    }
    val fraFile = File(args[0])
    val gufFile = File(args[1])
    val outputFile = File(args[2])

    val fraPolygons = parseFeaturePolygons(fraFile)
    val gufPolygons = parseFeaturePolygons(gufFile)

    val fraSignificant = filterSignificantPolygons(fraPolygons, MIN_COMPONENT_AREA_KM2)
    require(fraSignificant.size == 2) {
        "Expected exactly 2 significant FRA polygons (mainland + Corsica) at the " +
            "$MIN_COMPONENT_AREA_KM2 km^2 threshold, found ${fraSignificant.size} -- source data or " +
            "the threshold may have changed; re-verify before blindly adjusting this tool."
    }
    val fraByAreaDescending = fraSignificant.sortedByDescending { polygon -> ringAreaKm2(polygon[0]) }
    val mainland = fraByAreaDescending[0] // by far the larger of the two -- never assumed by position
    val corsica = fraByAreaDescending[1]

    val gufSignificant = filterSignificantPolygons(gufPolygons, MIN_COMPONENT_AREA_KM2)
    require(gufSignificant.size == 1) {
        "Expected exactly 1 significant GUF polygon (the main landmass) at the " +
            "$MIN_COMPONENT_AREA_KM2 km^2 threshold, found ${gufSignificant.size} -- source data or " +
            "the threshold may have changed; re-verify before blindly adjusting this tool."
    }
    val guiana = filterSignificantHoles(gufSignificant.single(), MIN_COMPONENT_AREA_KM2)

    // Deliberate, explicit order -- see this file's own doc comment -- never inherited from either
    // source file's own iteration order.
    val finalPolygons: List<List<List<List<Double>>>> = listOf(mainland, corsica, guiana)
        .map { polygon -> simplifyPolygon(polygon, SIMPLIFY_TOLERANCE_DEGREES) }

    val reference = GeographicAreaReferenceJson(
        id = "country:FR",
        type = "COUNTRY",
        displayName = "France",
        sourceId = "geoboundaries",
        sourceVersion = "gbOpen-ADM0@9469f09 (FRA: Wikipedia-derived boundary; " +
            "GUF: Sentinel-2 10m land-cover raster2polygon, processed by geoBoundaries/IMB)",
        // Not "OFFICIAL": geoBoundaries is a genuine, licensed, versioned open dataset, but not an
        // official government boundary authority (e.g. INSEE/IGN for France) -- see
        // GeographicAreaProvenance's own doc comment. Unchanged reasoning from the prior
        // Natural-Earth-based artifact, just now naming the real current source.
        sourceProvenance = "EXTERNAL_REFERENCE_DATASET",
        generatedAt = "2026-09-02",
        // Deliberately states BOTH components' real, DIFFERENT licenses explicitly, rather than
        // collapsing them into one falsely-uniform license string -- see this tool's own doc
        // comment and tools/geo/README.md's "Licensing is not uniform across components" section
        // for why GeographicArea has no per-component provenance field to do this more precisely.
        license = "Mixed per component. Mainland France & Corsica: geoBoundaries gbOpen FRA ADM0, " +
            "CC0 1.0 Universal (Public Domain) -- https://creativecommons.org/publicdomain/zero/1.0/ " +
            "-- no attribution legally required. French Guiana: geoBoundaries gbOpen GUF ADM0, " +
            "Creative Commons Attribution 4.0 International (CC BY 4.0) -- " +
            "https://creativecommons.org/licenses/by/4.0/ -- attribution required: \"Boundary data " +
            "derived from Sentinel-2 (ESA) via geoBoundaries.\" Because CC BY 4.0 is the stricter of " +
            "the two, this ENTIRE bundled artifact must be treated as requiring attribution. See " +
            "tools/geo/README.md for the full source/license breakdown per component.",
        polygons = finalPolygons,
    )

    // Compact, not pretty-printed: the prior Natural-Earth-based artifact (7.8KB) was small enough
    // that pretty-printing's indentation overhead didn't matter; at this artifact's real size,
    // pretty-printing a deeply nested List<List<List<List<Double>>>> inflates the file ~4.5x for
    // no functional benefit (this is a bundled app resource, never hand-edited in place) -- see
    // tools/geo/README.md's "why compact, not pretty-printed" note.
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(Json.encodeToString(GeographicAreaReferenceJson.serializer(), reference))

    println("Wrote ${outputFile.absolutePath} (${outputFile.length()} bytes)")
    println(
        "Components (post-filter, post-simplify): " +
            "mainland(${finalPolygons[0][0].size} outer vertices, ${finalPolygons[0].size - 1} holes), " +
            "corsica(${finalPolygons[1][0].size} outer vertices, ${finalPolygons[1].size - 1} holes), " +
            "guiana(${finalPolygons[2][0].size} outer vertices, ${finalPolygons[2].size - 1} holes)",
    )
    println("Total vertices (post-simplify): ${finalPolygons.sumOf { poly -> poly.sumOf { ring -> ring.size } }}")
}

private fun parseFeaturePolygons(file: File): List<RawPolygon> {
    val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    val features = root["features"]!!.jsonArray
    require(features.size == 1) { "Expected exactly 1 feature in ${file.name}, got ${features.size}" }
    val geometry = features[0].jsonObject["geometry"]!!.jsonObject
    val type = geometry["type"]!!.jsonPrimitive.content
    val coordinates = geometry["coordinates"]!!.jsonArray
    // geoBoundaries emits a plain Polygon for a single-ring country (e.g. Luxembourg) and a
    // MultiPolygon otherwise -- read defensively for either shape rather than assuming one.
    return when (type) {
        "Polygon" -> listOf(readRings(coordinates))
        "MultiPolygon" -> coordinates.map { polygonElement -> readRings(polygonElement.jsonArray) }
        else -> error("Unexpected geometry type in ${file.name}: $type -- source data may have changed shape")
    }
}

private fun readRings(rings: kotlinx.serialization.json.JsonArray): RawPolygon =
    rings.map { ringElement ->
        ringElement.jsonArray.map { pointElement ->
            val point = pointElement.jsonArray
            // Read defensively (only the first two values) rather than assuming exact array
            // length -- matches this tool's own prior convention for Natural Earth.
            listOf(point[0].jsonPrimitive.double, point[1].jsonPrimitive.double)
        }
    }

/** Same crude flat-degree area approximation throughout this file (`deltaLon * deltaLat` scaled by
 * `111 km/degree`) -- adequate, and only ever used, to separate "real landmass scale" from
 * "raster-noise scale" by orders of magnitude; never geodesically exact, never surfaced to a user. */
private fun ringAreaKm2(ring: RawRing): Double {
    var sum = 0.0
    for (i in ring.indices) {
        val (x1, y1) = ring[i]
        val (x2, y2) = ring[(i + 1) % ring.size]
        sum += x1 * y2 - x2 * y1
    }
    return abs(sum) / 2.0 * 111.0 * 111.0
}

private fun filterSignificantPolygons(polygons: List<RawPolygon>, minAreaKm2: Double): List<RawPolygon> =
    polygons.filter { polygon -> ringAreaKm2(polygon[0]) >= minAreaKm2 }

private fun filterSignificantHoles(polygon: RawPolygon, minAreaKm2: Double): RawPolygon =
    listOf(polygon[0]) + polygon.drop(1).filter { hole -> ringAreaKm2(hole) >= minAreaKm2 }

private fun perpendicularDistance(point: RawPoint, lineStart: RawPoint, lineEnd: RawPoint): Double {
    val (x, y) = point
    val (x1, y1) = lineStart
    val (x2, y2) = lineEnd
    val dx = x2 - x1
    val dy = y2 - y1
    if (dx == 0.0 && dy == 0.0) return sqrt((x - x1) * (x - x1) + (y - y1) * (y - y1))
    val t = ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)
    val clampedT = t.coerceIn(0.0, 1.0)
    val projectedX = x1 + clampedT * dx
    val projectedY = y1 + clampedT * dy
    return sqrt((x - projectedX) * (x - projectedX) + (y - projectedY) * (y - projectedY))
}

/** Standard recursive Douglas-Peucker line simplification. */
private fun douglasPeucker(points: RawRing, tolerance: Double): RawRing {
    if (points.size < 3) return points
    var maxDistance = 0.0
    var splitIndex = 0
    val lastIndex = points.size - 1
    for (i in 1 until lastIndex) {
        val distance = perpendicularDistance(points[i], points[0], points[lastIndex])
        if (distance > maxDistance) {
            maxDistance = distance
            splitIndex = i
        }
    }
    return if (maxDistance > tolerance) {
        val left = douglasPeucker(points.subList(0, splitIndex + 1), tolerance)
        val right = douglasPeucker(points.subList(splitIndex, points.size), tolerance)
        left.dropLast(1) + right
    } else {
        listOf(points[0], points[lastIndex])
    }
}

private fun roundTo(value: Double, decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return Math.round(value * factor) / factor
}

private fun simplifyPolygon(polygon: RawPolygon, toleranceDegrees: Double): List<List<List<Double>>> =
    polygon.map { ring ->
        douglasPeucker(ring, toleranceDegrees).map { (lon, lat) ->
            listOf(roundTo(lon, OUTPUT_COORDINATE_DECIMALS), roundTo(lat, OUTPUT_COORDINATE_DECIMALS))
        }
    }
