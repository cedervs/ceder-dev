package com.cedervs.worlddiscovery.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the actual Option G branch behavior in [insertCountryOverlayFillLayer] — the thing the
 * previous round's tests never exercised (they only checked [BASEMAP_WATER_LAYER_ID]'s value and its
 * non-collision with other layer ids, never which of `addLayerBelow`/`addLayer` actually gets called).
 * [RecordingFillInsertionTarget] is a pure-JVM fake of [CountryOverlayFillInsertionTarget] — no real
 * `Style`/`FillLayer` is ever constructed here, so these tests need no native MapLibre runtime.
 */
class CountryOverlayFillInsertionTest {

    private class RecordingFillInsertionTarget(private val waterExists: Boolean) : CountryOverlayFillInsertionTarget {
        var waterAnchorExistsCallCount = 0
            private set
        var insertBelowWaterAnchorCallCount = 0
            private set
        var insertOnTopCallCount = 0
            private set

        override fun waterAnchorExists(): Boolean {
            waterAnchorExistsCallCount++
            return waterExists
        }

        override fun insertBelowWaterAnchor() {
            insertBelowWaterAnchorCallCount++
        }

        override fun insertOnTop() {
            insertOnTopCallCount++
        }
    }

    @Test
    fun `water anchor present -- inserts below it exactly once, never adds on top`() {
        val target = RecordingFillInsertionTarget(waterExists = true)

        insertCountryOverlayFillLayer(target)

        assertEquals(1, target.insertBelowWaterAnchorCallCount)
        assertEquals(0, target.insertOnTopCallCount)
    }

    @Test
    fun `water anchor absent -- adds on top exactly once, never inserts below`() {
        val target = RecordingFillInsertionTarget(waterExists = false)

        insertCountryOverlayFillLayer(target)

        assertEquals(0, target.insertBelowWaterAnchorCallCount)
        assertEquals(1, target.insertOnTopCallCount)
    }

    @Test
    fun `exactly one insertion happens regardless of branch -- never both, never neither`() {
        for (waterExists in listOf(true, false)) {
            val target = RecordingFillInsertionTarget(waterExists)

            insertCountryOverlayFillLayer(target)

            val totalInsertions = target.insertBelowWaterAnchorCallCount + target.insertOnTopCallCount
            assertEquals("waterExists=$waterExists", 1, totalInsertions)
        }
    }

    @Test
    fun `the anchor check itself runs exactly once per call -- no redundant re-checking`() {
        val target = RecordingFillInsertionTarget(waterExists = true)

        insertCountryOverlayFillLayer(target)

        assertEquals(1, target.waterAnchorExistsCallCount)
    }
}
