package com.cedervs.worlddiscovery.core.location

/**
 * A monotonic time source, deliberately not wall-clock. [LocationTrackingSession]'s live-position
 * staleness window (see its doc comment) must never be affected by a wall-clock change — NTP
 * sync, timezone/DST, or the user manually changing the device clock — and must never trust
 * [LocationObservation.observedAt] (an `android.location.Location`-reported timestamp, which can
 * itself be old, zero, or otherwise unreliable — see `docs/ai-context/LOCATION_TRACKING.md`).
 * A tiny injectable interface, not a hard-wired system clock, so tests can supply a deterministic
 * fake instead of depending on real elapsed time.
 */
fun interface MonotonicClock {
    /** An arbitrary monotonically non-decreasing millisecond value — meaningful only as a
     * difference between two calls, never as an absolute wall-clock timestamp. */
    fun nowMillis(): Long
}

/** The real implementation — [System.nanoTime] is monotonic and immune to wall-clock changes,
 * unlike [System.currentTimeMillis]. Deliberately avoids any Android-specific clock API
 * (e.g. `android.os.SystemClock`) so this class, like the rest of [LocationTrackingSession]'s
 * dependencies, stays testable without Robolectric or a real Android runtime. */
class SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = System.nanoTime() / 1_000_000L
}
