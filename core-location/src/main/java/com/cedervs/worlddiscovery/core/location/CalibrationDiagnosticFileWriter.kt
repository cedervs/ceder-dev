package com.cedervs.worlddiscovery.core.location

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Two-file rotation caps total on-disk size at roughly 2x this — see the class doc comment's
 * "Rotation" section. A 30-60 minute calibration session at this module's event rate is expected
 * to produce well under one megabyte. */
internal const val CALIBRATION_LOG_MAX_FILE_SIZE_BYTES = 4L * 1024 * 1024

private const val CALIBRATION_LOG_FILE_NAME = "tracking-calibration.ndjson"
private const val CALIBRATION_LOG_BACKUP_FILE_NAME = "tracking-calibration.ndjson.1"
private const val CALIBRATION_LOG_SUBDIRECTORY = "calibration-diagnostics"
internal const val CALIBRATION_LOG_QUEUE_CAPACITY = 500
private const val DIAGNOSTIC_OVERFLOW_EVENT_TYPE = "DIAGNOSTIC_OVERFLOW"

/** Backoff between retried submission attempts in [CalibrationDiagnosticFileWriter.submitWithRetryUntilDeadline]
 * — short enough that several retries still fit comfortably inside the ~2s default critical/flush
 * timeout, long enough not to busy-spin. Never itself extends a caller's timeout budget. */
private const val RETRY_BACKOFF_MILLIS = 10L

/**
 * TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE — see `docs/ai-context/LOCATION_TRACKING.md`'s
 * "Background tracking calibration diagnostic logger" section for the full writeup. This is NOT
 * production analytics/telemetry, NOT a product feature, NOT route/discovery history, and NOT a
 * permanent tracking architecture — it exists solely so a physical background-tracking
 * calibration trip (screen lock, task swipe, process death) can be inspected offline afterward via
 * `adb pull`, because a live `adb logcat` capture cannot survive USB disconnection across a
 * multi-phase walking test.
 *
 * **Removal point**: once background tracking calibration (see the LOCATION_TRACKING.md section
 * above) is complete, delete this class, [AndroidCalibrationDiagnosticSink],
 * `AndroidDiscoveryDiagnosticSink`, [ScreenStateCalibrationReceiver], and their `AppContainer`
 * wiring (the `calibrationDiagnosticWriter`/`calibrationDiagnosticSink`/`discoveryDiagnosticSink`
 * fields and everything threaded from them).
 *
 * Writes one NDJSON (newline-delimited JSON) line per event to
 * `<outputDirectory>/tracking-calibration.ndjson`, appending across the whole process lifetime.
 * Deliberately takes an already-resolved [outputDirectory] (a plain [File]) rather than a
 * [Context] — the actual write/rotate/escape logic here is plain-JVM testable with a temp
 * directory, no Android dependency, no Robolectric needed. See [forContext] for the one place a
 * real [Context] is resolved into that directory.
 *
 * **Only ever constructed in a debug build.** `AppContainer` gates construction of this class
 * itself on `BuildConfig.DEBUG` — a release build never allocates this writer, its background
 * executor, or its bounded queue at all; it wires the existing [NoOpCalibrationDiagnosticSink] /
 * `NoOpDiscoveryDiagnosticSink` directly instead. Every field below can therefore assume it is
 * live and enabled by construction — see the [isEnabled] parameter's own doc comment for the one
 * remaining reason it still exists.
 *
 * ## Never blocks the calling thread (ordinary path)
 * [appendEvent] only builds the NDJSON line (pure, cheap) and submits the actual file write to a
 * single background thread — it never performs I/O itself, so it can never block a location
 * callback (the foreground path's main-thread FLP callback, a coroutine dispatcher, etc.) on disk
 * latency. The executor's queue is bounded ([CALIBRATION_LOG_QUEUE_CAPACITY]); once full, further
 * [appendEvent] writes are rejected — see "Overflow accounting" below for what happens then. This
 * never blocks or throws back to the caller.
 *
 * ## Overflow accounting
 * A prior review round found silently dropping events past the bounded queue unacceptable for
 * diagnostic interpretation: an incomplete capture could look complete. [droppedEventCount] counts
 * every [appendEvent]/[appendEventCritical] call rejected because the queue was full. Every task
 * that actually runs on the background thread — an ordinary write, a critical write, or an
 * [awaitFlush] barrier — checks this counter afterward and, if nonzero, atomically resets it and
 * writes one [DIAGNOSTIC_OVERFLOW_EVENT_TYPE] record carrying the lost count; always via a direct,
 * already-on-the-worker-thread call, never by re-submitting to the same executor, so an overflow
 * marker can never itself be dropped by the same queue that caused it (no possibility of an
 * enqueue loop). If that marker write itself fails, the count is restored (atomically added back,
 * so a concurrent new drop is never clobbered) rather than lost — a later successful flush will
 * still report it. [awaitFlush] additionally *retries* submitting its own barrier task, bounded by
 * its own timeout, rather than failing on the first rejection — the moment right after a burst of
 * drops is exactly when the queue is still most likely to be full, so a single-attempt barrier
 * could otherwise almost always miss the very overflow it exists to report.
 *
 * ## Critical, acknowledged writes
 * [appendEventCritical] is the bounded, blocking counterpart to [appendEvent] — see
 * [CalibrationDiagnosticSink.recordCritical]'s doc comment for the full contract and when to use
 * it instead of the ordinary fire-and-forget path.
 *
 * ## Rotation
 * Before each write, if the current file's length is at or beyond
 * [CALIBRATION_LOG_MAX_FILE_SIZE_BYTES], it's renamed to a single backup file (overwriting any
 * previous backup) and a fresh file is started. If the rename itself fails (e.g. Windows' rename-
 * onto-an-existing-destination semantics, a locked handle, a transient filesystem issue), the
 * current file is truncated instead of left to grow unbounded — losing that one rollover's
 * un-rotated content is an acceptable trade for a temporary, best-effort tool; growing without
 * bound is not. Both rotation and the write itself always happen on the same single background
 * thread, sequentially — that alone is what makes this thread-safe with no additional locking.
 *
 * A process killed mid-write can leave one partial/truncated final NDJSON line — this is an
 * inherent limitation of an append-only logger with no resumable transaction log, and is not
 * specially handled here (deliberately, to avoid over-engineering a temporary tool). Retrieval/
 * analysis must treat a non-parseable final line as an expected artifact of process death, not
 * evidence of a bug — see `docs/ai-context/LOCATION_TRACKING.md`.
 *
 * ## Failure safety
 * Every write is wrapped in a swallow-all catch, matching every other diagnostic logger in this
 * module ([AndroidLocationDiagnosticLogger] etc.) — a full disk, a permissions issue, or any other
 * I/O failure can never propagate into the tracking pipeline that triggered the event.
 */
