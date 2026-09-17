package com.cedervs.worlddiscovery.core.location

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — see [CalibrationDiagnosticFileWriter]'s doc
 * comment for the overall removal point.
 *
 * Pure, Android-free orchestration of the exact ordering/safety contract
 * `BackgroundLocationReceiver` and `BootCompletedReceiver` both need around their real functional
 * work: a critical diagnostic record attempt, then the functional work itself, then a bounded
 * diagnostic flush attempt — always in that order. Extracted here (rather than left inline in
 * each receiver) so this ordering and its failure-isolation guarantees are directly unit-testable
 * without Robolectric or a real `BroadcastReceiver` — this repository has no test harness for
 * invoking an actual `BroadcastReceiver.onReceive`, and the two receivers themselves stay thin
 * callers that only add `goAsync()`/`PendingResult.finish()`, which are not meaningfully
 * unit-testable here regardless of what this function does.
 *
 * ## Guarantees
 * - [recordCritical] and [flush] are both wrapped in a swallow-all boundary internally (mirroring
 *   [recordCriticalSafely]'s contract, defense-in-depth even if a caller's own lambda is not
 *   itself already safe) — a thrown diagnostic failure can never prevent or skip [functionalWork],
 *   and can never replace whatever [functionalWork] itself throws.
 * - [functionalWork] always runs, and its outcome — including a thrown exception or a genuine
 *   [kotlinx.coroutines.CancellationException] — always propagates unchanged from this function.
 *   Diagnostics are pure side effects around it, never a gate and never a substitute result.
 * - [flush] is always attempted after [functionalWork] finishes, whether it succeeded, threw, or
 *   was cancelled (a `finally` block) — so diagnostic evidence for whatever [functionalWork]
 *   itself already recorded (e.g. a background batch's own `LOCATION_DELIVERED`/`DISCOVERY_RESULT`
 *   lines) gets a chance to reach the file even when [functionalWork] itself failed.
 * - Callers own `goAsync()`/`PendingResult.finish()` entirely; this function neither knows about
 *   nor touches them — `finish()` in the caller's own `finally` around calling this function stays
 *   unconditional regardless of what this function does or throws.
 *
 * ## Timeout/worst-case bound
 * [recordCritical] and [flush] are each expected to be already-bounded calls (see
 * [CalibrationDiagnosticSink.recordCritical]/[CalibrationDiagnosticFileWriter.awaitFlush], both
 * defaulting to [CRITICAL_RECORD_DEFAULT_TIMEOUT_MILLIS]) — this function adds no additional
 * waiting of its own beyond invoking them once each, so the worst-case added latency for one
 * receiver invocation stays the sum of those two bounds (≈4s at the current 2s defaults),
 * regardless of how many locations a background batch happens to carry.
 */
suspend fun runReceiverWorkWithCriticalDiagnostics(
    recordCritical: () -> Boolean,
    flush: () -> Boolean,
    functionalWork: suspend () -> Unit,
) {
    safelyRunDiagnostic(recordCritical)
    try {
        functionalWork()
    } finally {
        safelyRunDiagnostic(flush)
    }
}

/** Swallow-all boundary shared by both diagnostic hooks in [runReceiverWorkWithCriticalDiagnostics]
 * — a thrown `Throwable` (including a fabricated [kotlinx.coroutines.CancellationException] from
 * a misbehaving implementation) is caught and treated identically to an ordinary `false` return.
 * Never `suspend`, so nothing here can observe or interfere with genuine structured-concurrency
 * cancellation of the surrounding coroutine — that only happens via [functionalWork] itself. */
private inline fun safelyRunDiagnostic(block: () -> Boolean): Boolean =
    try {
        block()
    } catch (t: Throwable) {
        false
    }
