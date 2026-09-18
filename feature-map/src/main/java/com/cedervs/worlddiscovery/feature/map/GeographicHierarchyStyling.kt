package com.cedervs.worlddiscovery.feature.map

import android.graphics.Color
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import org.maplibre.android.style.expressions.Expression

/**
 * **Physical-validation correction round.** Replaces the previous, rejected design (fixed colors
 * permanently assigned per [GeographicAreaType] — Country darkest, Region medium, Department
 * lightest, unconditionally) with a **selection-relative** hierarchy: color now depends on each
 * area's relationship to whatever is *currently focused*, not on its own type alone. The same Region
 * renders differently depending on context — darker while it's merely a sub-level beneath a selected
 * Country, LIGHT the moment that Region itself becomes the selected level. See
 * `PROJECT_STATUS.md`'s own entry for this round for the physical-device finding this replaces.
 *
 * Deliberately a **small, reusable, France-independent** abstraction: nothing here references France,
 * a specific area id, or a specific depth beyond [GeographicAreaType]'s own generic ordering — the
 * same three functions below work unchanged for any future country's Country/Region/Department (or
 * deeper) hierarchy.
 */

/** The three visual roles a rendered Country/Region/Department feature can have, purely relative to
 * the current selection — never a fixed per-[GeographicAreaType] identity. */
internal enum class GeographicAreaStyleRole {
    /** The exact area (or, for Country, the exact country) currently focused/selected. */
    SELECTED,

    /** Exactly one hierarchy level below the selected one, AND (when a specific area — not the
     * implicit "World" — is selected) a genuine child of it, i.e. its own [parentId]-equivalent
     * relationship matches the selected id. The level a user would naturally drill into next. */
    DIRECT_SUBLEVEL,

    /** Everything else: real ancestors of the current selection (rendered de-emphasized so they never
     * visually compete with the active level), an unselected sibling at the same level as the
     * selection, or any level two or more steps away from it. A single de-emphasized "context" tier
     * — see this file's own doc comment for why collapsing all of these into one dark tier is a
     * deliberate simplification, not an oversight. */
    ANCESTOR_CONTEXT,
}

/**
 * The generic representation of "what's currently selected" — deliberately plain nullable
 * type/id fields, the same shape [GeographicClickContext]'s own `focused*Id` fields already use, not
 * a France-specific "focused region" concept. [selectedType] `== null` means no geographic focus is
 * active at all (the World view) — [resolveGeographicAreaStyleRole] treats that as an *implicit*
 * selection one level above Country, so Country itself still reads as the next-selectable
 * [GeographicAreaStyleRole.DIRECT_SUBLEVEL] tier rather than falling into the same dark
 * [GeographicAreaStyleRole.ANCESTOR_CONTEXT] tier as everything else.
 */
internal data class GeographicFocusSelection(
    val selectedType: GeographicAreaType?,
    val selectedId: String?,
) {
    companion object {
        /** No geographic focus active at all — the World view. */
        val NONE = GeographicFocusSelection(selectedType = null, selectedId = null)
    }
}

// CALIBRATION REQUIRED -- starting values taken from this round's physical-device design review
// (reference image), not immutable product constants. A richer, more opaque palette than the
// previous round's single faint "#FF8C00" wash, per the explicit instruction that the France fill
// must visibly resemble the reference image rather than reading as a transparent tint.
internal const val GEOGRAPHIC_SELECTED_FILL_COLOR = "#FFA23A"
internal const val GEOGRAPHIC_DIRECT_SUBLEVEL_FILL_COLOR = "#C96A16"
internal const val GEOGRAPHIC_ANCESTOR_CONTEXT_FILL_COLOR = "#7A3D16"

// CALIBRATION REQUIRED -- FH-1 runtime hierarchy fix: unvisited Regions/Departments must now be
// rendered (see AdministrativeOverlayRendering.kt's own doc comment for why -- existence and
// discovery state are different concepts, and an unvisited administrative area must remain
// navigable) but must NEVER read as visited/orange -- orange stays reserved for real presence. These
// three neutral greys mirror the existing orange palette's own light/medium/dark progression (so
// "selected" still reads lightest/most prominent and "ancestor context" still reads darkest/most
// subdued) without ever being confused with the visited-orange semantics. Provisional starting
// values only, same status as the existing orange constants above -- not a final palette decision.
internal const val GEOGRAPHIC_UNVISITED_SELECTED_FILL_COLOR = "#9E9E9E"
internal const val GEOGRAPHIC_UNVISITED_DIRECT_SUBLEVEL_FILL_COLOR = "#707070"
internal const val GEOGRAPHIC_UNVISITED_ANCESTOR_CONTEXT_FILL_COLOR = "#454545"

/** The GeoJSON property every Country/Region/Department rendered `Feature` is tagged with, carrying
 * its own [GeographicAreaStyleRole] (by `name`) as computed at feature-collection-build time — the
 * FillLayer/LineLayer color is a static `Expression.match` reading this property (see
 * [geographicAreaStyleRoleColorExpression]), so re-coloring on a focus change only ever requires
 * rebuilding the `FeatureCollection` with fresh per-feature role tags, never touching the layer's own
 * paint property again. */
