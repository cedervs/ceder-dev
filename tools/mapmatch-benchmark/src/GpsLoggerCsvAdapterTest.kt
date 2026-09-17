package worlddiscovery.benchmark

import java.io.File

/**
 * Focused tests for `GpsLoggerCsvAdapter.kt` -- protocol correction round 2B-2 §14 ("Add focused
 * tests for any new parser/normalization logic"). Uses a small FABRICATED synthetic CSV (fake
 * coordinates in the ocean, not the real private capture) written to a temp file -- never reads
 * `benchmark-private/`, so this test is safe to keep as tracked, non-sensitive code. Same
 * plain-`main()`/`check()` style as the other test files in this tool.
 */
fun main() {
    testWellFormedRowsParseCleanly()
    testMissingAccuracyIsFlaggedNotSilentlyAccepted()
    testMissingLatLonIsFlaggedAndRowDropped()
    testInvalidCoordinateIsFlagged()
    testDuplicateTimestampIsFlagged()
    testNonMonotonicTimestampIsFlagged()
    testGapIsRecordedNotFilled()
    testNonTrackpointRowIsSkipped()
    testColumnCountMismatchIsFlagged()
    testReorderedHeaderStillParsesByName()
    println("GpsLoggerCsvAdapterTest: ALL PASS")
}

private val HEADER = "type,date time,latitude,longitude,accuracy(m),altitude(m),geoid_height(m),speed(m/s),bearing(deg),sat_used,sat_inview,name,desc"

private fun tempCsv(lines: List<String>): File {
    val f = File.createTempFile("gpslogger-test", ".csv")
    f.deleteOnExit()
    f.writeText((listOf(HEADER) + lines).joinToString("\n"))
    return f
}

private fun row(t: String, lat: String, lon: String, acc: String = "5.0", speed: String = "0.0", bearing: String = "", sat: String = "10", satIn: String = "12") =
    "T,$t,$lat,$lon,$acc,0.0,,$speed,$bearing,$sat,$satIn,,"

private fun testWellFormedRowsParseCleanly() {
    val f = tempCsv(
        listOf(
            row("2026-01-01 10:00:00.000", "0.00000", "0.00000"),
            row("2026-01-01 10:00:01.000", "0.00001", "0.00001"),
            row("2026-01-01 10:00:02.000", "0.00002", "0.00002"),
        ),
    )
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.points.size == 3) { "expected 3 parsed points, got ${result.points.size}" }
    check(result.report.issueCount == 0) { "well-formed rows should produce 0 issues, got ${result.report.issueCount}: ${result.report.issues}" }
    check(result.points.all { it.accuracyMeters != null }) { "all rows had accuracy set, must not be null" }
}

private fun testMissingAccuracyIsFlaggedNotSilentlyAccepted() {
    val f = tempCsv(
        listOf(
            row("2026-01-01 10:00:00.000", "0.0", "0.0", acc = ""),
        ),
    )
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.report.missingAccuracyCount == 1) { "expected missingAccuracyCount=1, got ${result.report.missingAccuracyCount}" }
    check(result.report.issues.any { it.kind == "MISSING_ACCURACY" }) { "expected a MISSING_ACCURACY issue" }
    // The row is still parsed (not silently dropped) -- but with a null accuracy, which downstream
    // code must treat as unusable for benchmark purposes (protocol correction 2B-1C §4/§8).
    check(result.points.size == 1 && result.points[0].accuracyMeters == null) { "row should parse with null accuracy, not be dropped or fabricated" }
}

private fun testMissingLatLonIsFlaggedAndRowDropped() {
    val f = tempCsv(
        listOf(
            row("2026-01-01 10:00:00.000", "0.0", "0.0"),
            row("2026-01-01 10:00:01.000", "", "0.0"),
            row("2026-01-01 10:00:02.000", "0.0", "0.0"),
        ),
    )
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.points.size == 2) { "row with missing lat should be dropped, expected 2 points, got ${result.points.size}" }
    check(result.report.missingLatLonCount == 1) { "expected missingLatLonCount=1, got ${result.report.missingLatLonCount}" }
}

