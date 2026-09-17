package com.cedervs.worlddiscovery.feature.map

/**
 * Holds the Region/Department focus stack ([AdminFocusFrame]) for the lifetime of the process —
 * same process-lifetime-holder shape and same "why this needs to survive [org.maplibre.android.maps.MapView]
 * recreation" rationale as [CountryFocusStateHolder]'s own doc comment (Navigation-Compose disposing/
 * recomposing `DiscoveryMapView` while a tab is backgrounded, not merely destroyed).
 *
 * **Deliberately a separate, additive holder from [CountryFocusStateHolder], never merged into
 * one generalized "geographic focus" mechanism.** [CountryFocusStateHolder] is the already
 * physically-validated single-slot Country-component focus — this holder adds Region/Department
 * focus *on top of* it without changing anything about how it works: `DiscoveryMapView`'s Back
 * handling checks this stack FIRST (pop one Region/Department frame if non-empty) and only falls
 * through to [CountryFocusStateHolder]'s own existing `exitCountryFocus()` once this stack is
 * empty — see `AdministrativeAreaNavigation.kt`'s own doc comment for the full push/pop contract.
 * From the user's perspective this reads as one continuous Back stack (Department -> Region ->
 * Country-component -> original camera); underneath, it is two small holders composed side by
 * side, not one unified state machine, specifically so this round's new code can never regress the
 * already-validated Country-level behavior by construction.
 *
 * `emptyList()` means no Region/Department focus is active — the normal state, and always the
 * state after every pop empties it or after [MapNavigationStateResetter.reset] runs on a real
 * session transition (see that class's own doc comment, updated this round to clear this holder
 * too, alongside [MapCameraStateHolder]/[CountryFocusStateHolder]).
 */
internal object AdministrativeFocusStateHolder {
    var current: List<AdminFocusFrame> = emptyList()
}
