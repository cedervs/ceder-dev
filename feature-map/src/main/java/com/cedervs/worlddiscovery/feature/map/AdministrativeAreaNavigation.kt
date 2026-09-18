package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaComponent
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.cedervs.worlddiscovery.core.discovery.GeographicBounds
import org.maplibre.geojson.Feature

/**
 * Resolves a map click into the single visited Region/Department [GeographicArea] that was
 * actually tapped — the Region/Department counterpart of
 * [resolveClickedCountryComponent][CountryOverlayComponentNavigation]. Simpler than that function:
 * [AdministrativeOverlayRendering] tags exactly one Feature per area (never per-component, see its
 * own doc comment), so resolution only needs to match [ADMIN_OVERLAY_AREA_ID_PROPERTY] against
 * [candidateAreas]' own ids directly — no positional index, no numeric-type hardening.
 *
 * **[candidateAreas] is the caller's pre-filtered candidate set, not necessarily every visited area
 * at this level.** [resolveGeographicClick] is the one real caller, and it always filters by the
 * *actual focused parent's real id* ([GeographicArea.parentId]) before calling this — see that
 * function's own doc comment for why (the Codex "parent-scoped click resolution" fix): a Department
 * belonging to a different Region must never resolve here just because it happens to be
 * geometrically visited and hit-tested, regardless of what this function's own name might suggest in
 * isolation. This function itself stays a plain, generic "resolve by tagged id against a candidate
 * list" primitive — the parent-scoping decision lives one level up, in [resolveGeographicClick].
 *
 * **Hardened the same way**: not interactive at [currentZoomLevel] (per [isInteractive], gating
 * whichever of [isAdmin1OverlayInteractive]/[isAdmin2OverlayInteractive] the caller passes), no
 * feature actually hit, the hit feature carrying no [ADMIN_OVERLAY_AREA_ID_PROPERTY], or that id not
 * matching any area in [candidateAreas] (a stale/foreign feature, or a real area excluded by the
 * caller's own parent-scoping filter — **not** "unvisited," since [candidateAreas] legitimately
 * includes unvisited areas as of the FH-1 runtime hierarchy fix, see [GeographicClickContext]'s own
 * doc comment) — every one of these resolves to `null`, never a crash or a fallback guess.
 */
internal fun resolveClickedAdministrativeArea(
    hitFeatures: List<Feature>,
    candidateAreas: List<GeographicArea>,
    currentZoomLevel: Double,
    isInteractive: (Double) -> Boolean,
): GeographicArea? {
    if (!isInteractive(currentZoomLevel)) return null
    val hit = hitFeatures.firstOrNull() ?: return null
    val areaId = hit.getStringProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY) ?: return null
    return candidateAreas.find { area -> area.id == areaId }
}

/**
 * One entry in the Region/Department focus stack — [returnCamera] is the camera to restore when
 * this specific frame is popped (see [nextAdminFocusStack]/[AdministrativeFocusStateHolder]).
 * Deliberately a separate, small, additive stack from [CountryFocusStateHolder]'s own single-slot
 * Country-component focus — see `AdministrativeFocusStateHolder.kt`'s own doc comment for why the
 * two are composed side by side in `DiscoveryMapView` rather than merged into one generalized
 * mechanism: this keeps the already physically-validated single-level Country focus code (including
 * [nextCountryFocusReturnCamera]/[resolveClickedCountryComponent]) completely untouched.
 *
 * **[areaType] is carried explicitly, never inferred from stack position.** An earlier version of
 * this type had no `areaType` field and [nextAdminFocusStack] inferred "index 0 is always the Region
 * frame, index 1 is always the Department frame" — a real bug (Codex review finding): a direct
 * Department selection from an empty stack stores that Department at index 0, and a *second*
 * Department selection would then misinterpret that index-0 Department frame as if it were a Region
 * frame, silently producing a false `[oldDepartment, newDepartment]` hierarchy with wrong Back
 * semantics. Carrying the real [GeographicAreaType] on every frame makes that class of bug
 * structurally impossible: lookups below are always by *type*, never by position, and this shape
 * generalizes to any future variable-depth hierarchy (worldwide datasets will not all have exactly
 * two levels below Country) without further changes.
 */
