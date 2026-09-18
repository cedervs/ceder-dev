package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaVisitedStatus
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
 * **One Feature per render-candidate area (visited or not, see [administrativeRenderCandidates]),
 * geometry = the area's own full `MultiPolygon` (every component together), never decomposed
 * per-island the way [CountryOverlayRendering] decomposes France's own 3 components.** This is a
 * deliberate, documented scoping decision for this round (see
 * `tools/geo/GenerateFranceAdministrativeReference.kt`'s own doc comment and `PROJECT_STATUS.md`):
 * clicking anywhere within a region/department (including any of its own real coastal islands, e.g.
 * Bretagne's) fits the camera to that ONE area's own bounds, not to an independently clickable
 * island. [com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent] still works unchanged
 * on any area this renders, so per-island navigation remains available to a future round with zero
 * data regeneration — this file simply doesn't call it.
 *
 * **Color is selection-relative AND visited-relative — two independent dimensions, never one merged
 * concept.** (FH-1 runtime hierarchy fix.) Administrative EXISTENCE and discovery PRESENCE are
 * different concepts: every loaded Region/Department is now always a render/click CANDIDATE — see
 * [administrativeRenderCandidates] — regardless of whether it has ever been visited, because an
 * unvisited Region or Department must remain navigable (tap Grand Est, tap Haute-Marne, with zero
 * discovered H3 in either — this must work). What DOES still depend on [GeographicAreaVisitedStatus.visited]
 * is color alone: [GeographicHierarchyStyling.administrativeFillColorHex] combines the existing
 * selection-relative [GeographicAreaStyleRole] (see that file's own doc comment for the full
 * rationale and the previous, rejected "Country darkest -> Region medium -> Department lightest,
 * permanently" design) with `visited` to pick one of six colors — three shades of orange when
 * `visited == true` (unchanged from before this fix, and still shared with
 * [CountryOverlayRendering]'s own Country-level fill via the same [GEOGRAPHIC_STYLE_ROLE_PROPERTY]
 * tag), three neutral greys when `visited == false`. **Orange still means VISITED/PRESENCE, and
 * nothing else — an unvisited area rendered here NEVER receives an orange fill, regardless of its
 * selection role** — see [com.cedervs.worlddiscovery.core.discovery.GeographicAreaVisitedStatus]'s
 * own doc comment for why visited/unvisited must stay a strictly separate axis from selection
 * styling. [applyAdministrativeOverlay]'s own parameters carry the full [GeographicAreaVisitedStatus]
 * (never a bare [GeographicArea] with the visited information already discarded, and never only the
 * `visited == true` subset the way this file used to filter before this fix) so both dimensions
 * reach feature-tagging together.
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
 * **This zoom-floor decision is now layered on top of the FH-1 runtime hierarchy fix's own
 * PARENT-SCOPED render-candidate filtering** (see [administrativeRenderCandidates]): the Department
 * layer's feature set itself is already restricted to the currently-focused Region's own real
 * children (never all 96 loaded Departments at once), so this zoom floor only ever needs to decide
 * WHEN that already-scoped handful becomes visible/interactive, never WHICH Departments are in it —
 * a materially simpler problem than the original "which visited Departments" framing this comment
 * used to describe, back when every Department nationwide shared one unfiltered feature set.
 */
internal fun effectiveAdmin2MinZoom(selection: GeographicFocusSelection): Float =
    if (selection.selectedType == GeographicAreaType.ADMIN_1 || selection.selectedType == GeographicAreaType.ADMIN_2) {
        ADMIN1_OVERLAY_MIN_ZOOM
    } else {
        ADMIN2_OVERLAY_MIN_ZOOM
    }

/** Applies both the Region and Department overlays from one caller-provided render-candidate
 * snapshot (see [administrativeRenderCandidates] — [regionCandidates] is always every loaded Region,
 * [departmentCandidates] is already parent-scoped to whichever Region is currently focused, or empty
 * at Country/World view) — called from the exact same effect as
 * [CountryOverlayRendering.applyCountryOverlay]/[applyBasemapAlignedFranceBorder] in
 * `DiscoveryMapView`, never a separate subscription, so all three levels always reflect the same
 * discovery snapshot. */
internal fun applyAdministrativeOverlay(
    style: Style,
    regionCandidates: List<GeographicAreaVisitedStatus>,
    departmentCandidates: List<GeographicAreaVisitedStatus>,
    selection: GeographicFocusSelection = GeographicFocusSelection.NONE,
) {
    applyAdministrativeOverlayLevel(
        style,
        regionCandidates,
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
        departmentCandidates,
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

/**
 * **PARENT-SCOPED render candidates (FH-1 runtime hierarchy fix).** Regions render unconditionally —
 * [admin1Statuses] as-is, every loaded Region, always — because there is only ever one loaded
 * Country in this round, so "France selected -> expose all 13 Regions" has no narrower parent to
 * scope against. Departments are different: [admin2Statuses] can hold up to 96 entries, so rendering
 * it unconditionally would put all 96 on screen at once the moment any Region/Department focus is
 * active — instead, [departments] is filtered down to only the entries whose own real
 * [GeographicArea.parentId] equals [focusedAdmin1Id], and is empty whenever no Region is focused
 * (`focusedAdmin1Id == null`, i.e. Country/World view) — Departments must never appear just because
 * France itself is selected (unchanged product rule, see [effectiveAdmin2MinZoom]'s own doc
 * comment). [focusedAdmin1Id] alone (never also checking for a Department frame) is sufficient
 * because [com.cedervs.worlddiscovery.core.discovery.GeographicArea] Department focus always keeps
 * its own parent Region frame too (see `AdministrativeAreaNavigation.kt`'s own
 * `nextAdminFocusStack` doc comment) — this one filter therefore already covers both "a Region is
 * focused" and "a Department within that Region is focused."
 */
internal data class AdministrativeRenderCandidates(
    val regions: List<GeographicAreaVisitedStatus>,
    val departments: List<GeographicAreaVisitedStatus>,
)

internal fun administrativeRenderCandidates(
    admin1Statuses: List<GeographicAreaVisitedStatus>,
    admin2Statuses: List<GeographicAreaVisitedStatus>,
    focusedAdmin1Id: String?,
): AdministrativeRenderCandidates = AdministrativeRenderCandidates(
    regions = admin1Statuses,
    departments = if (focusedAdmin1Id == null) {
        emptyList()
    } else {
        admin2Statuses.filter { status -> status.area.parentId == focusedAdmin1Id }
    },
)

@Suppress("LongParameterList") // Mirrors CountryOverlayRendering's own single-level function shape,
// parametrized over level -- an internal helper, never called with anything but this file's own
// two levels' constants above.
private fun applyAdministrativeOverlayLevel(
    style: Style,
    areaStatuses: List<GeographicAreaVisitedStatus>,
    selection: GeographicFocusSelection,
    sourceId: String,
    fillLayerId: String,
    outlineLayerId: String,
    minZoom: Float,
    maxZoom: Float,
    fadeOutStartZoom: Double,
    fadeOutEndZoom: Double,
) {
    val featureCollection = administrativeOverlayFeatureCollection(areaStatuses, selection)

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
                PropertyFactory.lineColor(administrativeAreaColorExpression()),
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
            PropertyFactory.fillColor(administrativeAreaColorExpression()),
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
    areaStatuses: List<GeographicAreaVisitedStatus>,
    selection: GeographicFocusSelection = GeographicFocusSelection.NONE,
): FeatureCollection {
    val features = areaStatuses.map { status -> status.toAdministrativeOverlayFeature(selection) }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

/** The area's full `MultiPolygon` (every component together, see this file's own doc comment for
 * why) as one GeoJSON Feature, tagged with [ADMIN_OVERLAY_AREA_ID_PROPERTY] — a plain id-string
 * match is all `resolveClickedAdministrativeArea` needs, unlike [CountryOverlayRendering]'s
 * positional `componentIndex` scheme (not needed here: exactly one Feature per area) — with
 * [GEOGRAPHIC_STYLE_ROLE_PROPERTY], this area's own [resolveGeographicAreaStyleRole] result against
 * [selection] (see `GeographicHierarchyStyling.kt`) — and, since this fix, [ADMIN_OVERLAY_VISITED_PROPERTY],
 * this status's own real [GeographicAreaVisitedStatus.visited] flag, so rendering an unvisited
 * candidate can never be styled as if it were visited. */
private fun GeographicAreaVisitedStatus.toAdministrativeOverlayFeature(selection: GeographicFocusSelection): Feature {
    val area = this.area
    val mapLibrePolygons = area.geometry.polygons.map { polygon -> polygon.toMapLibrePolygon() }
    val geoJsonGeometry: Geometry = if (mapLibrePolygons.size == 1) {
        mapLibrePolygons.single()
    } else {
        MultiPolygon.fromPolygons(mapLibrePolygons)
    }
    val styleRole = resolveGeographicAreaStyleRole(area.type, area.id, area.parentId, selection)
    val properties = JsonObject().apply {
        addProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY, area.id)
        addProperty(GEOGRAPHIC_STYLE_ROLE_PROPERTY, styleRole.name)
        addProperty(ADMIN_OVERLAY_VISITED_PROPERTY, visited.toString())
    }
    return Feature.fromGeometry(geoJsonGeometry, properties)
}
