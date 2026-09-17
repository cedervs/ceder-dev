package worlddiscovery.benchmark

import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

/**
 * Ingests a raw GPSLogger (mendhak, `gpslogger.app`) CSV export -- protocol §C.1bis (Phase 2B-1C
 * mini-validation) and Phase 2B-2 (first real capture). CSV is the canonical raw evidence format
 * for this benchmark (protocol correction 2B-1C §3) -- GPX is never parsed as benchmark evidence.
 *
 * Confirmed header (empirically, against the first real capture file, `20260907-160015 - Enrg 2.txt`
 * -- kept private, never committed, see `benchmark-private/phase2b/walk-rural-out-and-back-001/`):
 * `type,date time,latitude,longitude,accuracy(m),altitude(m),geoid_height(m),speed(m/s),
 * bearing(deg),sat_used,sat_inview,name,desc`. Column order is read from the header row, not
 * hardcoded by position, so a reordered or extended export still parses correctly as long as the
 * column names match.
 *
 * Known limitation: this parser splits each row on a bare `,` (no quoted-field support). Confirmed
 * safe for the real capture file inspected (no field contains an embedded comma), but a `name`/
 * `desc` value containing a comma would be misparsed -- documented here rather than silently
 * risked; not fixed because a full CSV/RFC-4180 parser was not needed for the file actually
 * encountered.
 *
 * This adapter performs NO repair of raw evidence (correction round 2B-2 §3: "Do not silently
 * repair raw evidence"). [IngestionReport] records every irregularity found; [parse] never drops,
 * reorders, or "fixes" a row -- a row that fails to parse becomes an [IngestionIssue], and is
 * simply excluded from the returned [ReferencePoint] list, never silently substituted.
 */
object GpsLoggerCsvAdapter {

    /** GPSLogger's CSV `date time` column is UTC (no offset suffix in the raw value) -- confirmed
     * against the real capture file by cross-checking its first row's timestamp against the
     * filename's own embedded local timestamp (`20260907-160015` = 16:00:15 local) against the
     * first data row (`14:00:15.000`): a 2-hour offset, consistent with CEST (UTC+2) in France in
     * September. Parsed as UTC accordingly, not as the JVM's local timezone. */
    private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    data class ParseResult(val points: List<ReferencePoint>, val report: IngestionReport)

    fun parse(file: File): ParseResult {
        val lines = file.readLines()
        require(lines.isNotEmpty()) { "empty file: ${file.path}" }
        val header = lines.first().split(",").map { it.trim() }
        val colIndex = header.withIndex().associate { (i, name) -> name to i }

        fun col(name: String): Int = colIndex[name] ?: error("missing required column '$name' in header: $header")

        val idxType = colIndex["type"]
        val idxTime = col("date time")
        val idxLat = col("latitude")
        val idxLon = col("longitude")
        val idxAcc = colIndex["accuracy(m)"]
        val idxAlt = colIndex["altitude(m)"]
        val idxSpeed = colIndex["speed(m/s)"]
        val idxBearing = colIndex["bearing(deg)"]
        val idxSatUsed = colIndex["sat_used"]
        val idxSatInview = colIndex["sat_inview"]

        val issues = mutableListOf<IngestionIssue>()
        val points = mutableListOf<ReferencePoint>()
        var missingLatLon = 0
        var missingAccuracy = 0
        var missingTimestamp = 0
        var invalidCoordinate = 0

        val dataLines = lines.drop(1)
        for ((rowIdx, line) in dataLines.withIndex()) {
            if (line.isBlank()) {
                issues.add(IngestionIssue(rowIdx, "BLANK_ROW", "row is blank, skipped"))
                continue
            }
            val cols = line.split(",")
            if (cols.size < header.size) {
                issues.add(IngestionIssue(rowIdx, "COLUMN_COUNT_MISMATCH", "expected ${header.size} columns, got ${cols.size}"))
                continue
            }
            if (idxType != null && cols[idxType].trim() != "T") {
                issues.add(IngestionIssue(rowIdx, "NON_TRACKPOINT_ROW", "type='${cols[idxType]}', not 'T' -- skipped"))
                continue
            }

            val timeStr = cols[idxTime].trim()
            val timestampEpochMs = try {
                java.time.LocalDateTime.parse(timeStr, TIMESTAMP_FORMAT).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            } catch (e: DateTimeParseException) {
                missingTimestamp++
                issues.add(IngestionIssue(rowIdx, "UNPARSEABLE_TIMESTAMP", "value='$timeStr'"))
                continue
            }

            val lat = cols[idxLat].trim().toDoubleOrNull()
            val lon = cols[idxLon].trim().toDoubleOrNull()
            if (lat == null || lon == null) {
                missingLatLon++
                issues.add(IngestionIssue(rowIdx, "MISSING_LAT_LON", "lat='${cols[idxLat]}' lon='${cols[idxLon]}'"))
                continue
            }
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
                invalidCoordinate++
                issues.add(IngestionIssue(rowIdx, "INVALID_COORDINATE", "lat=$lat lon=$lon out of range"))
                continue
            }

            val accuracy = idxAcc?.let { cols[it].trim().toFloatOrNull() }
            if (accuracy == null) {
                missingAccuracy++
                issues.add(IngestionIssue(rowIdx, "MISSING_ACCURACY", "accuracy field absent or unparseable"))
            }
            val speed = idxSpeed?.let { cols[it].trim().toFloatOrNull() }
            val bearing = idxBearing?.let { cols[it].trim().toFloatOrNull() }
            val satUsed = idxSatUsed?.let { cols[it].trim().toIntOrNull() }
            val satInview = idxSatInview?.let { cols[it].trim().toIntOrNull() }
            val altitude = idxAlt?.let { cols[it].trim().toDoubleOrNull() }

            if (bearing == null && idxBearing != null && cols[idxBearing].isNotBlank()) {
                issues.add(IngestionIssue(rowIdx, "UNPARSEABLE_BEARING", "value='${cols[idxBearing]}'"))
            }

            points.add(
                ReferencePoint(
                    lat = lat, lon = lon, timestampEpochMs = timestampEpochMs,
                    accuracyMeters = accuracy, bearingDegrees = bearing, speedMps = speed,
                    providerOrSource = "gpslogger-csv" + (satUsed?.let { "/sat_used=$it" } ?: "") + (satInview?.let { "/sat_inview=$it" } ?: "") + (altitude?.let { "/alt=$it" } ?: ""),
                ),
            )
        }