internal data class AdminFocusFrame(
    val returnCamera: MapCameraState,
    val areaType: GeographicAreaType,
    val areaId: String,
) {
    init {
        require(areaType == GeographicAreaType.ADMIN_1 || areaType == GeographicAreaType.ADMIN_2) {
            "AdminFocusFrame only supports ADMIN_1/ADMIN_2, got $areaType"
        }
    }
}

/**
 * Decides the next Region/Department focus stack after a click resolves to [targetArea] — a pure
 * function, directly unit-testable without any MapLibre runtime, mirroring
 * [nextCountryFocusReturnCamera]'s own "never overwrite an existing return camera on a sibling
 * reselect" contract, generalized to two stackable levels. Every lookup below is by
 * [AdminFocusFrame.areaType], never by list position — see that type's own doc comment for exactly
 * why (the Codex-flagged false-hierarchy bug this replaces). [targetArea] is the real, already-resolved
 * [GeographicArea] (not a bare id string) specifically so its own real [GeographicArea.parentId] is
 * available here — this is what makes the Region frame this function builds/preserves always
 * genuinely correspond to [targetArea]'s real parent, never merely "whatever region frame happened to
 * already be on the stack" (the Codex "no invalid hierarchy combination" requirement).
 *
 * - **Region tap** (`targetArea.type == ADMIN_1`): always collapses to a single-frame stack. Re-tapping
 *   a *different* region than the one currently focused keeps that existing frame's
 *   [AdminFocusFrame.returnCamera] (sibling reselect, exactly like Country's own component
 *   reselect) — any Department frame that was present is discarded, since it necessarily belonged to
 *   the *previous* region's own subtree and cannot be preserved once a different region is entered.
 *   Entering region focus from a stack with no Region frame captures [cameraBeforeThisClick] as the
 *   new frame's return camera.
 * - **Department tap** (`targetArea.type == ADMIN_2`): three cases, all keyed on whether an existing
 *   Region frame's own `areaId` matches [targetArea]'s real `parentId`:
 *   1. **No Region frame exists at all** — a direct-Department entry (see
 *      [eligibleClickLevels]'s own doc comment: this is no longer reachable through the real,
 *      hierarchy-gated click path, but this function stays safe against it regardless, per the
 *      Codex "defense in depth" requirement). Stays department-only, exactly as before — no
 *      fabricated Region ancestor.
 *   2. **An existing Region frame's `areaId` matches `targetArea.parentId`** — an ordinary
 *      same-Region Department tap: the Region frame is preserved completely untouched, and the
 *      Department frame is either replaced (sibling reselect, same return-camera-preservation rule,
 *      found by *type* so it's correctly recognized regardless of stack position) or freshly pushed.
 *   3. **An existing Region frame's `areaId` does NOT match `targetArea.parentId`** — by
 *      construction (see [resolveGeographicClick]'s own parent-scoping filter) this case is not
 *      reachable through the real click path either: [resolveClickedAdministrativeArea] is only ever
 *      offered Department candidates already filtered to the *currently focused* Region's own
 *      children, so a resolved [targetArea] can never disagree with the existing Region frame. Kept
 *      as a hard, loud [require] failure (never a silent wrong-parent stack) rather than a silent
 *      "just trust it" — an internal precondition violation here means a real bug upstream, not a
 *      state this function should paper over.
 *
 * [currentStack] has at most 2 entries by construction: `[]`, `[region]`, `[department]`, or
 * `[region, department]` — this function's own return value always stays within that same shape, and
 * always satisfies "Focused Admin2.parentId == focused Admin1.id" whenever both are present.
 */
