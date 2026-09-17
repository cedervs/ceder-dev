package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.google.gson.JsonObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.MultiPolygon

/**
 * **France Country -> Region (`ADMIN_1`) -> Department (`ADMIN_2`), Region/Department overlay
 * rendering.** Deliberately a SEPARATE source/layer pair per level from
 * [CountryOverlayRendering]'s own Country-level overlay — never touches
 * [COUNTRY_OVERLAY_SOURCE_ID] or its layers, so the already physically-validated Country-level
 * fill/outline/[BasemapAlignedBorderRendering] behavior is completely unaffected by this file
 * existing at all.
 *
 * **One Feature per visited area, geometry = the area's own full `MultiPolygon` (every component
 * together), never decomposed per-island the way [CountryOverlayRendering] decomposes France's own
 * 3 components.** This is a deliberate, documented scoping decision for this round (see
 * `tools/geo/GenerateFranceAdministrativeReference.kt`'s own doc comment and `PROJECT_STATUS.md`):
 * clicking anywhere within a visited region/department (including any of its own real coastal
 * islands, e.g. Bretagne's) fits the camera to that ONE area's own bounds, not to an independently
 * clickable island. [com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent] still works
 * unchanged on any area this renders, so per-island navigation remains available to a future round
 * with zero data regeneration — this file simply doesn't call it.
 *
 * **Color is selection-relative, not a fixed per-level constant** — physical-validation correction
 * round; see `GeographicHierarchyStyling.kt`'s own doc comment for the full rationale and the
 * previous, rejected "Country darkest -> Region medium -> Department lightest, permanently" design
 * this replaces. The same Region renders as [GeographicAreaStyleRole.DIRECT_SUBLEVEL] (darker) while
 * merely a sub-level beneath a selected Country, but as [GeographicAreaStyleRole.SELECTED] (light)
 * the moment that Region itself becomes the focused level — this file shares the exact same 3-color
 * palette and the exact same [GEOGRAPHIC_STYLE_ROLE_PROPERTY]-tagging mechanism as
 * [CountryOverlayRendering]'s own Country-level fill, so all three levels always read as one
 * consistent visual language. Still VISITED/PRESENCE semantics only, never a completion/coverage
 * claim — see [com.cedervs.worlddiscovery.core.discovery.GeographicAreaVisitedStatus]'s own doc
 * comment; the same "never highlight anything not actually visited" rule the Country overlay already
 * follows applies identically here: [visitedRegions]/[visitedDepartments] are the caller-pre-filtered
 * `visited == true` subsets (mirroring `MapScreen`'s existing `visitedFranceComponents` filtering),
 * never the full loaded area list.
 */
internal const val ADMIN1_OVERLAY_SOURCE_ID = "admin1-overlay-source"
internal const val ADMIN1_OVERLAY_FILL_LAYER_ID = "admin1-overlay-fill-layer"
internal const val ADMIN1_OVERLAY_OUTLINE_LAYER_ID = "admin1-overlay-outline-layer"
internal const val ADMIN2_OVERLAY_SOURCE_ID = "admin2-overlay-source"
internal const val ADMIN2_OVERLAY_FILL_LAYER_ID = "admin2-overlay-fill-layer"
internal const val ADMIN2_OVERLAY_OUTLINE_LAYER_ID = "admin2-overlay-outline-layer"

/** Tags a rendered Region/Department feature with the real [GeographicArea.id] it came from — a
 * plain string match at click-resolution time (see `AdministrativeAreaNavigation.kt`'s
 * `resolveClickedAdministrativeArea`), simpler than [CountryOverlayRendering]'s own positional
 * `componentIndex` scheme because there is exactly one Feature per area here, never several. */
internal const val ADMIN_OVERLAY_AREA_ID_PROPERTY = "areaId"

/**
 * **PRODUCT CALIBRATION REQUIRED** — provisional, chosen only to give a working phone prototype
 * with a plausible progressive drill-down feel against the already-validated Country band
 * (`COUNTRY_OVERLAY_MIN_ZOOM..MAX_ZOOM` = 0..7, fade 5..7, both left completely unchanged): Region
 * becomes interactive well before Country fully fades, Department the same relative to Region, so a
 * user zooming in smoothly sees one level's fill hand off to the next rather than a blank gap or an
 * abrupt jump. Not derived from any measured on-device legibility threshold.
 */
