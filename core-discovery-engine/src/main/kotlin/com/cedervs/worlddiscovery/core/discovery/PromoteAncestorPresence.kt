package com.cedervs.worlddiscovery.core.discovery

/**
 * Corrects a real cross-dataset classification disagreement: Country-level classification currently
 * uses `geoBoundaries` geometry while Region/Department classification uses OpenStreetMap geometry
 * (see `tools/geo/README.md`). The two datasets can genuinely disagree by a few meters near a shared
 * border, so a discovery that OSM geometry places inside a child area can, in principle, fall just
 * outside that child's own parent's `geoBoundaries` polygon — which would otherwise leave the parent
 * falsely `not visited` even though a real, already-classified-visited child sits entirely inside it.
 *
 * **The fix: presence is promoted upward from actually-visited children, never downward from a
 * visited parent.** [parentStatuses] is returned with each entry's `visited`/`certifiedPresent`/
 * `nonCertifiedPresent` unioned with whatever its own real children (matched via
 * [GeographicArea.parentId], never by position or count) resolved to — [childStatuses] itself is
 * never modified, and a parent's own visited status is never used to infer anything about its
 * children. This one function serves any two adjacent levels (Region/Department promoting into
 * Country/Region, or a future deeper level promoting into Region/Department) — nothing here is
 * France-specific or assumes a fixed hierarchy depth.
 *
 * **Never a second discovery truth.** This is a pure, read-time-only correction over already-derived
 * [GeographicAreaVisitedStatus] values — see that type's own doc comment for why `visited` is
 * presence-only. Nothing computed here is persisted; a fresh emission recomputes it from canonical
 * [DiscoveredCell]s exactly as before, this function is just one more derivation step layered on top.
 *
 * A parent with no matching children in [childStatuses] (e.g. a region whose own departments aren't
 * loaded yet — see `FranceAdministrativeAreas.kt`) is returned completely unchanged, by construction:
 * an empty `children` list can promote nothing.
 */
fun promoteAncestorPresence(
    childStatuses: List<GeographicAreaVisitedStatus>,
    parentStatuses: List<GeographicAreaVisitedStatus>,
): List<GeographicAreaVisitedStatus> {
    val childrenByParentId = childStatuses.groupBy { childStatus -> childStatus.area.parentId }

    return parentStatuses.map { parentStatus ->
        val children = childrenByParentId[parentStatus.area.id].orEmpty()
        val promotedCertifiedPresent = parentStatus.certifiedPresent || children.any { it.certifiedPresent }
        val promotedNonCertifiedPresent = parentStatus.nonCertifiedPresent || children.any { it.nonCertifiedPresent }
        val promotedVisited = parentStatus.visited || promotedCertifiedPresent || promotedNonCertifiedPresent

        if (
            promotedVisited == parentStatus.visited &&
            promotedCertifiedPresent == parentStatus.certifiedPresent &&
            promotedNonCertifiedPresent == parentStatus.nonCertifiedPresent
        ) {
            parentStatus
        } else {
            parentStatus.copy(
                visited = promotedVisited,
                certifiedPresent = promotedCertifiedPresent,
                nonCertifiedPresent = promotedNonCertifiedPresent,
            )
        }
    }
}