internal fun nextAdminFocusStack(
    currentStack: List<AdminFocusFrame>,
    cameraBeforeThisClick: MapCameraState,
    targetArea: GeographicArea,
): List<AdminFocusFrame> {
    require(targetArea.type == GeographicAreaType.ADMIN_1 || targetArea.type == GeographicAreaType.ADMIN_2) {
        "nextAdminFocusStack only supports ADMIN_1/ADMIN_2 targets, got ${targetArea.type}"
    }
    val existingRegionFrame = currentStack.firstOrNull { it.areaType == GeographicAreaType.ADMIN_1 }
    return when (targetArea.type) {
        GeographicAreaType.ADMIN_1 -> {
            listOf(existingRegionFrame?.copy(areaId = targetArea.id) ?: AdminFocusFrame(cameraBeforeThisClick, GeographicAreaType.ADMIN_1, targetArea.id))
        }
        GeographicAreaType.ADMIN_2 -> {
            if (existingRegionFrame != null) {
                require(existingRegionFrame.areaId == targetArea.parentId) {
                    "nextAdminFocusStack: targetArea \"${targetArea.id}\" has parentId \"${targetArea.parentId}\", " +
                        "which does not match the currently focused Region frame \"${existingRegionFrame.areaId}\" -- " +
                        "the caller (resolveGeographicClick) must parent-scope Department candidates before calling this"
                }
            }
            val existingDepartmentFrame = currentStack.firstOrNull { it.areaType == GeographicAreaType.ADMIN_2 }
            val departmentFrame = existingDepartmentFrame?.copy(areaId = targetArea.id)
                ?: AdminFocusFrame(cameraBeforeThisClick, GeographicAreaType.ADMIN_2, targetArea.id)
            listOfNotNull(existingRegionFrame, departmentFrame)
        }
        else -> error("unreachable: guarded by the require() above")
    }
}

/** Pops exactly one frame off [currentStack] (the Back action) — `null` if [currentStack] is
 * already empty (nothing to pop; the caller must fall through to the existing Country-focus Back
 * path instead, see `DiscoveryMapView`'s own Back handling). Returns the popped frame's own
 * [AdminFocusFrame.returnCamera] (what the camera should animate to) alongside the new, shorter
 * stack. */
internal data class AdminFocusBackResult(val cameraToRestore: MapCameraState, val newStack: List<AdminFocusFrame>)

internal fun adminFocusBack(currentStack: List<AdminFocusFrame>): AdminFocusBackResult? {
    val poppedFrame = currentStack.lastOrNull() ?: return null
    return AdminFocusBackResult(cameraToRestore = poppedFrame.returnCamera, newStack = currentStack.dropLast(1))
}

// ==============================================================================================
// Hierarchy-aware, PARENT-SCOPED click eligibility/resolution.
//
// First correction round (Codex Blocking 1): the click handler must only ever attempt the level(s)
// actually eligible from the current focus TYPE, so an ancestor level's resolve function is never
// even called once focus has moved past it.
//
// Second correction round (Codex re-review): eligibility by TYPE alone is not enough. Knowing
// "ADMIN_2 is eligible" does not by itself prevent a Department belonging to a completely different,
// unfocused Region from resolving as if it were a child of the currently focused Region -- e.g.
// focus Ile-de-France, pan the camera to Nouvelle-Aquitaine (still rendered/visited), tap
// Haute-Vienne: without parent-scoping this could resolve as Department(Haute-Vienne) attached under
// the still-focused Ile-de-France frame, an impossible [Ile-de-France, Haute-Vienne] hierarchy. The
// fix is PARENT-SCOPING: resolveGeographicClick filters each level's candidate areas down to only
// those whose own real GeographicArea.parentId matches the actually-focused parent's id BEFORE
// calling resolveClickedAdministrativeArea -- never relying on geographic overlap alone. A tap that
// fails the parent-scoped Department check simply isn't resolved as a Department at all; the next
// eligible level (Region) is then tried against the SAME point, which is exactly what makes "tapping
// a Department in a different Region" naturally read as "switch to that Region" (see
// eligibleClickLevels's own doc comment) rather than either silently misattaching or being ignored.
// ==============================================================================================

