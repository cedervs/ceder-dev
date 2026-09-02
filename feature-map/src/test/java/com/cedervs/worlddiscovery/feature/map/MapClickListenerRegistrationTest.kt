package com.cedervs.worlddiscovery.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapClickListenerRegistrationTest {

    @Test
    fun `attach registers exactly once on the target`() {
        val target = RecordingMapClickListenerTarget()
        val registration = MapClickListenerRegistration()

        registration.attach(target) { true }

        assertEquals(1, target.addCallCount)
        assertEquals(0, target.removeCallCount)
    }

    @Test
    fun `attaching twice without a detach in between is a no-op the second time`() {
        val target = RecordingMapClickListenerTarget()
        val registration = MapClickListenerRegistration()

        registration.attach(target) { true }
        registration.attach(target) { true }

        assertEquals(1, target.addCallCount)
    }

    @Test
    fun `detach removes exactly once from the attached target`() {
        val target = RecordingMapClickListenerTarget()
        val registration = MapClickListenerRegistration()
        registration.attach(target) { true }

        registration.detach()

        assertEquals(1, target.removeCallCount)
    }

    @Test
    fun `detaching twice only removes once`() {
        val target = RecordingMapClickListenerTarget()
        val registration = MapClickListenerRegistration()
        registration.attach(target) { true }

        registration.detach()
        registration.detach()

        assertEquals(1, target.removeCallCount)
    }

    @Test
    fun `detach without a prior attach does nothing`() {
        val registration = MapClickListenerRegistration()

        // Exercises the real bug's edge case: onDispose running before the asynchronous map/style
        // callback ever reached attach() -- must not throw and must not touch any target.
        registration.detach()
    }

    @Test
    fun `detach then attach to a new target re-registers on that new target, such as a style reload`() {
        val firstTarget = RecordingMapClickListenerTarget()
        val secondTarget = RecordingMapClickListenerTarget()
        val registration = MapClickListenerRegistration()
        registration.attach(firstTarget) { true }
        registration.detach()

        registration.attach(secondTarget) { true }

        assertEquals(1, firstTarget.addCallCount)
        assertEquals(1, firstTarget.removeCallCount)
        assertEquals(1, secondTarget.addCallCount)
        assertEquals(0, secondTarget.removeCallCount)
    }

    @Test
    fun `the attached callback is the one the target actually invokes, with its own return value`() {
        val target = RecordingMapClickListenerTarget()
        val registration = MapClickListenerRegistration()
        var clickCount = 0

        registration.attach(target) { clickCount++; true }
        val consumed = target.simulateClick(LatLng(1.0, 2.0))

        assertEquals(1, clickCount)
        assertTrue(consumed)
    }
}

private class RecordingMapClickListenerTarget : MapClickListenerTarget {
    var addCallCount = 0
        private set
    var removeCallCount = 0
        private set
    private var onClick: ((LatLng) -> Boolean)? = null

    override fun addOnMapClickListener(onClick: (LatLng) -> Boolean) {
        addCallCount++
        this.onClick = onClick
    }

    override fun removeOnMapClickListener() {
        removeCallCount++
        onClick = null
    }

    fun simulateClick(latLng: LatLng): Boolean = onClick?.invoke(latLng) ?: false
}
