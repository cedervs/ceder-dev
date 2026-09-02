package com.cedervs.worlddiscovery.feature.map

/**
 * The single reset contract for **all** in-memory map-navigation process state: both
 * [MapCameraStateHolder]'s last saved camera and [CountryFocusStateHolder]'s active-focus/return
 * camera. These two must always be cleared **together**, never independently — a real
 * authentication/session transition (logout, a different login, an account/session replacement)
 * must never let a new session inherit a previous one's camera position, an active
 * component-focus, or the ability to Back out of it.
 *
 * **Not public API for casual use.** This is deliberately called from exactly one place — the
 * real auth/session hook (see `AppContainer`'s `init` block, which observes
 * `AuthRepository.sessionState`) — and nowhere else. In particular, [DiscoveryMapView] itself never
 * calls this: a genuine [org.maplibre.android.maps.MapView] recreation, a bottom-nav tab switch, or
 * an ordinary Compose recomposition must all continue to *preserve* navigation state (that is the
 * entire reason [MapCameraStateHolder]/[CountryFocusStateHolder] exist as process-lifetime holders
 * in the first place) — none of those are session transitions, and none of them should ever reach
 * this function.
 *
 * Deliberately just these two fields, nothing more: no navigation stack, no persistence, and no
 * coupling to geographic reference data (the France reference artifact is loaded once per process
 * and is not session-scoped — a session change never needs to reload or reclassify it).
 *
 * Public (not `internal`): the real auth/session hook lives in `:app`'s `AppContainer` — a
 * separate Gradle module from `:feature-map` — so this needs to be visible across that module
 * boundary. Everything it delegates to ([MapCameraStateHolder], [CountryFocusStateHolder]) stays
 * `internal`, reachable only through this one function.
 */
object MapNavigationStateResetter {
    fun reset() {
        MapCameraStateHolder.current = null
        CountryFocusStateHolder.current = null
    }
}
