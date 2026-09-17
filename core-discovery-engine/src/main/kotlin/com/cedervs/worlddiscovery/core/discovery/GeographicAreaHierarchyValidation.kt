package com.cedervs.worlddiscovery.core.discovery

/**
 * Validates a **set** of [GeographicArea]s forms a structurally coherent hierarchy — deliberately
 * generic over [GeographicAreaType], never a France-specific rule (`CLAUDE.md`'s own instruction:
 * "the same engine + different geographic datasets"). This belongs at the *set* level, never inside
 * a single-file parser ([parseGeographicAreaReference]/[loadGeographicAreaReference]): one file
 * cannot know whether the parent it names actually exists — only the caller that has loaded every
 * file in the set can check that.
 *
 * Checked, for every area in [areas]:
 * - **Unique ids.** Two areas sharing an id would make [GeographicArea.parentId] lookups and
 *   [ClassifyDiscoveredCellsByGeographicAreas]' own results ambiguous.
 * - **`COUNTRY` areas have no parent.** [GeographicArea.parentId] must be `null` — a country is
 *   top-level by definition in this app's current hierarchy.
 * - **`ADMIN_1` areas have a parent that exists and is a `COUNTRY`.** Never assumes *which* country
 *   — any `COUNTRY`-typed area in [areas] satisfies this, so the exact same check works for a future
 *   second country's own regions without modification.
 * - **`ADMIN_2` areas have a parent that exists and is an `ADMIN_1`.** Same genericity: never assumes
 *   *which* region.
 * - **Every non-null `parentId`, regardless of type, must resolve to a real area in [areas].** A
 *   dangling reference (a typo, a partially-updated dataset) fails loudly here rather than silently
 *   producing an area whose ancestor can never be found by [promoteAncestorPresence] or any future
 *   hierarchy-walking code.
 *
 * `LOCALITY`/`ZONE` areas are deliberately **not** constrained by this function — their place in the
 * hierarchy isn't decided yet (see `docs/discovery-engine.md` §19's own "zones World Discovery" note),
 * so validating a rule for them here would invent product behavior this round doesn't own.
 *
 * Throws [IllegalArgumentException] with a specific, actionable message identifying exactly which
 * area/rule failed — never a generic "invalid hierarchy". Returns normally (no return value) when
 * every check passes; callers that need to distinguish success from "not yet checked" should simply
 * call this before using [areas], the same way [GeographicArea]'s own `init` block validates itself
 * at construction time.
 */
fun validateGeographicAreaHierarchy(areas: List<GeographicArea>) {
    val areasById = mutableMapOf<String, GeographicArea>()
    for (area in areas) {
        val existing = areasById.put(area.id, area)
        require(existing == null) { "Duplicate GeographicArea id \"${area.id}\" -- every area in a loaded set must have a unique id" }
    }

    for (area in areas) {
        when (area.type) {
            GeographicAreaType.COUNTRY -> {
                require(area.parentId == null) {
                    "COUNTRY area \"${area.id}\" must not have a parentId -- a country is top-level, found parentId=\"${area.parentId}\""
                }
            }
            GeographicAreaType.ADMIN_1 -> {
                val parent = resolveRequiredParent(area, areasById)
                require(parent.type == GeographicAreaType.COUNTRY) {
                    "ADMIN_1 area \"${area.id}\" has parentId \"${area.parentId}\" which resolves to a " +
                        "${parent.type} area, not a COUNTRY -- an ADMIN_1 area's parent must be a COUNTRY"
                }
            }
            GeographicAreaType.ADMIN_2 -> {
                val parent = resolveRequiredParent(area, areasById)
                require(parent.type == GeographicAreaType.ADMIN_1) {
                    "ADMIN_2 area \"${area.id}\" has parentId \"${area.parentId}\" which resolves to a " +
                        "${parent.type} area, not an ADMIN_1 -- an ADMIN_2 area's parent must be an ADMIN_1"
                }
            }
            GeographicAreaType.LOCALITY, GeographicAreaType.ZONE -> {
                // Deliberately unconstrained -- see this function's own doc comment. Still, ANY
                // non-null parentId on ANY area type must resolve to something real, checked below.
                if (area.parentId != null) resolveRequiredParent(area, areasById)
            }
        }
    }
}

private fun resolveRequiredParent(area: GeographicArea, areasById: Map<String, GeographicArea>): GeographicArea {
    val parentId = area.parentId
    require(parentId != null) { "${area.type} area \"${area.id}\" must have a parentId, found none" }
    return areasById[parentId]
        ?: throw IllegalArgumentException(
            "${area.type} area \"${area.id}\" has parentId \"$parentId\" which does not resolve to any area in the loaded set",
        )
}