        // Duplicate / non-monotonic timestamp detection (over the successfully parsed points only).
        var duplicateTimestamps = 0
        var nonMonotonic = 0
        val intervalHistogramSeconds = sortedMapOf<Long, Int>()
        var maxGapSeconds = 0L
        for (i in 1 until points.size) {
            val dtMs = points[i].timestampEpochMs - points[i - 1].timestampEpochMs
            if (dtMs < 0) {
                nonMonotonic++
                issues.add(IngestionIssue(i, "NON_MONOTONIC_TIMESTAMP", "delta=${dtMs}ms vs previous point"))
                continue
            }
            if (dtMs == 0L) {
                duplicateTimestamps++
                issues.add(IngestionIssue(i, "DUPLICATE_TIMESTAMP", "identical timestamp to previous point"))
            }
            val dtSec = dtMs / 1000
            intervalHistogramSeconds[dtSec] = (intervalHistogramSeconds[dtSec] ?: 0) + 1
            if (dtSec > maxGapSeconds) maxGapSeconds = dtSec
        }

        val accuracies = points.mapNotNull { it.accuracyMeters?.toDouble() }.sorted()
        val speeds = points.mapNotNull { it.speedMps?.toDouble() }.sorted()

        var cumulativeDistanceMeters = 0.0
        for (i in 1 until points.size) {
            cumulativeDistanceMeters += GeometryComparison.haversineMeters(
                LatLon(points[i - 1].lat, points[i - 1].lon),
                LatLon(points[i].lat, points[i].lon),
            )
        }

        val report = IngestionReport(
            sourceFileName = file.name,
            totalDataRows = dataLines.size,
            parsedObservations = points.size,
            issueCount = issues.size,
            issues = issues,
            firstTimestampEpochMs = points.firstOrNull()?.timestampEpochMs,
            lastTimestampEpochMs = points.lastOrNull()?.timestampEpochMs,
            durationSeconds = if (points.size >= 2) (points.last().timestampEpochMs - points.first().timestampEpochMs) / 1000.0 else 0.0,
            cumulativeRawDistanceMeters = cumulativeDistanceMeters,
            minAccuracyMeters = accuracies.firstOrNull(),
            maxAccuracyMeters = accuracies.lastOrNull(),
            medianAccuracyMeters = median(accuracies),
            medianSpeedMps = median(speeds),
            intervalHistogramSeconds = intervalHistogramSeconds,
            maxGapSeconds = maxGapSeconds,
            duplicateTimestampCount = duplicateTimestamps,
            nonMonotonicTimestampCount = nonMonotonic,
            missingLatLonCount = missingLatLon,
            missingAccuracyCount = missingAccuracy,
            missingTimestampCount = missingTimestamp,
            invalidCoordinateCount = invalidCoordinate,
        )

        return ParseResult(points, report)
    }

    private fun median(sorted: List<Double>): Double? {
        if (sorted.isEmpty()) return null
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}

@Serializable
data class IngestionIssue(val rowIndex: Int, val kind: String, val detail: String)

/** Machine-readable ingestion report -- protocol §3 of the 2B-2 correction ("Produce a PRIVATE
 * machine-readable ingestion report"). Contains no coordinates itself (only aggregate statistics
 * and row-index-tagged issue descriptions), but is still written under `benchmark-private/` by
 * convention since it is derived from a private capture and its `issues` details could, in
 * principle, echo a raw field value (e.g. an unparseable coordinate string). */
@Serializable
data class IngestionReport(
    val sourceFileName: String,
    val totalDataRows: Int,
    val parsedObservations: Int,
    val issueCount: Int,
    val issues: List<IngestionIssue>,
    val firstTimestampEpochMs: Long?,
    val lastTimestampEpochMs: Long?,
    val durationSeconds: Double,
    val cumulativeRawDistanceMeters: Double,
    val minAccuracyMeters: Double?,
    val maxAccuracyMeters: Double?,
    val medianAccuracyMeters: Double?,
    val medianSpeedMps: Double?,
    val intervalHistogramSeconds: Map<Long, Int>,
    val maxGapSeconds: Long,
    val duplicateTimestampCount: Int,
    val nonMonotonicTimestampCount: Int,
    val missingLatLonCount: Int,
    val missingAccuracyCount: Int,
    val missingTimestampCount: Int,
    val invalidCoordinateCount: Int,
)
