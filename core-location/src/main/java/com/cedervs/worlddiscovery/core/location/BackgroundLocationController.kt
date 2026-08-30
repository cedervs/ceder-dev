package com.cedervs.worlddiscovery.core.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the decision of whether background location tracking should be armed right now. Checks
 * only [BackgroundTrackingConsent] — never permission, which is [BackgroundLocationRegistrar]'s
 * sole responsibility (see that interface's doc). Consent and permission are deliberately never
 * checked in the same place, so persisted consent can never be mistaken for permission.
 *
 * Used by [AppForegroundTrackingController] (foreground/background mutual exclusion, driven by
 * `ProcessLifecycleOwner`) and, via [armSuspending], by the boot-recovery receiver — the same
 * decision logic either way, so there is exactly one implementation of "should background
 * tracking be armed right now."
 */
class BackgroundLocationController(
    private val consent: BackgroundTrackingConsent,
    private val registrar: BackgroundLocationRegistrar,
    private val scope: CoroutineScope,
) {
    private var armJob: Job? = null

    /**
     * Called when the app leaves the foreground. Fire-and-forget but cancellable: if [disarm]
     * runs before this resolves (e.g. the app returns to the foreground again almost
     * immediately, before the consent flag has even finished reading from disk), the in-flight
     * attempt is cancelled before it can register anything — this is what guarantees foreground
     * and background are never simultaneously active by construction, not by timing luck.
     */
    fun arm() {
        armJob?.cancel()
        armJob = scope.launch { armSuspending() }
    }

    /**
     * Awaitable variant for a caller that must know the attempt has actually finished before its
     * own process might be killed (the boot-recovery receiver's `goAsync()` usage). Idempotent in
     * effect: calling it while background tracking is already correctly armed just re-registers
     * with the same parameters.
     */
    suspend fun armSuspending() {
        if (consent.isEnabled.first()) {
            registrar.register()
        }
    }

    /**
     * Called when the app returns to the foreground. Cancels any in-flight [arm] attempt and
     * unregisters — idempotent and safe even if nothing was ever registered.
     */
    fun disarm() {
        armJob?.cancel()
        armJob = null
        registrar.unregister()
    }
}
