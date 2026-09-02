package com.cedervs.worlddiscovery.core.location

import android.util.Log
import java.time.Instant

private const val BACKGROUND_LOCATION_CALIBRATION_LOG_TAG = "BackgroundLocationCalibration"

/**
 * The real, Android-backed [BackgroundLocationDiagnosticLogger] — writes only to Logcat, under a
 * tag deliberately distinct from [AndroidLocationDiagnosticLogger]'s `"LocationQuality"` so a
 * physical background-calibration trip can be captured in isolation, e.g.
 * `adb logcat -s BackgroundLocationCalibration:D`. Never a durable/permanent store, and only when
 * [isEnabled]. Wire this to `BuildConfig.DEBUG` in production (see `AppContainer`), matching every
 * other diagnostic-only logger in this module.
 *
 * Guaranteed never to throw, for the same reason as [AndroidLocationDiagnosticLogger]: this is
 * best-effort, throwaway diagnostic logging that must never affect whether registration or
 * submission proceeds. Catches [Throwable], not just [Exception], deliberately.
 */
class AndroidBackgroundLocationDiagnosticLogger(private val isEnabled: Boolean) : BackgroundLocationDiagnosticLogger {
    override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) {
        if (!isEnabled) return
        try {
            Log.d(BACKGROUND_LOCATION_CALIBRATION_LOG_TAG, formatBackgroundRegistrationLogMessage(config, outcome))
        } catch (t: Throwable) {
            // Best-effort diagnostic only — must never affect whether registration proceeded.
        }
    }

    override fun logDelivery(observations: List<LocationObservation>) {
        if (!isEnabled) return
        try {
            Log.d(BACKGROUND_LOCATION_CALIBRATION_LOG_TAG, formatBackgroundDeliveryLogMessage(observations, Instant.now()))
        } catch (t: Throwable) {
            // Best-effort diagnostic only — must never affect whether the batch is submitted.
        }
    }
}
