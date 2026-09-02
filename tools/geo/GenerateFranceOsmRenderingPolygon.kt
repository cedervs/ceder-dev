import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private typealias RawPoint = List<Double> // [lon, lat]
private typealias RawRing = List<RawPoint>
private typealias RawPolygon = List<RawRing>

/**
 * **PHASE G3 rendering-only artifact generator.** Produces
 * `feature-map/src/main/resources/geo/france-mainland-osm-render.json` from a local copy of the
 * France mainland OSM administrative relation (1403916), fetched via
 * `polygons.openstreetmap.fr/get_geojson.py?id=1403916&params=0` — see `tools/geo/README.md` for
 * the reproducible retrieval step.
 *
 * **Deliberately produces a SEPARATE artifact from `GenerateFranceReference.kt`'s own output.**
 * That tool's `core-discovery-engine/.../geo/france-reference.json` remains the CLASSIFICATION
 * source (geoBoundaries, unchanged by this tool) — this generator's output is RENDERING-only, lives
 * in `feature-map`'s own resources, uses a distinct JSON schema
 * ([MainlandFranceRenderingPolygonJson], a single `Polygon`, never a `MultiPolygon`/component list),
 * and is loaded by a completely separate parser (`MainlandFranceRenderingPolygon.kt`). Nothing here
 * touches classification.
 *
 * **Mainland only, by design.** The source relation's `MultiPolygon` may contain several
 * significant polygons (mainland, possibly Corsica, and/or large coastal islands) — this tool keeps
 * only the single largest by area (mainland, by an enormous margin — see
 * `GenerateFranceReference.kt`'s own precedent for why this is never a close call) and discards
 * everything else. Corsica and French Guiana's *rendering* is explicitly out of scope this round —
 * they keep using their existing classification-geometry-as-rendering-geometry path unchanged.
 */
fun main(args: Array<String>) {
    require(args.size == 2) {
        "Usage: GenerateFranceOsmRenderingPolygon <path-to-france-osm-relation.geojson> <output-path>"
    }
    val sourceFile = File(args[0])
    val outputFile = File(args[1])

    val polygons = parseMultiPolygon(sourceFile)
    println("Source multipolygon: ${polygons.size} polygon(s)")
    val byAreaDescending = polygons.sortedByDescending { polygon -> ringAreaKm2(polygon[0]) }
    for ((i, p) in byAreaDescending.withIndex()) {
        println("  polygon[$i]: area~=${"%.0f".format(ringAreaKm2(p[0]))}km^2 (approx), outerVerts=${p[0].size}, holes=${p.size - 1}")
    }
    val mainland = byAreaDescending.first()
    require(byAreaDescending.size < 2 || ringAreaKm2(mainland[0]) > ringAreaKm2(byAreaDescending[1][0]) * 10) {
        "Expected the largest polygon (mainland) to be an unambiguous, order-of-magnitude outlier " +
            "by area -- found a close second-largest, re-verify source data before proceeding blindly."
    }

    val significantHoles = mainland.drop(1).filter { hole -> ringAreaKm2(hole) >= MIN_HOLE_AREA_KM2 }
    println("Mainland outer ring: ${mainland[0].size} vertices; holes: ${mainland.size - 1} total, ${significantHoles.size} significant (>= $MIN_HOLE_AREA_KM2 km^2)")

    val simplifiedOuter = douglasPeucker(mainland[0], SIMPLIFY_TOLERANCE_DEGREES).roundedTo(OUTPUT_COORDINATE_DECIMALS)
    val simplifiedHoles = significantHoles.map { hole -> douglasPeucker(hole, SIMPLIFY_TOLERANCE_DEGREES).roundedTo(OUTPUT_COORDINATE_DECIMALS) }
    val finalPolygon = listOf(simplifiedOuter) + simplifiedHoles

    validateRing(simplifiedOuter, "outer ring")
    simplifiedHoles.forEachIndexed { i, hole -> validateRing(hole, "hole[$i]") }
    println("Post-simplification structural validation passed for outer ring + ${simplifiedHoles.size} hole(s).")

    val artifact = MainlandFranceRenderingPolygonJson(
        sourceId = "openstreetmap",
        sourceVersion = "relation 1403916 (\"France métropolitaine\"), retrieved via " +
            "polygons.openstreetmap.fr/get_geojson.py?id=1403916&params=0 on 2026-09-02; " +
            "validated (point-to-segment, median 6-15m) against real deployed OpenFreeMap/" +
            "OpenMapTiles admin_level=2 boundary tiles, build 20260830_080001_pt, at Geneva/" +
            "Spain/Italy/Andorra/Monaco -- see the Phase G/G2 design-review record (not yet " +
            "transcribed into /docs).",
        sourceProvenance = "EXTERNAL_REFERENCE_DATASET",
        generatedAt = "2026-09-02",
        license = "OpenStreetMap contributors, Open Data Commons Open Database License (ODbL) v1.0 " +
            "-- https://www.openstreetmap.org/copyright -- attribution required " +
            "(\"© OpenStreetMap contributors\"). This is a Derivative Database under ODbL: if " +
            "shipped in a released build, World Discovery must make this specific extracted/" +
            "processed polygon available under ODbL to whoever requests it. Commercial use is " +
            "permitted. Not a classification source -- see core-discovery-engine's own " +
            "france-reference.json (geoBoundaries) for that.",
        polygon = finalPolygon,
    )

    outputFile.parentFile?.mkdirs()
    outputFile.writeText(Json.encodeToString(MainlandFranceRenderingPolygonJson.serializer(), artifact))
    println("Wrote ${outputFile.absolutePath} (${outputFile.length()} bytes)")
    println("Final: outer=${finalPolygon[0].size} vertices, holes=${finalPolygon.size - 1}, total=${finalPolygon.sumOf { it.size }} vertices")
}