internal const val GEOGRAPHIC_STYLE_ROLE_PROPERTY = "styleRole"

internal const val GEOGRAPHIC_OVERLAY_FILL_OPACITY = 0.45f
internal const val GEOGRAPHIC_OVERLAY_OUTLINE_OPACITY = 0.85f

/** Country=1, Region=2, Department=3; the implicit "World" (no focus / `null` type) is 0 — used only
 * to compute *relative* distance between an area and the current selection, never compared against
 * any absolute product meaning on its own. */
private fun hierarchyDepth(type: GeographicAreaType?): Int = when (type) {
    null -> 0
    GeographicAreaType.COUNTRY -> 1
    GeographicAreaType.ADMIN_1 -> 2
    GeographicAreaType.ADMIN_2 -> 3
    else -> Int.MAX_VALUE // LOCALITY/ZONE: not a focusable level in this round's map navigation.
}

/**
 * The single decision behind this round's whole color rework: what [GeographicAreaStyleRole] should
 * an area of [areaType]/[areaId]/[areaParentId] render as, given the current [selection]?
 *
 * - **Exact match** ([areaType] equals [GeographicFocusSelection.selectedType] AND [areaId] equals
 *   [GeographicFocusSelection.selectedId]) -> [GeographicAreaStyleRole.SELECTED]. For Country, this
 *   means *any* of its components render SELECTED together once Country focus is active — component
 *   granularity only matters for click/camera navigation, never for this styling decision (matches
 *   the reference design: "France: LIGHT orange", not per-island shading).
 * - **Direct sublevel**: [areaType] is exactly one [hierarchyDepth] step below [selection]'s own
 *   depth, AND — whenever [selection] actually names a specific area (not the implicit "World") —
 *   [areaParentId] genuinely equals [GeographicFocusSelection.selectedId]. This second, parent-scoped
 *   condition is what keeps, e.g., a Brittany department from rendering as a bright "direct sublevel"
 *   while an unrelated Nouvelle-Aquitaine department is actually selected — depth alone is not enough,
 *   real parentage is required. The implicit "World" selection has no id to parent-scope against, so
 *   every Country-depth area qualifies for [GeographicAreaStyleRole.DIRECT_SUBLEVEL] by depth alone —
 *   Country is always the next selectable level from the unfocused World view.
 * - **Everything else** -> [GeographicAreaStyleRole.ANCESTOR_CONTEXT] — see that value's own doc
 *   comment for the full list of cases this catch-all covers.
 */
internal fun resolveGeographicAreaStyleRole(
    areaType: GeographicAreaType,
    areaId: String,
    areaParentId: String?,
    selection: GeographicFocusSelection,
): GeographicAreaStyleRole {
    if (areaType == selection.selectedType && areaId == selection.selectedId) {
        return GeographicAreaStyleRole.SELECTED
    }

    val isOneLevelBelowSelection = hierarchyDepth(areaType) == hierarchyDepth(selection.selectedType) + 1
    val isGenuineChildOfSelection = when (selection.selectedType) {
        null -> true // implicit "World" selection -- no id to parent-scope against.
        else -> areaParentId != null && areaParentId == selection.selectedId
    }

    return if (isOneLevelBelowSelection && isGenuineChildOfSelection) {
        GeographicAreaStyleRole.DIRECT_SUBLEVEL
    } else {
        GeographicAreaStyleRole.ANCESTOR_CONTEXT
    }
}

internal fun GeographicAreaStyleRole.fillColorHex(): String = when (this) {
    GeographicAreaStyleRole.SELECTED -> GEOGRAPHIC_SELECTED_FILL_COLOR
    GeographicAreaStyleRole.DIRECT_SUBLEVEL -> GEOGRAPHIC_DIRECT_SUBLEVEL_FILL_COLOR
    GeographicAreaStyleRole.ANCESTOR_CONTEXT -> GEOGRAPHIC_ANCESTOR_CONTEXT_FILL_COLOR
}

/**
 * **FH-1 runtime hierarchy fix.** [GeographicAreaStyleRole] alone answered "how prominent should
 * this area read, relative to the current selection" — a question that only had a sensible answer
 * because every rendered Region/Department used to be visited already (the very bug this fix
 * corrects). Now that unvisited administrative areas are rendered too (see
 * `AdministrativeOverlayRendering.kt`'s own doc comment), color must depend on BOTH [role] and
 * [visited] — this is the single combined decision, kept as its own small pure function (directly
 * unit-testable, no MapLibre runtime needed, mirroring [fillColorHex]'s own shape) rather than
 * folded into [GeographicAreaStyleRole] itself, which stays a purely selection-relative concept with
 * no notion of discovery state — see [GeographicAreaVisitedStatus]'s own doc comment for why
 * "administrative existence" and "discovery presence" must stay two different concepts, never
 * merged into one enum. **Orange (any of the three visited colors above) always means VISITED/
 * PRESENCE; every unvisited combination uses one of the three neutral greys instead — never orange,
 * regardless of role.**
 */
