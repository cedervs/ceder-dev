package com.cedervs.worlddiscovery.feature.map

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MapViewLifecycleControllerTest {

    private lateinit var target: RecordingLifecycleTarget
    private lateinit var controller: MapViewLifecycleController

    @Before
    fun setUp() {
        target = RecordingLifecycleTarget()
        controller = MapViewLifecycleController(target)
    }

    @Test
    fun `dispatch forwards each lifecycle event to the target in order`() {
        controller.dispatch(Lifecycle.Event.ON_CREATE)
        controller.dispatch(Lifecycle.Event.ON_START)
        controller.dispatch(Lifecycle.Event.ON_RESUME)

        assertEquals(listOf("onCreate", "onStart", "onResume"), target.calls)
    }

    @Test
    fun `dispatching ON_DESTROY tears down through pause and stop first`() {
        controller.dispatch(Lifecycle.Event.ON_CREATE)
        controller.dispatch(Lifecycle.Event.ON_START)
        controller.dispatch(Lifecycle.Event.ON_RESUME)

        controller.dispatch(Lifecycle.Event.ON_DESTROY)

        assertEquals(
            listOf("onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy"),
            target.calls,
        )
        assertTrue(controller.isDestroyed)
    }

    @Test
    fun `destroy from an unstarted state skips pause and stop`() {
        controller.dispatch(Lifecycle.Event.ON_CREATE)

        controller.destroy()

        assertEquals(listOf("onCreate", "onDestroy"), target.calls)
    }

    @Test
    fun `destroy is safe to call twice — the second call does nothing`() {
        controller.dispatch(Lifecycle.Event.ON_CREATE)
        controller.dispatch(Lifecycle.Event.ON_START)

        controller.destroy()
        controller.destroy()

        assertEquals(listOf("onCreate", "onStart", "onStop", "onDestroy"), target.calls)
    }

    @Test
    fun `an explicit destroy followed by the real ON_DESTROY event does not double-destroy`() {
        // Models DiscoveryMapView's actual scenario: composition disposal calls destroy()
        // first; if the host lifecycle's own ON_DESTROY is forwarded afterward, it must be a
        // no-op rather than a second, dangerous teardown.
        controller.dispatch(Lifecycle.Event.ON_CREATE)
        controller.dispatch(Lifecycle.Event.ON_START)
        controller.dispatch(Lifecycle.Event.ON_RESUME)
        controller.destroy()

        controller.dispatch(Lifecycle.Event.ON_DESTROY)

        assertEquals(
            listOf("onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy"),
            target.calls,
        )
    }

    @Test
    fun `no lifecycle event reaches the target after destruction`() {
        controller.dispatch(Lifecycle.Event.ON_CREATE)
        controller.destroy()

        controller.dispatch(Lifecycle.Event.ON_START)
        controller.dispatch(Lifecycle.Event.ON_RESUME)

        assertEquals(listOf("onCreate", "onDestroy"), target.calls)
    }

    @Test
    fun `destroying before onCreate ever ran does not call onDestroy on the target`() {
        controller.destroy()

        assertTrue(target.calls.isEmpty())
        assertTrue(controller.isDestroyed)
    }

    @Test
    fun `onLowMemory forwards while active and is a no-op once destroyed`() {
        controller.dispatch(Lifecycle.Event.ON_CREATE)
        controller.onLowMemory()
        assertEquals(listOf("onCreate", "onLowMemory"), target.calls)

        controller.destroy()
        controller.onLowMemory()

        assertEquals(listOf("onCreate", "onLowMemory", "onDestroy"), target.calls)
    }

    @Test
    fun `a repeated ON_CREATE does not re-invoke the target`() {
        controller.dispatch(Lifecycle.Event.ON_CREATE)
        controller.dispatch(Lifecycle.Event.ON_CREATE)

        assertEquals(listOf("onCreate"), target.calls)
    }
}

private class RecordingLifecycleTarget : MapViewLifecycleTarget {
    val calls = mutableListOf<String>()

    override fun onCreate() {
        calls.add("onCreate")
    }

    override fun onStart() {
        calls.add("onStart")
    }

    override fun onResume() {
        calls.add("onResume")
    }

    override fun onPause() {
        calls.add("onPause")
    }

    override fun onStop() {
        calls.add("onStop")
    }

    override fun onDestroy() {
        calls.add("onDestroy")
    }

    override fun onLowMemory() {
        calls.add("onLowMemory")
    }
}
