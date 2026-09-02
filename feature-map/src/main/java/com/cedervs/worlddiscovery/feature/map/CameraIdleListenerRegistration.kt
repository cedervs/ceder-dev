package com.cedervs.worlddiscovery.feature.map

import org.maplibre.android.maps.MapLibreMap

/**
 * The subset of [MapLibreMap]'s camera-idle-listener API this needs, extracted so the
 * attach/detach symmetry in [CameraIdleListenerRegistration] is unit-testable without a real
 * [MapLibreMap] — same pattern as [MapViewLifecycleTarget]/[MapViewLifecycleController].
 */
internal interface CameraIdleListenerTarget {
    fun addOnCameraIdleListener(onIdle: () -> Unit)
    fun removeOnCameraIdleListener()
}

/** The real adapter used outside tests. Keeps the exact [MapLibreMap.OnCameraIdleListener]
 * instance it registers, privately, so [removeOnCameraIdleListener] can hand that same instance
 * back to [MapLibreMap.removeOnCameraIdleListener] — removal only works by reference/equality to
 * the originally-added listener, not by any general "last listener" concept. */
internal fun MapLibreMap.asCameraIdleListenerTarget(): CameraIdleListenerTarget =
    object : CameraIdleListenerTarget {
        private var attachedListener: MapLibreMap.OnCameraIdleListener? = null

        override fun addOnCameraIdleListener(onIdle: () -> Unit) {
            val listener = MapLibreMap.OnCameraIdleListener { onIdle() }
            attachedListener = listener
            this@asCameraIdleListenerTarget.addOnCameraIdleListener(listener)
        }

        override fun removeOnCameraIdleListener() {
            val listener = attachedListener ?: return
            this@asCameraIdleListenerTarget.removeOnCameraIdleListener(listener)
            attachedListener = null
        }
    }

/**
 * Idempotent attach/detach of a single camera-idle listener to whichever [CameraIdleListenerTarget]
 * (in practice, a specific [MapLibreMap] instance) it was last attached to. Exists because the
 * listener registered inside `DiscoveryMapView`'s `setStyle` callback previously had no reference
 * kept anywhere reachable from `onDispose`, so it was never removed — this makes "exactly one
 * target attached at a time, detach always cleans it up" a small, separately testable invariant
 * rather than inline state scattered across two Compose effects.
 *
 * [attach] is a no-op if something is already attached (nothing here re-attaches without an
 * explicit [detach] first) and [detach] is a no-op if nothing is currently attached — both safe to
 * call from a path that may or may not have run before (e.g. `onDispose` running before the
 * asynchronous map/style callback ever reached [attach] at all).
 */
internal class CameraIdleListenerRegistration {
    private var attachedTarget: CameraIdleListenerTarget? = null

    fun attach(target: CameraIdleListenerTarget, onIdle: () -> Unit) {
        if (attachedTarget != null) return
        target.addOnCameraIdleListener(onIdle)
        attachedTarget = target
    }

    fun detach() {
        val target = attachedTarget ?: return
        target.removeOnCameraIdleListener()
        attachedTarget = null
    }
}
