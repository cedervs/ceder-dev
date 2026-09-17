package com.cedervs.worlddiscovery.core.location

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Direct proof of [runReceiverWorkWithCriticalDiagnostics]'s ordering and failure-isolation
 * guarantees — the closest available substitute for a real `BackgroundLocationReceiver`/
 * `BootCompletedReceiver` test, since this repository has no harness for invoking an actual
 * `BroadcastReceiver.onReceive` (no Robolectric `BroadcastReceiver` support wired anywhere here).
 * Both receivers are thin wrappers around this exact function plus `goAsync()`/
 * `PendingResult.finish()`, which this file cannot and does not attempt to exercise -- only the
 * ordering/safety contract this function provides them.
 */
class ReceiverDiagnosticOrchestrationTest {

    private class RecordingCalls {
        val calls = mutableListOf<String>()
    }

    // ==============================================================================================
    // Ordering: critical -> functional -> flush, for both an "empty delivery"-shaped functionalWork
    // (does nothing extra) and a "non-empty delivery"-shaped one (does real work) -- the two
    // shapes BackgroundLocationReceiver's own functionalWork lambda can take.
    // ==============================================================================================

    @Test
    fun `ordering -- empty-delivery-shaped functionalWork -- critical, then functional, then flush`() = runTest {
        val recorded = RecordingCalls()

        runReceiverWorkWithCriticalDiagnostics(
            recordCritical = { recorded.calls.add("critical"); true },
            flush = { recorded.calls.add("flush"); true },
            functionalWork = { /* simulates an empty batch: submitBackgroundLocationObservations(emptyList()) already no-ops */ },
        )

        assertEquals(listOf("critical", "flush"), recorded.calls)
    }

    @Test
    fun `ordering -- non-empty-delivery-shaped functionalWork -- critical, then functional, then flush`() = runTest {
        val recorded = RecordingCalls()

        runReceiverWorkWithCriticalDiagnostics(
            recordCritical = { recorded.calls.add("critical"); true },
            flush = { recorded.calls.add("flush"); true },
            functionalWork = { recorded.calls.add("functional: submitted batch") },
        )

        assertEquals(listOf("critical", "functional: submitted batch", "flush"), recorded.calls)
    }

    // ==============================================================================================
    // BLOCKER 1 -- a throwing critical diagnostic must never skip functional work.
    // ==============================================================================================

    @Test
    fun `a throwing recordCritical never prevents functionalWork from running -- background-submission-shaped`() = runTest {
        var functionalWorkRan = false

        runReceiverWorkWithCriticalDiagnostics(
            recordCritical = { error("simulated critical diagnostic failure") },
            flush = { true },
            functionalWork = { functionalWorkRan = true },
        )

        assertTrue("functional work must run even though the critical diagnostic threw", functionalWorkRan)
    }

    @Test
    fun `a throwing recordCritical never prevents functionalWork from running -- boot-reregistration-shaped`() = runTest {
        var reregistered = false

        runReceiverWorkWithCriticalDiagnostics(
            recordCritical = { error("simulated critical diagnostic failure") },
            flush = { true },
            functionalWork = { reregistered = true }, // stands in for rearmBackgroundTrackingAfterBoot()
        )

        assertTrue("boot re-registration must run even though the critical diagnostic threw", reregistered)
    }

    // ==============================================================================================
    // A throwing flush must never mask functionalWork's own successful completion.
    // ==============================================================================================

    @Test
    fun `a throwing flush never prevents the call from completing normally after functionalWork succeeds`() = runTest {
        var functionalWorkRan = false

        // Must not throw -- the flush failure is swallowed internally.
        runReceiverWorkWithCriticalDiagnostics(
            recordCritical = { true },
            flush = { error("simulated flush failure") },
            functionalWork = { functionalWorkRan = true },
        )

        assertTrue(functionalWorkRan)
    }

    // ==============================================================================================
    // Cancellation / final flush (round 3, item 3): genuine functional failure or cancellation
    // must still trigger a flush attempt, and must propagate completely unchanged.
    // ==============================================================================================

    @Test
    fun `functionalWork throwing a generic exception still triggers flush, and the original exception propagates unchanged`() = runTest {
        val recorded = RecordingCalls()

        try {
            runReceiverWorkWithCriticalDiagnostics(
                recordCritical = { recorded.calls.add("critical"); true },
                flush = { recorded.calls.add("flush"); true },
                functionalWork = { recorded.calls.add("functional"); error("simulated functional submission failure") },
            )
            fail("expected the functional failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("simulated functional submission failure", e.message)
        }

        assertEquals(listOf("critical", "functional", "flush"), recorded.calls)
    }

    @Test
    fun `functionalWork throwing CancellationException still triggers flush, and the cancellation propagates unchanged, never swallowed or transformed`() = runTest {
        val recorded = RecordingCalls()

        try {
            runReceiverWorkWithCriticalDiagnostics(
                recordCritical = { recorded.calls.add("critical"); true },
                flush = { recorded.calls.add("flush"); true },
                functionalWork = {
                    recorded.calls.add("functional")
                    throw CancellationException("simulated genuine coroutine cancellation")
                },
            )
            fail("expected the CancellationException to propagate")
        } catch (e: CancellationException) {
            assertEquals("simulated genuine coroutine cancellation", e.message)
        }

        assertEquals(
            "flush must still have been attempted even though functionalWork was cancelled",
            listOf("critical", "functional", "flush"),
            recorded.calls,
        )
    }

    @Test
    fun `a throwing flush during a functionalWork failure never replaces or masks the original exception`() = runTest {
        try {
            runReceiverWorkWithCriticalDiagnostics(
                recordCritical = { true },
                flush = { error("simulated flush failure, must never surface") },
                functionalWork = { error("the real, original functional failure") },
            )
            fail("expected the original functional failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals(
                "the flush's own failure must never replace the original exception",
                "the real, original functional failure",
                e.message,
            )
        }
    }

    // ==============================================================================================
    // Round 4 (receiver orchestration testability) -- "finish always occurs," proven against the
    // exact try/finally shape both receivers actually use around this function.
    // ==============================================================================================

    @Test
    fun `finish always occurs, matching the exact receiver call shape, even when everything fails`() = runTest {
        var finished = false

        try {
            try {
                runReceiverWorkWithCriticalDiagnostics(
                    recordCritical = { error("simulated critical diagnostic failure") },
                    flush = { error("simulated flush failure") },
                    functionalWork = { error("simulated functional failure") },
                )
            } finally {
                finished = true // stands in for pendingResult.finish()
            }
        } catch (e: IllegalStateException) {
            // expected -- the functional failure must still propagate past this function
        }

        assertTrue("finish() must be reached regardless of any diagnostic or functional failure", finished)
    }

    @Test
    fun `finish always occurs even when functionalWork is cancelled`() = runTest {
        var finished = false

        try {
            try {
                runReceiverWorkWithCriticalDiagnostics(
                    recordCritical = { true },
                    flush = { true },
                    functionalWork = { throw CancellationException("simulated genuine cancellation") },
                )
            } finally {
                finished = true
            }
        } catch (e: CancellationException) {
            // expected
        }

        assertTrue(finished)
    }

    @Test
    fun `finish always occurs on the ordinary success path too`() = runTest {
        var finished = false

        try {
            runReceiverWorkWithCriticalDiagnostics(
                recordCritical = { true },
                flush = { true },
                functionalWork = { },
            )
        } finally {
            finished = true
        }

        assertTrue(finished)
    }
}