/**
 * Which [GeographicAreaType] levels a click may resolve to, given [currentFocusLevel] (`null` means
 * no geographic focus is active at all) — in priority order (checked first-to-last; the first level
 * that actually resolves to a real hit wins). [GeographicClickContext]'s own parent-id fields are
 * what turn this TYPE-level eligibility into the real, PARENT-SCOPED eligibility — see
 * [resolveGeographicClick]'s own doc comment.
 *
 * - **No focus** -> only `COUNTRY` is eligible. The very first tap in a session must be on a Country
 *   component (e.g. mainland France) — Region/Department are never eligible yet, even though their
 *   own overlays may already be rendered at this zoom (see `AdministrativeOverlayRendering.kt`'s
 *   own overlapping zoom bands) — an ancestor rendering does not make a not-yet-reachable descendant
 *   clickable.
 * - **Country focused** -> `ADMIN_1` (Region) first, then `COUNTRY` itself as a **fallback** — this
 *   restores the pre-hierarchy-gating ability to switch between a fragmented country's own components
 *   (mainland <-> Corsica <-> French Guiana) while Country-focused, exactly as it worked before the
 *   first correction round, without letting Country resolution win over a genuinely eligible Region
 *   hit: Region is always tried FIRST, so a point that resolves to both a real Region and a real
 *   Country-component sibling always drills into the Region, never falls back to the component
 *   switch. The fallback only ever fires when Region resolution finds nothing at all (e.g. tapping a
 *   part of Corsica that has no loaded Region data).
 * - **Region focused** -> `ADMIN_2` (Department, checked first — the more specific level wins on a
 *   point that is geometrically inside both the focused region and a candidate department), THEN
 *   `ADMIN_1` (a different Region — sibling reselect / Region switch), THEN `COUNTRY` as the
 *   outermost **ancestor-selection fallback** (physical-validation fix, this round: France must
 *   remain selectable while a Region is focused, e.g. after the user manually zooms out so France is
 *   the only thing still visible). A Department tap that fails [resolveGeographicClick]'s own
 *   parent-scoping filter (belongs to a *different* Region than the one focused) falls through to the
 *   Region check first — which is what makes tapping a foreign Region's Department read as "switch
 *   Region" rather than jumping straight to Country — and only falls through to Country once neither
 *   Department nor Region resolves at all.
 * - **Department focused** -> `ADMIN_2` (sibling reselect, itself parent-scoped to the currently
 *   focused Region — see [resolveGeographicClick]) first, THEN `ADMIN_1` (select the parent Region
 *   directly, without first Back-ing out — physical-validation fix, this round), THEN `COUNTRY` as
 *   the outermost ancestor-selection fallback, for the exact same "France must stay reachable from
 *   any depth" reason as the Region-focused case above. Nothing deeper than `ADMIN_2` exists this
 *   round (no Admin3/communes).
 *
 * **Ancestor selection always wins over nothing, never over a more specific hit** — `COUNTRY` (and,
 * from Department focus, `ADMIN_1`) is only ever reached once every more specific level in front of
 * it has already failed to resolve against the same point, so a genuinely eligible Department/Region
 * hit is never shadowed by an ancestor fallback. See [nextGeographicSelectionOutcome] for what
 * resolving to an ancestor then does to any deeper focus that was active — it is always fully
 * cleared, never left stale.
 */
