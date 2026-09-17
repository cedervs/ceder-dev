package com.cedervs.worlddiscovery.core.location

/** Default bound for [CalibrationDiagnosticSink.recordCritical] and
 * [CalibrationDiagnosticFileWriter.awaitFlush] — long enough for a single write attempt to
 * complete even under transient I/O pressure, short enough to leave ample room inside a
 * `BroadcastReceiver.goAsync()` window (~10s total budget on most Android versions) for the
 * functional work around it. Not itself calibrated against a physical device; a generous,
 * deliberately round bound for a rare, low-frequency operation. */
const val CRITICAL_RECORD_DEFAULT_TIMEOUT_MILLIS = 2000L

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — see [CalibrationDiagnosticFileWriter]'s doc
 * comment for the overall removal point. Diagnostic-only logging seam for a
 * [CalibrationDiagnosticEvent] — deliberately a pure Kotlin interface, matching every other
 * diagnostic seam in this module ([LocationDiagnosticLogger], [TransitionDiagnosticLogger],
 * [BackgroundLocationDiagnosticLogger]): the real, file-writing implementation
 * ([AndroidCalibrationDiagnosticSink]) is a separate class so [record] itself stays trivially
 * fakeable in tests without any file I/O.
 *
 * Same non-throwing contract as every other diagnostic seam here — [recordSafely] is the actual
 * enforcement point, so a misbehaving implementation can never affect tracking, registration, or
 * submission.
 */
interface CalibrationDiagnosticSink {
    fun record(event: CalibrationDiagnosticEvent)

    /**
     * Records [event] and blocks the **calling thread** — which must already be a background
     * thread, never the main thread — until the write has actually reached the file-writing
     * boundary (its `write()`/`close()` calls completed), or [timeoutMillis] elapses, whichever
     * comes first. Returns `true` only if the write itself completed successfully within that
     * bound; `false` on a timeout, a full internal queue, or a genuine write failure.
     *
     * Reserved for the small number of lifecycle-critical events where the calling component's
     * *process* may be killed moments after returning — chiefly, a `BroadcastReceiver` actually
     * having run at all (see `BackgroundLocationReceiver`/`BootCompletedReceiver`, and
     * `docs/ai-context/LOCATION_TRACKING.md`'s "Receiver durability" section). [record]'s ordinary
     * fire-and-forget path cannot answer "did this reach disk before the process might die" —
     * that ambiguity is exactly what made "receiver actually ran" indistinguishable from "receiver
     * never ran" in a prior review round.
     *
     * Ordinary events (every location fix, every arm/disarm) **must** keep using [record] —
     * calling this for each of them would reintroduce the very blocking-the-callback risk
     * [record] exists to avoid. This is deliberately a *separate* method rather than a parameter
     * on [record], so a call site's choice between "fire-and-forget" and "wait for durability" is
     * visible at the call site, not buried in a boolean argument.
     */
    fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long = CRITICAL_RECORD_DEFAULT_TIMEOUT_MILLIS): Boolean
}

/** Default used by every call site's constructor parameter — logging is opt-in, wired explicitly
 * in production (see `AppContainer`) and only for debug builds; tests that don't care about it
 * need not pass anything. [recordCritical] returns `true` unconditionally and immediately — there
 * is nothing to persist, so "nothing to wait for" is trivially successful, not a failure a caller
 * needs to react to. */
class NoOpCalibrationDiagnosticSink : CalibrationDiagnosticSink {
    override fun record(event: CalibrationDiagnosticEvent) = Unit
    override fun recordCritical(event: CalibrationDiagnosticEvent, timeoutMillis: Long): Boolean = true
}

/** See [CalibrationDiagnosticSink]'s doc comment's "non-throwing contract" paragraph. Does not
 * special-case [kotlinx.coroutines.CancellationException]: [record] is never `suspend`, so any
 * such exception here can only be fabricated by a misbehaving implementation, never genuine job
 * cancellation — see [LocationDiagnosticLogger.logSafely]'s doc comment for the identical
 * reasoning applied elsewhere in this module. */
fun CalibrationDiagnosticSink.recordSafely(event: CalibrationDiagnosticEvent) {
    try {
        record(event)
    } catch (t: Throwable) {
        // Diagnostic-only — must never affect tracking, registration, or submission.
    }
}

/**
 * The [recordCritical] counterpart to [recordSafely] — Codex review round: a thrown diagnostic
 * failure here (a formatting bug, a misbehaving sink implementation, anything) must never be able
 * to skip a `BroadcastReceiver`'s real functional work by propagating out of what was meant to be
 * a best-effort call. Returns `false` for a thrown failure, identical to a genuine timeout or a
 * full queue — a caller cannot and must not distinguish the two; both simply mean "the critical
 * evidence may not have been durably recorded," which is diagnostic-only information that never
 * gates anything. See [runReceiverWorkWithCriticalDiagnostics], which wraps this same guarantee
 * again at the orchestration level for defense in depth.
 */
fun CalibrationDiagnosticSink.recordCriticalSafely(
    event: CalibrationDiagnosticEvent,
    timeoutMillis: Long = CRITICAL_RECORD_DEFAULT_TIMEOUT_MILLIS,
): Boolean =
    try {
        recordCritical(event, timeoutMillis)
    } catch (t: Throwable) {
        false
    }
