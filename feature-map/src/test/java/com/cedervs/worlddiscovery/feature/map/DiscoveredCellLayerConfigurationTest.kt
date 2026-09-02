package com.cedervs.worlddiscovery.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Focused, pure-JVM guard on the discovered-cell layer configuration constants.
 * `applyDiscoveredCellGeometries` itself constructs real MapLibre `Style`/`FillLayer`/`LineLayer`
 * objects, which need a live Android/native runtime and can't be unit-tested here — same boundary
 * as the rest of this module (see `DiscoveredCellGeometryRenderingTest`/`AntimeridianUnwrappingTest`,
 * which test only the pure-JVM geometry logic for the same reason).
 */
class DiscoveredCellLayerConfigurationTest {

    @Test
    fun `the fill and outline layers use distinct ids`() {
        // A collision here would mean the outline layer's style.addLayer(...) call silently
        // clashes with the fill layer instead of adding a genuinely separate layer on top of it.
        assertNotEquals(DISCOVERED_CELLS_FILL_LAYER_ID, DISCOVERED_CELLS_OUTLINE_LAYER_ID)
    }

    @Test
    fun `the provisional cell outline is black and 2dp wide`() {
        // Provisional physical-validation styling, not final art direction — see the constants'
        // own doc comment in DiscoveredCellGeometryRendering.kt.
        assertEquals("#000000", CELL_OUTLINE_COLOR)
        assertEquals(2f, CELL_OUTLINE_WIDTH, 0f)
    }
}