class CalibrationDiagnosticFileWriter internal constructor(
    private val outputDirectory: File,
    /** Only meaningful for direct construction (as every test here does) — production code never
     * constructs this class at all in a release build (see the class doc comment), so this flag
     * is not the release/debug gate; it exists so a test can build a writer that deliberately
     * never touches the filesystem. */
    private val isEnabled: Boolean,
    val sessionId: String = UUID.randomUUID().toString(),
    private val clock: () -> Instant = Instant::now,
    private val additionalSessionFields: Map<String, Any?> = emptyMap(),
    queueCapacity: Int = CALIBRATION_LOG_QUEUE_CAPACITY,
) {
    // Default rejection handler (AbortPolicy) throws RejectedExecutionException synchronously on
    // a full queue -- appendEvent/appendEventCritical below both rely on catching that, rather
    // than a custom silent handler, so overflow is always observable (see "Overflow accounting").
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
    )

    /** Count of [appendEvent] calls rejected because the queue was full since the last time a
     * [DIAGNOSTIC_OVERFLOW_EVENT_TYPE] record was written — see the class doc comment's "Overflow
     * accounting" section. */
    private val droppedEventCount = AtomicLong(0)

    init {
        if (isEnabled) {
            appendEvent("SESSION_START", additionalSessionFields)
        }
    }

    /**
     * Records one event, identified by [eventType], with arbitrary [fields] — the actual NDJSON
     * line (including [sessionId] and a fresh [clock] timestamp) is built by
     * [buildCalibrationEventLine], and the write itself is dispatched to a background thread; see
     * the class doc comment. No-ops entirely (never even submits to the executor) if [isEnabled]
     * is false. Never blocks or throws back to the caller — a full queue is counted (see
     * [droppedEventCount]), never silently discarded and forgotten.
     */
    fun appendEvent(eventType: String, fields: Map<String, Any?>) {
        if (!isEnabled) return
        val line = buildCalibrationEventLine(sessionId, clock(), eventType, fields)
        try {
            executor.execute {
                writeLineReturningSuccess(line)
                flushOverflowMarkerIfNeeded()
            }
        } catch (t: Throwable) {
            // Queue full (RejectedExecutionException) or any other executor-level failure --
            // either way, count it as lost rather than silently discarding it. See "Overflow
            // accounting" in the class doc comment.
            droppedEventCount.incrementAndGet()
        }
    }

    /**
     * See [CalibrationDiagnosticSink.recordCritical]'s doc comment for the full contract.
     * [timeoutMillis] bounds the *entire* call, including any retried submission attempts (see
     * [submitWithRetryUntilDeadline]) — this never extends the documented worst-case bound, it
     * only makes better use of the same budget instead of failing on the first transient
     * rejection. A rejection that still isn't accepted by the deadline counts as a lost event
     * (see the class doc comment's "Overflow accounting" — a critical event that never got
     * recorded at all must not silently escape [droppedEventCount]).
     */
    fun appendEventCritical(
        eventType: String,
        fields: Map<String, Any?>,
        timeoutMillis: Long = CRITICAL_RECORD_DEFAULT_TIMEOUT_MILLIS,
    ): Boolean {
        if (!isEnabled) return true
        val line = buildCalibrationEventLine(sessionId, clock(), eventType, fields)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val latch = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val submitted = submitWithRetryUntilDeadline(deadlineNanos) {
            succeeded.set(writeLineReturningSuccess(line))
            flushOverflowMarkerIfNeeded()
            latch.countDown()
        }
        if (!submitted) {
            droppedEventCount.incrementAndGet()
            return false
        }
        val completedInTime = try {
            latch.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return completedInTime && succeeded.get()
    }

    /**
     * Blocks the calling thread (bounded by [timeoutMillis]) until every write already submitted
     * to the background thread *before* this call has been attempted, **and** any pending
     * diagnostic-loss count has had a chance to be persisted as a [DIAGNOSTIC_OVERFLOW_EVENT_TYPE]
     * record — a synchronization barrier, not itself a new diagnostic event. Used by
     * `BackgroundLocationReceiver`/`BootCompletedReceiver` so that whatever calibration evidence
     * (including overflow evidence) is already enqueued has actually reached the file before the
     * receiver's lifetime ends. Retries submitting its own barrier task until accepted or
     * [timeoutMillis] elapses (see [submitWithRetryUntilDeadline]) — the moment right after a
     * burst of drops is exactly when the queue is still most likely to be full, so this is what
     * makes the class doc comment's "final rejected events -> awaitFlush() -> overflow marker
     * present" guarantee actually hold. Returns `true` immediately if disabled (nothing to flush),
     * `true` if the barrier ran in time, `false` on an overall timeout.
     */
    fun awaitFlush(timeoutMillis: Long = CRITICAL_RECORD_DEFAULT_TIMEOUT_MILLIS): Boolean {
        if (!isEnabled) return true
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val latch = CountDownLatch(1)
        val submitted = submitWithRetryUntilDeadline(deadlineNanos) {
            flushOverflowMarkerIfNeeded()
            latch.countDown()
        }
        if (!submitted) return false
        return try {
            latch.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Repeatedly attempts to submit [task] to [executor] until accepted or [deadlineNanos]
     * passes, backing off briefly (bounded, never more than the time remaining) between attempts
     * — shared by [awaitFlush] and [appendEventCritical] so a transient full-queue rejection
     * (most likely immediately after the very burst that filled the queue) doesn't fail either
     * operation on its very first attempt within its own already-budgeted timeout. This only
     * makes better use of that same budget; it never extends it. Returns `true` once accepted,
     * `false` if the deadline passed first. */
    private fun submitWithRetryUntilDeadline(deadlineNanos: Long, task: Runnable): Boolean {
        while (true) {
            val remaining = remainingMillis(deadlineNanos)
            if (remaining <= 0) return false
            try {
                executor.execute(task)
                return true
            } catch (t: Throwable) {
                try {
                    Thread.sleep(minOf(remaining, RETRY_BACKOFF_MILLIS))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0)

    /** Writes [line] (already fully built), returning whether the write itself succeeded — the
     * boolean [appendEventCritical] and tests rely on. [appendEvent]'s fire-and-forget path uses
     * this too, simply discarding the return value. */
    private fun writeLineReturningSuccess(line: String): Boolean {
        return try {
            rotateIfNeeded()
            val file = currentFile()
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
            true
        } catch (t: Throwable) {
            // Best-effort diagnostic only -- see class doc comment's "Failure safety".
            false
        }
    }

    /** Runs only on the background executor thread (called from inside every task submitted by
     * [appendEvent]/[appendEventCritical]/[awaitFlush]) — never re-submits to [executor], so an
     * overflow marker can never itself be rejected by the same bounded queue that produced it.
     * If the marker write itself fails, the count is restored (via [AtomicLong.addAndGet], not a
     * plain read-modify-write) rather than permanently lost — a concurrent new drop recorded by
     * another thread between the reset and the restore is never clobbered, since the restore adds
     * on top of whatever the counter currently holds instead of overwriting it. See the class doc
     * comment's "Overflow accounting" section. */
    private fun flushOverflowMarkerIfNeeded() {
        val dropped = droppedEventCount.getAndSet(0)
        if (dropped <= 0) return
        val overflowLine = buildCalibrationEventLine(
            sessionId,
            clock(),
            DIAGNOSTIC_OVERFLOW_EVENT_TYPE,
            mapOf("droppedEventCount" to dropped),
        )
        val wrote = writeLineReturningSuccess(overflowLine)
        if (!wrote) {
            droppedEventCount.addAndGet(dropped)
        }
    }

    /** Test-only: occupies the sole background thread until [releaseLatch] counts down to zero,
     * so a test can deterministically fill the bounded queue and force overflow before releasing
     * it. [startedLatch], if supplied, counts down the instant the blocking task actually begins
     * running (not merely once it has been submitted) — a test should await it before assuming
     * the queue is otherwise empty, since submission and execution are not synchronous. Never
     * called from production code. */
    internal fun blockWorkerForTest(releaseLatch: CountDownLatch, startedLatch: CountDownLatch? = null) {
        executor.execute {
            startedLatch?.countDown()
            releaseLatch.await()
        }
    }

    private fun currentFile() = File(outputDirectory, CALIBRATION_LOG_FILE_NAME)

    private fun rotateIfNeeded() {
        val file = currentFile()
        if (file.length() < CALIBRATION_LOG_MAX_FILE_SIZE_BYTES) return
        val backup = File(outputDirectory, CALIBRATION_LOG_BACKUP_FILE_NAME)
        backup.delete()
        val renamed = file.renameTo(backup)
        if (!renamed) {
            // Rename can fail for reasons unrelated to disk space (see the class doc comment's
            // "Rotation" section) -- falling through and continuing to append would let the file
            // grow unbounded. Truncating instead keeps the documented size bound; only this one
            // rollover's un-rotated content is lost, and only when rename itself already failed.
            try {
                FileOutputStream(file, false).close()
            } catch (t: Throwable) {
                // Even this fallback is best-effort -- see class doc comment's "Failure safety".
            }
        }
    }

    companion object {
        /**
         * Resolves the real Android app-specific external files directory — the one genuinely
         * Android-dependent step in this whole class; see the class doc comment for why the rest
         * of this writer takes a plain [File] instead. Returns `null` if external storage is
         * currently unavailable ([Context.getExternalFilesDir] itself can return `null` — e.g.
         * removable media unmounted); callers should fall back to the existing
         * [NoOpCalibrationDiagnosticSink]/`NoOpDiscoveryDiagnosticSink` in that case, exactly as
         * they already do for a release build. Only ever called when the caller has already
         * decided debug calibration logging should be active (see `AppContainer`) — this
         * constructs a real, enabled writer unconditionally; it is not itself a debug/release
         * gate.
         */
        fun forContext(context: Context): CalibrationDiagnosticFileWriter? {
            val baseDir = context.applicationContext.getExternalFilesDir(null) ?: return null
            return CalibrationDiagnosticFileWriter(
                outputDirectory = File(baseDir, CALIBRATION_LOG_SUBDIRECTORY),
                isEnabled = true,
                additionalSessionFields = mapOf(
                    "sdkInt" to Build.VERSION.SDK_INT,
                    "pid" to Process.myPid(),
                ),
            )
        }
    }
}

/**
 * Pure Kotlin, no Android dependency, no I/O — directly unit-testable. Builds one NDJSON object
 * line: [sessionId] and [eventType] always come first (so every line is self-describing even
 * without cross-referencing source), followed by [fields] in their given order. `null` values are
 * emitted as JSON `null` (never omitted) so a missing/unavailable field is still visible.
 */
internal fun buildCalibrationEventLine(
    sessionId: String,
    recordedAt: Instant,
    eventType: String,
    fields: Map<String, Any?>,
): String {
    val allFields = linkedMapOf<String, Any?>(
        "recordedAt" to recordedAt.toString(),
        "sessionId" to sessionId,
        "eventType" to eventType,
    )
    allFields.putAll(fields)
    return allFields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
        "\"${jsonEscape(key)}\":${jsonValue(value)}"
    }
}

private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Number -> value.toString()
    is Boolean -> value.toString()
    else -> "\"${jsonEscape(value.toString())}\""
}

private fun jsonEscape(raw: String): String = buildString {
    for (c in raw) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
    }
}
