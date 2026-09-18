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
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaVisitedStatus
import com.cedervs.worlddiscovery.core.discovery.RouteSegment
import com.cedervs.worlddiscovery.core.discovery.clipRouteSegmentsToArea
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
 * empty when none are) is rendered by its own `LaunchedEffect` below, keyed on the map/style being
 * ready, the visited-components data changing, AND the current geographic focus/selection changing
 * (physical-validation correction round: fill/outline color is now selection-relative, see
 * `GeographicHierarchyStyling.kt`, so a focus change alone — no new discovery — must still re-color)
 * — **never** on raw camera/zoom, matching the `geometries` effect's own principle. Highlighting
 * follows real per-component presence: one discovery in metropolitan France colors only metropolitan
 * France, never Corsica or French Guiana just because they share the same parent country — see
 * `CountryOverlayRendering.kt`'s `applyCountryOverlay` doc comment for the full rationale and its
 * `PRODUCT CALIBRATION REQUIRED` provisional zoom range.
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
 * On any resolved hit (Country component, Region, or Department): [nextGeographicSelectionOutcome]
 * (see `AdministrativeAreaNavigation.kt`'s own doc comment) is the single pure decision the click
 * handler applies — it decides the return camera (via [nextCountryFocusReturnCamera]: the *first*
 * pre-focus camera is preserved across repeated taps, never overwritten by a later tap while focus is
 * already active), the next Region/Department focus stack, and the camera target bounds, all at once.
 * The camera then animates to fit *that resolved area/component's own* bounds
 * ([GeographicAreaComponent.bounds]/[com.cedervs.worlddiscovery.core.discovery.GeographicArea.bounds],
 * antimeridian-safe — see `computeGeographicBounds`), with padding — **unconditionally, every time**,
 * regardless of any manual pan/zoom that preceded the tap (physical-validation fix: previously a
 * genuinely resolved click could still leave the camera on France after drilling into a Department and
 * zooming out; the camera-fit call itself was always unconditional, so the actual bug was
 * [eligibleClickLevels] not allowing an ancestor to resolve at all from deep focus — now fixed). This
 * is a **one-time** navigation action per tap — nothing here re-fits on every recomposition or every
 * `visitedFranceComponents` data change.
 *
 * **Selecting an ancestor clears descendant focus.** A resolved [GeographicClickResolution.CountryComponent]
 * always clears [adminFocusStack] to `emptyList()` via [nextGeographicSelectionOutcome] — selecting
 * Country can never leave a stale Region/Department frame behind. Selecting a Region always collapses
 * to a single Region frame ([nextAdminFocusStack]'s own already-validated behavior), which already
 * discards any Department frame from the previous Region's subtree.
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
 * ## Region/Department focus — additive, on top of Country focus, never merged into it
 * [admin1Statuses]/[admin2Statuses] extend the same "click the overlay, fit its bounds, remember how
 * to get back" idea one and two levels below Country — see `AdministrativeOverlayRendering.kt`/
 * `AdministrativeAreaNavigation.kt`/`AdministrativeFocusStateHolder.kt`. **FH-1 runtime hierarchy
 * fix: these carry EVERY loaded Region/Department, not only visited ones** — administrative
 * existence and discovery presence are different concepts, so an unvisited Region/Department must
 * remain navigable. [administrativeRenderCandidates] does the actual PARENT-SCOPED filtering (all 13
 * Regions always render; Departments are restricted to whichever Region is currently focused) right
 * before rendering, in the same effect that calls [applyAdministrativeOverlay] below — see that
 * function's own doc comment for why Regions and Departments need different scoping rules.
 *
 * **Click resolution is hierarchy-aware AND parent-scoped, not priority-ordered and not geographic-
 * overlap-only.** [currentGeographicFocusLevel] reads whichever level is currently focused (or
 * `null`, none) and [resolveGeographicClick] (delegating to `AdministrativeAreaNavigation.kt`'s
 * [eligibleClickLevels]) only ever attempts the level(s) actually eligible from there — see that
 * function's own doc comment for the exact per-state eligible-level table. [currentFocusedCountryId]/
 * [currentFocusedAdmin1Id]/[currentFocusedAdmin2Id] additionally give it the REAL currently-focused
 * ancestor ids, so a Region/Department candidate is only ever resolved if its own real
 * [com.cedervs.worlddiscovery.core.discovery.GeographicArea.parentId] actually matches the focused
 * parent — never merely because its polygon happens to be hit-tested at the same point (the Codex
 * re-review "parent-scoped click resolution" fix: focus Île-de-France, pan to Nouvelle-Aquitaine,
 * tap Haute-Vienne must never resolve as a Department attached under the still-focused
 * Île-de-France). Country resolution is also eligible again as a **fallback**, after Region, while
 * Country-focused — restoring the pre-hierarchy-gating ability to switch between a fragmented
 * country's own components (mainland <-> Corsica <-> French Guiana), without ever letting that
 * fallback win over a genuinely eligible Region hit. This click handler still always queries all
 * three layers' `queryRenderedFeatures` up front (cheap, and it keeps the decision itself
 * pure/testable — see [GeographicClickContext]), but only the features for an *eligible,
 * parent-scoped* level ever get inspected.
 *
 * **Ancestor selection is reachable from ANY focus depth (physical-validation fix, this round).**
 * `COUNTRY` is now an eligible fallback level not only from Country focus but from Region and
 * Department focus too, and `ADMIN_1` is additionally eligible as a fallback from Department focus —
 * see [eligibleClickLevels]'s own doc comment for the exact per-state table. This is what makes the
 * physically-observed bug ("drill into a Department, manually zoom out until only France is visible,
 * tap France, nothing reliably happens") actually fixable: previously `COUNTRY` was never even
 * attempted once focus had moved past it, so a tap on France while Region/Department-focused simply
 * failed to resolve at all. An ancestor fallback is always tried *last*, after every more specific
 * level in front of it, so it can never shadow a genuinely eligible descendant hit.
 *
 * [AdministrativeFocusStateHolder] is a small, separate process-lifetime stack (composed beside,
 * never merged into, [CountryFocusStateHolder]) so the already physically-validated single-level
 * Country focus code is never touched by this addition. The visible Back button and system
 * `BackHandler` are unified across all three levels ([goBackOneGeographicFocusLevel]: pop one
 * Region/Department frame if present, otherwise fall through to the existing [exitCountryFocus]) —
 * from the user's perspective, Back always undoes exactly the last focus action, at whichever level
 * it happened.
 *
 * ## Department-level derived first-discovery corridor
 * [routeSegments] ("derived first-discovery corridor segments — never a GPS route, never canonical
 * discovery truth, see `DiscoveredRoute.kt`'s own file-level doc comment for the full 'what this
 * explicitly is NOT' list — globally computed, not yet Department-clipped") is clipped down to
 * whichever Department is the actual current selection ([currentFocusedAdmin2Id], via
 * [com.cedervs.worlddiscovery.core.discovery.clipRouteSegmentsToArea]) in the same effect as the
 * Country/Region/Department overlays above, and rendered by [applyRouteOverlay] — see
 * `RouteOverlayRendering.kt`'s own doc comment for the exact halo/core/node layer shape and z-order.
 * Empty (clearing the overlay) whenever no Department is actively selected — this is deliberately
 * Department-scoped only, never shown at Region/Country/World scale.
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
    admin1Statuses: List<GeographicAreaVisitedStatus>,
    admin2Statuses: List<GeographicAreaVisitedStatus>,
    routeSegments: List<RouteSegment>,
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
    val currentAdmin1Statuses by rememberUpdatedState(admin1Statuses)
    val currentAdmin2Statuses by rememberUpdatedState(admin2Statuses)

    // Composition-local, single nullable slot: non-null means a country-component focus is active,
    // and holds the camera to return to. Initialized from -- and, on every change, written through
    // to -- CountryFocusStateHolder, a small process-lifetime holder adjacent to (never merged into)
    // MapCameraStateHolder, so this state survives a genuine MapView recreation instead of resetting
    // to null along with every other composition-local remember here. See the class doc comment's
    // "Country overlay" section.
    var countryFocusReturnCamera by remember { mutableStateOf(CountryFocusStateHolder.current) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    // Region/Department focus stack -- see AdministrativeFocusStateHolder's own doc comment for why
    // this is a small, additive holder composed *alongside* countryFocusReturnCamera above, never
    // merged into it: the existing single-slot Country-component focus stays completely untouched.
    var adminFocusStack by remember { mutableStateOf(AdministrativeFocusStateHolder.current) }

    fun exitCountryFocus() {
        val map = mapLibreMap
        val previousCamera = countryFocusReturnCamera
        if (map != null && previousCamera != null) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(previousCamera.toCameraPosition()))
        }
        countryFocusReturnCamera = null
        CountryFocusStateHolder.current = null
    }

    /** The level currently focused, if any — `null` (no focus), [GeographicAreaType.COUNTRY], or
     * whichever [AdminFocusFrame.areaType] sits on top of [adminFocusStack]. This drives
     * [eligibleClickLevels]/[resolveGeographicClick] (see `AdministrativeAreaNavigation.kt`'s own
     * doc comment) — the hierarchy-aware click-eligibility fix that stops an ancestor level from
     * intercepting a descendant's click. Read fresh on every click (a plain expression, not a
     * remembered value) so it always reflects whichever focus action most recently ran. */
    fun currentGeographicFocusLevel(): GeographicAreaType? = when {
        adminFocusStack.isNotEmpty() -> adminFocusStack.last().areaType
        countryFocusReturnCamera != null -> GeographicAreaType.COUNTRY
        else -> null
    }

    /** The generic, id-based focus-context fields [GeographicClickContext] needs for PARENT-SCOPED
     * click resolution (Codex re-review fix — see `AdministrativeAreaNavigation.kt`'s own doc
     * comment): which Country is focused (this app only ever loads one, [franceAreaId], but the
     * field itself carries no France-specific assumption), and which real Region/Department id is
     * currently on top of [adminFocusStack], if any. Read fresh on every click, exactly like
     * [currentGeographicFocusLevel]. */
    fun currentFocusedCountryId(): String? = if (countryFocusReturnCamera != null) currentFranceAreaId else null
    fun currentFocusedAdmin1Id(): String? = adminFocusStack.firstOrNull { it.areaType == GeographicAreaType.ADMIN_1 }?.areaId
    fun currentFocusedAdmin2Id(): String? = adminFocusStack.firstOrNull { it.areaType == GeographicAreaType.ADMIN_2 }?.areaId

    /** The generic "what's currently selected" representation [resolveGeographicAreaStyleRole] needs
     * (see `GeographicHierarchyStyling.kt`) — derived from exactly the same focus state
     * [currentGeographicFocusLevel]/[currentFocusedCountryId]/[currentFocusedAdmin1Id]/
     * [currentFocusedAdmin2Id] already read, so styling and click-resolution can never disagree about
     * what's focused. Read fresh on every recomposition (a plain expression, not a remembered value),
     * exactly like those functions. */
    fun currentGeographicFocusSelection(): GeographicFocusSelection {
        val level = currentGeographicFocusLevel() ?: return GeographicFocusSelection.NONE
        val selectedId = when (level) {
            GeographicAreaType.COUNTRY -> currentFocusedCountryId()
            GeographicAreaType.ADMIN_1 -> currentFocusedAdmin1Id()
            GeographicAreaType.ADMIN_2 -> currentFocusedAdmin2Id()
            else -> null
        } ?: return GeographicFocusSelection.NONE
        return GeographicFocusSelection(selectedType = level, selectedId = selectedId)
    }

    /** Unified Back action: pop one Region/Department frame first (see `AdministrativeAreaNavigation.kt`'s
     * own doc comment for why this stack is checked before falling through) — only once it's already
     * empty does Back fall through to the existing, untouched [exitCountryFocus] path. From the
     * user's perspective this reads as one continuous Back stack across all three levels. */
    fun goBackOneGeographicFocusLevel() {
        val backResult = adminFocusBack(adminFocusStack)
        if (backResult != null) {
            mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(backResult.cameraToRestore.toCameraPosition()))
            adminFocusStack = backResult.newStack
            AdministrativeFocusStateHolder.current = backResult.newStack
        } else {
            exitCountryFocus()
        }
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
                    val screenPoint = map.projection.toScreenLocation(latLng)
                    val zoom = map.cameraPosition.zoom
                    val cameraBeforeThisClick = map.cameraPosition.toMapCameraState()

                    // Hierarchy-aware, PARENT-SCOPED click resolution -- see
                    // AdministrativeAreaNavigation.kt's own doc comment (both correction rounds:
                    // eligibility by focus TYPE, then candidate filtering by the actually-focused
                    // PARENT's real id). currentGeographicFocusLevel() gates which level(s)
                    // resolveGeographicClick even attempts; the three currentFocused*Id() calls give
                    // it the real ancestor ids to scope Region/Department candidates against, so a
                    // Region/Department belonging to a different, unfocused parent can never resolve
                    // as if it were the focused parent's own child, regardless of geographic overlap.
                    val clickContext = GeographicClickContext(
                        currentFocusLevel = currentGeographicFocusLevel(),
                        focusedCountryId = currentFocusedCountryId(),
                        focusedAdmin1Id = currentFocusedAdmin1Id(),
                        focusedAdmin2Id = currentFocusedAdmin2Id(),
                        zoomLevel = zoom,
                        countryHitFeatures = map.queryRenderedFeatures(screenPoint, COUNTRY_OVERLAY_FILL_LAYER_ID),
                        visitedCountryComponents = currentVisitedFranceComponents,
                        countryAreaId = currentFranceAreaId,
                        regionHitFeatures = map.queryRenderedFeatures(screenPoint, ADMIN1_OVERLAY_FILL_LAYER_ID),
                        // Every loaded Region/Department, regardless of visited state -- resolveGeographicClick
                        // itself does the real PARENT-SCOPING (AdministrativeAreaNavigation.kt's own doc
                        // comment) before ever matching against a hit feature, and hit features themselves are
                        // already bounded to whatever administrativeRenderCandidates actually rendered (see the
                        // styling effect below), so passing the full lists here is both correct and simple.
                        regions = currentAdmin1Statuses.map { status -> status.area },
                        departmentHitFeatures = map.queryRenderedFeatures(screenPoint, ADMIN2_OVERLAY_FILL_LAYER_ID),
                        departments = currentAdmin2Statuses.map { status -> status.area },
                    )

                    val resolution = resolveGeographicClick(clickContext)
                    if (resolution == null) {
                        false
                    } else {
                        // Single pure decision for the whole state transition -- see
                        // AdministrativeAreaNavigation.kt's own doc comment. Fixes this round's
                        // physical camera bug: an ancestor selection (Country, or a Region reselected
                        // from Department depth) now always clears any stale deeper focus, and the
                        // camera unconditionally fits the newly selected geography's own bounds,
                        // regardless of any manual pan/zoom that preceded the tap.
                        val outcome = nextGeographicSelectionOutcome(resolution, countryFocusReturnCamera, adminFocusStack, cameraBeforeThisClick)
                        countryFocusReturnCamera = outcome.nextCountryFocusReturnCamera
                        CountryFocusStateHolder.current = outcome.nextCountryFocusReturnCamera
                        adminFocusStack = outcome.nextAdminFocusStack
                        AdministrativeFocusStateHolder.current = outcome.nextAdminFocusStack
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(outcome.cameraTargetBounds.toLatLngBounds(), COUNTRY_FOCUS_FIT_PADDING_PX),
                        )
                        true
                    }
                }
            }
        }
    }

    LaunchedEffect(mapLibreMap, geometries) {
        if (controller.isDestroyed) return@LaunchedEffect
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        applyDiscoveredCellGeometries(style, geometries)
    }

    // Re-applies on EITHER a visited-data change OR a focus change (adminFocusStack/
    // countryFocusReturnCamera) -- selection-relative styling (see GeographicHierarchyStyling.kt)
    // means color now depends on the current focus, not only on which areas are visited, so a focus
    // change alone (no new discovery) must still re-tag and re-render every feature's styleRole. Also
    // keyed on routeSegments (new discovery data can change the derived route independently of any
    // focus/hierarchy change) -- see RouteOverlayRendering.kt's own doc comment.
    LaunchedEffect(mapLibreMap, visitedFranceComponents, admin1Statuses, admin2Statuses, adminFocusStack, countryFocusReturnCamera, routeSegments) {
        if (controller.isDestroyed) return@LaunchedEffect
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val selection = currentGeographicFocusSelection()
        applyCountryOverlay(style, visitedFranceComponents, selection)
        // PHYSICAL PROTOTYPE -- see BasemapAlignedBorderRendering.kt's own doc comment. Same
        // effect/key as applyCountryOverlay above (never a separate subscription), so both stay
        // perfectly in sync with the exact same visited-components snapshot AND the same selection.
        applyBasemapAlignedFranceBorder(
            style,
            visitedFranceComponents,
            resolveGeographicAreaStyleRole(GeographicAreaType.COUNTRY, currentFranceAreaId, null, selection),
        )
        // Region/Department overlay -- same effect/single-snapshot principle, see
        // AdministrativeOverlayRendering.kt's own doc comment. Never touches the Country overlay's
        // own source/layers above. PARENT-SCOPED render candidates (FH-1 runtime hierarchy fix):
        // every loaded Region always renders (all 13, visited or not); Departments are restricted to
        // whichever Region is currently focused (empty at Country/World view) -- see
        // administrativeRenderCandidates's own doc comment for why this never renders all 96
        // Departments at once.
        val renderCandidates = administrativeRenderCandidates(admin1Statuses, admin2Statuses, currentFocusedAdmin1Id())
        applyAdministrativeOverlay(style, renderCandidates.regions, renderCandidates.departments, selection)
        // Department-level route visualization -- see RouteOverlayRendering.kt's own doc comment.
        // Only ever non-empty while an ADMIN_2 is the actual current selection (never at Region/
        // Country/World scale, per this round's own explicit "no blue spaghetti clutter" requirement)
        // -- clipped to that specific Department's own real geometry, never every route in France.
        // Looked up from the COMPLETE admin2Statuses (not renderCandidates.departments) since a
        // selected Department must resolve its own geometry for clipping regardless of visited state
        // -- an unvisited selected Department simply clips to naturally-empty route data, never fails
        // to resolve at all (FH-1 runtime hierarchy fix).
        val selectedDepartmentId = currentFocusedAdmin2Id()
        val selectedDepartmentArea = selectedDepartmentId?.let { id -> admin2Statuses.find { status -> status.area.id == id }?.area }
        val departmentRouteSegments = if (selectedDepartmentArea != null) {
            clipRouteSegmentsToArea(routeSegments, selectedDepartmentArea)
        } else {
            emptyList()
        }
        applyRouteOverlay(style, departmentRouteSegments)
    }

    LaunchedEffect(mapLibreMap, currentPosition) {
        if (controller.isDestroyed) return@LaunchedEffect
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        applyCurrentPosition(style, currentPosition)
    }

    val anyGeographicFocusActive = adminFocusStack.isNotEmpty() || countryFocusReturnCamera != null
    BackHandler(enabled = anyGeographicFocusActive) { goBackOneGeographicFocusLevel() }

    Box(modifier = modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier)
        if (anyGeographicFocusActive) {
            Button(
                onClick = { goBackOneGeographicFocusLevel() },
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            ) {
                Text(stringResource(R.string.map_country_focus_back))
            }
        }
    }
}
