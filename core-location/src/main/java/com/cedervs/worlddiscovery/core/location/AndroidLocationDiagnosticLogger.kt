package com.cedervs.worlddiscovery.core.location

import android.util.Log
import java.time.Instant

private const val LOCATION_QUALITY_LOG_TAG = "LocationQuality"

/**
 * The real, Android-backed [LocationDiagnosticLogger] — writes only to Logcat (the platform's own
 * short-retention ring buffer), never to a durable/permanent store, and only when [isEnabled].
 * Wire this to `BuildConfig.DEBUG` in production (see `AppContainer`) so release builds stay
 * non-verbose by default with no separate opt-out mechanism needed.
 *
 * Guaranteed never to throw: this is best-effort, throwaway diagnostic logging, and a failure
 * here (a logging backend issue, an unexpected formatting edge case, anything at all) must never
 * affect whether the caller goes on to submit the observation to the discovery engine — see
 * [LocationDiagnosticLogger]'s doc comment. Catches [Throwable], not just [Exception]:
 * deliberately absolute for this one narrow, fully disposable path, where continuing after any
 * failure is strictly safer than letting it propagate into a functional code path that must not
 * depend on it.
 */
class AndroidLocationDiagnosticLogger(private val isEnabled: Boolean) : LocationDiagnosticLogger {
    override fun log(observation: LocationObservation) {
        if (!isEnabled) return
        try {
            Log.d(LOCATION_QUALITY_LOG_TAG, formatLocationObservationLogMessage(observation, Instant.now()))
        } catch (t: Throwable) {
            // Best-effort diagnostic only — must never affect the functional submission path.
        }
    }
}
