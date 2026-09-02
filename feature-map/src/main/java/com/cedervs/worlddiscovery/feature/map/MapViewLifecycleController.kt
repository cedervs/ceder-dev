package com.cedervs.worlddiscovery.feature.map

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import org.maplibre.android.maps.MapView

/**
 * The subset of [MapView]'s lifecycle-forwarding methods [MapViewLifecycleController] needs,
 * extracted so its sequencing/idempotency logic is unit-testable without a real or
 * Robolectric-simulated [MapView] — this module has no Android test infrastructure otherwise.
 */
internal interface MapViewLifecycleTarget {
    fun onCreate()
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onLowMemory()
}

/** The real adapter used outside tests. Always passes a fresh, empty [Bundle] to `onCreate` —
 * this architecture never has real saved instance state to hand it. */
internal fun MapView.asLifecycleTarget(): MapViewLifecycleTarget = object : MapViewLifecycleTarget {
    override fun onCreate() = this@asLifecycleTarget.onCreate(Bundle())
    override fun onStart() = this@asLifecycleTarget.onStart()
    override fun onResume() = this@asLifecycleTarget.onResume()
    override fun onPause() = this@asLifecycleTarget.onPause()
    override fun onStop() = this@asLifecycleTarget.onStop()
    override fun onDestroy() = this@asLifecycleTarget.onDestroy()
    override fun onLowMemory() = this@asLifecycleTarget.onLowMemory()
}

/**
 * Forwards Android lifecycle events to a [MapView] (via [MapViewLifecycleTarget]) while it is
 * composed, and separately guarantees a full, idempotent teardown (pause → stop → destroy,
 * skipping steps already reached) exactly once.
 *
 * This exists because two independent things can end a [MapView]'s life, and either can happen
 * first:
 * 1. The host lifecycle reaching `ON_DESTROY` (a real Activity teardown) — forwarded via
 *    [dispatch].
 * 2. [DiscoveryMapView] leaving composition while the host lifecycle stays alive — Compose
 *    Navigation can dispose a destination's content without the host Activity, or even that
 *    destination's own back-stack-entry lifecycle, ever reaching `ON_STOP`/`ON_DESTROY` (state
 *    is kept for `restoreState`). [destroy] handles this path explicitly.
 *
 * [destroy] is safe to call from both places — only the first call does anything, so calling it
 * once from composition-leave and once from a genuine `ON_DESTROY` (in either order) is never a
 * dangerous double-destroy. Once destroyed, every method here is a no-op: no further lifecycle
 * event or [onLowMemory] can reach the [MapView] again, and [isDestroyed] lets callers (see
 * `DiscoveryMapView`'s asynchronous map-ready/style-loaded callbacks) check before touching
 * anything else on an abandoned map.
 */
internal class MapViewLifecycleController(private val target: MapViewLifecycleTarget) {
    private var stage = Stage.NONE

    val isDestroyed: Boolean
        get() = stage == Stage.DESTROYED

    private enum class Stage { NONE, CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED }

    fun dispatch(event: Lifecycle.Event) {
        if (isDestroyed) return
        when (event) {
            Lifecycle.Event.ON_CREATE -> if (stage == Stage.NONE) {
                target.onCreate()
                stage = Stage.CREATED
            }
            Lifecycle.Event.ON_START -> {
                target.onStart()
                stage = Stage.STARTED
            }
            Lifecycle.Event.ON_RESUME -> {
                target.onResume()
                stage = Stage.RESUMED
            }
            Lifecycle.Event.ON_PAUSE -> {
                target.onPause()
                stage = Stage.PAUSED
            }
            Lifecycle.Event.ON_STOP -> {
                target.onStop()
                stage = Stage.STOPPED
            }
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    /** Brings the target down to `onDestroy()`, calling only the steps not already reached.
     * A no-op if [isDestroyed] already, and a no-op (no `onDestroy()` call either) if `onCreate`
     * was never reached — there would be nothing valid to destroy. */
    fun destroy() {
        if (isDestroyed) return
        if (stage == Stage.NONE) {
            stage = Stage.DESTROYED
            return
        }
        if (stage == Stage.RESUMED) {
            target.onPause()
            stage = Stage.PAUSED
        }
        if (stage == Stage.STARTED || stage == Stage.PAUSED) {
            target.onStop()
            stage = Stage.STOPPED
        }
        target.onDestroy()
        stage = Stage.DESTROYED
    }

    /** Not part of [Lifecycle.Event] — see `DiscoveryMapView`'s doc comment for why this is
     * forwarded via `ComponentCallbacks2` registered directly on the application context rather
     * than through the lifecycle observer above. */
    fun onLowMemory() {
        if (!isDestroyed) target.onLowMemory()
    }
}
