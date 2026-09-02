package com.cedervs.worlddiscovery.feature.map

import android.graphics.Color
import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.destinationPoint
import com.cedervs.worlddiscovery.core.location.LocationObservation
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal const val CURRENT_POSITION_ACCURACY_SOURCE_ID = "current-position-accuracy-source"
internal const val CURRENT_POSITION_ACCURACY_LAYER_ID = "current-position-accuracy-layer"
internal const val CURRENT_POSITION_SOURCE_ID = "current-position-source"
internal const val CURRENT_POSITION_DOT_LAYER_ID = "current-position-dot-layer"

// Provisional visual constants only — not final art direction (docs/ai-context/OPEN_QUESTIONS.md);
// deliberately easy to retune. Small, luminous/electric blue with a subtle white halo/stroke so it
// stays visible against varied basemap colors, per the product direction — direction toward the
// future immersive/cinematic map aesthetic, not itself that aesthetic.
internal const val CURRENT_POSITION_DOT_COLOR = "#2979FF"
internal const val CURRENT_POSITION_DOT_RADIUS_PX = 7f
internal const val CURRENT_POSITION_DOT_STROKE_COLOR = "#FFFFFF"
internal const val CURRENT_POSITION_DOT_STROKE_WIDTH_PX = 2f
internal const val CURRENT_POSITION_DOT_STROKE_OPACITY = 0.9f
internal const val CURRENT_POSITION_ACCURACY_FILL_COLOR = "#2979FF"
internal const val CURRENT_POSITION_ACCURACY_FILL_OPACITY = 0.15f

/** Number of evenly spaced bearings used to approximate the accuracy circle as a polygon —
 * enough for a visually smooth provisional circle without being expensive to rebuild on every
 * update (see [applyCurrentPosition]). Provisional, easy to retune. */
private const val ACCURACY_POLYGON_BEARING_COUNT = 32

/**
 * Pure geometry/rendering logic for the live current-position marker — "where am I right now,"
 * deliberately separate from [applyDiscoveredCellGeometries] ("what have I discovered"): never
 * derived from, and never derives, any H3/discovered-cell state. Same reasoning as that function
 * for staying free of any Jetpack Compose dependency, so it's testable without a Compose test
 * environment.
 *
 * Two independent source/layer pairs, each reused in place (checked and `setGeoJson` if already
 * present, added only once otherwise) — never removed and recreated on every update, matching
 * [applyDiscoveredCellGeometries]'s established pattern. The accuracy source/layer is added
 * *before* the position source/layer specifically so it renders *below* the dot (MapLibre/GL
 * layers render in the order they were added to the style) — checked independently so a later
 * call updating one never skips reusing the other. On a genuine style reload (a new [Style]
 * instance), `getSourceAs` naturally returns `null` for both, so both are recreated from whatever
 * [observation] currently holds — no separate reload-handling path needed.
 *
 * `observation = null` (before the first fix, after tracking stops, or during a transient
 * unavailability — see `LocationTrackingSession.currentObservation`) updates both existing sources
 * to empty `FeatureCollection`s rather than removing them, so nothing is ever drawn without
 * removing/recreating any style object.
 */
internal fun applyCurrentPosition(style: Style, observation: LocationObservation?) {
    val accuracyFeatures = accuracyFeatureCollection(observation)
    val existingAccuracySource = style.getSourceAs<GeoJsonSource>(CURRENT_POSITION_ACCURACY_SOURCE_ID)
    if (existingAccuracySource != null) {
        existingAccuracySource.setGeoJson(accuracyFeatures)
    } else {
        style.addSource(GeoJsonSource(CURRENT_POSITION_ACCURACY_SOURCE_ID, accuracyFeatures))
        style.addLayer(
            FillLayer(CURRENT_POSITION_ACCURACY_LAYER_ID, CURRENT_POSITION_ACCURACY_SOURCE_ID).withProperties(
                PropertyFactory.fillColor(Color.parseColor(CURRENT_POSITION_ACCURACY_FILL_COLOR)),
                PropertyFactory.fillOpacity(CURRENT_POSITION_ACCURACY_FILL_OPACITY),
            ),
        )
    }

    val positionFeatures = positionFeatureCollection(observation)
    val existingPositionSource = style.getSourceAs<GeoJsonSource>(CURRENT_POSITION_SOURCE_ID)
    if (existingPositionSource != null) {
        existingPositionSource.setGeoJson(positionFeatures)
    } else {
        style.addSource(GeoJsonSource(CURRENT_POSITION_SOURCE_ID, positionFeatures))
        // Added after the accuracy layer above, so it renders on top of it.
        style.addLayer(
            CircleLayer(CURRENT_POSITION_DOT_LAYER_ID, CURRENT_POSITION_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(CURRENT_POSITION_DOT_RADIUS_PX),
                PropertyFactory.circleColor(Color.parseColor(CURRENT_POSITION_DOT_COLOR)),
                PropertyFactory.circleStrokeWidth(CURRENT_POSITION_DOT_STROKE_WIDTH_PX),
                PropertyFactory.circleStrokeColor(Color.parseColor(CURRENT_POSITION_DOT_STROKE_COLOR)),
                PropertyFactory.circleStrokeOpacity(CURRENT_POSITION_DOT_STROKE_OPACITY),
            ),
        )
    }
}

