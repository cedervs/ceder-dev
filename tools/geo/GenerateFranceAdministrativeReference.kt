import com.cedervs.worlddiscovery.core.discovery.GeographicAreaReferenceJson
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private typealias RawPoint = List<Double> // [lon, lat]
private typealias RawRing = List<RawPoint>
private typealias RawPolygon = List<RawRing>

/**
 * **France Country -> Region (ADMIN_1) -> Department (ADMIN_2) hierarchy, classification geometry.**
 * Generates ONE [GeographicAreaReferenceJson] artifact per invocation from a single bare OSM
 * `Polygon`/`MultiPolygon` geometry document (never a `FeatureCollection`) fetched via
 * `polygons.openstreetmap.fr/get_geojson.py?id=<relation>&params=0` — see `tools/geo/README.md`
 * for the full reproducible retrieval steps and the exact relation IDs used to generate every
 * bundled artifact this round.
 *
 * **Deliberately produces the SAME [GeographicAreaReferenceJson] schema `GenerateFranceReference.kt`
 * already writes for `country:FR`** — this is not a new, region/department-specific type. An
 * `ADMIN_1`/`ADMIN_2` area is a first-class [com.cedervs.worlddiscovery.core.discovery.GeographicArea]
 * exactly like the country is, distinguished only by its `type`/`parentId` fields (see
 * `GeographicArea.kt`'s own doc comment for why `parentId` is a single optional link, not a
 * hard-coded chain) — reusing the parser this round added `parentId` support to
 * ([com.cedervs.worlddiscovery.core.discovery.loadGeographicAreaReference]) rather than inventing
 * a parallel one, exactly the same way this file reuses [GeographicAreaReferenceJson] itself.
 *
 * **No mainland/Corsica/Guiana-style noise filtering.** `GenerateFranceReference.kt`'s
 * `MIN_COMPONENT_AREA_KM2 = 50.0` threshold exists because French Guiana's *raw* geoBoundaries
 * source is `raster2polygon` output from satellite land-cover classification — genuinely full of
 * sub-pixel-classification noise polygons. A `polygons.openstreetmap.fr` administrative relation is
 * a real, human-maintained cartographic boundary, not raster-derived, so a real region/department's
 * `MultiPolygon` components (e.g. Bretagne's many real coastal islands — Belle-Île, Ouessant, Groix,
 * Île de Ré for Charente-Maritime, ...) are genuine geography, not classification artifacts, and
 * must never be dropped by an aggressive area threshold the way `GUF`'s raster noise was. This tool
 * instead applies only [MIN_STRUCTURAL_NOISE_AREA_KM2] — several orders of magnitude smaller,
 * calibrated only to catch genuine near-zero-area topology artifacts (a documented, real but rare
 * failure mode of multipolygon relation assembly — overlapping/duplicate ways producing a
 * degenerate sliver ring), never a real named island.
 *
 * **Components are kept, never decomposed into per-island navigation.** Unlike
 * `GenerateFranceReference.kt`'s explicit mainland/Corsica/Guiana ordering contract (each rendered
 * and navigated as its own [com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent]),
 * this round's Region/Department rendering and click-navigation
 * (`feature-map`'s `AdministrativeOverlayRendering.kt`/`AdministrativeAreaNavigation.kt`) treats
 * each `ADMIN_1`/`ADMIN_2` area's full [com.cedervs.worlddiscovery.core.discovery.GeographicMultiPolygon]
 * (all its components together, e.g. Bretagne mainland + all its real islands) as ONE navigable
 * shape — a deliberate, documented scoping decision for this round (see `PROJECT_STATUS.md`), not
 * an architecture limitation: [com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent]/
 * `.components()` still works unchanged on any `ADMIN_1`/`ADMIN_2` area produced by this tool, so
 * per-island navigation remains available to a future round with zero data regeneration.
 *
 * Components ARE still sorted by area descending before being written (largest first) — a
 * consistent, deterministic artifact contract (mirroring `GenerateFranceReference.kt`'s own
 * ordering rule), even though nothing this round's rendering/navigation reads depends on the order.
 *
 * **Provenance calendar-date domain: `Europe/Paris`, always — see [PROVENANCE_ZONE] and
 * [currentGenerationDate].** This is a France-specific generator; every calendar date it writes
 * (`generatedAt`, and the retrieval date conventionally embedded in a caller-supplied
 * `sourceVersionNote`) is defined to mean "the France/Europe/Paris calendar day," never "whatever
 * day the machine that happened to run this generator was in." A `sourceVersionNote`'s own
 * "retrieved <date>" text is written by the operator following the same convention -- this
 * generator does not parse or validate that text, but the convention is what makes it and
 * `generatedAt` comparable at all.
 */
