package com.cedervs.worlddiscovery.core.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor

/**
 * [BackgroundLocationRegistrar] backed by `FusedLocationProviderClient.requestLocationUpdates`'s
 * `PendingIntent` form — the registration lives at the Play-services layer, independent of this
 * process, and can cold-start the app via the manifest-declared receiver it targets even after
 * process death (see `docs/architecture.md`'s background-tracking notes).
 *
 * The target receiver is identified by fully-qualified class name only (no action/intent-filter)
 * — it must match exactly the `<receiver>` entry in `app/src/main/AndroidManifest.xml` and the
 * actual `BackgroundLocationReceiver` class in `:app`. `:core-location` cannot depend on `:app`,
 * so this string is the one place that link is expressed from this side.
 */
class FusedBackgroundLocationRegistrar(
    context: Context,
    private val config: LocationUpdateConfig = LocationUpdateConfig.BACKGROUND_PROVISIONAL,
    private val diagnosticLogger: BackgroundLocationDiagnosticLogger = NoOpBackgroundLocationDiagnosticLogger(),
) : BackgroundLocationRegistrar {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

    // See performBackgroundLocationRegistration's doc comment for why an inline-run Executor is
    // used here instead of Play Services' own TaskExecutors.MAIN_THREAD default.
    private val diagnosticListenerExecutor = Executor { runnable -> runnable.run() }

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent().setClassName(appContext.packageName, RECEIVER_CLASS_NAME)
        PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            // Mutable: Play services must be able to add the LocationResult extras to the intent
            // it delivers — an immutable PendingIntent is rejected for this use.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    override fun register() {
        // hasBackgroundLocationPermission is the single authoritative permission check, rechecked
        // fresh on every call — see LocationPermissions' doc comment. Same PendingIntent identity
        // (matching request code + component) on every call, so a repeated register() replaces
        // rather than duplicates the standing request. Never cancelled or otherwise altered — see
        // performBackgroundLocationRegistration.
        performBackgroundLocationRegistration(
            hasPermission = LocationPermissions.hasBackgroundLocationPermission(appContext),
            startRequest = { fusedClient.requestLocationUpdates(backgroundLocationRequest(config), pendingIntent) },
            config = config,
            diagnosticLogger = diagnosticLogger,
            listenerExecutor = diagnosticListenerExecutor,
        )
    }

    override fun unregister() {
        // Documented as safe to call even when nothing is currently registered.
        fusedClient.removeLocationUpdates(pendingIntent)
    }

    companion object {
        private const val RECEIVER_CLASS_NAME = "com.cedervs.worlddiscovery.BackgroundLocationReceiver"
        private const val REQUEST_CODE = 1001
    }
}

/**
 * Builds the actual production background [LocationRequest] — extracted, like
 * [foregroundLocationRequest], so a test can inspect exactly what [FusedBackgroundLocationRegistrar]
 * registers instead of a test duplicating the builder call and silently drifting from it. See
 * [LocationUpdateConfig.BACKGROUND_PROVISIONAL]'s doc comment for what the current experimental
 * cadence values are and why.
 */
internal fun backgroundLocationRequest(config: LocationUpdateConfig): LocationRequest =
    LocationRequest.Builder(config.intervalMillis)
        .setPriority(config.priority)
        .setMinUpdateIntervalMillis(config.minUpdateIntervalMillis)
        .setMaxUpdateDelayMillis(config.maxUpdateDelayMillis)
        .build()

/**
 * The Context-free core of [FusedBackgroundLocationRegistrar.register] — the whole registration
 * decision (permission gate, then Task-completion diagnostics), extracted so a test can exercise
 * every outcome directly without needing a real `Context`, `PendingIntent`, or
 * `FusedLocationProviderClient` (none of which [startRequest] itself is required to touch; a test
 * can supply one that simply returns a real, already-completed [Task] from
 * [com.google.android.gms.tasks.Tasks.forResult]/[com.google.android.gms.tasks.Tasks.forException],
 * or a later-completed one from [com.google.android.gms.tasks.TaskCompletionSource]).
 * [hasPermission] is [register]'s single, freshly-evaluated call to
 * [LocationPermissions.hasBackgroundLocationPermission] — the actual authoritative check stays in
 * [register]; this function only branches on its already-computed result, never re-implements or
 * bypasses it.
 *
 * If [hasPermission] is false, [startRequest] is never invoked and only
 * [BackgroundRegistrationOutcome.SKIPPED_NO_PERMISSION] is logged.
 *
 * Otherwise: `requestLocationUpdates(LocationRequest, PendingIntent)` returns a [Task]`<Void>` —
 * the call returning normally only means the request was *submitted*, not that Play Services
 * actually accepted it; the Task can still complete unsuccessfully afterward.
 * [BackgroundRegistrationOutcome.REGISTERED] is therefore only logged from `addOnSuccessListener`,
 * once the Task has genuinely completed successfully, never right after [startRequest] returns.
 * [BackgroundRegistrationOutcome.FAILED_TASK] is logged from `addOnFailureListener` without ever
 * inspecting or logging the [Exception] itself — only this outcome tag is diagnostic-safe.
 * [BackgroundRegistrationOutcome.FAILED_SECURITY_EXCEPTION] stays the *synchronous* path: a
 * concurrent permission revocation racing the call itself, distinct from an asynchronous Task
 * failure.
 *
 * **Callback timing**: per the Tasks API contract, `addOnSuccessListener`/`addOnFailureListener`
 * may invoke their listener *immediately, inline* on the calling thread if the Task is already
 * complete by the time the listener is attached, or *later, on whatever thread completes it*, if
 * it completes afterward. Both are attached with [listenerExecutor] — deliberately run-inline
 * rather than Play Services' own `TaskExecutors.MAIN_THREAD` default, since [register] can be
 * called from a background coroutine dispatcher with no prepared `Looper` (`BootCompletedReceiver`,
 * `BackgroundLocationController`), and diagnostic logging has no main-thread requirement of its
 * own. Both listener bodies do exactly one thing — one best-effort, non-throwing
 * [logRegistrationSafely] call — and touch no shared mutable state, so neither timing needs any
 * additional synchronization here.
 */
internal fun performBackgroundLocationRegistration(
    hasPermission: Boolean,
    startRequest: () -> Task<Void>,
    config: LocationUpdateConfig,
    diagnosticLogger: BackgroundLocationDiagnosticLogger,
    listenerExecutor: Executor,
) {
    if (!hasPermission) {
        diagnosticLogger.logRegistrationSafely(config, BackgroundRegistrationOutcome.SKIPPED_NO_PERMISSION)
        return
    }

    try {
        startRequest()
            .addOnSuccessListener(listenerExecutor) {
                diagnosticLogger.logRegistrationSafely(config, BackgroundRegistrationOutcome.REGISTERED)
            }
            .addOnFailureListener(listenerExecutor) {
                diagnosticLogger.logRegistrationSafely(config, BackgroundRegistrationOutcome.FAILED_TASK)
            }
    } catch (e: SecurityException) {
        // Permission was just checked (hasPermission) but can still race with a concurrent
        // revocation; fail safely — nothing to submit, the next attempt re-checks from scratch.
        diagnosticLogger.logRegistrationSafely(config, BackgroundRegistrationOutcome.FAILED_SECURITY_EXCEPTION)
    }
}
