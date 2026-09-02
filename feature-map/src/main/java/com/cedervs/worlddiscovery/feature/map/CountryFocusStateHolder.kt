package com.cedervs.worlddiscovery.feature.map

/**
 * Holds the "country/component focus" navigation state for the lifetime of the process —
 * deliberately in-memory only, and independent of any particular [org.maplibre.android.maps.MapView]
 * instance, mirroring [MapCameraStateHolder]'s own process-lifetime shape but for a genuinely
 * separate concern: [MapCameraStateHolder] tracks "where the camera literally is right now" (raw
 * camera state, updated on every `onCameraIdle`, with no navigation semantics), while this tracks
 * "is a geographic-component focus currently active, and if so, what camera should Back restore" —
 * kept as a small adjacent holder rather than overloading [MapCameraStateHolder] with unrelated
 * geographic-navigation meaning.
 *
 * **Why this needs to survive [org.maplibre.android.maps.MapView] recreation** (Navigation-Compose
 * disposing/recomposing `DiscoveryMapView` — see its own doc comment): without this, a composition-
 * local-only "return camera" (a bare `remember { mutableStateOf(...) } `) is lost exactly when a
 * recreation happens while focus is active — the *focused* camera would still be restored (via
 * [MapCameraStateHolder], since that's whatever the camera last settled on), but focus would then
 * silently read as inactive, stranding the user in a fitted single-component view with no way back
 * to where they started. Mirroring this into a process-lifetime holder, exactly like the camera
 * itself already is, keeps the two coherent: "recreation preserves what was on screen" now also
 * means "recreation preserves the ability to get back to what was on screen before that."
 *
 * `null` means no focus is active (nothing to return to) — the normal state, and always the state
 * after [exitCountryFocus][DiscoveryMapView]'s "exit" path runs (which clears this alongside
 * restoring the camera), never left dangling once the user has explicitly backed out.
 */
internal object CountryFocusStateHolder {
    var current: MapCameraState? = null
}