internal const val ADMIN1_OVERLAY_MIN_ZOOM = 3f
internal const val ADMIN1_OVERLAY_MAX_ZOOM = 10f
internal const val ADMIN1_OVERLAY_FADE_OUT_START_ZOOM = 8.0
internal const val ADMIN1_OVERLAY_FADE_OUT_END_ZOOM = 10.0

internal const val ADMIN2_OVERLAY_MIN_ZOOM = 6f
internal const val ADMIN2_OVERLAY_MAX_ZOOM = 13f
internal const val ADMIN2_OVERLAY_FADE_OUT_START_ZOOM = 11.0
internal const val ADMIN2_OVERLAY_FADE_OUT_END_ZOOM = 13.0

// Fixed per-level colors removed this round -- see this file's own doc comment and
// GeographicHierarchyStyling.kt. Opacity is still shared, level-independent (GEOGRAPHIC_OVERLAY_*),
// deliberate visual consistency across all three levels; only COLOR now varies, and by selection role
// rather than by level.
private const val ADMIN_OVERLAY_OUTLINE_WIDTH = 1.5f

internal fun isAdmin1OverlayInteractive(zoomLevel: Double): Boolean = zoomLevel < ADMIN1_OVERLAY_FADE_OUT_END_ZOOM
internal fun isAdmin2OverlayInteractive(zoomLevel: Double): Boolean = zoomLevel < ADMIN2_OVERLAY_FADE_OUT_END_ZOOM

/**
 * **Physical-validation correction round (Admin2-visibility-after-Admin1-selection fix).** Root
 * cause: [ADMIN2_OVERLAY_MIN_ZOOM] is a single, generic, level-only zoom threshold — it renders the
 * Department layer starting at zoom 6 regardless of what's currently selected. `CameraUpdateFactory
 * .newLatLngBounds(nouvelleAquitaine.bounds, ...)`'s own resulting fit zoom, confirmed on a physical
 * device, lands slightly *below* that threshold for a Region the size of Nouvelle-Aquitaine — so the
 * Department layer stayed invisible (MapLibre hides a `FillLayer`/`LineLayer` entirely below its own
 * `minZoom`, confirmed against the real `Layer` API: `getMinZoom`/`setMinZoom` are plain, always-
 * callable methods, not merely construction-time values) until the user manually zoomed in past 6,
 * even though the Region-fit camera had already landed exactly where the product wants Departments to
 * be immediately visible.
 *
 * **Fix: the Department layer's effective minimum zoom is selection-relative, not a single constant.**
 * Once an `ADMIN_1` (Region) — or its own `ADMIN_2` (Department) child, so the effective floor doesn't
 * snap back up the instant the user drills one level deeper — is the actual currently-selected level,
 * Departments must render immediately at whatever zoom the Region-fit camera produced, so the floor
 * drops all the way down to [ADMIN1_OVERLAY_MIN_ZOOM] (Region's own render floor — Departments can
 * never usefully appear before their own parent Region does anyway, so this is a safe, non-arbitrary
 * lower bound, not `0f`). Whenever `COUNTRY` is selected (or nothing is selected at all — the World
 * view), the ordinary [ADMIN2_OVERLAY_MIN_ZOOM] generic threshold applies unchanged: Departments do
 * NOT become visible just because France itself is selected, matching this round's own explicit "do
 * not simply make Departments visible everywhere at World/Country scale" requirement.
 *
 * Deliberately does **not** filter which visited Departments become visible down to only the selected
 * Region's own children — [applyAdministrativeOverlay] already renders every visited Department in one
 * shared source/layer pair (a pre-existing, Round 0 scoping decision, unchanged by this fix): a
 * visited-but-unrelated Department elsewhere also becomes visible once any Region is selected. Fixing
 * that would require per-feature (not layer-level) zoom gating, a materially larger change than this
 * round's own physically-observed defect calls for — documented here as a known, accepted limitation,
 * not silently pretended away.
 */
