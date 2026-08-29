package com.cedervs.worlddiscovery.core.location

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Starts/stops a [LocationTrackingSession] in step with the *application's* foreground state —
 * not any single screen or Activity. Registered against `ProcessLifecycleOwner.get().lifecycle`
 * by the composition root (`AppContainer`), so tracking runs whenever World Discovery is in the
 * foreground regardless of which tab is visible, and stops the moment the whole app is
 * backgrounded. This is still not background tracking: there is no service, no WorkManager, and
 * no persisted "was a session active" flag — killing the process always resets to
 * [TrackingSessionState.Idle].
 *
 * Takes a [LifecycleOwner] rather than hardcoding `ProcessLifecycleOwner` so this stays testable
 * with any [LifecycleOwner] test double (e.g. one backed by `LifecycleRegistry`).
 */
class AppForegroundTrackingController(
    private val session: LocationTrackingSession,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        session.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        session.stop()
    }
}
