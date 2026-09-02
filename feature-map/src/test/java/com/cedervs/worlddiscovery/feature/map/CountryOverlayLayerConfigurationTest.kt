package com.cedervs.worlddiscovery.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused, pure-JVM guard on the Country overlay's layer configuration constants — mirrors
 * `DiscoveredCellLayerConfigurationTest`: `applyCountryOverlay` itself constructs real MapLibre
 * `Style`/`FillLayer`/`LineLayer` objects, which need a live Android/native runtime and can't be
 * unit-tested here.
 */
class CountryOverlayLayerConfigurationTest {

    @Test
    fun `the country overlay source and layers are entirely separate from the fine-cell and current-position ids`() {
        val countryOverlayIds = setOf(COUNTRY_OVERLAY_SOURCE_ID, COUNTRY_OVERLAY_FILL_LAYER_ID, COUNTRY_OVERLAY_OUTLINE_LAYER_ID)
        val fineIds = setOf(DISCOVERED_CELLS_SOURCE_ID, DISCOVERED_CELLS_FILL_LAYER_ID, DISCOVERED_CELLS_OUTLINE_LAYER_ID)

        assertTrue(countryOverlayIds.intersect(fineIds).isEmpty())
    }

    @Test
    fun `the fill and outline layer ids are distinct from each other`() {
        assertNotEquals(COUNTRY_OVERLAY_FILL_LAYER_ID, COUNTRY_OVERLAY_OUTLINE_LAYER_ID)
    }

    @Test
    fun `the zoom visibility range is ordered and non-empty`() {
        assertTrue(COUNTRY_OVERLAY_MIN_ZOOM < COUNTRY_OVERLAY_MAX_ZOOM)
    }

    @Test
    fun `the fade-out window is ordered and ends at or before the overlay's own max zoom`() {
        assertTrue(COUNTRY_OVERLAY_FADE_OUT_START_ZOOM < COUNTRY_OVERLAY_FADE_OUT_END_ZOOM)
        assertTrue(COUNTRY_OVERLAY_FADE_OUT_END_ZOOM <= COUNTRY_OVERLAY_MAX_ZOOM)
    }

    @Test
    fun `the fit padding is a positive, safe value`() {
        assertTrue(COUNTRY_FOCUS_FIT_PADDING_PX > 0)
    }

    @Test
    fun `the areaId and componentIndex feature property keys are distinct`() {
        assertNotEquals(COUNTRY_OVERLAY_AREA_ID_PROPERTY, COUNTRY_OVERLAY_COMPONENT_INDEX_PROPERTY)
    }

    @Test
    fun `the basemap-aligned border prototype layer id is distinct from every existing layer id`() {
        val existingIds = setOf(
            COUNTRY_OVERLAY_SOURCE_ID,
            COUNTRY_OVERLAY_FILL_LAYER_ID,
            COUNTRY_OVERLAY_OUTLINE_LAYER_ID,
            DISCOVERED_CELLS_SOURCE_ID,
            DISCOVERED_CELLS_FILL_LAYER_ID,
            DISCOVERED_CELLS_OUTLINE_LAYER_ID,
        )

        assertTrue(BASEMAP_ALIGNED_FRANCE_BORDER_LAYER_ID !in existingIds)
    }

    @Test
    fun `the basemap-aligned border prototype references the basemap's own vector source, never a new one`() {
        // Deliberately NOT one of our own source ids -- this prototype must never accidentally
        // declare/own a competing "openmaptiles" source of its own.
        assertTrue(BASEMAP_VECTOR_SOURCE_ID != COUNTRY_OVERLAY_SOURCE_ID)
        assertTrue(BASEMAP_VECTOR_SOURCE_ID != DISCOVERED_CELLS_SOURCE_ID)
    }

    @Test
    fun `the water-masking anchor id matches the real Liberty style's own water layer id`() {
        // Confirmed by direct style-JSON inspection of the live Liberty style during the Option G
        // design-review round this prototype was introduced in: {"id":"water","type":"fill",
        // "source":"openmaptiles","source-layer":"water", ...}. Not one of our own layer ids -- a
        // real, explicit coupling to whatever style is currently loaded, same category as
        // BASEMAP_VECTOR_SOURCE_ID/BASEMAP_BOUNDARY_SOURCE_LAYER above.
        assertEquals("water", BASEMAP_WATER_LAYER_ID)
    }

    @Test
    fun `the water-masking anchor id is never one of World Discovery's own layer or source ids`() {
        val ownIds = setOf(
            COUNTRY_OVERLAY_SOURCE_ID,
            COUNTRY_OVERLAY_FILL_LAYER_ID,
            COUNTRY_OVERLAY_OUTLINE_LAYER_ID,
            DISCOVERED_CELLS_SOURCE_ID,
            DISCOVERED_CELLS_FILL_LAYER_ID,
            DISCOVERED_CELLS_OUTLINE_LAYER_ID,
            BASEMAP_ALIGNED_FRANCE_BORDER_LAYER_ID,
        )

        assertTrue(BASEMAP_WATER_LAYER_ID !in ownIds)
    }
}