internal fun effectiveAdmin2MinZoom(selection: GeographicFocusSelection): Float =
    if (selection.selectedType == GeographicAreaType.ADMIN_1 || selection.selectedType == GeographicAreaType.ADMIN_2) {
        ADMIN1_OVERLAY_MIN_ZOOM
    } else {
        ADMIN2_OVERLAY_MIN_ZOOM
    }

/** Applies both the Region and Department overlays from one caller-provided, already-filtered
 * (`visited == true`) snapshot — called from the exact same effect as
 * [CountryOverlayRendering.applyCountryOverlay]/[applyBasemapAlignedFranceBorder] in
 * `DiscoveryMapView`, never a separate subscription, so all three levels always reflect the same
 * discovery snapshot. */
internal fun applyAdministrativeOverlay(
    style: Style,
    visitedRegions: List<GeographicArea>,
    visitedDepartments: List<GeographicArea>,
    selection: GeographicFocusSelection = GeographicFocusSelection.NONE,
) {
    applyAdministrativeOverlayLevel(
        style,
        visitedRegions,
        selection,
        ADMIN1_OVERLAY_SOURCE_ID,
        ADMIN1_OVERLAY_FILL_LAYER_ID,
        ADMIN1_OVERLAY_OUTLINE_LAYER_ID,
        ADMIN1_OVERLAY_MIN_ZOOM,
        ADMIN1_OVERLAY_MAX_ZOOM,
        ADMIN1_OVERLAY_FADE_OUT_START_ZOOM,
        ADMIN1_OVERLAY_FADE_OUT_END_ZOOM,
    )
    applyAdministrativeOverlayLevel(
        style,
        visitedDepartments,
        selection,
        ADMIN2_OVERLAY_SOURCE_ID,
        ADMIN2_OVERLAY_FILL_LAYER_ID,
        ADMIN2_OVERLAY_OUTLINE_LAYER_ID,
        effectiveAdmin2MinZoom(selection),
        ADMIN2_OVERLAY_MAX_ZOOM,
        ADMIN2_OVERLAY_FADE_OUT_START_ZOOM,
        ADMIN2_OVERLAY_FADE_OUT_END_ZOOM,
    )
}

@Suppress("LongParameterList") // Mirrors CountryOverlayRendering's own single-level function shape,
// parametrized over level -- an internal helper, never called with anything but this file's own
// two levels' constants above.
private fun applyAdministrativeOverlayLevel(
    style: Style,
    visitedAreas: List<GeographicArea>,
    selection: GeographicFocusSelection,
    sourceId: String,
    fillLayerId: String,
    outlineLayerId: String,
    minZoom: Float,
    maxZoom: Float,
    fadeOutStartZoom: Double,
    fadeOutEndZoom: Double,
) {
    val featureCollection = administrativeOverlayFeatureCollection(visitedAreas, selection)

    val existingSource = style.getSourceAs<GeoJsonSource>(sourceId)
    if (existingSource != null) {
        existingSource.setGeoJson(featureCollection)
        // The Department layer's own effective minZoom is selection-relative (see
        // effectiveAdmin2MinZoom's own doc comment) -- re-applied on every call, exactly like
        // BasemapAlignedBorderRendering.kt's own visibility/color properties, since a focus change
        // alone (no new discovery data) must still be able to lower/raise this floor. A no-op set for
        // the Region layer, whose own minZoom is always the same static ADMIN1_OVERLAY_MIN_ZOOM.
        style.getLayerAs<FillLayer>(fillLayerId)?.minZoom = minZoom
        style.getLayerAs<LineLayer>(outlineLayerId)?.minZoom = minZoom
        return
    }

    style.addSource(GeoJsonSource(sourceId, featureCollection))
    addAdministrativeOverlayFillLayer(
        style,
        administrativeOverlayFillLayer(fillLayerId, sourceId, minZoom, maxZoom, fadeOutStartZoom, fadeOutEndZoom),
    )
    style.addLayer(
        LineLayer(outlineLayerId, sourceId)
            .withProperties(
                PropertyFactory.lineColor(geographicAreaStyleRoleColorExpression()),
                PropertyFactory.lineWidth(ADMIN_OVERLAY_OUTLINE_WIDTH),
                PropertyFactory.lineOpacity(fadeOutOpacityExpression(GEOGRAPHIC_OVERLAY_OUTLINE_OPACITY, fadeOutStartZoom, fadeOutEndZoom)),
            )
            .apply {
                this.minZoom = minZoom
                this.maxZoom = maxZoom
            },
    )
}

