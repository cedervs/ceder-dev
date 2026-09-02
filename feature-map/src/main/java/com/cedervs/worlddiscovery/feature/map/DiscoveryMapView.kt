package com.cedervs.worlddiscovery.feature.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellGeometry
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent
import com.cedervs.worlddiscovery.core.location.LocationObservation
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * **Provisional, dev-only style — not a product decision.** The rendering engine (MapLibre
 * Native) is decided (`docs/ai-context/ARCHITECTURE_DECISIONS.md`); the tile/style provider,
 * hosting, and offline packaging strategy are still open (`docs/ai-context/OPEN_QUESTIONS.md`).
 * OpenFreeMap's "Liberty" style — https://openfreemap.org — is used here only as a technical
 * validation basemap for this increment. No account, no API key, no commercial dependency. Must
 * be replaced once the real tile/style provider is decided; never treat this URL as a chosen
 * vendor.
 *
 * Not the original choice: MapLibre's own demo style (`https://demotiles.maplibre.org/style.json`)
 * was used first, but its vector source has real data only up to zoom 6 (confirmed by fetching
 * its TileJSON directly) and produced a solid black screen on physical-device testing when
 * overzoomed to street level — isolated via an A/B test (a bare diagnostic Activity, no World
 * Discovery logic, reproduced the same black screen with the demo style and rendered correctly
 * with this one) to be specific to that style/source's overzoom behavior, not a MapLibre Native
 * OpenGL 13.6.0 or device issue, and not this integration. OpenFreeMap's `openmaptiles` source
 * (confirmed via its own TileJSON) provides real worldwide vector data up to zoom 14 — actual
 * street/building-level detail — which this increment's physical validation needs.
 */