internal fun administrativeFillColorHex(role: GeographicAreaStyleRole, visited: Boolean): String = if (visited) {
    role.fillColorHex()
} else {
    when (role) {
        GeographicAreaStyleRole.SELECTED -> GEOGRAPHIC_UNVISITED_SELECTED_FILL_COLOR
        GeographicAreaStyleRole.DIRECT_SUBLEVEL -> GEOGRAPHIC_UNVISITED_DIRECT_SUBLEVEL_FILL_COLOR
        GeographicAreaStyleRole.ANCESTOR_CONTEXT -> GEOGRAPHIC_UNVISITED_ANCESTOR_CONTEXT_FILL_COLOR
    }
}

/** The GeoJSON property every Region/Department rendered `Feature` additionally carries (alongside
 * [GEOGRAPHIC_STYLE_ROLE_PROPERTY]) — an explicit `"true"`/`"false"` visited flag, read by
 * [administrativeAreaColorExpression] together with the role property so color reflects both
 * dimensions at once. Deliberately a separate property from [GEOGRAPHIC_STYLE_ROLE_PROPERTY] rather
 * than folding visited-ness into the role name itself, so a future consumer that only cares about
 * one dimension (e.g. a click handler that already receives the real, already-classified
 * [GeographicAreaVisitedStatus]) never has to parse a compound tag to get it. Not used by the
 * Country-level overlay ([CountryOverlayRendering]), which is unaffected by this fix — see this
 * property's own introduction in `PROJECT_STATUS.md`'s FH-1 runtime hierarchy fix entry for why the
 * scope stayed Region/Department-only. */
internal const val ADMIN_OVERLAY_VISITED_PROPERTY = "visited"

/**
 * The Region/Department-specific counterpart of [geographicAreaStyleRoleColorExpression] — reads
 * BOTH [GEOGRAPHIC_STYLE_ROLE_PROPERTY] and [ADMIN_OVERLAY_VISITED_PROPERTY] off each rendered
 * Feature (via a single concatenated match key, the same "one property, one `Expression.match`"
 * shape every other color expression in this module already uses — see
 * [geographicAreaStyleRoleColorExpression]'s own doc comment) and maps the combination to
 * [administrativeFillColorHex]'s own result. **Never used by [CountryOverlayRendering]** — that
 * level keeps its existing, unchanged [geographicAreaStyleRoleColorExpression] since Country-level
 * rendering was never filtered to visited-only and is out of this fix's scope.
 */
internal fun administrativeAreaColorExpression(): Expression {
    val combinedKey = Expression.concat(
        Expression.get(GEOGRAPHIC_STYLE_ROLE_PROPERTY),
        Expression.literal("_"),
        Expression.get(ADMIN_OVERLAY_VISITED_PROPERTY),
    )
    return Expression.match(
        combinedKey,
        Expression.color(Color.parseColor(administrativeFillColorHex(GeographicAreaStyleRole.ANCESTOR_CONTEXT, visited = false))),
        *GeographicAreaStyleRole.entries.flatMap { role ->
            listOf(true, false).map { visited ->
                Expression.stop(
                    "${role.name}_$visited",
                    Expression.color(Color.parseColor(administrativeFillColorHex(role, visited))),
                )
            }
        }.toTypedArray(),
    )
}

/**
 * The data-driven MapLibre color expression every Country/Region/Department fill AND outline layer
 * shares — reads [GEOGRAPHIC_STYLE_ROLE_PROPERTY] off each rendered `Feature` and maps it to that
 * role's own [fillColorHex]. Built once at layer-creation time; a focus change never needs to touch
 * the layer's paint property again, only the per-feature `styleRole` tag in a fresh
 * `FeatureCollection` (see `CountryOverlayRendering.kt`/`AdministrativeOverlayRendering.kt`'s own
 * `applyXxxOverlay` functions). `Expression` is a plain, pure-JVM-constructible type — no native
 * MapLibre runtime needed, same as every other `Expression` built elsewhere in this module (see
 * `BasemapAlignedBorderRenderingTest`'s own doc comment for the verification this precedent rests on).
 */
internal fun geographicAreaStyleRoleColorExpression(): Expression =
    Expression.match(
        Expression.get(GEOGRAPHIC_STYLE_ROLE_PROPERTY),
        Expression.color(Color.parseColor(GeographicAreaStyleRole.ANCESTOR_CONTEXT.fillColorHex())),
        Expression.stop(GeographicAreaStyleRole.SELECTED.name, Expression.color(Color.parseColor(GeographicAreaStyleRole.SELECTED.fillColorHex()))),
        Expression.stop(
            GeographicAreaStyleRole.DIRECT_SUBLEVEL.name,
            Expression.color(Color.parseColor(GeographicAreaStyleRole.DIRECT_SUBLEVEL.fillColorHex())),
        ),
        Expression.stop(
            GeographicAreaStyleRole.ANCESTOR_CONTEXT.name,
            Expression.color(Color.parseColor(GeographicAreaStyleRole.ANCESTOR_CONTEXT.fillColorHex())),
        ),
    )
