package com.cedervs.worlddiscovery.core.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-disk/bundled-resource shape of a [GeographicArea] reference artifact — e.g.
 * `geo/france-reference.json`, bundled as a plain classpath resource (works identically from a
 * JVM unit test and from the real Android app, since this module's compiled resources end up on
 * the app's classpath too; no separate Android `assets/` copy needed).
 *
 * Deliberately **not** strict GeoJSON: the `polygons` field reuses GeoJSON's own
 * MultiPolygon -> Polygon -> Ring -> `[longitude, latitude]` coordinate nesting (a well-understood
 * convention), but the surrounding object carries this app's own required provenance metadata
 * (source, version, license, generation date) that GeoJSON has no standard place for — see
 * `tools/geo/README.md` for exactly how this file is generated, from which real source, and why.
 */
@Serializable
internal data class GeographicAreaReferenceJson(
    val id: String,
    val type: String,
    val displayName: String,
    val sourceId: String,
    val sourceVersion: String,
    val sourceProvenance: String,
    val generatedAt: String,
    val license: String,
    /** MultiPolygon -> Polygon -> Ring -> [longitude, latitude]. */
    val polygons: List<List<List<List<Double>>>>,
)

/**
 * Parses a [GeographicAreaReferenceJson] document into the real [GeographicArea] domain type,
 * computing [GeographicArea.bounds] via [computeGeographicBounds] rather than trusting any
 * precomputed value in the file itself — bounds are always a recomputable projection of the
 * geometry, never a second, independently-editable fact that could drift from it.
 */
internal fun parseGeographicAreaReference(json: String): GeographicArea {
    val parsed = Json.decodeFromString<GeographicAreaReferenceJson>(json)

    val geometry = GeographicMultiPolygon(
        polygons = parsed.polygons.map { polygon ->
            GeographicPolygon(
                rings = polygon.map { ring ->
                    ring.map { point ->
                        require(point.size == 2) { "Expected [longitude, latitude], got $point" }
                        Coordinate(latitude = point[1], longitude = point[0])
                    }
                },
            )
        },
    )

    return GeographicArea(
        id = parsed.id,
        type = GeographicAreaType.valueOf(parsed.type),
        displayName = parsed.displayName,
        geometry = geometry,
        bounds = computeGeographicBounds(geometry),
        sourceId = parsed.sourceId,
        sourceVersion = parsed.sourceVersion,
        sourceProvenance = GeographicAreaProvenance.valueOf(parsed.sourceProvenance),
    )
}

/**
 * Loads and parses the bundled France reference artifact (`geo/france-reference.json` — see
 * `tools/geo/README.md`). Reads from this class's own classloader, which resolves correctly both
 * from a plain JVM test (Gradle puts `src/main/resources` on the test runtime classpath
 * automatically) and from the real Android app (this module's resources are bundled into the
 * final app the same way its compiled classes are).
 */
fun loadFranceGeographicAreaReference(): GeographicArea {
    val resourceStream = object {}.javaClass.getResourceAsStream("/geo/france-reference.json")
        ?: error("geo/france-reference.json resource not found on the classpath")
    val json = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return parseGeographicAreaReference(json)
}