/**
 * The component-level counterpart to [promoteAncestorPresence] — same "promote upward from real
 * presence, never downward, never persisted" contract, but matched **geometrically** rather than by
 * [GeographicArea.parentId], because [GeographicAreaComponent] has no such link to `ADMIN_1`/`ADMIN_2`
 * areas at all: a component is a positional decomposition of one COUNTRY's own geometry (see
 * [GeographicAreaComponent]'s own doc comment), never aware of the administrative areas nested inside
 * it.
 *
 * **Why this is needed on top of [promoteAncestorPresence].** That function already promotes a
 * visited Department into its Region and a visited Region into the whole-Country aggregate
 * ([GeographicAreaVisitedStatus] for `country:FR` as one value) — but `MapReadState.franceComponents`
 * (the *per-component* statuses that actually drive Country-level rendering/click-navigation) is
 * derived independently, straight from [ClassifyDiscoveredCellsByGeographicAreaComponents] against
 * the Country's own `geoBoundaries` geometry. Without this function, the same cross-dataset
 * disagreement [promoteAncestorPresence] fixes at the whole-Country level could leave every
 * *component* falsely unvisited even while the aggregate Country and its visited Region both
 * correctly read `visited == true` — meaning France could be logically visited yet render as nothing
 * and accept no clicks at all.
 *
 * **The match, and why it is safe.** For every visited area in [childStatuses] (already promoted
 * from anything deeper, so this also transitively covers "a visited Department promotes its Region's
 * matching component"), a single **verified** interior point ([findVerifiedInteriorPoint] — never an
 * unverified centroid/vertex-average guess, see that function's own doc comment for the full
 * candidate-generation-then-verification contract) is tested against every component's own polygon
 * via [PointInPolygonClassifier] — presence is promoted into the ONE component (if any) whose polygon
 * actually contains that point, **never all components**, and never a hardcoded index/name (no
 * `if region == "Nouvelle-Aquitaine" then componentIndex = 0` — this generalizes to any fragmented
 * country the moment its own component/region data is loaded). A child area for which
 * [findVerifiedInteriorPoint] itself finds no verified point at all (a genuinely degenerate geometry)
 * — or whose verified point matches no loaded component (e.g. a future overseas region with no
 * corresponding loaded country component) — simply promotes nothing for that child: **never a crash,
 * never a guess.**
 *
 * **This app's own bundled data is verified, not merely assumed, to satisfy "one region maps to
 * exactly one component."** See `InteriorPointFinderRealDataTest`'s own real-artifact regression test
 * for all 13 bundled metropolitan France `ADMIN_1` regions — each resolves to a verified interior
 * point that is provably inside that region's own geometry and matches exactly one real France
 * Country component (the 12 mainland regions all match the mainland component; Corse matches the
 * Corsica component). **This is a verified France-prototype property, not a worldwide guarantee**: a
 * future genuinely fragmented `ADMIN_1` area (a region that is itself split across two disjoint
 * country components, which does not occur among France's own current regions) would need explicit
 * component-ancestry metadata or discovery-location-aware evidence that does not exist yet — not
 * silently assumed to work by this function.
 *
 * Certified/non-certified presence are promoted independently, exactly like [promoteAncestorPresence]
 * — a certified child never upgrades a component's own non-certified-only presence to certified, and
 * vice versa; both are simply unioned.
 */
fun promoteAncestorComponentPresence(
    childStatuses: List<GeographicAreaVisitedStatus>,
    componentStatuses: List<GeographicAreaComponentVisitedStatus>,
): List<GeographicAreaComponentVisitedStatus> {
    val visitedChildren = childStatuses.filter { it.visited }
    if (visitedChildren.isEmpty()) return componentStatuses

    // Computed once per call, not once per component below -- findVerifiedInteriorPoint does real
    // (if cheap) geometry work, and there are far fewer visited children than components*children
    // pairs at this app's current scale.
    val verifiedInteriorPointByChild = visitedChildren.associateWith { child -> findVerifiedInteriorPoint(child.area) }

    return componentStatuses.map { componentStatus ->
        val matchingChildren = visitedChildren.filter { child ->
            val interiorPoint = verifiedInteriorPointByChild.getValue(child) ?: return@filter false
            PointInPolygonClassifier.contains(componentStatus.component.polygon, interiorPoint)
        }
        if (matchingChildren.isEmpty()) return@map componentStatus

        val promotedCertifiedPresent = componentStatus.certifiedPresent || matchingChildren.any { it.certifiedPresent }
        val promotedNonCertifiedPresent = componentStatus.nonCertifiedPresent || matchingChildren.any { it.nonCertifiedPresent }
        val promotedVisited = componentStatus.visited || promotedCertifiedPresent || promotedNonCertifiedPresent

        if (
            promotedVisited == componentStatus.visited &&
            promotedCertifiedPresent == componentStatus.certifiedPresent &&
            promotedNonCertifiedPresent == componentStatus.nonCertifiedPresent
        ) {
            componentStatus
        } else {
            componentStatus.copy(
                visited = promotedVisited,
                certifiedPresent = promotedCertifiedPresent,
                nonCertifiedPresent = promotedNonCertifiedPresent,
            )
        }
    }
}
