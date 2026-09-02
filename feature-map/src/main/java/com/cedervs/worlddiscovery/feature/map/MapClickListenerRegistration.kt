package com.cedervs.worlddiscovery.feature.map

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * The subset of [MapLibreMap]'s map-click-listener API this needs, extracted so the attach/detach
 * symmetry in [MapClickListenerRegistration] is unit-testable without a real [MapLibreMap] — same
 * pattern as [CameraIdleListenerTarget]/[CameraIdleListenerRegistration].
 */
internal interface MapClickListenerTarget {
    fun addOnMapClickListener(onClick: (LatLng) -> Boolean)
    fun removeOnMapClickListener()
}

/** The real adapter used outside tests. Keeps the exact [MapLibreMap.OnMapClickListener] instance
 * it registers, privately, so [removeOnMapClickListener] can hand that same instance back to
 * [MapLibreMap.removeOnMapClickListener] — removal only works by reference/equality to the
 * originally-added listener, not by any general "last listener" concept (verified directly against
 * the real MapLibre jar: `removeOnMapClickListener(OnMapClickListener)` takes the listener back). */
internal fun MapLibreMap.asMapClickListenerTarget(): MapClickListenerTarget =
    object : MapClickListenerTarget {
        private var attachedListener: MapLibreMap.OnMapClickListener? = null

        override fun addOnMapClickListener(onClick: (LatLng) -> Boolean) {
            val listener = MapLibreMap.OnMapClickListener { latLng -> onClick(latLng) }
            attachedListener = listener
            this@asMapClickListenerTarget.addOnMapClickListener(listener)
        }

        override fun removeOnMapClickListener() {
            val listener = attachedListener ?: return
            this@asMapClickListenerTarget.removeOnMapClickListener(listener)
            attachedListener = null
        }
    }

/**
 * Idempotent attach/detach of a single map-click listener to whichever [MapClickListenerTarget] (in
 * practice, a specific [MapLibreMap] instance) it was last attached to — the exact same lifecycle
 * shape as [CameraIdleListenerRegistration], applied to the Country-overlay click listener that was
 * previously registered anonymously inside `DiscoveryMapView`'s `setStyle` callback with no
 * reference kept anywhere reachable from `onDispose`, so it was never removed.
 *
 * [attach] is a no-op if something is already attached (nothing here re-attaches without an
 * explicit [detach] first) and [detach] is a no-op if nothing is currently attached — both safe to
 * call from a path that may or may not have run before (e.g. `onDispose` running before the
 * asynchronous map/style callback ever reached [attach] at all, or a style reload re-running the
 * style-ready callback while the previous listener is still attached to the same live map).
 */
internal class MapClickListenerRegistration {
    private var attachedTarget: MapClickListenerTarget? = null

    fun attach(target: MapClickListenerTarget, onClick: (LatLng) -> Boolean) {
        if (attachedTarget != null) return
        target.addOnMapClickListener(onClick)
        attachedTarget = target
    }

    fun detach() {
        val target = attachedTarget ?: return
        target.removeOnMapClickListener()
        attachedTarget = null
    }
}