fun main(args: Array<String>) {
    require(args.size == 7) {
        "Usage: GenerateFranceAdministrativeReference <path-to-osm-relation.geojson> <id> <type: ADMIN_1|ADMIN_2> " +
            "<displayName> <parentId> <sourceVersionNote> <output-path>"
    }
    val sourceFile = File(args[0])
    val id = args[1]
    val type = args[2]
    require(type == "ADMIN_1" || type == "ADMIN_2") { "type must be ADMIN_1 or ADMIN_2, got $type" }
    val displayName = args[3]
    val parentId = args[4]
    val sourceVersionNote = args[5]
    val outputFile = File(args[6])

    val reference = buildGeographicAreaReference(
        sourceFile = sourceFile,
        id = id,
        type = type,
        displayName = displayName,
        parentId = parentId,
        sourceVersionNote = sourceVersionNote,
    )

    outputFile.parentFile?.mkdirs()
    outputFile.writeText(Json.encodeToString(GeographicAreaReferenceJson.serializer(), reference))

    println("Wrote ${outputFile.absolutePath} (${outputFile.length()} bytes)")
    println(
        "id=$id type=$type parentId=$parentId components=${reference.polygons.size} " +
            "totalVertices=${reference.polygons.sumOf { poly -> poly.sumOf { ring -> ring.size } }}",
    )
}

/**
 * The real generation pipeline (parse -> drop structural noise -> simplify -> drop post-
 * simplification collapses -> build the artifact), extracted out of [main] so it can be exercised
 * directly against a controlled, local, non-network [sourceFile] -- see
 * `GenerateFranceAdministrativeReferenceProvenanceTest.kt`'s production-artifact-path test, which
 * proves the artifact's own `generatedAt` genuinely comes from [currentGenerationDate] rather than
 * merely asserting the helper works in isolation (a helper-only test would keep passing even if
 * [main] stopped calling it). [clock] defaults to the real system clock and is threaded straight
 * through to [currentGenerationDate] -- this function never reads the wall clock itself, so a test
 * needs to control only this one parameter to get a fully deterministic artifact.
 */
internal fun buildGeographicAreaReference(
    sourceFile: File,
    id: String,
    type: String,
    displayName: String,
    parentId: String,
    sourceVersionNote: String,
    clock: Clock = Clock.systemUTC(),
): GeographicAreaReferenceJson {
    val rawPolygons = parseMultiPolygon(sourceFile)
    val significant = rawPolygons.filter { polygon -> ringAreaKm2(polygon[0]) >= MIN_STRUCTURAL_NOISE_AREA_KM2 }
    require(significant.isNotEmpty()) {
        "Every polygon in ${sourceFile.name} fell under the $MIN_STRUCTURAL_NOISE_AREA_KM2 km^2 " +
            "structural-noise floor -- source data is unexpectedly empty/degenerate, not a real " +
            "administrative boundary; re-verify before proceeding."
    }
    val droppedCount = rawPolygons.size - significant.size
    if (droppedCount > 0) {
        println(
            "Dropped $droppedCount polygon(s) under the $MIN_STRUCTURAL_NOISE_AREA_KM2 km^2 " +
                "structural-noise floor (degenerate multipolygon-assembly artifacts, not real islands).",
        )
    }

    val byAreaDescending = significant.sortedByDescending { polygon -> ringAreaKm2(polygon[0]) }
    val simplifiedAll = byAreaDescending.map { polygon -> simplifyPolygon(polygon, SIMPLIFY_TOLERANCE_DEGREES) }

    // A polygon that survives the pre-simplification MIN_STRUCTURAL_NOISE_AREA_KM2 filter can still
    // legitimately collapse to a degenerate ring AFTER Douglas-Peucker -- e.g. a real but tiny
    // skerry/rock whose entire diameter is smaller than SIMPLIFY_TOLERANCE_DEGREES (~111m):
    // douglasPeucker's own base case then returns just [ring.first(), ring.last()], and for a closed
    // ring those are the SAME point, producing a 2-identical-point "ring" that GeographicPolygon's
    // own >=3-point invariant correctly rejects at parse time. Checking the ACTUAL post-simplification
    // result (never a pre-simplification area guess) is what makes this self-correcting regardless of
    // a component's exact shape -- mirrors GenerateFranceOsmRenderingPolygon.kt's own validateRing
    // "distinct points >= 3" check, applied here as a filter (drop and report) rather than a hard
    // failure, since a real region/department can legitimately contain many such sub-tolerance rocks.
    val finalPolygons = simplifiedAll.filter { polygon -> polygon[0].distinct().size >= 3 }
    val collapsedCount = simplifiedAll.size - finalPolygons.size
    if (collapsedCount > 0) {
        println(
            "Dropped $collapsedCount polygon(s) that collapsed to fewer than 3 distinct points after " +
                "simplification (real geography below this artifact's ${SIMPLIFY_TOLERANCE_DEGREES}-degree precision, not noise).",
        )
    }
    require(finalPolygons.isNotEmpty()) {
        "Every polygon in ${sourceFile.name} collapsed after simplification -- source data is " +
            "unexpectedly degenerate; re-verify before proceeding."
    }

    return GeographicAreaReferenceJson(
        id = id,
        type = type,
        displayName = displayName,
        sourceId = "openstreetmap",
        sourceVersion = sourceVersionNote,
        sourceProvenance = "EXTERNAL_REFERENCE_DATASET",
        generatedAt = currentGenerationDate(clock),
        license = "OpenStreetMap contributors, Open Data Commons Open Database License (ODbL) v1.0 " +
            "-- https://www.openstreetmap.org/copyright -- attribution required " +
            "(\"© OpenStreetMap contributors\"). This is a Derivative Database under ODbL: if " +
            "shipped in a released build, World Discovery must make this specific extracted/" +
            "processed polygon available under ODbL to whoever requests it. Commercial use is " +
            "permitted. Classification geometry (H3 point-in-polygon membership), not rendering " +
            "geometry -- see feature-map's own separate rendering-geometry artifacts where those exist.",
        polygons = finalPolygons,
        parentId = parentId,
    )
}

