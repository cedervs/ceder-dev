package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent
import org.maplibre.geojson.Feature

/**
 * Resolves a map click into the single [GeographicAreaComponent] that was actually tapped — the
 * core of this round's product rule: **navigation follows the geographic component the user
 * clicked, never the whole administrative entity**. Tapping metropolitan France must fit only
 * metropolitan France; tapping Corsica must fit only Corsica; tapping French Guiana must fit only
 * French Guiana — the parent area may still know all three belong to one country (for future
 * statistics/hierarchy), but that knowledge never leaks into which bounds a click fits to.
 *
 * [hitFeatures] is whatever `MapLibreMap.queryRenderedFeatures` returned against the Country
 * overlay's own fill layer (see `DiscoveryMapView`'s click handler). Each rendered `Feature` is
 * tagged with [COUNTRY_OVERLAY_AREA_ID_PROPERTY] and [COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY] by
 * `CountryOverlayRendering.kt` — [componentIndex][GeographicAreaComponent.componentIndex] is purely
 * a positional index, nothing here hard-codes a component name or assumes how many components
 * exist, so the exact same mechanism transparently covers any other fragmented entity the moment
 * its own reference geometry is loaded. [visitedComponents] is the *current* set of components
 * actually being rendered (see `CountryOverlayRendering.kt`'s per-component-highlighting doc
 * comment) — matched by each component's own `componentIndex` *value*, never by its position in
 * this list, since [visitedComponents] is already a filtered subset and a rendered feature's tagged
 * index refers to the component's position within the *full* area, not within this subset.
 *
 * **Hardened against malformed/stale rendered data** — every one of the following must hold, or
 * this returns `null` (no navigation, never a crash):
 * - the overlay is still interactive at [currentZoomLevel] (not faded out — see
 *   [isCountryOverlayInteractive]);
 * - a feature was actually hit;
 * - the hit feature's [COUNTRY_OVERLAY_AREA_ID_PROPERTY] is present **and exactly equals**
 *   [currentAreaId] — a feature from a stale render (e.g. a previous area, in a hypothetical future
 *   multi-area world) is rejected even if its `componentIndex` would otherwise look valid;
 * - [COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY] is present, is a genuine **JSON number** — not a
 *   JSON string, even a numeric-looking one like `"1"` (see the raw-`JsonElement` inspection below;
 *   `Feature.getNumberProperty`/`JsonPrimitive.getAsNumber()` would otherwise silently *coerce* a
 *   string primitive into a `Number`, which is exactly the loophole this guards against) — **finite**,
 *   **exactly integer-valued** (never silently truncated — `1.5` is rejected outright, not coerced to
 *   `1`; see [parseStrictNonNegativeInt]), and non-negative;
 * - that exact index matches one of [visitedComponents]' own `componentIndex` values (an
 *   out-of-range or otherwise-unmatched index — e.g. a component that exists in the full area but
 *   isn't currently visited/rendered — resolves to nothing, not a fallback guess).
 */
internal fun resolveClickedCountryComponent(
    hitFeatures: List<Feature>,
    visitedComponents: List<GeographicAreaComponent>,
    currentAreaId: String,
    currentZoomLevel: Double,
): GeographicAreaComponent? {
    if (!isCountryOverlayInteractive(currentZoomLevel)) return null
    val hit = hitFeatures.firstOrNull() ?: return null

    val featureAreaId = hit.getStringProperty(COUNTRY_OVERLAY_AREA_ID_PROPERTY) ?: return null
    if (featureAreaId != currentAreaId) return null

    // Deliberately inspect the raw JsonElement rather than calling Feature.getNumberProperty(): that
    // convenience method (like JsonPrimitive.getAsNumber()) coerces ANY numeric-looking JSON value
    // -- including a JSON STRING primitive such as "1" or "01" -- into a Number, silently accepting
    // exactly the malformed shape this hardening exists to reject. isJsonPrimitive + isNumber (both
    // verified directly against the real Gson jar) check the value's actual underlying JSON type
    // before any numeric interpretation is attempted at all.
    val componentIndexElement = hit.getProperty(COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY) ?: return null
    if (!componentIndexElement.isJsonPrimitive) return null
    val componentIndexPrimitive = componentIndexElement.asJsonPrimitive
    if (!componentIndexPrimitive.isNumber) return null

    val componentIndex = parseStrictNonNegativeInt(componentIndexPrimitive.asNumber) ?: return null

    return visitedComponents.find { component -> component.componentIndex == componentIndex }
}

/**
 * Strictly interprets [number] as a non-negative array index: `null` unless it is finite,
 * *exactly* integer-valued, and `>= 0`. Deliberately rejects `1.5` rather than truncating it to `1`
 * — a rendered feature's `componentIndex` property is always written as a genuine integer by
 * `CountryOverlayRendering.kt`, so any non-integer, negative, `NaN`, or infinite value observed here
 * can only mean corrupted/stale/unexpected data, which must be rejected outright, not "helpfully"
 * coerced into some other, wrong component.
 */
private fun parseStrictNonNegativeInt(number: Number): Int? {
    val value = number.toDouble()
    if (!value.isFinite()) return null
    if (value != Math.floor(value)) return null
    if (value < 0.0) return null
    return value.toInt()
}

/**
 * Decides the "return camera" to remember when entering (or re-entering) component focus.
 * Deliberately **never overwrites an existing return camera**: [currentReturnCamera] is only ever
 * replaced by [cameraBeforeThisClick] the first time focus is entered from a genuinely unfocused
 * state ([currentReturnCamera] `== null`). A second tap on a different component while focus is
 * already active must still return to the camera from *before the very first* focus action, not to
 * the most recently focused component's own camera — otherwise repeated taps would silently erode
 * the user's actual starting point.
 */
internal fun nextCountryFocusReturnCamera(
    currentReturnCamera: MapCameraState?,
    cameraBeforeThisClick: MapCameraState,
): MapCameraState = currentReturnCamera ?: cameraBeforeThisClick
