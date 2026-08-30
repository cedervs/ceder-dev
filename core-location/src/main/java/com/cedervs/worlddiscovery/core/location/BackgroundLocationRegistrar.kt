package com.cedervs.worlddiscovery.core.location

/**
 * Registers/unregisters a `PendingIntent`-based background location subscription — event-driven
 * via a manifest-declared broadcast receiver, not a live collector. This is not [LocationUpdatesProvider]
 * again under a different name: that interface models "collect while something stays subscribed";
 * this one models "arm a standing request that keeps delivering even if nothing in this process
 * is listening, including across process death."
 *
 * [register] is the single authoritative check for whether background location is actually
 * permitted right now — callers (see [BackgroundLocationController]) never duplicate that check
 * themselves; they only decide *whether to ask*, never *whether it's allowed*. Both methods are
 * idempotent: calling either when already in the target state is safe and a no-op in effect.
 */
interface BackgroundLocationRegistrar {
    fun register()
    fun unregister()
}