private fun administrativeOverlayFillLayer(
    fillLayerId: String,
    sourceId: String,
    minZoomValue: Float,
    maxZoomValue: Float,
    fadeOutStartZoom: Double,
    fadeOutEndZoom: Double,
): FillLayer =
    FillLayer(fillLayerId, sourceId)
        .withProperties(
            PropertyFactory.fillColor(geographicAreaStyleRoleColorExpression()),
            PropertyFactory.fillOpacity(fadeOutOpacityExpression(GEOGRAPHIC_OVERLAY_FILL_OPACITY, fadeOutStartZoom, fadeOutEndZoom)),
        )
        .apply {
            minZoom = minZoomValue
            maxZoom = maxZoomValue
        }

/** Same Option G water-masking decision as [CountryOverlayRendering]'s own
 * `addCountryOverlayFillLayer` — reuses [insertCountryOverlayFillLayer]'s pure decision function
 * (already generic over any [CountryOverlayFillInsertionTarget]) rather than duplicating it, with a
 * fresh adapter closing over this level's own [FillLayer]. */
private fun addAdministrativeOverlayFillLayer(style: Style, fillLayer: FillLayer) {
    val target = object : CountryOverlayFillInsertionTarget {
        override fun waterAnchorExists(): Boolean = style.getLayerAs<org.maplibre.android.style.layers.Layer>(BASEMAP_WATER_LAYER_ID) != null
        override fun insertBelowWaterAnchor() = style.addLayerBelow(fillLayer, BASEMAP_WATER_LAYER_ID)
        override fun insertOnTop() = style.addLayer(fillLayer)
    }
    insertCountryOverlayFillLayer(target)
}

private fun fadeOutOpacityExpression(baseOpacity: Float, startZoom: Double, endZoom: Double) =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        Expression.stop(startZoom, baseOpacity),
        Expression.stop(endZoom, 0f),
    )

internal fun administrativeOverlayFeatureCollection(
    visitedAreas: List<GeographicArea>,
    selection: GeographicFocusSelection = GeographicFocusSelection.NONE,
): FeatureCollection {
    val features = visitedAreas.map { area -> area.toAdministrativeOverlayFeature(selection) }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

/** The area's full `MultiPolygon` (every component together, see this file's own doc comment for
 * why) as one GeoJSON Feature, tagged with [ADMIN_OVERLAY_AREA_ID_PROPERTY] — a plain id-string
 * match is all `resolveClickedAdministrativeArea` needs, unlike [CountryOverlayRendering]'s
 * positional `componentIndex` scheme (not needed here: exactly one Feature per area) — and with
 * [GEOGRAPHIC_STYLE_ROLE_PROPERTY], this area's own [resolveGeographicAreaStyleRole] result against
 * [selection] (see `GeographicHierarchyStyling.kt`). */
private fun GeographicArea.toAdministrativeOverlayFeature(selection: GeographicFocusSelection): Feature {
    val mapLibrePolygons = geometry.polygons.map { polygon -> polygon.toMapLibrePolygon() }
    val geoJsonGeometry: Geometry = if (mapLibrePolygons.size == 1) {
        mapLibrePolygons.single()
    } else {
        MultiPolygon.fromPolygons(mapLibrePolygons)
    }
    val styleRole = resolveGeographicAreaStyleRole(type, id, parentId, selection)
    val properties = JsonObject().apply {
        addProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY, id)
        addProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY, styleRole.name)
    }
    return Feature.fromGeometry(geoJsonGeometry, properties)
}