/**
 * The single explicit calendar-date domain every date this generator writes is defined in — see
 * this file's own class-level doc comment. Fixed to France's own zone regardless of the host
 * machine, so this generator's provenance dates never depend on where it happens to run.
 */
val PROVENANCE_ZONE: ZoneId = ZoneId.of("Europe/Paris")

/**
 * The artifact's truthful `generatedAt` provenance value: the real-world instant read from [clock],
 * always reinterpreted through [PROVENANCE_ZONE] regardless of [clock]'s own zone -- so the SAME
 * instant produces the SAME calendar date no matter what zone the caller's clock carries (including
 * the host machine's default zone). This is design choice (A) from the FH-1 provenance-fix review:
 * the timezone contract is enforced here, in the implementation, rather than merely documented and
 * left to callers to honor -- `clock.withZone(PROVENANCE_ZONE)` makes it structurally impossible
 * for a caller-supplied clock's own zone to leak into the result.
 *
 * Computed fresh at generation time (ISO-8601 `yyyy-MM-dd`, matching this file's established
 * provenance-string convention) instead of a hard-coded literal a developer would otherwise have to
 * remember to bump before every run -- that hard-coded-literal approach previously produced an
 * impossible chronology (a stale `generatedAt` predating the same artifact's own `sourceVersion`
 * retrieval date) when this generator was reused on a later date. A first fix attempt defaulted to
 * [Clock.systemDefaultZone], which reproduced a smaller version of the same bug (a host running in
 * UTC, or simply queried close to the Europe/Paris midnight boundary, could compute a different
 * calendar date than the operator's own "today") -- the explicit `Europe/Paris` [PROVENANCE_ZONE]
 * above fixes that class of bug structurally rather than by convention.
 *
 * [clock] defaults to the real system clock (any zone -- it is immediately normalized to
 * [PROVENANCE_ZONE] regardless) but is injectable so this can be verified deterministically,
 * including at the Europe/Paris midnight boundary and independently of the host's own default zone
 * (see this file's own provenance test), without ever pinning a real calendar date.
 */
fun currentGenerationDate(clock: Clock = Clock.systemUTC()): String =
    LocalDate.now(clock.withZone(PROVENANCE_ZONE)).toString()

/**
 * **PRODUCT CALIBRATION REQUIRED** — several orders of magnitude below
 * `GenerateFranceReference.kt`'s `MIN_COMPONENT_AREA_KM2 = 50.0` (which targets raster-classification
 * noise, not applicable here — see this file's own doc comment). `0.001 km^2` (10,000 m^2, a
 * ~100m x 100m square in this generator's own crude flat-degree approximation) is chosen only to
 * catch genuine degenerate/zero-area topology artifacts from multipolygon relation assembly, never
 * a real named island — the smallest real French coastal islands with their own OSM presence
 * (e.g. within Bretagne's/Charente-Maritime's fetched relations) are multiple orders of magnitude
 * larger than this floor.
 */
private const val MIN_STRUCTURAL_NOISE_AREA_KM2 = 0.001

/** Same tolerance as `GenerateFranceReference.kt`'s own `SIMPLIFY_TOLERANCE_DEGREES` — this is
 * classification geometry (H3 cell-center point-in-polygon testing), not basemap-pixel-aligned
 * rendering geometry, so it does not need `GenerateFranceOsmRenderingPolygon.kt`'s tighter
 * `0.0002` tolerance; consistency with the country-level classification artifact's own precision
 * class is more valuable here than the extra detail. */
private const val SIMPLIFY_TOLERANCE_DEGREES = 0.001
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

private fun simplifyPolygon(polygon: RawPolygon, toleranceDegrees: Double): List<List<List<Double>>> =
    polygon.map { ring ->
        val simplified = douglasPeucker(ring, toleranceDegrees)
        // A ring's first/last vertex must survive simplification unchanged so the ring stays
        // explicitly closed post-simplification -- douglasPeucker's own recursive contract already
        // guarantees this (both endpoints are always kept, see its own base case), asserted here
        // defensively rather than trusted silently.
        require(simplified.first() == ring.first() && simplified.last() == ring.last()) {
            "Simplification unexpectedly altered a ring's closing endpoint -- source data or " +
                "tolerance may need review before proceeding."
        }
        simplified.map { (lon, lat) -> listOf(roundTo(lon, OUTPUT_COORDINATE_DECIMALS), roundTo(lat, OUTPUT_COORDINATE_DECIMALS)) }
    }