@Serializable
private data class MainlandFranceRenderingPolygonJson(
    val sourceId: String,
    val sourceVersion: String,
    val sourceProvenance: String,
    val generatedAt: String,
    val license: String,
    /** Single Polygon -> Ring -> [longitude, latitude]. `polygon[0]` is the outer ring, any further
     * rings are holes -- deliberately never a MultiPolygon/component list: this artifact represents
     * mainland France's rendering geometry only. */
    val polygon: List<List<List<Double>>>,
)

private const val MIN_HOLE_AREA_KM2 = 50.0

/** Tighter than `GenerateFranceReference.kt`'s 0.001 deg (~111m) -- chosen to preserve the
 * demonstrated ~6-15m point-to-segment alignment (Phase G2) rather than simplify it away; still a
 * meaningful reduction from the raw multi-hundred-thousand-vertex source. ~0.0002 deg is ~15-22m at
 * French latitudes, comfortably below the z0-7 country-overlay's own real resolution need
 * (~860 m/pixel at z7, the highest zoom it's ever visible). */
private const val SIMPLIFY_TOLERANCE_DEGREES = 0.0002
private const val OUTPUT_COORDINATE_DECIMALS = 6

private fun parseMultiPolygon(file: File): List<RawPolygon> {
    val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    val type = root["type"]!!.jsonPrimitive.content
    val coordinates = root["coordinates"]!!.jsonArray
    return when (type) {
        "Polygon" -> listOf(readRings(coordinates))
        "MultiPolygon" -> coordinates.map { polygonElement -> readRings(polygonElement.jsonArray) }
        else -> error("Unexpected geometry type in ${file.name}: $type")
    }
}

private fun readRings(rings: kotlinx.serialization.json.JsonArray): RawPolygon =
    rings.map { ringElement ->
        ringElement.jsonArray.map { pointElement ->
            val point = pointElement.jsonArray
            listOf(point[0].jsonPrimitive.double, point[1].jsonPrimitive.double)
        }
    }

private fun ringAreaKm2(ring: RawRing): Double {
    var sum = 0.0
    for (i in ring.indices) {
        val (x1, y1) = ring[i]
        val (x2, y2) = ring[(i + 1) % ring.size]
        sum += x1 * y2 - x2 * y1
    }
    return abs(sum) / 2.0 * 111.0 * 111.0
}

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

private fun RawRing.roundedTo(decimals: Int): List<List<Double>> =
    map { (lon, lat) -> listOf(roundTo(lon, decimals), roundTo(lat, decimals)) }

/**
 * Reliable, lightweight structural validation applied after simplification, before writing output
 * -- catches the failure modes a corrupted/degenerate simplification result would actually produce
 * (Codex's Phase G3 stabilization finding #3). Deliberately **not** a self-intersection/topology
 * check: proving a ~14k-vertex ring is simple (non-self-intersecting) needs either a real
 * computational-geometry library (e.g. JTS) or a disproportionately complex custom sweep-line
 * implementation -- out of scope for this prototype's size. **Known, documented limitation**: a ring
 * can pass every check below and still be topologically invalid (self-intersecting, incorrectly
 * wound). Structural checks only, never a substitute for real topology validation.
 */
private fun validateRing(ring: List<List<Double>>, label: String) {
    require(ring.size >= 4) {
        "$label: only ${ring.size} point(s) after simplification -- a valid closed ring needs at " +
            "least 4 (3 distinct corners + the closing repeat)"
    }
    require(ring.first() == ring.last()) {
        "$label: not closed after simplification -- first=${ring.first()} last=${ring.last()}"
    }
    for ((i, point) in ring.withIndex()) {
        require(point.size == 2) { "$label: point $i has ${point.size} component(s), expected [lon, lat]" }
        val (lon, lat) = point
        require(lon.isFinite() && lat.isFinite()) { "$label: point $i is not finite: ($lon, $lat)" }
        require(lon in -180.0..180.0) { "$label: point $i longitude out of range: $lon" }
        require(lat in -90.0..90.0) { "$label: point $i latitude out of range: $lat" }
    }
    val distinctPoints = ring.dropLast(1).distinct()
    require(distinctPoints.size >= 3) {
        "$label: only ${distinctPoints.size} distinct point(s) after simplification -- degenerate " +
            "(collapsed to a line or point)"
    }
}
