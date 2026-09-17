package com.cedervs.worlddiscovery.core.location

import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CalibrationDiagnosticFileWriter]'s actual write/rotate/escape logic directly against a
 * plain [java.io.File] temp directory — no Android dependency, no Robolectric, matching this
 * module's existing "extract the Context-free core, test that directly" pattern (see
 * [FusedBackgroundLocationRegistrarRegistrationTest]/`performBackgroundLocationRegistration`).
 * [CalibrationDiagnosticFileWriter.forContext] (the one genuinely Android-dependent line) is
 * deliberately not covered here — resolving a real `Context.getExternalFilesDir` is not
 * meaningfully unit-testable without a real device/emulator.
 */
class CalibrationDiagnosticFileWriterTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("calibration-diagnostic-test").toFile()

    @Test
    fun `buildCalibrationEventLine always includes recordedAt, sessionId, and eventType first`() {
        val line = buildCalibrationEventLine(
            sessionId = "session-1",
            recordedAt = Instant.parse("2026-01-01T10:00:00Z"),
            eventType = "LIFECYCLE",
            fields = mapOf("kind" to "APP_FOREGROUND_START"),
        )

        assertEquals(
            "{\"recordedAt\":\"2026-01-01T10:00:00Z\",\"sessionId\":\"session-1\",\"eventType\":\"LIFECYCLE\",\"kind\":\"APP_FOREGROUND_START\"}",
            line,
        )
    }

    @Test
    fun `buildCalibrationEventLine emits null fields as JSON null, never omitting them`() {
        val line = buildCalibrationEventLine(
            sessionId = "s",
            recordedAt = Instant.parse("2026-01-01T10:00:00Z"),
            eventType = "LOCATION_DELIVERED",
            fields = mapOf("accuracyMeters" to null, "provider" to "gps"),
        )

        assertTrue("a null field must still be present as JSON null", line.contains("\"accuracyMeters\":null"))
        assertTrue(line.contains("\"provider\":\"gps\""))
    }

    @Test
    fun `buildCalibrationEventLine escapes quotes and backslashes in string values`() {
        val line = buildCalibrationEventLine(
            sessionId = "s",
            recordedAt = Instant.parse("2026-01-01T10:00:00Z"),
            eventType = "TEST",
            fields = mapOf("value" to "has \"quotes\" and \\backslash\\"),
        )

        assertTrue(line.contains("\"value\":\"has \\\"quotes\\\" and \\\\backslash\\\\\""))
    }

    @Test
    fun `buildCalibrationEventLine renders numbers and booleans unquoted`() {
        val line = buildCalibrationEventLine(
            sessionId = "s",
            recordedAt = Instant.parse("2026-01-01T10:00:00Z"),
            eventType = "TEST",
            fields = mapOf("batchSize" to 3, "isEnabled" to true),
        )

        assertTrue(line.contains("\"batchSize\":3"))
        assertTrue(line.contains("\"isEnabled\":true"))
    }

    @Test
    fun `appendEvent writes one NDJSON line per call, in order, to the deterministic file name`() {
        val dir = tempDir()
        val clock = FakeClock(Instant.parse("2026-01-01T10:00:00Z"))
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true, clock = clock)

        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START"))
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_STOP"))
        writer.awaitFlush()

        val file = File(dir, "tracking-calibration.ndjson")
        assertTrue("expected the deterministic file name to exist", file.exists())
        val lines = file.readLines()
        // First line is always the writer's own SESSION_START, logged from init.
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"eventType\":\"SESSION_START\""))
        assertTrue(lines[1].contains("APP_FOREGROUND_START"))
        assertTrue(lines[2].contains("APP_FOREGROUND_STOP"))
    }

    @Test
    fun `a disabled writer never creates any file`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = false)

        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START"))
        writer.awaitFlush()

        assertFalse(File(dir, "tracking-calibration.ndjson").exists())
    }

    @Test
    fun `rotation renames the current file to a backup once it reaches the size bound`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)
        val file = File(dir, "tracking-calibration.ndjson")
        val backup = File(dir, "tracking-calibration.ndjson.1")
        writer.awaitFlush()

        // Pad the file past the rotation bound directly (isolating rotation logic from needing
        // thousands of real appendEvent calls).
        file.writeBytes(ByteArray(CALIBRATION_LOG_MAX_FILE_SIZE_BYTES.toInt()) { 'x'.code.toByte() })

        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START"))
        writer.awaitFlush()

        assertTrue("the oversized file must have been rotated to the backup name", backup.exists())
        assertTrue("a fresh current file must exist after rotation", file.exists())
        // The fresh file must contain only the new event, not the padding that was rotated away.
        assertTrue(file.readText().contains("APP_FOREGROUND_START"))
        assertFalse(file.readText().contains("xxxx"))
    }

    @Test
    fun `writing to an output directory that cannot be created never throws`() {
        // A regular file where a directory is expected -- mkdirs() will fail, but appendEvent
        // itself must never propagate that failure. See the class doc comment's "Failure safety".
        val parent = tempDir()
        val blockingFile = File(parent, "not-a-directory")
        blockingFile.writeText("blocking")
        val unusableDir = File(blockingFile, "calibration-diagnostics")

        val writer = CalibrationDiagnosticFileWriter(outputDirectory = unusableDir, isEnabled = true)
        // Must not throw.
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START"))
        writer.awaitFlush()
    }

    @Test
    fun `many rapid appendEvent calls never throw, even far beyond the internal queue capacity`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)

        // Must not throw -- excess writes beyond the bounded queue are silently dropped, never
        // blocked and never propagated as a rejection. See the class doc comment.
        repeat(2000) { i -> writer.appendEvent("LIFECYCLE", mapOf("i" to i)) }
        writer.awaitFlush()

        assertTrue(File(dir, "tracking-calibration.ndjson").exists())
    }

    @Test
    fun `each writer instance gets its own random sessionId by default, and every line carries it`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)

        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START"))
        writer.awaitFlush()

        val lines = File(dir, "tracking-calibration.ndjson").readLines()
        assertTrue(lines.all { it.contains("\"sessionId\":\"${writer.sessionId}\"") })
    }

    // ==============================================================================================
    // TEMPORARY DEBUG CALIBRATION INFRASTRUCTURE -- Codex review round: critical, acknowledged
    // writes (appendEventCritical/awaitFlush) and overflow-loss accounting (droppedEventCount /
    // DIAGNOSTIC_OVERFLOW). See the class doc comment's "Overflow accounting" and "Critical,
    // acknowledged writes" sections.
    // ==============================================================================================

    @Test
    fun `appendEventCritical returns true once the write has actually reached the file`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)

        val acknowledged = writer.appendEventCritical("LIFECYCLE", mapOf("kind" to "BACKGROUND_LOCATION_RECEIVER_INVOKED"))

        assertTrue("a real write with no contention must succeed well within the default timeout", acknowledged)
        assertTrue(File(dir, "tracking-calibration.ndjson").readText().contains("BACKGROUND_LOCATION_RECEIVER_INVOKED"))
    }

    @Test
    fun `appendEventCritical returns false on a timeout, without blocking past the bound`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)
        writer.awaitFlush() // drain SESSION_START first so the worker is idle before we occupy it
        val releaseLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        writer.blockWorkerForTest(releaseLatch, startedLatch)
        startedLatch.await(5, TimeUnit.SECONDS)

        val start = System.nanoTime()
        val acknowledged = writer.appendEventCritical(
            "LIFECYCLE",
            mapOf("kind" to "BACKGROUND_LOCATION_RECEIVER_INVOKED"),
            timeoutMillis = 100,
        )
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertFalse("the worker is still blocked, so this must time out rather than succeed", acknowledged)
        assertTrue(
            "must be bounded by the timeout, not hang indefinitely (elapsed=${elapsedMillis}ms)",
            elapsedMillis < 2000,
        )

        releaseLatch.countDown() // cleanup: let the worker thread proceed so the test JVM can exit cleanly
    }

    @Test
    fun `appendEventCritical returns false when the write itself fails, not just on timeout`() {
        val parent = tempDir()
        val blockingFile = File(parent, "not-a-directory")
        blockingFile.writeText("blocking")
        val unusableDir = File(blockingFile, "calibration-diagnostics")
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = unusableDir, isEnabled = true)

        val acknowledged = writer.appendEventCritical("LIFECYCLE", mapOf("kind" to "BACKGROUND_LOCATION_RECEIVER_INVOKED"))

        assertFalse("a genuine write failure must be distinguishable from success, not swallowed into true", acknowledged)
    }

    @Test
    fun `a disabled writer's appendEventCritical returns true immediately -- nothing to acknowledge`() {
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = tempDir(), isEnabled = false)

        assertTrue(writer.appendEventCritical("LIFECYCLE", mapOf("kind" to "BACKGROUND_LOCATION_RECEIVER_INVOKED")))
    }

    @Test
    fun `awaitFlush returns false on a timeout while the worker is blocked, and true once released`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)
        writer.awaitFlush()
        val releaseLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        writer.blockWorkerForTest(releaseLatch, startedLatch)
        startedLatch.await(5, TimeUnit.SECONDS)

        assertFalse(writer.awaitFlush(timeoutMillis = 100))

        releaseLatch.countDown()
        assertTrue(writer.awaitFlush())
    }

    @Test
    fun `overflow -- events rejected while the worker is blocked are counted and never block the caller`() {
        val dir = tempDir()
        // A small queue capacity keeps this test fast and deterministic -- 500 real appendEvent
        // calls would work identically but far more slowly.
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true, queueCapacity = 3)
        // Drain the writer's own SESSION_START write first, so the queue is provably empty before
        // blocking the worker -- otherwise a slow/loaded machine could still have that write
        // queued when blockWorkerForTest runs, making the exact dropped count below non-deterministic.
        writer.awaitFlush()
        val releaseLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        writer.blockWorkerForTest(releaseLatch, startedLatch) // occupies the sole worker thread
        startedLatch.await(5, TimeUnit.SECONDS)

        // Fill the bounded queue (capacity 3), then force well past it -- every one of these calls
        // must return immediately (this whole block runs on the test's own thread, single-threaded,
        // so if any call blocked, the test itself would hang here).
        val callSite = Executors.newSingleThreadExecutor()
        val allCallsReturned = callSite.submit<Boolean> {
            repeat(20) { i -> writer.appendEvent("LIFECYCLE", mapOf("i" to i)) }
            true
        }
        val returnedInTime = try {
            allCallsReturned.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
        assertTrue("appendEvent must never block the caller, even with the worker occupied and the queue full", returnedInTime)
        callSite.shutdown()

        releaseLatch.countDown() // let the worker drain: the blocking task, then the 3 queued writes

        // awaitFlush's own barrier can itself be rejected while the 3 queued writes still occupy
        // every queue slot (capacity 3) -- it now retries internally (bounded by this timeout),
        // so a single generous call is enough; see submitWithRetryUntilDeadline.
        assertTrue("awaitFlush must eventually succeed once the worker drains", writer.awaitFlush(timeoutMillis = 5000))

        val lines = File(dir, "tracking-calibration.ndjson").readLines()
        val overflowLine = lines.singleOrNull { it.contains("\"eventType\":\"DIAGNOSTIC_OVERFLOW\"") }
        assertTrue("an overflow marker must eventually be persisted once the worker recovers", overflowLine != null)
        // 20 attempts, capacity 3 -- 17 must have been rejected and counted.
        assertTrue(
            "the overflow marker must carry a nonzero droppedEventCount (got: $overflowLine)",
            overflowLine!!.contains("\"droppedEventCount\":17"),
        )
    }

    @Test
    fun `awaitFlush -- retries submitting its own barrier until the queue has room, within its own timeout budget`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true, queueCapacity = 1)
        writer.awaitFlush()
        val releaseLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        writer.blockWorkerForTest(releaseLatch, startedLatch)
        startedLatch.await(5, TimeUnit.SECONDS)
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START")) // fills the capacity-1 queue

        // Release the worker on a delay from a second thread, so awaitFlush's own first submission
        // attempt is guaranteed to be rejected (queue still full) and must retry to eventually
        // succeed, all within one call.
        Executors.newSingleThreadExecutor().apply {
            execute {
                Thread.sleep(150)
                releaseLatch.countDown()
            }
            shutdown()
        }

        assertTrue(
            "must retry past the initial full-queue rejection and still succeed within its own budget",
            writer.awaitFlush(timeoutMillis = 3000),
        )
    }

    @Test
    fun `overflow -- a critical write rejected because the queue is full is itself counted as loss`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true, queueCapacity = 1)
        writer.awaitFlush()
        val releaseLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        writer.blockWorkerForTest(releaseLatch, startedLatch)
        startedLatch.await(5, TimeUnit.SECONDS)
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START")) // fills the capacity-1 queue

        // The queue has no room left, and stays full for the whole timeout window below -- this
        // critical call must give up and report failure, but must not let that loss go unaccounted.
        val acknowledged = writer.appendEventCritical(
            "LIFECYCLE",
            mapOf("kind" to "BACKGROUND_LOCATION_RECEIVER_INVOKED"),
            timeoutMillis = 200,
        )
        assertFalse("the queue never had room, so this must fail, not silently succeed", acknowledged)

        releaseLatch.countDown()
        assertTrue(writer.awaitFlush(timeoutMillis = 5000))

        val overflowLine = File(dir, "tracking-calibration.ndjson").readLines()
            .singleOrNull { it.contains("\"eventType\":\"DIAGNOSTIC_OVERFLOW\"") }
        assertTrue("the rejected critical write must show up as counted loss", overflowLine != null)
        assertTrue(
            "expected exactly one lost event (the rejected critical write) -- got: $overflowLine",
            overflowLine!!.contains("\"droppedEventCount\":1"),
        )
    }

    @Test
    fun `overflow -- a failed marker write retains the loss count instead of losing it, across repeated failures`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true, queueCapacity = 2)
        writer.awaitFlush() // ensures tracking-calibration.ndjson already exists (SESSION_START written)
        val file = File(dir, "tracking-calibration.ndjson")

        // Make the file read-only *before* forcing overflow -- every write attempt while released
        // below (both the queued ordinary writes and every flushOverflowMarkerIfNeeded call they
        // each trigger) will fail, deliberately, so the marker write itself fails repeatedly.
        assertTrue("test setup: must be able to mark the file read-only on this platform", file.setWritable(false))

        val releaseLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        writer.blockWorkerForTest(releaseLatch, startedLatch)
        startedLatch.await(5, TimeUnit.SECONDS)

        // Force exactly 2 lost events (queue capacity 2, already full, plus these 2 more rejected).
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "A"))
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "B"))
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "C")) // rejected -- dropped=1
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "D")) // rejected -- dropped=2

        releaseLatch.countDown()
        // Drains the 2 queued writes -- both fail (file read-only), and each of their own
        // flushOverflowMarkerIfNeeded calls also fails to write the marker (same reason) and must
        // restore the count rather than lose it. awaitFlush itself will also fail here (still
        // read-only), which is expected and fine -- this call is just to let the worker settle.
        writer.awaitFlush(timeoutMillis = 2000)

        // Restore writability, then flush again -- this time the marker write must succeed and
        // report the full, un-lost count from before.
        assertTrue(file.setWritable(true))
        assertTrue("awaitFlush must succeed once the file is writable again", writer.awaitFlush(timeoutMillis = 5000))

        val overflowLines = file.readLines().filter { it.contains("\"eventType\":\"DIAGNOSTIC_OVERFLOW\"") }
        assertEquals(
            "exactly one overflow marker must have actually been persisted (all earlier attempts failed silently)",
            1,
            overflowLines.size,
        )
        assertTrue(
            "the eventually-persisted marker must carry the full, un-lost count -- got: $overflowLines",
            overflowLines.single().contains("\"droppedEventCount\":2"),
        )
    }

    @Test
    fun `overflow -- a fresh writer with no contention never emits a DIAGNOSTIC_OVERFLOW marker`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)

        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START"))
        writer.awaitFlush()

        assertFalse(File(dir, "tracking-calibration.ndjson").readText().contains("DIAGNOSTIC_OVERFLOW"))
    }

    @Test
    fun `rotation falls back to truncating the current file if renameTo itself fails`() {
        val dir = tempDir()
        val writer = CalibrationDiagnosticFileWriter(outputDirectory = dir, isEnabled = true)
        val file = File(dir, "tracking-calibration.ndjson")
        val backupPath = File(dir, "tracking-calibration.ndjson.1")
        writer.awaitFlush()

        // Force renameTo to fail: make the backup destination an existing, non-empty directory --
        // a file can never be renamed onto that, on any platform this runs on.
        backupPath.mkdirs()
        File(backupPath, "placeholder").writeText("occupied")

        file.writeBytes(ByteArray(CALIBRATION_LOG_MAX_FILE_SIZE_BYTES.toInt()) { 'x'.code.toByte() })

        // Must not throw or crash, and must not grow the file unbounded.
        writer.appendEvent("LIFECYCLE", mapOf("kind" to "APP_FOREGROUND_START"))
        writer.awaitFlush()

        assertTrue("the file must still exist after a failed rotation attempt", file.exists())
        assertTrue(
            "truncation fallback must keep the file bounded, not let it keep growing past the cap",
            file.length() < CALIBRATION_LOG_MAX_FILE_SIZE_BYTES,
        )
        assertTrue(file.readText().contains("APP_FOREGROUND_START"))
    }
}

private class FakeClock(private var current: Instant) : () -> Instant {
    override fun invoke(): Instant = current
}
