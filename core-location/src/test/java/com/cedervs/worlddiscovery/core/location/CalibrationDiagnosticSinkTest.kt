package com.cedervs.worlddiscovery.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the non-throwing contract [recordSafely]/[recordCriticalSafely] add on top of a raw
 * [CalibrationDiagnosticSink] implementation -- the actual enforcement point for "diagnostic
 * failure must never affect the caller," mirroring every other `*Safely` wrapper in this module. */
class CalibrationDiagnosticSinkTest {

    private class ThrowingSink : CalibrationDiagnosticSink {
        override fun record(event: CalibrationDiagnosticEvent) {
            error("simulated diagnostic sink failure")
        }

        override fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long): Boolean {
            error("simulated diagnostic sink failure")
        }
    }

    private class SucceedingSink : CalibrationDiagnosticSink {
        var lastTimeoutMillis: Long? = null

        override fun record(event: CalibrationDiagnosticEvent) = Unit

        override fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long): Boolean {
            lastTimeoutMillis = timeoutMillis
            return true
        }
    }

    private val event = CalibrationDiagnosticEvent.Lifecycle(CalibrationLifecycleKind.BACKGROUND_LOCATION_RECEIVER_INVOKED)

    @Test
    fun `recordSafely swallows a throwing sink and never propagates`() {
        // Must not throw.
        ThrowingSink().recordSafely(event)
    }

    @Test
    fun `recordCriticalSafely swallows a throwing sink and returns false, rather than propagating`() {
        val result = ThrowingSink().recordCriticalSafely(event)

        assertFalse("a thrown diagnostic failure must be indistinguishable from an ordinary false return", result)
    }

    @Test
    fun `recordCriticalSafely passes the timeout through and returns the sink's own result on success`() {
        val sink = SucceedingSink()

        val result = sink.recordCriticalSafely(event, timeoutMillis = 1234)

        assertTrue(result)
        assertEquals(1234L, sink.lastTimeoutMillis)
    }

    @Test
    fun `NoOpCalibrationDiagnosticSink recordCritical returns true immediately -- nothing to acknowledge`() {
        assertTrue(NoOpCalibrationDiagnosticSink().recordCritical(event))
    }
}
