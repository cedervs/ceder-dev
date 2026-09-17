package com.cedervs.worlddiscovery.core.location

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [performBackgroundLocationRegistration] — the Context-free core of
 * [FusedBackgroundLocationRegistrar.register] — specifically the correction that
 * [BackgroundRegistrationOutcome.REGISTERED] must only be logged once `requestLocationUpdates`'s
 * [Task] has genuinely completed successfully, not right after the call that starts it.
 *
 * Uses real [Task] instances from [Tasks.forResult]/[Tasks.forException] (already complete) and
 * [TaskCompletionSource] (completed later, under the test's own control) — real Task/listener
 * semantics, not a hand-rolled substitute, without needing a real `FusedLocationProviderClient`,
 * `Context`, `PendingIntent`, or Play Services connection. Does not require
 * `@RunWith(RobolectricTestRunner)`: unlike `LocationRequest.Builder.build()` (which constructs a
 * real `android.os.WorkSource`), the [Task]/[Tasks]/[TaskCompletionSource] classes used here touch
 * no Android SDK class needing a real runtime — confirmed by this file compiling and running
 * cleanly in a plain JVM unit test.
 *
 * The listener [Executor] used throughout is a simple inline-run one, matching what
 * [FusedBackgroundLocationRegistrar] itself uses — see [performBackgroundLocationRegistration]'s
 * doc comment for why. This also makes every assertion here deterministic: no real async
 * scheduling is involved.
 */
class FusedBackgroundLocationRegistrarRegistrationTest {

    private val inlineExecutor = Executor { runnable -> runnable.run() }

    private class RecordingBackgroundLocationDiagnosticLoggerForRegistrar : BackgroundLocationDiagnosticLogger {
        val registrations = mutableListOf<BackgroundRegistrationOutcome>()

        override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) {
            registrations.add(outcome)
        }

        override fun logDelivery(observations: List<LocationObservation>) = Unit
    }

    private fun register(
        hasPermission: Boolean = true,
        startRequest: () -> Task<Void>,
        diagnosticLogger: BackgroundLocationDiagnosticLogger,
    ) {
        performBackgroundLocationRegistration(
            hasPermission = hasPermission,
            startRequest = startRequest,
            config = LocationUpdateConfig.BACKGROUND_PROVISIONAL,
            diagnosticLogger = diagnosticLogger,
            listenerExecutor = inlineExecutor,
        )
    }

    @Test
    fun `missing background permission logs SKIPPED_NO_PERMISSION and never starts the request`() {
        val logger = RecordingBackgroundLocationDiagnosticLoggerForRegistrar()
        var startRequestCallCount = 0

        register(
            hasPermission = false,
            startRequest = { startRequestCallCount++; Tasks.forResult(null) },
            diagnosticLogger = logger,
        )

        assertEquals(listOf(BackgroundRegistrationOutcome.SKIPPED_NO_PERMISSION), logger.registrations)
        assertEquals(0, startRequestCallCount)
    }

    @Test
    fun `a successfully completed Task logs REGISTERED`() {
        val logger = RecordingBackgroundLocationDiagnosticLoggerForRegistrar()

        register(startRequest = { Tasks.forResult(null) }, diagnosticLogger = logger)

        assertEquals(listOf(BackgroundRegistrationOutcome.REGISTERED), logger.registrations)
    }

    @Test
    fun `an asynchronously failed Task logs FAILED_TASK, not REGISTERED`() {
        val logger = RecordingBackgroundLocationDiagnosticLoggerForRegistrar()

        register(
            startRequest = { Tasks.forException(RuntimeException("simulated Play Services failure")) },
            diagnosticLogger = logger,
        )

        assertEquals(listOf(BackgroundRegistrationOutcome.FAILED_TASK), logger.registrations)
    }

    @Test
    fun `no REGISTERED is logged before the Task actually completes`() {
        val logger = RecordingBackgroundLocationDiagnosticLoggerForRegistrar()
        val completionSource = TaskCompletionSource<Void>()

        register(startRequest = { completionSource.task }, diagnosticLogger = logger)
        assertTrue("no outcome should be logged before the Task completes", logger.registrations.isEmpty())

        completionSource.setResult(null)
        assertEquals(listOf(BackgroundRegistrationOutcome.REGISTERED), logger.registrations)
    }

    @Test
    fun `FAILED_TASK is only logged once the Task actually fails, not before`() {
        val logger = RecordingBackgroundLocationDiagnosticLoggerForRegistrar()
        val completionSource = TaskCompletionSource<Void>()

        register(startRequest = { completionSource.task }, diagnosticLogger = logger)
        assertTrue(logger.registrations.isEmpty())

        completionSource.setException(RuntimeException("simulated Play Services failure"))
        assertEquals(listOf(BackgroundRegistrationOutcome.FAILED_TASK), logger.registrations)
    }

    @Test
    fun `a synchronous SecurityException from starting the request logs FAILED_SECURITY_EXCEPTION`() {
        val logger = RecordingBackgroundLocationDiagnosticLoggerForRegistrar()

        register(
            startRequest = { throw SecurityException("simulated concurrent permission revocation") },
            diagnosticLogger = logger,
        )

        assertEquals(listOf(BackgroundRegistrationOutcome.FAILED_SECURITY_EXCEPTION), logger.registrations)
    }

    @Test
    fun `a diagnostic logger that throws never prevents the registration call itself`() {
        var startRequestCallCount = 0
        val throwingLogger = object : BackgroundLocationDiagnosticLogger {
            override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) {
                error("simulated diagnostic logger failure")
            }

            override fun logDelivery(observations: List<LocationObservation>) = Unit
        }

        // Must not throw — and the request must still have been started despite the logger
        // failing.
        register(
            startRequest = { startRequestCallCount++; Tasks.forResult(null) },
            diagnosticLogger = throwingLogger,
        )

        assertEquals(1, startRequestCallCount)
    }

    @Test
    fun `a diagnostic logger that throws from the success path never surfaces as an application failure`() {
        val throwingLogger = object : BackgroundLocationDiagnosticLogger {
            override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) {
                if (outcome == BackgroundRegistrationOutcome.REGISTERED) {
                    error("simulated diagnostic logger failure from the success listener")
                }
            }

            override fun logDelivery(observations: List<LocationObservation>) = Unit
        }

        // Must not throw — logRegistrationSafely swallows it.
        register(startRequest = { Tasks.forResult(null) }, diagnosticLogger = throwingLogger)
    }

    @Test
    fun `a diagnostic logger that throws from the failure path never surfaces as an application failure`() {
        val throwingLogger = object : BackgroundLocationDiagnosticLogger {
            override fun logRegistration(config: LocationUpdateConfig, outcome: BackgroundRegistrationOutcome) {
                if (outcome == BackgroundRegistrationOutcome.FAILED_TASK) {
                    error("simulated diagnostic logger failure from the failure listener")
                }
            }

            override fun logDelivery(observations: List<LocationObservation>) = Unit
        }

        // Must not throw — logRegistrationSafely swallows it.
        register(
            startRequest = { Tasks.forException(RuntimeException("simulated Play Services failure")) },
            diagnosticLogger = throwingLogger,
        )
    }

    // ==============================================================================================
    // TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE -- calibrationDiagnosticSink wiring, alongside
    // (not instead of) the existing BackgroundLocationDiagnosticLogger above. See
    // CalibrationDiagnosticFileWriter.kt's doc comment for the removal point.
    // ==============================================================================================

    private class RecordingCalibrationDiagnosticSinkForRegistrar : CalibrationDiagnosticSink {
        val outcomes = mutableListOf<BackgroundRegistrationOutcome>()

        override fun record(event: CalibrationDiagnosticEvent) {
            if (event is CalibrationDiagnosticEvent.BackgroundRegistration) outcomes.add(event.outcome)
        }

        override fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long): Boolean {
            record(event)
            return true
        }
    }

    @Test
    fun `the calibration sink receives the same outcome as the Logcat diagnostic logger`() {
        val logger = RecordingBackgroundLocationDiagnosticLoggerForRegistrar()
        val sink = RecordingCalibrationDiagnosticSinkForRegistrar()

        performBackgroundLocationRegistration(
            hasPermission = true,
            startRequest = { Tasks.forResult(null) },
            config = LocationUpdateConfig.BACKGROUND_PROVISIONAL,
            diagnosticLogger = logger,
            listenerExecutor = inlineExecutor,
            calibrationDiagnosticSink = sink,
        )

        assertEquals(listOf(BackgroundRegistrationOutcome.REGISTERED), logger.registrations)
        assertEquals(listOf(BackgroundRegistrationOutcome.REGISTERED), sink.outcomes)
    }

    @Test
    fun `a throwing calibration sink never prevents registration from proceeding`() {
        var startRequestCallCount = 0
        val throwingSink = object : CalibrationDiagnosticSink {
            override fun record(event: CalibrationDiagnosticEvent) = error("simulated calibration sink failure")
            override fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long) =
                error("simulated calibration sink failure")
        }

        performBackgroundLocationRegistration(
            hasPermission = true,
            startRequest = { startRequestCallCount++; Tasks.forResult(null) },
            config = LocationUpdateConfig.BACKGROUND_PROVISIONAL,
            diagnosticLogger = RecordingBackgroundLocationDiagnosticLoggerForRegistrar(),
            listenerExecutor = inlineExecutor,
            calibrationDiagnosticSink = throwingSink,
        )

        assertEquals(1, startRequestCallCount)
    }
}