internal fun eligibleClickLevels(currentFocusLevel: GeographicAreaType?): List<GeographicAreaType> = when (currentFocusLevel) {
    null -> listOf(GeographicAreaType.COUNTRY)
    GeographicAreaType.COUNTRY -> listOf(GeographicAreaType.ADMIN_1, GeographicAreaType.COUNTRY)
    GeographicAreaType.ADMIN_1 -> listOf(GeographicAreaType.ADMIN_2, GeographicAreaType.ADMIN_1, GeographicAreaType.COUNTRY)
    GeographicAreaType.ADMIN_2 -> listOf(GeographicAreaType.ADMIN_2, GeographicAreaType.ADMIN_1, GeographicAreaType.COUNTRY)
    else -> emptyList() // LOCALITY/ZONE: not a focusable level in this round's map navigation.
}

// ==================================================================================================
// Physical-validation correction round: the full click -> next-state decision, extracted as ONE pure
// function so DiscoveryMapView's real click handler has exactly one dispatch to make (apply the
// returned state, animate to the returned bounds) instead of three near-duplicate branches that could
// individually forget to clear stale focus or fit the camera. See this round's own PROJECT_STATUS.md
// entry for the two physical bugs this fixes: (1) selecting an ancestor (Country, or a Region from
// Department depth) left a stale deeper focus frame behind; (2) camera-fit was correct but only
// reachable if the click was allowed to resolve at all, which eligibleClickLevels above now permits
// from any depth.
// ==================================================================================================

/**
 * Everything about the state transition a resolved [GeographicClickResolution] produces — the camera
 * bounds to fit, and the next Country/Region/Department focus state, computed as one pure decision so
 * it is directly unit-testable without any MapLibre runtime (mirrors [nextAdminFocusStack]'s own
 * "pure function, real `DiscoveryMapView` delegates to it" shape).
 *
 * **Ancestor selection clears descendant focus.** Resolving to a [GeographicClickResolution.CountryComponent]
 * always sets [nextAdminFocusStack] to `emptyList()`, regardless of what [currentAdminFocusStack] was —
 * selecting Country must never leave a stale Region/Department frame behind (the physical-validation
 * "clicking France doesn't reliably refocus" bug: the underlying cause was this clearing never
 * happening, not the camera-fit call itself, which was already unconditional). Resolving to a
 * [GeographicClickResolution.Region] delegates to [nextAdminFocusStack]'s own already-validated
 * "always collapses to a single Region frame" behavior, which already discards any Department frame
 * from a previous Region's subtree. Resolving to a [GeographicClickResolution.Department] delegates to
 * [nextAdminFocusStack] unchanged.
 *
 * **Camera always fits the newly selected geography, unconditionally.** [cameraTargetBounds] is
 * always the resolved area/component's own real bounds — never the previous camera, never contingent
 * on whether focus was already active or how the user had manually panned/zoomed beforehand. This is
 * what satisfies "every successful geographic selection must focus the camera on the newly selected
 * geography," including the specific physical-validation scenario: department focused, camera manually
 * zoomed out until France is barely visible, tap France — [nextAdminFocusStack] value is `emptyList()`
 * and [cameraTargetBounds] is France's own bounds, regardless of the manual zoom that preceded it.
 *
 * **Never invents a country focus.** [nextCountryFocusReturnCamera] for a Region/Department resolution
 * is [currentCountryFocusReturnCamera] passed through completely unchanged — Region/Department are
 * only ever reachable (per [eligibleClickLevels]) once Country focus is already active, so this never
 * needs to fabricate one; preserves the exact pre-existing (already physically-validated) behavior
 * where only an actual Country-component click ever calls [nextCountryFocusReturnCamera].
 */
internal data class GeographicSelectionOutcome(
    val nextCountryFocusReturnCamera: MapCameraState?,
    val nextAdminFocusStack: List<AdminFocusFrame>,
    val cameraTargetBounds: GeographicBounds,
)

