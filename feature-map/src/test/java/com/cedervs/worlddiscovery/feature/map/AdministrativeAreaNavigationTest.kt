package com.cedervs.worlddiscovery.feature.map

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import com.cedervs.worlddiscovery.core.discovery.GeographicArea
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaProvenance
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaType
import com.cedervs.worlddiscovery.core.discovery.GeographicMultiPolygon
import com.cedervs.worlddiscovery.core.discovery.GeographicPolygon
import com.cedervs.worlddiscovery.core.discovery.components
import com.cedervs.worlddiscovery.core.discovery.computeGeographicBounds
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * Covers all three halves of Region/Department navigation: click resolution
 * ([resolveClickedAdministrativeArea]/[resolveGeographicClick], mirroring
 * `CountryOverlayComponentNavigationTest`'s own style), the focus-stack push/pop pure functions
 * ([nextAdminFocusStack]/[adminFocusBack]), and — this round — PARENT-SCOPED eligibility
 * ([eligibleClickLevels]/[GeographicClickContext]'s `focused*Id` fields).
 *
 * **First correction round (Codex Blocking 1/2)**: hierarchy-aware click ELIGIBILITY (an ancestor's
 * resolve function is never even called once focus has moved past it) and the index-INDEPENDENT
 * focus-stack ([AdminFocusFrame] carrying its own real [GeographicAreaType]).
 *
 * **Second correction round (Codex re-review)**: eligibility by TYPE alone was not enough — a
 * Department belonging to an unfocused Region could still resolve as if it were the focused Region's
 * own child, purely from geographic overlap. [resolveGeographicClick] now additionally filters every
 * candidate list by the real [GeographicArea.parentId] of the currently-focused ancestor before
 * calling [resolveClickedAdministrativeArea] — never relying on geometry alone. Also restores
 * Country-component sibling switching (mainland <-> Corsica <-> Guiana) as a FALLBACK after a Region
 * miss while Country-focused, which the first round's strict gating had removed as an unintended side
 * effect.
 */
class AdministrativeAreaNavigationTest {

    private fun ring(vararg points: Pair<Double, Double>) =
        points.map { (lon, lat) -> Coordinate(latitude = lat, longitude = lon) }

    private fun testArea(id: String, type: GeographicAreaType, parentId: String?, ring: List<Coordinate>): GeographicArea {
        val geometry = GeographicMultiPolygon(listOf(GeographicPolygon(listOf(ring))))
        return GeographicArea(
            id = id,
            type = type,
            displayName = id,
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.EXTERNAL_REFERENCE_DATASET,
            parentId = parentId,
        )
    }

    private val regionA = testArea("admin1:FR-A", GeographicAreaType.ADMIN_1, "country:FR", ring(0.0 to 44.0, 1.0 to 44.0, 1.0 to 45.0, 0.0 to 45.0))
    private val regionB = testArea("admin1:FR-B", GeographicAreaType.ADMIN_1, "country:FR", ring(2.0 to 44.0, 3.0 to 44.0, 3.0 to 45.0, 2.0 to 45.0))
    private val visitedRegions = listOf(regionA, regionB)
    private val departmentInA = testArea("admin2:FR-A1", GeographicAreaType.ADMIN_2, regionA.id, ring(0.1 to 44.1, 0.2 to 44.1, 0.2 to 44.2, 0.1 to 44.2))
    private val departmentInB = testArea("admin2:FR-B1", GeographicAreaType.ADMIN_2, regionB.id, ring(2.1 to 44.1, 2.2 to 44.1, 2.2 to 44.2, 2.1 to 44.2))

    private val interactiveZoom = 5.0
    private val alwaysInteractive: (Double) -> Boolean = { true }
    private val neverInteractive: (Double) -> Boolean = { false }

    private fun featureWith(areaId: String?): Feature {
        val properties = JsonObject()
        if (areaId != null) properties.addProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY, areaId)
        return Feature.fromGeometry(Point.fromLngLat(0.5, 44.5), properties)
    }

    // ===== resolveClickedAdministrativeArea =====

    @Test
    fun `no hit features resolves to no navigation`() {
        val result = resolveClickedAdministrativeArea(emptyList(), visitedRegions, interactiveZoom, alwaysInteractive)

        assertNull(result)
    }

    @Test
    fun `a hit feature whose areaId matches a candidate area resolves to that exact area`() {
        val result = resolveClickedAdministrativeArea(listOf(featureWith(regionA.id)), visitedRegions, interactiveZoom, alwaysInteractive)

        assertEquals(regionA, result)
    }

    @Test
    fun `a hit feature with no areaId property fails safely -- no navigation, never a crash`() {
        val result = resolveClickedAdministrativeArea(listOf(featureWith(null)), visitedRegions, interactiveZoom, alwaysInteractive)

        assertNull(result)
    }

    @Test
    fun `a hit feature whose areaId matches no candidate area fails safely`() {
        val result = resolveClickedAdministrativeArea(listOf(featureWith("admin1:FR-UNKNOWN")), visitedRegions, interactiveZoom, alwaysInteractive)

        assertNull(result)
    }

    @Test
    fun `resolution is limited to the caller's own candidate list, not any area that could theoretically exist`() {
        val onlyRegionAOffered = listOf(regionA)

        val result = resolveClickedAdministrativeArea(listOf(featureWith(regionB.id)), onlyRegionAOffered, interactiveZoom, alwaysInteractive)

        assertNull(result)
    }

    @Test
    fun `a faded-out (non-interactive) overlay never resolves to an area, even with an otherwise-valid hit`() {
        val result = resolveClickedAdministrativeArea(listOf(featureWith(regionA.id)), visitedRegions, interactiveZoom, neverInteractive)

        assertNull(result)
    }

    // ===== nextAdminFocusStack =====

    private val cameraBeforeAnyFocus = MapCameraState(latitude = 46.0, longitude = 1.5, zoom = 5.0, bearing = 0.0, tilt = 0.0)
    private val cameraWhileRegionFocused = MapCameraState(latitude = 44.5, longitude = 0.5, zoom = 8.0, bearing = 0.0, tilt = 0.0)

    @Test
    fun `entering region focus from an empty stack captures the pre-click camera`() {
        val next = nextAdminFocusStack(emptyList(), cameraBeforeAnyFocus, regionA)

        assertEquals(listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id)), next)
    }

    @Test
    fun `re-tapping a different region while already region-focused keeps the original return camera`() {
        val focusedOnA = listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id))

        val next = nextAdminFocusStack(focusedOnA, cameraWhileRegionFocused, regionB)

        assertEquals(listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionB.id)), next)
    }

    @Test
    fun `Region to Department -- entering department focus while region-focused pushes a new frame, keeping the region frame's own return camera untouched`() {
        val focusedOnA = listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id))

        val next = nextAdminFocusStack(focusedOnA, cameraWhileRegionFocused, departmentInA)

        assertEquals(
            listOf(
                AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
                AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, departmentInA.id),
            ),
            next,
        )
    }

    @Test
    fun `Region to Department to sibling Department -- re-tapping a different SAME-REGION department keeps both the region frame and the department's own original return camera`() {
        val anotherDepartmentInA = testArea("admin2:FR-A2", GeographicAreaType.ADMIN_2, regionA.id, ring(0.3 to 44.1, 0.4 to 44.1, 0.4 to 44.2, 0.3 to 44.2))
        val regionThenDepartment = listOf(
            AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
            AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, departmentInA.id),
        )
        val cameraWhileDepartmentFocused = MapCameraState(latitude = 45.8, longitude = 1.2, zoom = 10.0, bearing = 0.0, tilt = 0.0)

        val next = nextAdminFocusStack(regionThenDepartment, cameraWhileDepartmentFocused, anotherDepartmentInA)

        assertEquals(
            listOf(
                AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
                AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, anotherDepartmentInA.id),
            ),
            next,
        )
    }

    @Test
    fun `direct Department from an empty administrative state -- no prior region tap -- pushes a single department-only frame, correctly typed`() {
        val next = nextAdminFocusStack(emptyList(), cameraBeforeAnyFocus, departmentInA)

        assertEquals(listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_2, departmentInA.id)), next)
    }

    @Test
    fun `Codex Blocking 2 regression -- direct Department, then a SECOND direct Department, never misinterprets the first department frame as a region frame`() {
        val directDepartmentX = nextAdminFocusStack(emptyList(), cameraBeforeAnyFocus, departmentInA)
        assertEquals(listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_2, departmentInA.id)), directDepartmentX)

        val cameraWhileDepartmentXFocused = MapCameraState(latitude = 45.6, longitude = 0.2, zoom = 9.0, bearing = 0.0, tilt = 0.0)
        val anotherDepartmentInA = testArea("admin2:FR-A2", GeographicAreaType.ADMIN_2, regionA.id, ring(0.3 to 44.1, 0.4 to 44.1, 0.4 to 44.2, 0.3 to 44.2))
        val directDepartmentY = nextAdminFocusStack(directDepartmentX, cameraWhileDepartmentXFocused, anotherDepartmentInA)

        assertEquals(1, directDepartmentY.size)
        assertEquals(GeographicAreaType.ADMIN_2, directDepartmentY.single().areaType)
        assertEquals(anotherDepartmentInA.id, directDepartmentY.single().areaId)
        assertEquals(
            "sibling Department reselect must preserve the ORIGINAL return camera, not the most recent one",
            cameraBeforeAnyFocus,
            directDepartmentY.single().returnCamera,
        )
    }

    @Test
    fun `switching Region after Department -- tapping a different region while department-focused discards the department frame and keeps the original region-entry camera`() {
        val regionThenDepartment = listOf(
            AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
            AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, departmentInA.id),
        )
        val cameraWhileDepartmentFocused = MapCameraState(latitude = 45.8, longitude = 1.2, zoom = 10.0, bearing = 0.0, tilt = 0.0)

        val next = nextAdminFocusStack(regionThenDepartment, cameraWhileDepartmentFocused, regionB)

        assertEquals(listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionB.id)), next)
    }

    @Test
    fun `nextAdminFocusStack refuses to build an impossible parent-child stack -- a Department whose real parentId disagrees with the existing Region frame fails loudly`() {
        // By construction this should never be reachable through the real, parent-scoped click path
        // (resolveGeographicClick filters candidates before this is ever called) -- kept as a hard,
        // internal-precondition failure rather than a silently wrong stack, per the "no impossible
        // parent-child stack may exist" requirement.
        val focusedOnA = listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id))

        assertThrows(IllegalArgumentException::class.java) {
            nextAdminFocusStack(focusedOnA, cameraWhileRegionFocused, departmentInB)
        }
    }

    // ===== adminFocusBack =====

    @Test
    fun `popping an empty stack fails safely, signalling the caller to fall through instead of crashing`() {
        val result = adminFocusBack(emptyList())

        assertNull(result)
    }

    @Test
    fun `popping a single-frame stack empties it and restores that frame's own return camera`() {
        val stack = listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id))

        val result = adminFocusBack(stack)

        assertEquals(cameraBeforeAnyFocus, result?.cameraToRestore)
        assertTrue(result!!.newStack.isEmpty())
    }

    @Test
    fun `popping a two-frame stack -- department focused -- restores the region-entry camera and leaves the region frame in place`() {
        val stack = listOf(
            AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
            AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, departmentInA.id),
        )

        val result = adminFocusBack(stack)

        assertEquals(cameraWhileRegionFocused, result?.cameraToRestore)
        assertEquals(listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id)), result!!.newStack)
    }

    @Test
    fun `repeated Back -- popping twice from a two-frame stack fully empties it, restoring the true original pre-focus camera last`() {
        val stack = listOf(
            AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
            AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, departmentInA.id),
        )

        val firstPop = adminFocusBack(stack)!!
        val secondPop = adminFocusBack(firstPop.newStack)!!
        val thirdPop = adminFocusBack(secondPop.newStack)

        assertEquals(cameraBeforeAnyFocus, secondPop.cameraToRestore)
        assertTrue(secondPop.newStack.isEmpty())
        assertNull("a third Back with nothing left to pop fails safely, never a crash", thirdPop)
    }

    @Test
    fun `direct Department then Back empties the stack directly -- no phantom region level to pop first`() {
        val directDepartment = listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_2, departmentInA.id))

        val result = adminFocusBack(directDepartment)

        assertEquals(cameraBeforeAnyFocus, result?.cameraToRestore)
        assertTrue(result!!.newStack.isEmpty())
    }

    // ==============================================================================================
    // eligibleClickLevels -- hierarchy-aware TYPE eligibility (first correction round) plus the
    // restored Country-component fallback (second correction round, Minor Issue 3).
    // ==============================================================================================

    @Test
    fun `eligibleClickLevels -- no focus only makes Country eligible, even though Region and Department are also rendered`() {
        assertEquals(listOf(GeographicAreaType.COUNTRY), eligibleClickLevels(null))
    }

    @Test
    fun `eligibleClickLevels -- Country focused makes Region eligible first, then Country itself as a fallback`() {
        assertEquals(listOf(GeographicAreaType.ADMIN_1, GeographicAreaType.COUNTRY), eligibleClickLevels(GeographicAreaType.COUNTRY))
    }

    @Test
    fun `eligibleClickLevels -- Region focused makes Department eligible first, then Region itself for sibling reselect, then Country as an ancestor fallback`() {
        assertEquals(
            listOf(GeographicAreaType.ADMIN_2, GeographicAreaType.ADMIN_1, GeographicAreaType.COUNTRY),
            eligibleClickLevels(GeographicAreaType.ADMIN_1),
        )
    }

    @Test
    fun `eligibleClickLevels -- Department focused makes Department itself eligible first, then Region, then Country as ancestor fallbacks -- physical-validation fix`() {
        // Previously only ADMIN_2 was eligible here, which meant a tap on France while
        // Department-focused could never resolve at all (the physical "camera doesn't refocus on
        // France" bug) -- ADMIN_1 and COUNTRY are now reachable as ancestor-selection fallbacks.
        assertEquals(
            listOf(GeographicAreaType.ADMIN_2, GeographicAreaType.ADMIN_1, GeographicAreaType.COUNTRY),
            eligibleClickLevels(GeographicAreaType.ADMIN_2),
        )
    }

    // ==============================================================================================
    // resolveGeographicClick -- both correction rounds together: hierarchy-aware eligibility AND
    // parent-scoped candidate filtering. Uses a real nested France-like fixture (mainland/
    // Nouvelle-Aquitaine/Haute-Vienne shaped, synthetic coordinates) so the SAME point can validly hit
    // all three layers at once -- exactly the ambiguous case a naive resolver gets wrong.
    // ==============================================================================================

    private val mainlandPolygon = GeographicPolygon(listOf(ring(-5.0 to 41.0, 10.0 to 41.0, 10.0 to 51.0, -5.0 to 51.0)))
    private val corsicaLikePolygon = GeographicPolygon(listOf(ring(80.0 to 1.0, 81.0 to 1.0, 81.0 to 2.0, 80.0 to 2.0)))
    private val franceLikeArea = run {
        val geometry = GeographicMultiPolygon(listOf(mainlandPolygon, corsicaLikePolygon))
        GeographicArea(
            id = "country:FR",
            type = GeographicAreaType.COUNTRY,
            displayName = "France",
            geometry = geometry,
            bounds = computeGeographicBounds(geometry),
            sourceId = "test",
            sourceVersion = "v1",
            sourceProvenance = GeographicAreaProvenance.EXTERNAL_REFERENCE_DATASET,
        )
    }
    private val mainlandComponent = franceLikeArea.components()[0]
    private val corsicaLikeComponent = franceLikeArea.components()[1]
    private val nouvelleAquitaineLike = testArea("admin1:FR-NAQ", GeographicAreaType.ADMIN_1, "country:FR", ring(-1.0 to 44.0, 3.0 to 44.0, 3.0 to 47.0, -1.0 to 47.0))
    private val ileDeFranceLike = testArea("admin1:FR-IDF", GeographicAreaType.ADMIN_1, "country:FR", ring(2.0 to 48.0, 3.0 to 48.0, 3.0 to 49.0, 2.0 to 49.0))
    private val hauteVienneLike = testArea("admin2:FR-87", GeographicAreaType.ADMIN_2, "admin1:FR-NAQ", ring(0.0 to 45.0, 1.0 to 45.0, 1.0 to 46.0, 0.0 to 46.0))

    private val nestedPointHitFeature = Feature.fromGeometry(Point.fromLngLat(0.5, 45.5), JsonObject())

    private fun countryHitFeature(): Feature = mainlandComponent.toCountryOverlayFeature()
    private fun administrativeHitFeature(area: GeographicArea): Feature {
        val properties = JsonObject().apply { addProperty(ADMIN_OVERLAY_AREA_ID_PROPERTY, area.id) }
        return Feature.fromGeometry(nestedPointHitFeature.geometry(), properties)
    }

    /** A click context where all three layers genuinely have a hit at the same point, and the focus
     * chain is internally consistent (Country=France, Region=Nouvelle-Aquitaine when relevant) --
     * the exact ambiguous scenario a naive resolver mishandles. Only [currentFocusLevel] (and the
     * matching `focused*Id`s, passed explicitly) vary across the test steps below. */
    private fun fullyAmbiguousClickContext(
        currentFocusLevel: GeographicAreaType?,
        focusedCountryId: String? = null,
        focusedAdmin1Id: String? = null,
        focusedAdmin2Id: String? = null,
    ) = GeographicClickContext(
        currentFocusLevel = currentFocusLevel,
        focusedCountryId = focusedCountryId,
        focusedAdmin1Id = focusedAdmin1Id,
        focusedAdmin2Id = focusedAdmin2Id,
        zoomLevel = 5.0,
        countryHitFeatures = listOf(countryHitFeature()),
        visitedCountryComponents = listOf(mainlandComponent),
        countryAreaId = franceLikeArea.id,
        regionHitFeatures = listOf(administrativeHitFeature(nouvelleAquitaineLike)),
        visitedRegions = listOf(nouvelleAquitaineLike, ileDeFranceLike),
        departmentHitFeatures = listOf(administrativeHitFeature(hauteVienneLike)),
        visitedDepartments = listOf(hauteVienneLike),
    )

    @Test
    fun `World to France -- with no focus active, an ambiguous point resolves to the Country component, never Region or Department`() {
        val resolution = resolveGeographicClick(fullyAmbiguousClickContext(currentFocusLevel = null))

        assertEquals(GeographicClickResolution.CountryComponent(mainlandComponent), resolution)
    }

    @Test
    fun `France to Nouvelle-Aquitaine -- once Country is focused, the SAME ambiguous point resolves to Region, and Country cannot intercept it`() {
        val resolution = resolveGeographicClick(
            fullyAmbiguousClickContext(currentFocusLevel = GeographicAreaType.COUNTRY, focusedCountryId = franceLikeArea.id),
        )

        assertEquals(GeographicClickResolution.Region(nouvelleAquitaineLike), resolution)
    }

    @Test
    fun `Nouvelle-Aquitaine to Haute-Vienne -- once Region is focused, the SAME ambiguous point resolves to Department, not Region or Country`() {
        val resolution = resolveGeographicClick(
            fullyAmbiguousClickContext(currentFocusLevel = GeographicAreaType.ADMIN_1, focusedAdmin1Id = nouvelleAquitaineLike.id),
        )

        assertEquals(GeographicClickResolution.Department(hauteVienneLike), resolution)
    }

    @Test
    fun `the full World to France to Nouvelle-Aquitaine to Haute-Vienne resolution sequence, driven purely by focus-context transitions`() {
        val step1 = resolveGeographicClick(fullyAmbiguousClickContext(currentFocusLevel = null))
        assertEquals(GeographicClickResolution.CountryComponent(mainlandComponent), step1)

        val step2 = resolveGeographicClick(
            fullyAmbiguousClickContext(currentFocusLevel = GeographicAreaType.COUNTRY, focusedCountryId = franceLikeArea.id),
        )
        assertEquals(GeographicClickResolution.Region(nouvelleAquitaineLike), step2)

        val step3 = resolveGeographicClick(
            fullyAmbiguousClickContext(currentFocusLevel = GeographicAreaType.ADMIN_1, focusedAdmin1Id = nouvelleAquitaineLike.id),
        )
        assertEquals(GeographicClickResolution.Department(hauteVienneLike), step3)
    }

    @Test
    fun `Department focused -- the same ambiguous point resolves to Department sibling reselect only, never back to Region or Country`() {
        val resolution = resolveGeographicClick(
            fullyAmbiguousClickContext(
                currentFocusLevel = GeographicAreaType.ADMIN_2,
                focusedAdmin1Id = nouvelleAquitaineLike.id,
                focusedAdmin2Id = hauteVienneLike.id,
            ),
        )

        assertEquals(GeographicClickResolution.Department(hauteVienneLike), resolution)
    }

    @Test
    fun `no eligible level resolves -- a click with no hits anywhere fails safely to null`() {
        val emptyContext = GeographicClickContext(
            currentFocusLevel = null,
            focusedCountryId = null,
            focusedAdmin1Id = null,
            focusedAdmin2Id = null,
            zoomLevel = 5.0,
            countryHitFeatures = emptyList(),
            visitedCountryComponents = listOf(mainlandComponent),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(),
            visitedRegions = listOf(nouvelleAquitaineLike),
            departmentHitFeatures = emptyList(),
            visitedDepartments = listOf(hauteVienneLike),
        )

        assertNull(resolveGeographicClick(emptyContext))
    }

    // ==============================================================================================
    // Codex re-review's own required regression list -- parent-SCOPED resolution, not merely
    // geographic overlap.
    // ==============================================================================================

    @Test
    fun `1 -- Focus Region A, hit a Department whose real parent is Region B -- the Department must NOT resolve under Region A`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_1,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = null,
            zoomLevel = interactiveZoom,
            countryHitFeatures = emptyList(),
            visitedCountryComponents = emptyList(),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(), // no Region hit at this point -- proves this isn't accidentally resolving as a Region fallback either
            visitedRegions = visitedRegions,
            departmentHitFeatures = listOf(administrativeHitFeature(departmentInB)), // parentId = regionB.id, NOT regionA.id
            visitedDepartments = listOf(departmentInB),
        )

        assertNull(
            "a Department whose real parentId names a different, unfocused Region must never resolve while Region A is focused",
            resolveGeographicClick(context),
        )
    }

    @Test
    fun `2 -- Focus a Department in Region A, hit a Department in Region B -- must NOT become sibling under Region A`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_2,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = departmentInA.id,
            zoomLevel = interactiveZoom,
            countryHitFeatures = emptyList(),
            visitedCountryComponents = emptyList(),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(),
            visitedRegions = visitedRegions,
            departmentHitFeatures = listOf(administrativeHitFeature(departmentInB)),
            visitedDepartments = listOf(departmentInA, departmentInB),
        )

        assertNull(
            "a foreign-Region Department must never silently attach as a sibling under the focused Region's Department",
            resolveGeographicClick(context),
        )
    }

    @Test
    fun `3 -- Focus Region A, hit its own valid child Department -- resolves normally`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_1,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = null,
            zoomLevel = interactiveZoom,
            countryHitFeatures = emptyList(),
            visitedCountryComponents = emptyList(),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(),
            visitedRegions = visitedRegions,
            departmentHitFeatures = listOf(administrativeHitFeature(departmentInA)),
            visitedDepartments = listOf(departmentInA, departmentInB),
        )

        assertEquals(GeographicClickResolution.Department(departmentInA), resolveGeographicClick(context))
    }

    @Test
    fun `4 -- Focus Region A, hit sibling Region B -- the click resolves to Region B (a Region switch)`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_1,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = null,
            zoomLevel = interactiveZoom,
            countryHitFeatures = emptyList(),
            visitedCountryComponents = emptyList(),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = listOf(administrativeHitFeature(regionB)),
            visitedRegions = visitedRegions,
            departmentHitFeatures = emptyList(), // no Department hit -- proves Region-level resolution, not a lucky Department fallback
            visitedDepartments = listOf(departmentInA, departmentInB),
        )

        val resolution = resolveGeographicClick(context)
        assertEquals(GeographicClickResolution.Region(regionB), resolution)

        // And the stack update itself must be a clean switch -- Region B only, no stale child.
        val previousStack = listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id))
        val next = nextAdminFocusStack(previousStack, cameraWhileRegionFocused, (resolution as GeographicClickResolution.Region).area)
        assertEquals(listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionB.id)), next)
    }

    @Test
    fun `5 -- Focus Department A in Region A, hit sibling Department B also in Region A -- one Department frame only`() {
        val anotherDepartmentInA = testArea("admin2:FR-A2", GeographicAreaType.ADMIN_2, regionA.id, ring(0.3 to 44.1, 0.4 to 44.1, 0.4 to 44.2, 0.3 to 44.2))
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_2,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = departmentInA.id,
            zoomLevel = interactiveZoom,
            countryHitFeatures = emptyList(),
            visitedCountryComponents = emptyList(),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(),
            visitedRegions = visitedRegions,
            departmentHitFeatures = listOf(administrativeHitFeature(anotherDepartmentInA)),
            visitedDepartments = listOf(departmentInA, anotherDepartmentInA),
        )

        val resolution = resolveGeographicClick(context)
        assertEquals(GeographicClickResolution.Department(anotherDepartmentInA), resolution)

        val previousStack = listOf(
            AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
            AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, departmentInA.id),
        )
        val next = nextAdminFocusStack(previousStack, cameraWhileRegionFocused, (resolution as GeographicClickResolution.Department).area)
        assertEquals(1, next.count { it.areaType == GeographicAreaType.ADMIN_2 })
        assertEquals(
            listOf(
                AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
                AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, anotherDepartmentInA.id),
            ),
            next,
        )
    }

    @Test
    fun `6 -- a Department with a malformed (null) parentId fails safely -- never resolves, never crashes`() {
        val malformedDepartment = testArea("admin2:FR-MALFORMED", GeographicAreaType.ADMIN_2, parentId = null, ring(5.0 to 44.1, 5.1 to 44.1, 5.1 to 44.2, 5.0 to 44.2))
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_1,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = null,
            zoomLevel = interactiveZoom,
            countryHitFeatures = emptyList(),
            visitedCountryComponents = emptyList(),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(),
            visitedRegions = visitedRegions,
            departmentHitFeatures = listOf(administrativeHitFeature(malformedDepartment)),
            visitedDepartments = listOf(malformedDepartment),
        )

        assertNull(
            "a malformed (parentId == null) Department must never match a real, non-null focused Region id",
            resolveGeographicClick(context),
        )
    }

    // ==============================================================================================
    // Minor Issue 3 -- Country-component sibling fallback restored, without letting it intercept a
    // genuinely eligible Region hit.
    // ==============================================================================================

    @Test
    fun `Country-component fallback after Admin1 miss -- Country focused, no Region hit at all, falls back to a Country-component sibling switch`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.COUNTRY,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = null,
            focusedAdmin2Id = null,
            zoomLevel = interactiveZoom,
            countryHitFeatures = listOf(corsicaLikeComponent.toCountryOverlayFeature()),
            visitedCountryComponents = listOf(mainlandComponent, corsicaLikeComponent),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(), // no Region anywhere near this tap
            visitedRegions = visitedRegions,
            departmentHitFeatures = emptyList(),
            visitedDepartments = emptyList(),
        )

        assertEquals(GeographicClickResolution.CountryComponent(corsicaLikeComponent), resolveGeographicClick(context))
    }

    @Test
    fun `valid Admin1 still has priority over the Country-component fallback -- a point hitting both resolves to Region, never the Country fallback`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.COUNTRY,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = null,
            focusedAdmin2Id = null,
            zoomLevel = interactiveZoom,
            countryHitFeatures = listOf(mainlandComponent.toCountryOverlayFeature()),
            visitedCountryComponents = listOf(mainlandComponent, corsicaLikeComponent),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = listOf(administrativeHitFeature(nouvelleAquitaineLike)),
            visitedRegions = listOf(nouvelleAquitaineLike),
            departmentHitFeatures = emptyList(),
            visitedDepartments = emptyList(),
        )

        assertEquals(GeographicClickResolution.Region(nouvelleAquitaineLike), resolveGeographicClick(context))
    }

    // ==============================================================================================
    // Physical-validation correction round -- ANCESTOR SELECTION reachable from any focus depth.
    // ==============================================================================================

    @Test
    fun `ancestor selection -- Region focused, hit only Country -- resolves to CountryComponent, not null`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_1,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = null,
            zoomLevel = interactiveZoom,
            countryHitFeatures = listOf(mainlandComponent.toCountryOverlayFeature()),
            visitedCountryComponents = listOf(mainlandComponent),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(), // no Region hit at all -- the user zoomed out past Region data
            visitedRegions = visitedRegions,
            departmentHitFeatures = emptyList(),
            visitedDepartments = emptyList(),
        )

        assertEquals(GeographicClickResolution.CountryComponent(mainlandComponent), resolveGeographicClick(context))
    }

    @Test
    fun `ancestor selection -- Department focused, hit only Country -- resolves to CountryComponent (the physically-observed bug)`() {
        // Exact physical scenario: drill into a Department, manually zoom out until only France is
        // visible, tap France. Previously COUNTRY was not even in eligibleClickLevels(ADMIN_2), so
        // this always resolved to null no matter what was hit.
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_2,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = departmentInA.id,
            zoomLevel = interactiveZoom,
            countryHitFeatures = listOf(mainlandComponent.toCountryOverlayFeature()),
            visitedCountryComponents = listOf(mainlandComponent),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = emptyList(),
            visitedRegions = visitedRegions,
            departmentHitFeatures = emptyList(),
            visitedDepartments = listOf(departmentInA),
        )

        assertEquals(GeographicClickResolution.CountryComponent(mainlandComponent), resolveGeographicClick(context))
    }

    @Test
    fun `ancestor selection -- Department focused, hit the parent Region directly (no Back needed) -- resolves to Region`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_2,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = departmentInA.id,
            zoomLevel = interactiveZoom,
            countryHitFeatures = emptyList(),
            visitedCountryComponents = emptyList(),
            countryAreaId = franceLikeArea.id,
            departmentHitFeatures = emptyList(), // no Department hit -- proves this isn't a lucky Department resolve
            visitedDepartments = listOf(departmentInA),
            regionHitFeatures = listOf(administrativeHitFeature(regionA)),
            visitedRegions = visitedRegions,
        )

        assertEquals(GeographicClickResolution.Region(regionA), resolveGeographicClick(context))
    }

    @Test
    fun `ancestor selection never shadows a genuinely eligible descendant -- Department focused, a point hitting both Department and Country resolves to Department`() {
        val context = GeographicClickContext(
            currentFocusLevel = GeographicAreaType.ADMIN_2,
            focusedCountryId = franceLikeArea.id,
            focusedAdmin1Id = regionA.id,
            focusedAdmin2Id = departmentInA.id,
            zoomLevel = interactiveZoom,
            countryHitFeatures = listOf(mainlandComponent.toCountryOverlayFeature()),
            visitedCountryComponents = listOf(mainlandComponent),
            countryAreaId = franceLikeArea.id,
            regionHitFeatures = listOf(administrativeHitFeature(regionA)),
            visitedRegions = visitedRegions,
            departmentHitFeatures = listOf(administrativeHitFeature(departmentInA)),
            visitedDepartments = listOf(departmentInA),
        )

        assertEquals(GeographicClickResolution.Department(departmentInA), resolveGeographicClick(context))
    }

    // ==============================================================================================
    // Physical-validation correction round -- nextGeographicSelectionOutcome, the single pure
    // state-transition decision DiscoveryMapView's real click handler now delegates to. Covers the
    // required regression scenarios: camera always targets the newly selected geography, ancestor
    // selection clears descendant focus, and Region/Department resolutions never invent a country
    // focus return camera.
    // ==============================================================================================

    private val worldCamera = MapCameraState(latitude = 20.0, longitude = 0.0, zoom = 1.0, bearing = 0.0, tilt = 0.0)
    private val manuallyZoomedOutCamera = MapCameraState(latitude = 40.0, longitude = -10.0, zoom = 2.0, bearing = 0.0, tilt = 0.0)

    @Test
    fun `1 -- World to France -- camera target is France's own bounds`() {
        val outcome = nextGeographicSelectionOutcome(
            resolution = GeographicClickResolution.CountryComponent(mainlandComponent),
            currentCountryFocusReturnCamera = null,
            currentAdminFocusStack = emptyList(),
            cameraBeforeThisClick = worldCamera,
        )

        assertEquals(mainlandComponent.bounds, outcome.cameraTargetBounds)
        assertEquals(worldCamera, outcome.nextCountryFocusReturnCamera)
        assertTrue(outcome.nextAdminFocusStack.isEmpty())
    }

    @Test
    fun `2 -- France to Nouvelle-Aquitaine -- camera target is the Region's own bounds`() {
        val outcome = nextGeographicSelectionOutcome(
            resolution = GeographicClickResolution.Region(nouvelleAquitaineLike),
            currentCountryFocusReturnCamera = worldCamera,
            currentAdminFocusStack = emptyList(),
            cameraBeforeThisClick = cameraBeforeAnyFocus,
        )

        assertEquals(nouvelleAquitaineLike.bounds, outcome.cameraTargetBounds)
        assertEquals(
            listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, nouvelleAquitaineLike.id)),
            outcome.nextAdminFocusStack,
        )
    }

    @Test
    fun `3 -- Nouvelle-Aquitaine to Haute-Vienne -- camera target is the Department's own bounds`() {
        val outcome = nextGeographicSelectionOutcome(
            resolution = GeographicClickResolution.Department(hauteVienneLike),
            currentCountryFocusReturnCamera = worldCamera,
            currentAdminFocusStack = listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, nouvelleAquitaineLike.id)),
            cameraBeforeThisClick = cameraWhileRegionFocused,
        )

        assertEquals(hauteVienneLike.bounds, outcome.cameraTargetBounds)
        assertEquals(2, outcome.nextAdminFocusStack.size)
        assertEquals(hauteVienneLike.id, outcome.nextAdminFocusStack.last().areaId)
    }

    @Test
    fun `4 -- Department selected, camera manually changed, then France selected -- admin focus is cleared and target is France's bounds`() {
        val departmentFocused = listOf(
            AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
            AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, departmentInA.id),
        )

        val outcome = nextGeographicSelectionOutcome(
            resolution = GeographicClickResolution.CountryComponent(mainlandComponent),
            currentCountryFocusReturnCamera = worldCamera,
            currentAdminFocusStack = departmentFocused,
            cameraBeforeThisClick = manuallyZoomedOutCamera, // the user's manual pan/zoom before this click
        )

        assertEquals(mainlandComponent.bounds, outcome.cameraTargetBounds)
        assertTrue("selecting Country must fully clear the Region/Department focus stack", outcome.nextAdminFocusStack.isEmpty())
        // Selecting Country while already country-focused never overwrites the ORIGINAL return camera
        // with the manual mid-drill-down camera -- same "never overwrite an existing return camera"
        // contract nextCountryFocusReturnCamera already guarantees.
        assertEquals(worldCamera, outcome.nextCountryFocusReturnCamera)
    }

    @Test
    fun `5 -- Department selected, then parent Region selected directly -- Department is cleared and target is the Region's bounds`() {
        val departmentFocused = listOf(
            AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id),
            AdminFocusFrame(cameraWhileRegionFocused, GeographicAreaType.ADMIN_2, departmentInA.id),
        )

        val outcome = nextGeographicSelectionOutcome(
            resolution = GeographicClickResolution.Region(regionA),
            currentCountryFocusReturnCamera = worldCamera,
            currentAdminFocusStack = departmentFocused,
            cameraBeforeThisClick = manuallyZoomedOutCamera,
        )

        assertEquals(regionA.bounds, outcome.cameraTargetBounds)
        assertEquals(
            "selecting the parent Region directly must discard the Department frame, leaving only the Region frame",
            listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id)),
            outcome.nextAdminFocusStack,
        )
    }

    // Required scenario 6 ("Back after normal drill-down restores exact stored previous camera") is
    // already covered by the existing adminFocusBack tests above (see, e.g., "popping a two-frame
    // stack -- department focused -- restores the region-entry camera and leaves the region frame in
    // place") -- nextGeographicSelectionOutcome only decides the FORWARD (selection) transition, never
    // Back, which is why it isn't repeated here.

    @Test
    fun `7 -- Region resolution never invents or overwrites the country focus return camera, regardless of the pre-click camera`() {
        val outcome = nextGeographicSelectionOutcome(
            resolution = GeographicClickResolution.Region(regionB),
            currentCountryFocusReturnCamera = worldCamera,
            currentAdminFocusStack = listOf(AdminFocusFrame(cameraBeforeAnyFocus, GeographicAreaType.ADMIN_1, regionA.id)),
            cameraBeforeThisClick = manuallyZoomedOutCamera,
        )

        assertEquals(
            "only an actual Country-component click may ever change the country focus return camera",
            worldCamera,
            outcome.nextCountryFocusReturnCamera,
        )
    }
}