private fun testInvalidCoordinateIsFlagged() {
    val f = tempCsv(
        listOf(
            row("2026-01-01 10:00:00.000", "999.0", "0.0"),
        ),
    )
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.report.invalidCoordinateCount == 1) { "expected invalidCoordinateCount=1, got ${result.report.invalidCoordinateCount}" }
    check(result.points.isEmpty()) { "row with out-of-range latitude must not be included in parsed points" }
}

private fun testDuplicateTimestampIsFlagged() {
    val f = tempCsv(
        listOf(
            row("2026-01-01 10:00:00.000", "0.0", "0.0"),
            row("2026-01-01 10:00:00.000", "0.00001", "0.00001"),
        ),
    )
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.report.duplicateTimestampCount == 1) { "expected duplicateTimestampCount=1, got ${result.report.duplicateTimestampCount}" }
    check(result.points.size == 2) { "duplicate-timestamp rows are flagged, not dropped -- expected 2 points" }
}

private fun testNonMonotonicTimestampIsFlagged() {
    val f = tempCsv(
        listOf(
            row("2026-01-01 10:00:05.000", "0.0", "0.0"),
            row("2026-01-01 10:00:00.000", "0.00001", "0.00001"),
        ),
    )
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.report.nonMonotonicTimestampCount == 1) { "expected nonMonotonicTimestampCount=1, got ${result.report.nonMonotonicTimestampCount}" }
}

private fun testGapIsRecordedNotFilled() {
    val f = tempCsv(
        listOf(
            row("2026-01-01 10:00:00.000", "0.0", "0.0"),
            row("2026-01-01 10:05:00.000", "0.001", "0.001"), // 300s gap
        ),
    )
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.report.maxGapSeconds == 300L) { "expected maxGapSeconds=300, got ${result.report.maxGapSeconds}" }
    check(result.points.size == 2) { "a gap must not cause a synthesized intermediate point -- expected exactly 2 points" }
}

private fun testNonTrackpointRowIsSkipped() {
    val lines = listOf(
        row("2026-01-01 10:00:00.000", "0.0", "0.0"),
        "S,2026-01-01 10:00:01.000,,,,,,,,,,,GPS Logger: started",
    )
    val f = tempCsv(lines)
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.points.size == 1) { "non-'T' row (e.g. a status row) must be skipped, expected 1 point, got ${result.points.size}" }
    check(result.report.issues.any { it.kind == "NON_TRACKPOINT_ROW" }) { "expected a NON_TRACKPOINT_ROW issue" }
}

private fun testColumnCountMismatchIsFlagged() {
    val f = tempCsv(listOf("T,2026-01-01 10:00:00.000,0.0,0.0"))
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.report.issues.any { it.kind == "COLUMN_COUNT_MISMATCH" }) { "expected a COLUMN_COUNT_MISMATCH issue for a truncated row" }
    check(result.points.isEmpty()) { "a truncated row must not be parsed into a point" }
}

private fun testReorderedHeaderStillParsesByName() {
    val reorderedHeader = "date time,type,longitude,latitude,accuracy(m)"
    val f = File.createTempFile("gpslogger-test-reordered", ".csv")
    f.deleteOnExit()
    f.writeText(
        listOf(
            reorderedHeader,
            "2026-01-01 10:00:00.000,T,0.5,0.25,5.0",
        ).joinToString("\n"),
    )
    val result = GpsLoggerCsvAdapter.parse(f)
    check(result.points.size == 1) { "expected 1 point from a reordered header, got ${result.points.size}" }
    check(result.points[0].lat == 0.25 && result.points[0].lon == 0.5) { "columns must be read by header name, not fixed position -- got lat=${result.points[0].lat} lon=${result.points[0].lon}" }
}