private const val DEV_ONLY_DEMO_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Minimal MapLibre Native rendering of locally stored discovered cells. No camera controls
 * beyond MapLibre's own defaults, no clustering, no basemap/style beyond the provisional demo
 * style above — this increment only proves that [DiscoveredCellGeometry] flowing out of
 * `ObserveDiscoveredCellGeometries` can reach real map geometry on screen (see
 * `DiscoveredCellGeometryRendering.kt` for the actual GeoJSON/style logic). Never modifies
 * discovery history — display only.
 *
 * Wraps the classic (non-Compose) `MapView` via `AndroidView` rather than the official
 * `maplibre-compose` library: that wrapper is still pre-1.0 (0.15.0) and documents known gaps/
 * bugs, too immature a dependency for this foundational increment.
 *
 * ## Lifecycle
 * [MapView] needs `onCreate`/`onStart`/`onResume`/`onPause`/`onStop`/`onDestroy` forwarded, and
 * this is driven from two independent sources, handled by [MapViewLifecycleController]:
 * - the composition's [LocalLifecycleOwner], for as long as this composable is both composed
 *   *and* the host lifecycle is genuinely transitioning (covers real backgrounding/foregrounding
 *   and a real Activity `ON_DESTROY`);
 * - `onDispose` below, which force-tears the [MapView] down to `onDestroy()` regardless of what
 *   the host lifecycle is doing — necessary because Navigation-Compose can dispose this
 *   composable's content (e.g. switching bottom-nav tabs) while the destination is kept on the
 *   back stack for `restoreState`, meaning its lifecycle never reaches `ON_STOP`/`ON_DESTROY` on
 *   its own. Composition leaving is the authoritative "this MapView instance is done" signal
 *   here, independent of lifecycle state.
 *
 * [MapViewLifecycleController.destroy] is idempotent, so running it from `onDispose` is safe
 * even when a real `ON_DESTROY` already ran it via the lifecycle observer.
 *
 * `onLowMemory`/`onTrimMemory` are not [androidx.lifecycle.Lifecycle.Event] values — Android
 * delivers them via `ComponentCallbacks2`, registered here directly on the application context
 * (available on any `Context`, no `MainActivity`/`WorldDiscoveryApplication` change needed) and
 * unregistered in the same `onDispose`.
 *
 * Both asynchronous MapLibre callbacks below (`getMapAsync`, `setStyle`'s style-loaded callback)
 * check [MapViewLifecycleController.isDestroyed] before touching any Compose state or calling
 * any further MapLibre API — they are plain callbacks registered directly on the [MapView], not
 * coroutines, so leaving composition does not by itself cancel them; a late callback firing after
 * disposal must not touch an abandoned map.
 *
 * ## Camera
 * A genuine [MapView] recreation (the same composition-leaves-then-returns case [destroy] above
 * handles) otherwise loses the camera: [MapViewLifecycleController]'s real adapter always passes
 * `onCreate` a fresh, empty `Bundle` — there is no Android-level saved instance state for a new
 * [MapView] to restore from. [MapCameraStateHolder] fills that specific gap: the camera is saved
 * on every `onCameraIdle` and restored once, right after the style is ready, only when this
 * effect key (`mapView`) itself changed — i.e. only across a genuine recreation, never from an
 * ordinary recomposition or a `geometries` update (those are driven by the separate effect below
 * and never touch the camera). [CameraIdleListenerRegistration] keeps the listener/map pair the
 * camera-idle callback was actually attached to (see its own doc comment for why that reference
 * has to be kept at all) and detaches it in `onDispose`, before [MapViewLifecycleController.destroy],
 * mirroring how the `ComponentCallbacks2` above is unregistered in the same place.
 *
 * ## Country overlay — component-level highlighting, click, camera fit, and back/up restoration
 * [visitedFranceComponents] ("exactly the France components that are themselves actually visited" —
 * empty when none are) is rendered by its own `LaunchedEffect` below, keyed only on the map/style
 * being ready and the visited-components data itself changing — **never** on camera/zoom, matching
 * the `geometries` effect's own principle. Highlighting follows real per-component presence: one
 * discovery in metropolitan France colors only metropolitan France, never Corsica or French Guiana
 * just because they share the same parent country — see `CountryOverlayRendering.kt`'s
 * `applyCountryOverlay` doc comment for the full rationale and its `PRODUCT CALIBRATION REQUIRED`
 * provisional zoom range.
 *
 * **Navigation follows the clicked geographic *component*, never the whole area.** France's real
 * geometry is three spatially separate pieces (metropolitan France, Corsica, French Guiana); tapping
 * one must fit the camera to *that piece alone* — tapping metropolitan France must never pull French
 * Guiana into the camera fit. See `CountryOverlayComponentNavigation.kt`'s `resolveClickedCountryComponent`
 * for exactly how the tapped component is identified and hardened against malformed/stale rendered
 * data (a plain positional index tagged on each rendered `Feature`, cross-checked against
 * [franceAreaId] — nothing here is France-specific, so the same mechanism transparently covers any
 * other fragmented entity once its reference geometry exists).
 *
 * A tap is handled by a single `OnMapClickListener`, attached/detached through
 * [MapClickListenerRegistration] (mirroring [CameraIdleListenerRegistration]'s own idempotent
 * lifecycle) rather than registered anonymously with no reference kept anywhere — [attach] happens
 * once per style-ready callback, and [MapClickListenerRegistration.detach] runs in `onDispose`,
 * before [MapViewLifecycleController.destroy], exactly like the camera-idle listener beside it. The
 * click closure uses [rememberUpdatedState] so it always sees the *current* [visitedFranceComponents]/
 * [franceAreaId] regardless of when it was registered.
 *
 * On a resolved component hit: [nextCountryFocusReturnCamera] decides the return camera — the
 * *first* pre-focus camera is preserved across repeated taps on different components, never
 * overwritten by a later tap while focus is already active. The camera then animates to fit *that
 * component's own* bounds ([GeographicAreaComponent.bounds], antimeridian-safe — see
 * `computeGeographicBounds`), with padding. This is a **one-time** navigation action per tap —
 * nothing here re-fits on every recomposition or every `visitedFranceComponents` data change.
 *
 * **Focus state survives [MapView] recreation and tab-leave/return, deliberately.** [countryFocusReturnCamera]
 * is composition-local (`remember`, reset on a genuine recreation like every other local `remember`
 * here) but is initialized from, and written through to, [CountryFocusStateHolder] — a small
 * process-lifetime holder adjacent to (never merged into) [MapCameraStateHolder]: that one tracks
 * "where the camera literally is right now" with no navigation meaning, this one tracks "is a
 * component focus active, and what should Back restore". Without this, a recreation while focus was
 * active would restore the *focused* camera (via [MapCameraStateHolder], as always) while silently
 * forgetting that focus was ever active — stranding the user with no way back. With it: the focused
 * camera restores as before, focus still reads as active, the visible Back button reappears, and
 * system Back (via [BackHandler], enabled only while focus is active) keeps working — all from the
 * same underlying state. [exitCountryFocus] is the single shared function both exit paths use, and
 * is the only place that clears [CountryFocusStateHolder.current] — after it runs, a later focus
 * action starts genuinely fresh, capturing a new return camera.
 *
 * **Physical prototype: basemap-aligned mainland-France border line.** See
 * `BasemapAlignedBorderRendering.kt`'s own doc comment for the full rationale — a narrow, reversible
 * experiment adding one extra `LineLayer` sourced from the basemap's own already-loaded vector data,
 * visible only while mainland France is visited, to test whether that removes the visible offset
 * between the geoBoundaries-derived orange border and the basemap's own OpenStreetMap-derived one.
 * Applied from the same effect as [applyCountryOverlay] below, never a separate subscription.
 *
 * ## Live current-position marker
 * [currentPosition] ("where am I right now") is rendered by a separate `LaunchedEffect` below,
 * deliberately structured exactly like the `geometries` effect it sits beside — same
 * `controller.isDestroyed`/`mapLibreMap?.style` guard, same "pass only the `Style`, never the
 * `MapLibreMap`" shape (see `applyCurrentPosition` in `CurrentPositionRendering.kt`), so it is
 * structurally impossible for that rendering path to touch the camera, not merely a convention
 * someone could accidentally break. Never added to the camera-restoration effect's own key list
 * above, and never triggers a camera move itself — the user stays free to pan/zoom while the
 * marker keeps updating underneath.
 */