internal fun nextGeographicSelectionOutcome(
    resolution: GeographicClickResolution,
    currentCountryFocusReturnCamera: MapCameraState?,
    currentAdminFocusStack: List<AdminFocusFrame>,
    cameraBeforeThisClick: MapCameraState,
): GeographicSelectionOutcome = when (resolution) {
    is GeographicClickResolution.CountryComponent -> GeographicSelectionOutcome(
        nextCountryFocusReturnCamera = nextCountryFocusReturnCamera(currentCountryFocusReturnCamera, cameraBeforeThisClick),
        nextAdminFocusStack = emptyList(),
        cameraTargetBounds = resolution.component.bounds,
    )
    is GeographicClickResolution.Region -> GeographicSelectionOutcome(
        nextCountryFocusReturnCamera = currentCountryFocusReturnCamera,
        nextAdminFocusStack = nextAdminFocusStack(currentAdminFocusStack, cameraBeforeThisClick, resolution.area),
        cameraTargetBounds = resolution.area.bounds,
    )
    is GeographicClickResolution.Department -> GeographicSelectionOutcome(
        nextCountryFocusReturnCamera = currentCountryFocusReturnCamera,
        nextAdminFocusStack = nextAdminFocusStack(currentAdminFocusStack, cameraBeforeThisClick, resolution.area),
        cameraTargetBounds = resolution.area.bounds,
    )
}

/**
 * Everything [resolveGeographicClick] needs to decide one click, gathered up front by the real
 * click listener (`DiscoveryMapView`) — a plain data holder, no MapLibre runtime type inside it
 * (`Feature`/[GeographicArea]/[GeographicAreaComponent] are all already plain, pure-JVM-constructible
 * types — see `CountryOverlayComponentNavigationTest`'s own precedent), so this whole decision is
 * directly unit-testable with synthetic data, exactly reproducing what the real listener does.
 *
 * **The `focused*Id` fields are the generic representation of "where in the hierarchy focus
 * currently is"** — deliberately plain nullable id strings tied to [GeographicArea.parentId]'s own
 * shape, not a France-specific "focused region" concept: [focusedCountryId] is set once *any* Country
 * component is focused, [focusedAdmin1Id] once *any* `ADMIN_1` is focused, [focusedAdmin2Id] once
 * *any* `ADMIN_2` is focused (all three are simultaneously derivable from
 * [CountryFocusStateHolder]/[AdministrativeFocusStateHolder]'s own state in `DiscoveryMapView` — see
 * that file's own `currentGeographicFocusLevel`/focus-id computation). This same shape works
 * unchanged for a future multi-country, variable-depth worldwide hierarchy: nothing here assumes
 * "exactly one Country" or "exactly two levels below Country."
 *
 * **[regions]/[departments] are the raw, UNSCOPED candidate universe — every loaded Region/Department
 * regardless of visited state, not a caller-pre-filtered subset** (FH-1 runtime hierarchy fix; these
 * fields were named `visitedRegions`/`visitedDepartments` before this fix, back when only visited
 * areas were ever rendered/offered as click candidates at all). [resolveGeographicClick] itself does
 * the real parent-scoping (see this function's own doc comment) before ever calling
 * [resolveClickedAdministrativeArea] — callers do not need to pre-filter by parent OR by visited
 * state; a click can resolve to an unvisited Region/Department exactly as it can to a visited one,
 * since administrative existence and discovery presence are different concepts (see
 * [com.cedervs.worlddiscovery.core.discovery.GeographicAreaVisitedStatus]'s own doc comment).
 */
internal data class GeographicClickContext(
    val currentFocusLevel: GeographicAreaType?,
    val focusedCountryId: String?,
    val focusedAdmin1Id: String?,
    val focusedAdmin2Id: String?,
    val zoomLevel: Double,
    val countryHitFeatures: List<Feature>,
    val visitedCountryComponents: List<GeographicAreaComponent>,
    val countryAreaId: String,
    val regionHitFeatures: List<Feature>,
    val regions: List<GeographicArea>,
    val departmentHitFeatures: List<Feature>,
    val departments: List<GeographicArea>,
)

