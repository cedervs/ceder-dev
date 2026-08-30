package com.cedervs.worlddiscovery.core.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundLocationControllerTest {

    private lateinit var consent: FakeBackgroundTrackingConsent
    private lateinit var registrar: FakeBackgroundLocationRegistrar

    @Before
    fun setUp() {
        consent = FakeBackgroundTrackingConsent()
        registrar = FakeBackgroundLocationRegistrar()
    }

    @Test
    fun `arm with consent enabled registers background updates`() = runTest {
        val controller = BackgroundLocationController(consent, registrar, this)
        consent.emitNext(true)

        controller.arm()
        advanceUntilIdle()

        assertEquals(1, registrar.registerCallCount)
    }

    @Test
    fun `arm with consent disabled does not register`() = runTest {
        val controller = BackgroundLocationController(consent, registrar, this)
        consent.emitNext(false)

        controller.arm()
        advanceUntilIdle()

        assertEquals(0, registrar.registerCallCount)
    }

    @Test
    fun `disarm cancels an in-flight arm before it can register`() = runTest {
        val controller = BackgroundLocationController(consent, registrar, this)

        // No value sent on the consent channel yet, so arm()'s awaited consent.isEnabled.first()
        // is still pending when disarm() runs immediately after.
        controller.arm()
        controller.disarm()
        advanceUntilIdle()

        assertEquals(0, registrar.registerCallCount)
        assertEquals(1, registrar.unregisterCallCount)
    }

    @Test
    fun `disarm is safe when nothing was ever armed`() = runTest {
        val controller = BackgroundLocationController(consent, registrar, this)

        controller.disarm()
        advanceUntilIdle()

        assertEquals(0, registrar.registerCallCount)
        assertEquals(1, registrar.unregisterCallCount)
    }

    @Test
    fun `calling arm twice cancels the first attempt`() = runTest {
        val controller = BackgroundLocationController(consent, registrar, this)

        controller.arm()
        controller.arm()
        consent.emitNext(true)
        advanceUntilIdle()

        // Only the second attempt's consent read ever resolves; the first was cancelled before
        // it could reach the registrar.
        assertEquals(1, registrar.registerCallCount)
    }

    @Test
    fun `armSuspending directly awaits completion`() = runTest {
        val controller = BackgroundLocationController(consent, registrar, this)
        consent.emitNext(true)

        controller.armSuspending()

        assertEquals(1, registrar.registerCallCount)
    }

    @Test
    fun `armSuspending with consent disabled does not register`() = runTest {
        val controller = BackgroundLocationController(consent, registrar, this)
        consent.emitNext(false)

        controller.armSuspending()

        assertEquals(0, registrar.registerCallCount)
    }
}

private class FakeBackgroundTrackingConsent : BackgroundTrackingConsent {
    private val channel = Channel<Boolean>(Channel.UNLIMITED)
    val setEnabledCalls = mutableListOf<Boolean>()

    fun emitNext(value: Boolean) {
        channel.trySend(value)
    }

    override val isEnabled: Flow<Boolean> = channel.receiveAsFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        setEnabledCalls.add(enabled)
    }
}

private class FakeBackgroundLocationRegistrar : BackgroundLocationRegistrar {
    var registerCallCount = 0
        private set
    var unregisterCallCount = 0
        private set

    override fun register() {
        registerCallCount++
    }

    override fun unregister() {
        unregisterCallCount++
    }
}
