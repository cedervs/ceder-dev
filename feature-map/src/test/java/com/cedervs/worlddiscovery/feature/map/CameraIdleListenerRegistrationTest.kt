package com.cedervs.worlddiscovery.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraIdleListenerRegistrationTest {

    @Test
    fun `attach registers exactly once on the target`() {
        val target = RecordingCameraIdleListenerTarget()
        val registration = CameraIdleListenerRegistration()

        registration.attach(target) {}

        assertEquals(1, target.addCallCount)
        assertEquals(0, target.removeCallCount)
    }

    @Test
    fun `attaching twice without a detach in between is a no-op the second time`() {
        val target = RecordingCameraIdleListenerTarget()
        val registration = CameraIdleListenerRegistration()

        registration.attach(target) {}
        registration.attach(target) {}

        assertEquals(1, target.addCallCount)
    }

    @Test
    fun `detach removes exactly once from the attached target`() {
        val target = RecordingCameraIdleListenerTarget()
        val registration = CameraIdleListenerRegistration()
        registration.attach(target) {}

        registration.detach()

        assertEquals(1, target.removeCallCount)
    }

    @Test
    fun `detaching twice only removes once`() {
        val target = RecordingCameraIdleListenerTarget()
        val registration = CameraIdleListenerRegistration()
        registration.attach(target) {}

        registration.detach()
        registration.detach()

        assertEquals(1, target.removeCallCount)
    }

    @Test
    fun `detach without a prior attach does nothing`() {
        val registration = CameraIdleListenerRegistration()

        // Exercises exactly the real bug's edge case: onDispose running before the asynchronous
        // map/style callback ever reached attach() — must not throw and must not touch any target.
        registration.detach()
    }

    @Test
    fun `detach then attach to a new target re-registers on that new target`() {
        val firstTarget = RecordingCameraIdleListenerTarget()
        val secondTarget = RecordingCameraIdleListenerTarget()
        val registration = CameraIdleListenerRegistration()
        registration.attach(firstTarget) {}
        registration.detach()

        registration.attach(secondTarget) {}

        assertEquals(1, firstTarget.addCallCount)
        assertEquals(1, firstTarget.removeCallCount)
        assertEquals(1, secondTarget.addCallCount)
        assertEquals(0, secondTarget.removeCallCount)
    }

    @Test
    fun `the attached callback is the one the target actually invokes`() {
        val target = RecordingCameraIdleListenerTarget()
        val registration = CameraIdleListenerRegistration()
        var idleCount = 0

        registration.attach(target) { idleCount++ }
        target.simulateIdle()
        target.simulateIdle()

        assertEquals(2, idleCount)
    }
}

private class RecordingCameraIdleListenerTarget : CameraIdleListenerTarget {
    var addCallCount = 0
        private set
    var removeCallCount = 0
        private set
    private var onIdle: (() -> Unit)? = null

    override fun addOnCameraIdleListener(onIdle: () -> Unit) {
        addCallCount++
        this.onIdle = onIdle
    }

    override fun removeOnCameraIdleListener() {
        removeCallCount++
        onIdle = null
    }

    fun simulateIdle() {
        onIdle?.invoke()
    }
}
