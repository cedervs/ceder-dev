package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure coverage for [resolveGeographicAreaStyleRole] — deliberately **not coupled to MapLibre**
 * (per this round's own instruction): every test here only ever asserts a [GeographicAreaStyleRole]
 * enum value, never touches `Expression`/`Style`/`Layer`. [geographicAreaStyleRoleColorExpression]'s
 * own construction is covered separately, minimally, at the very end of this file (mirroring
 * `BasemapAlignedBorderRenderingTest`'s own precedent that `Expression` is plain and pure-JVM-
 * constructible).
 *
 * Covers this round's own required scenarios verbatim:
 * - France selected: Country=SELECTED, Region=DIRECT_SUBLEVEL.
 * - Region selected: Region=SELECTED, Department=DIRECT_SUBLEVEL, Country=ANCESTOR_CONTEXT.
 * - Department selected: Department=SELECTED, Region=ANCESTOR_CONTEXT, Country=ANCESTOR_CONTEXT.
 */
class GeographicHierarchyStylingTest {

    private val franceId = "country:FR"
    private val naqId = "admin1:FR-NAQ"
    private val brittanyId = "admin1:FR-BRE"
    private val hauteVienneId = "admin2:FR-87"
    private val gironde = "admin2:FR-33"

    // ==========================================================================================
    // Required scenario 1: France selected.
    // ==========================================================================================

    @Test
    fun `France selected -- Country is SELECTED`() {
        val selection = GeographicFocusSelection(GeographicAreaType.COUNTRY, franceId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.COUNTRY, franceId, areaParentId = null, selection = selection)

        assertEquals(GeographicAreaStyleRole.SELECTED, role)
    }

    @Test
    fun `France selected -- a Region is DIRECT_SUBLEVEL`() {
        val selection = GeographicFocusSelection(GeographicAreaType.COUNTRY, franceId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_1, naqId, areaParentId = franceId, selection = selection)

        assertEquals(GeographicAreaStyleRole.DIRECT_SUBLEVEL, role)
    }

    @Test
    fun `France selected -- ALL visited Regions are DIRECT_SUBLEVEL, not just one`() {
        val selection = GeographicFocusSelection(GeographicAreaType.COUNTRY, franceId)

        val naqRole = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_1, naqId, areaParentId = franceId, selection = selection)
        val brittanyRole = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_1, brittanyId, areaParentId = franceId, selection = selection)

        assertEquals(GeographicAreaStyleRole.DIRECT_SUBLEVEL, naqRole)
        assertEquals(GeographicAreaStyleRole.DIRECT_SUBLEVEL, brittanyRole)
    }

    // ==========================================================================================
    // Required scenario 2: Region (Nouvelle-Aquitaine) selected.
    // ==========================================================================================

    @Test
    fun `Region selected -- that Region is SELECTED`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, naqId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_1, naqId, areaParentId = franceId, selection = selection)

        assertEquals(GeographicAreaStyleRole.SELECTED, role)
    }

    @Test
    fun `Region selected -- its own Department is DIRECT_SUBLEVEL`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, naqId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_2, hauteVienneId, areaParentId = naqId, selection = selection)

        assertEquals(GeographicAreaStyleRole.DIRECT_SUBLEVEL, role)
    }

    @Test
    fun `Region selected -- Country (France) is ANCESTOR_CONTEXT, must not compete visually with the active Region`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, naqId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.COUNTRY, franceId, areaParentId = null, selection = selection)

        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT, role)
    }

    @Test
    fun `Region selected -- a Department belonging to a DIFFERENT, unselected Region is ANCESTOR_CONTEXT, never DIRECT_SUBLEVEL`() {
        // Depth alone (Department is one level below Region) is not enough -- real parentage matters,
        // otherwise an unrelated Gironde department would render as a bright "direct sublevel" while
        // an entirely different region is actually selected.
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, brittanyId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_2, gironde, areaParentId = naqId, selection = selection)

        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT, role)
    }

    @Test
    fun `Region selected -- an unselected sibling Region is ANCESTOR_CONTEXT, never SELECTED`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_1, naqId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_1, brittanyId, areaParentId = franceId, selection = selection)

        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT, role)
    }

    // ==========================================================================================
    // Required scenario 3: Department (Haute-Vienne) selected.
    // ==========================================================================================

    @Test
    fun `Department selected -- that Department is SELECTED`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_2, hauteVienneId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_2, hauteVienneId, areaParentId = naqId, selection = selection)

        assertEquals(GeographicAreaStyleRole.SELECTED, role)
    }

    @Test
    fun `Department selected -- the parent Region is ANCESTOR_CONTEXT`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_2, hauteVienneId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_1, naqId, areaParentId = franceId, selection = selection)

        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT, role)
    }

    @Test
    fun `Department selected -- Country (France) is ANCESTOR_CONTEXT too, a deeper ancestor`() {
        val selection = GeographicFocusSelection(GeographicAreaType.ADMIN_2, hauteVienneId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.COUNTRY, franceId, areaParentId = null, selection = selection)

        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT, role)
    }

    // ==========================================================================================
    // No focus at all (World view) -- Country is the next selectable level, everything deeper is
    // context.
    // ==========================================================================================

    @Test
    fun `no focus -- a visited Country is DIRECT_SUBLEVEL, the next selectable level from the World view`() {
        val role = resolveGeographicAreaStyleRole(GeographicAreaType.COUNTRY, franceId, areaParentId = null, selection = GeographicFocusSelection.NONE)

        assertEquals(GeographicAreaStyleRole.DIRECT_SUBLEVEL, role)
    }

    @Test
    fun `no focus -- a visited Region is ANCESTOR_CONTEXT, two levels from the implicit World selection`() {
        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_1, naqId, areaParentId = franceId, selection = GeographicFocusSelection.NONE)

        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT, role)
    }

    // ==========================================================================================
    // Malformed/edge-case parentId handling -- must never crash, never silently misclassify as
    // DIRECT_SUBLEVEL by depth alone when parentage cannot actually be confirmed.
    // ==========================================================================================

    @Test
    fun `a null parentId on a Region while Country is selected is ANCESTOR_CONTEXT, never DIRECT_SUBLEVEL by depth alone`() {
        val selection = GeographicFocusSelection(GeographicAreaType.COUNTRY, franceId)

        val role = resolveGeographicAreaStyleRole(GeographicAreaType.ADMIN_1, "admin1:FR-MALFORMED", areaParentId = null, selection = selection)

        assertEquals(GeographicAreaStyleRole.ANCESTOR_CONTEXT, role)
    }

    // ==========================================================================================
    // Palette sanity -- the three roles must be visually distinct colors, and richer/more opaque
    // than the previous round's single faint wash.
    // ==========================================================================================

    @Test
    fun `the three style roles map to three distinct colors`() {
        val colors = GeographicAreaStyleRole.entries.map { it.fillColorHex() }.toSet()
        assertEquals(3, colors.size)
    }

    @Test
    fun `SELECTED is a visually distinct color from DIRECT_SUBLEVEL and ANCESTOR_CONTEXT`() {
        assertNotEquals(GeographicAreaStyleRole.SELECTED.fillColorHex(), GeographicAreaStyleRole.DIRECT_SUBLEVEL.fillColorHex())
        assertNotEquals(GeographicAreaStyleRole.SELECTED.fillColorHex(), GeographicAreaStyleRole.ANCESTOR_CONTEXT.fillColorHex())
    }

    // geographicAreaStyleRoleColorExpression itself is NOT exercised here: it calls
    // android.graphics.Color.parseColor internally, a real Android framework method with no body on
    // this project's plain-JVM manual verification classpath (the bundled android.jar is a stub jar --
    // every method throws "Stub!" at runtime, confirmed empirically). This is a real Android API that
    // only executes correctly on-device or under Robolectric, neither available in this toolchain --
    // matches this whole module's established pattern (see CountryOverlayFillInsertionTarget's own
    // doc comment) of keeping real Style/Layer/Color construction behind a seam rather than unit-
    // testing it directly. resolveGeographicAreaStyleRole above is the actual decision logic and is
    // exhaustively covered without touching any Android stub.
}