/** `internal`, like [DiscoveredCellGeometryRendering.kt]'s `toFeature`/`unwrapAntimeridianRing` —
 * pure GeoJSON construction (only `org.maplibre.geojson.*` types, no `org.maplibre.android.style.*`
 * `Style`/`Source`/`Layer` types), directly unit-testable without a live Android/native runtime,
 * unlike [applyCurrentPosition] itself. */
internal fun positionFeatureCollection(observation: LocationObservation?): FeatureCollection {
    if (observation == null) return FeatureCollection.fromFeatures(emptyArray())
    val point = Point.fromLngLat(observation.coordinate.longitude, observation.coordinate.latitude)
    return FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(point)))
}

internal fun accuracyFeatureCollection(observation: LocationObservation?): FeatureCollection {
    if (observation == null) return FeatureCollection.fromFeatures(emptyArray())
    val radiusMeters = renderableAccuracyMetersOrNull(observation.accuracyMeters)
        ?: return FeatureCollection.fromFeatures(emptyArray())
    return FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(accuracyPolygon(observation.coordinate, radiusMeters))))
}

/**
 * Android's `Location.getAccuracy()` (carried here as [LocationObservation.accuracyMeters]) is an
 * estimated horizontal uncertainty radius in meters — conventionally interpreted as ~68%
 * confidence (roughly one standard deviation of a 2D Gaussian error model), **not** a guaranteed
 * hard boundary the true position is confined to.
 *
 * Returns `null` (render the dot only, no accuracy polygon — never a fabricated fallback radius)
 * for anything that isn't a genuinely usable radius: absent, `NaN`, `+`/`-Infinity`, zero, or
 * negative. `isFinite()` is what actually excludes both infinities — a naive `> 0.0` check alone
 * would let `+Infinity` through, since `Double.POSITIVE_INFINITY > 0.0` is `true`.
 */
internal fun renderableAccuracyMetersOrNull(accuracyMeters: Float?): Double? {
    val accuracy = accuracyMeters?.toDouble() ?: return null
    return accuracy.takeIf { it.isFinite() && it > 0.0 }
}

/** [ACCURACY_POLYGON_BEARING_COUNT] points at [radiusMeters] from [center], one per evenly spaced
 * bearing, geodesically placed via [destinationPoint] so the polygon scales with real geography
 * (e.g. a 10 m accuracy renders as an actual ~10 m radius on the map, not a fixed screen-pixel
 * circle) rather than [org.maplibre.android.style.layers.CircleLayer]'s pixel-only radius, which
 * is why the dot above uses that layer type but the accuracy circle does not. Reuses
 * [unwrapAntimeridianRing] (see `DiscoveredCellGeometryRendering.kt`) for the same antimeridian
 * normalization H3 cell boundaries already need, and closes the ring the same way (first vertex
 * repeated at the end). */
internal fun accuracyPolygon(center: Coordinate, radiusMeters: Double): Polygon {
    val rawRing = (0 until ACCURACY_POLYGON_BEARING_COUNT).map { i ->
        val bearingDegrees = 360.0 * i / ACCURACY_POLYGON_BEARING_COUNT
        val vertex = destinationPoint(center, bearingDegrees, radiusMeters)
        Point.fromLngLat(vertex.longitude, vertex.latitude)
    }
    val unwrappedRing = unwrapAntimeridianRing(rawRing)
    val closedRing = unwrappedRing + unwrappedRing.first()
    return Polygon.fromLngLats(listOf(closedRing))
}