@Composable
fun DiscoveryMapView(
    geometries: List<DiscoveredCellGeometry>,
    franceAreaId: String,
    visitedFranceComponents: List<GeographicAreaComponent>,
    currentPosition: LocationObservation?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    val controller = remember { MapViewLifecycleController(mapView.asLifecycleTarget()) }
    val cameraIdleListenerRegistration = remember { CameraIdleListenerRegistration() }
    val mapClickListenerRegistration = remember { MapClickListenerRegistration() }
    val currentFranceAreaId by rememberUpdatedState(franceAreaId)
    val currentVisitedFranceComponents by rememberUpdatedState(visitedFranceComponents)

    // Composition-local, single nullable slot: non-null means a country-component focus is active,
    // and holds the camera to return to. Initialized from -- and, on every change, written through
    // to -- CountryFocusStateHolder, a small process-lifetime holder adjacent to (never merged into)
    // MapCameraStateHolder, so this state survives a genuine MapView recreation instead of resetting
    // to null along with every other composition-local remember here. See the class doc comment's
    // "Country overlay" section.
    var countryFocusReturnCamera by remember { mutableStateOf(CountryFocusStateHolder.current) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    fun enterOrKeepCountryFocus(cameraBeforeThisClick: MapCameraState) {
        val next = nextCountryFocusReturnCamera(countryFocusReturnCamera, cameraBeforeThisClick)
        countryFocusReturnCamera = next
        CountryFocusStateHolder.current = next
    }

    fun exitCountryFocus() {
        val map = mapLibreMap
        val previousCamera = countryFocusReturnCamera
        if (map != null && previousCamera != null) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(previousCamera.toCameraPosition()))
        }
        countryFocusReturnCamera = null
        CountryFocusStateHolder.current = null
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event -> controller.dispatch(event) }
        lifecycleOwner.lifecycle.addObserver(observer)

        val componentCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() {
                controller.onLowMemory()
            }
            override fun onTrimMemory(level: Int) = Unit
        }
        context.applicationContext.registerComponentCallbacks(componentCallbacks)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(componentCallbacks)
            // Before destroy(): once the MapView is torn down there's nothing meaningful left to
            // unregister either listener from. Each detach() is a no-op if the async map/style
            // callback below never reached its own attach() yet — see
            // CameraIdleListenerRegistration/MapClickListenerRegistration's own doc comments.
            mapClickListenerRegistration.detach()
            cameraIdleListenerRegistration.detach()
            controller.destroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            if (controller.isDestroyed) return@getMapAsync
            map.setStyle(DEV_ONLY_DEMO_STYLE_URL) {
                if (controller.isDestroyed) return@setStyle
                // Restore this MapView instance's camera to wherever it was before — only meaningful
                // right here, once per genuine MapView recreation, after the style is actually ready
                // to accept it. A first launch in this process has nothing saved yet, so the map
                // keeps MapLibre's normal default camera. See MapCameraStateHolder's doc comment.
                MapCameraStateHolder.current?.let { savedCamera -> map.cameraPosition = savedCamera.toCameraPosition() }
                cameraIdleListenerRegistration.attach(map.asCameraIdleListenerTarget()) {
                    if (!controller.isDestroyed) {
                        MapCameraStateHolder.current = map.cameraPosition.toMapCameraState()
                    }
                }
                mapLibreMap = map

                // Attached once per MapView instance/style-ready callback via
                // MapClickListenerRegistration's idempotent attach() -- see the class doc comment's
                // "Country overlay" section for both that lifecycle and why rememberUpdatedState is
                // what keeps this seeing the latest visited-area data despite being set up only here.
                mapClickListenerRegistration.attach(map.asMapClickListenerTarget()) { latLng ->
                    if (controller.isDestroyed) return@attach false
                    val components = currentVisitedFranceComponents
                    if (components.isEmpty()) return@attach false
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val hitFeatures = map.queryRenderedFeatures(screenPoint, COUNTRY_OVERLAY_FILL_LAYER_ID)
                    val component = resolveClickedCountryComponent(
                        hitFeatures,
                        components,
                        currentFranceAreaId,
                        map.cameraPosition.zoom,
                    ) ?: return@attach false

                    val cameraBeforeThisClick = map.cameraPosition.toMapCameraState()
                    enterOrKeepCountryFocus(cameraBeforeThisClick)
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(component.bounds.toLatLngBounds(), COUNTRY_FOCUS_FIT_PADDING_PX),
                    )
                    true
                }
            }
        }
    }

    LaunchedEffect(mapLibreMap, geometries) {
        if (controller.isDestroyed) return@LaunchedEffect
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        applyDiscoveredCellGeometries(style, geometries)
    }

    LaunchedEffect(mapLibreMap, visitedFranceComponents) {
        if (controller.isDestroyed) return@LaunchedEffect
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        applyCountryOverlay(style, visitedFranceComponents)
        // PHYSICAL PROTOTYPE -- see BasemapAlignedBorderRendering.kt's own doc comment. Same
        // effect/key as applyCountryOverlay above (never a separate subscription), so both stay
        // perfectly in sync with the exact same visited-components snapshot.
        applyBasemapAlignedFranceBorder(style, visitedFranceComponents)
    }

    LaunchedEffect(mapLibreMap, currentPosition) {
        if (controller.isDestroyed) return@LaunchedEffect
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        applyCurrentPosition(style, currentPosition)
    }

    BackHandler(enabled = countryFocusReturnCamera != null) { exitCountryFocus() }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier)
        if (countryFocusReturnCamera != null) {
            Button(
                onClick = { exitCountryFocus() },
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            ) {
                Text(stringResource(R.string.map_country_focus_back))
            }
        }
    }
}