/** What [resolveGeographicClick] resolved a click to — exactly one of the three navigable kinds,
 * never more than one, and `null` (not a value of this type at all) when nothing resolved. */
internal sealed class GeographicClickResolution {
    internal data class CountryComponent(val component: GeographicAreaComponent) : GeographicClickResolution()
    internal data class Region(val area: GeographicArea) : GeographicClickResolution()
    internal data class Department(val area: GeographicArea) : GeographicClickResolution()
}

/**
 * The single, pure decision `DiscoveryMapView`'s real `OnMapClickListener` delegates to — this is
 * "the actual resolution sequence" a real click would run, expressed as ordinary data in and a
 * plain nullable result out, so both correction rounds' fixes (hierarchy-aware eligibility, and
 * parent-scoped candidate filtering) are verified against the exact logic the map uses, not merely
 * against isolated stack helpers.
 *
 * Tries each level [eligibleClickLevels] returns for [GeographicClickContext.currentFocusLevel], in
 * that exact order, stopping at the first that actually resolves. A level not present in
 * [eligibleClickLevels]'s result is never attempted at all — this is what makes an ancestor
 * structurally unable to intercept a descendant's click.
 *
 * **Parent-scoping (the Codex re-review fix): before testing ADMIN_1/ADMIN_2 candidates, each
 * candidate list is filtered down to areas whose own real [GeographicArea.parentId] matches the
 * relevant currently-focused ancestor's id** — `ADMIN_1` candidates are filtered to
 * `parentId == focusedCountryId`; `ADMIN_2` candidates are filtered to
 * `parentId == focusedAdmin1Id`. This is a real hierarchy-contract check, never a geographic-overlap
 * guess: a Department whose polygon happens to be hit-tested but whose `parentId` names a different
 * Region is simply excluded from the candidate list handed to
 * [resolveClickedAdministrativeArea] — it can never resolve as a child of the wrong Region, no matter
 * how the polygons happen to overlap on screen. When the parent-scoped filter leaves zero candidates
 * (or the level itself has no hit), that level contributes nothing and the loop moves on to the next
 * eligible level against the *same* point — this is exactly the mechanism that turns "tap a
 * Department belonging to a different Region while that other Region is visited" into a Region-level
 * switch instead of either a crash, a silent no-op, or a corrupted stack.
 */
internal fun resolveGeographicClick(context: GeographicClickContext): GeographicClickResolution? {
    for (level in eligibleClickLevels(context.currentFocusLevel)) {
        when (level) {
            GeographicAreaType.COUNTRY -> {
                val component = resolveClickedCountryComponent(
                    context.countryHitFeatures,
                    context.visitedCountryComponents,
                    context.countryAreaId,
                    context.zoomLevel,
                )
                if (component != null) return GeographicClickResolution.CountryComponent(component)
            }
            GeographicAreaType.ADMIN_1 -> {
                val parentScopedRegions = context.regions.filter { region -> region.parentId == context.focusedCountryId }
                val region = resolveClickedAdministrativeArea(
                    context.regionHitFeatures,
                    parentScopedRegions,
                    context.zoomLevel,
                    ::isAdmin1OverlayInteractive,
                )
                if (region != null) return GeographicClickResolution.Region(region)
            }
            GeographicAreaType.ADMIN_2 -> {
                val parentScopedDepartments = context.departments.filter { department -> department.parentId == context.focusedAdmin1Id }
                val department = resolveClickedAdministrativeArea(
                    context.departmentHitFeatures,
                    parentScopedDepartments,
                    context.zoomLevel,
                    ::isAdmin2OverlayInteractive,
                )
                if (department != null) return GeographicClickResolution.Department(department)
            }
            else -> Unit // LOCALITY/ZONE never appear in eligibleClickLevels's own result.
        }
    }
    return null
}
