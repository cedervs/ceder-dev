package worlddiscovery.benchmark

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * CLI runner for Phase 2B-3 (`walk-urban-parallel-corridors-002` and any future one-way/turning
 * urban scenario): ingests a raw GPSLogger CSV, builds the independent truth model using
 * algorithmic [TurnSegmentation] (NOT the out-and-back-assuming turnaround logic in
 * `IngestAndBuildScenario.kt`, which stays untouched -- Scenario 1's accepted evidence is not
 * touched by this file), generates standard degraded input sets, and reports provisional H3
 * coverage -- all purely locally, no network calls, no candidate matcher involved in truth
 * construction.
 *
 * Usage: `IngestAndBuildUrbanScenarioKt <rawCsvPath> <scenarioRootDir> <scenarioId>`
 */
private val json = Json { prettyPrint = true; encodeDefaults = true }

fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: IngestAndBuildUrbanScenarioKt <rawCsvPath> <scenarioRootDir> <scenarioId> [transportMode=WALK|CAR|BIKE] [corpusCategory=WALK|ROAD|BIKE]" }
    val rawCsvPath = File(args[0])
    val scenarioRootDir = File(args[1])
    val scenarioId = args[2]
    // 2B-4: mode/category are now explicit arguments (default WALK, preserving Scenarios 1-2's
    // behavior unchanged) instead of hardcoded TransportMode.WALK/CorpusCategory.WALK baked into
    // this file -- generic across scenarios (this file already wasn't out-and-back-specific; it
    // was still WALK-specific until now).
    val transportMode = if (args.size >= 4) TransportMode.valueOf(args[3]) else TransportMode.WALK
    val corpusCategory = if (args.size >= 5) CorpusCategory.valueOf(args[4]) else CorpusCategory.WALK
    val outputDir = File(scenarioRootDir, "truth")
    outputDir.mkdirs()

    println("=== Phase 2B-3 urban-scenario ingestion + truth-model build (all local, no network) ===")
    println("scenarioId: $scenarioId")

    // --- 1. Ingest -------------------------------------------------------------------------
    val parseResult = GpsLoggerCsvAdapter.parse(rawCsvPath)
    val report = parseResult.report
    File(outputDir, "ingestion-report.json").writeText(json.encodeToString(report))
    println()
    println("--- Ingestion report (aggregate only -- see ingestion-report.json for full detail) ---")
    println("sourceFileName: ${report.sourceFileName}")
    println("totalDataRows: ${report.totalDataRows}, parsedObservations: ${report.parsedObservations}, issueCount: ${report.issueCount}")
    println("durationSeconds: ${report.durationSeconds}")
    println("cumulativeRawDistanceMeters: ${"%.2f".format(java.util.Locale.ROOT, report.cumulativeRawDistanceMeters)}")
    println("accuracy min/median/max (m): ${report.minAccuracyMeters}/${report.medianAccuracyMeters}/${report.maxAccuracyMeters}")
    println("medianSpeedMps (raw device field): ${report.medianSpeedMps}")
    println("maxGapSeconds: ${report.maxGapSeconds}, duplicateTimestampCount: ${report.duplicateTimestampCount}, nonMonotonicTimestampCount: ${report.nonMonotonicTimestampCount}")
    println("missingLatLonCount: ${report.missingLatLonCount}, missingAccuracyCount: ${report.missingAccuracyCount}, missingTimestampCount: ${report.missingTimestampCount}, invalidCoordinateCount: ${report.invalidCoordinateCount}")
    println("intervalHistogramSeconds: ${report.intervalHistogramSeconds}")
    if (report.issues.isNotEmpty()) {
        println("first few issues: ${report.issues.take(5)}")
    }

    val points = parseResult.points
    if (points.size < 3) {
        println("STOP: fewer than 3 usable points parsed -- cannot build a turn-segmented truth model.")
        return
    }

    // --- 2. Independently computed inter-point walking speed cross-check -------------------
    val interPointSpeeds = mutableListOf<Double>()
    for (i in 1 until points.size) {
        val d = GeometryComparison.haversineMeters(LatLon(points[i - 1].lat, points[i - 1].lon), LatLon(points[i].lat, points[i].lon))
        val dtSec = (points[i].timestampEpochMs - points[i - 1].timestampEpochMs) / 1000.0
        if (dtSec > 0) interPointSpeeds.add(d / dtSec)
    }
    val sortedSpeeds = interPointSpeeds.sorted()
    val medianInterPointSpeed = if (sortedSpeeds.isEmpty()) null else {
        val mid = sortedSpeeds.size / 2
        if (sortedSpeeds.size % 2 == 0) (sortedSpeeds[mid - 1] + sortedSpeeds[mid]) / 2.0 else sortedSpeeds[mid]
    }
    println("medianSpeedMps (independently computed from consecutive-point distance/time): $medianInterPointSpeed")

    // --- 3. Algorithmic turn detection (protocol round 2B-3: NOT out-and-back) -------------
    val turnIndices = TurnSegmentation.detectTurnIndices(points)
    println()
    println("--- Turn detection (algorithmic, from GPS bearing evidence only -- screenshot NOT used as a source of indices) ---")
    println("detected turn count: ${turnIndices.size}")
    println("detected turn indices (into rawObservations): $turnIndices")

    // 2B-4 §5/§6: a large real observation-timestamp gap (e.g. a logger/app pause) must ALWAYS
    // produce its own segment boundary -- turn detection alone (spatial bearing only) would never
    // catch a near-stationary gap. Merged with the turn indices before building segments so no
    // segment silently spans across a real gap.
    val largeGapIndices = TurnSegmentation.detectLargeTimeGapIndices(points, thresholdSeconds = 60.0)
    if (largeGapIndices.isNotEmpty()) {
        println("--- Large observation-timestamp gap(s) detected (>60s) -- forced as segment boundaries ---")
        for (gi in largeGapIndices) {
            val gapSeconds = (points[gi].timestampEpochMs - points[gi - 1].timestampEpochMs) / 1000.0
            println("  gap boundary at index $gi: ${gapSeconds}s between rawObservations[${gi - 1}] and rawObservations[$gi]")
        }
    }
    val allBoundaryIndices = (turnIndices + largeGapIndices).distinct().sorted()

    // --- 4. Build the truth model -----------------------------------------------------------
    // corridorConfidence = HIGH: corroborated by the independently annotated multi-waypoint map
    // screenshot (protocol §4/§2B of the round) confirming the general streets/corridors walked.
    // exactGeometryTruthStatus stays PENDING: the dense raw GPS trace is used AS the geometry field
    // directly; no stronger (e.g. manual imagery-traced) verification has been produced.
    val corridorIdPrefix = scenarioId
    val verifiedSegmentsRaw = TurnSegmentation.buildSegments(
        totalPoints = points.size,
        turnIndices = allBoundaryIndices,
        corridorIdPrefix = corridorIdPrefix,
        confidence = TruthConfidence.HIGH,
        verificationMethod = VerificationMethod.MANUAL_ROUTE_ANNOTATION,
    )
    // Annotate segments immediately adjacent to a large gap boundary -- their corridor identity is
    // still HIGH confidence (the screenshot still corroborates the overall route), but the
    // ambiguityNote flags that this specific segment's boundary touches a real observation gap,
    // not an ordinary turn, for any downstream engine-output analysis to treat specially (2B-4 Test
    // A/Test B separation).
    val verifiedSegments = verifiedSegmentsRaw.map { seg ->
        val touchesGap = largeGapIndices.any { it == seg.startReferenceIndex || it == seg.endReferenceIndex }
        if (touchesGap) seg.copy(ambiguityNote = "segment boundary coincides with a real observation-timestamp gap (>60s) -- see ingestion report / gap investigation, not an ordinary turn boundary") else seg
    }
    val verifiedGeometry = VerifiedRouteGeometry(
        scenarioId = scenarioId,
        geometry = points.map { LatLon(it.lat, it.lon) },
        segments = verifiedSegments,
        corridorConfidence = TruthConfidence.HIGH,
        exactGeometryTruthStatus = GeometryTruthStatus.PENDING,
        sourceDescription = "Corridor identity corroborated by an independently annotated multi-waypoint map screenshot (urban walk with intentional intermediate stops, confirming the general streets/corridors used -- no candidate matcher involved). Segment boundaries are algorithmic (TurnSegmentation, sustained-bearing-change detection over GPS evidence), NOT read off the screenshot. Geometry field is the dense 1Hz raw GPS trace itself; exact metre-level accuracy is NOT independently verified (exactGeometryTruthStatus=PENDING).",
    )
    val referenceTrace = ReferenceTrace(
        id = scenarioId,
        capturedAtEpochMs = points.first().timestampEpochMs,
        device = "Samsung SM-G998U1 (GPSLogger CSV export)",
        acquisitionMode = "foreground, screen on, GPSLogger 1s interval, 0m distance filter",
        rawObservations = points,
        verifiedGeometry = verifiedGeometry,
        corpusCategory = corpusCategory,
        corpusStatus = CorpusStatus.VALIDATED,
    )
    File(outputDir, "reference-trace.json").writeText(json.encodeToString(referenceTrace))
    println()
    println("--- Truth model built: ReferenceTrace + VerifiedRouteGeometry (${verifiedSegments.size} segments) ---")
    println("corridorConfidence: ${verifiedGeometry.corridorConfidence}")
    println("exactGeometryTruthStatus: ${verifiedGeometry.exactGeometryTruthStatus}")
    for (seg in verifiedSegments) {
        println("  ${seg.segmentId}: indices [${seg.startReferenceIndex},${seg.endReferenceIndex}] corridorId=${seg.corridorId} confidence=${seg.confidence}")
    }
    println("--- TruthGuard demonstration on this real scenario's current status ---")
    println("exactGeometryEvaluability: ${TruthGuard.exactGeometryEvaluability(verifiedGeometry)} (expected NOT_EVALUABLE while status=PENDING)")
    println("h3Evaluability: ${TruthGuard.h3Evaluability(verifiedGeometry)} (expected NOT_EVALUABLE while status=PENDING)")

    // --- 5. Generate standard degraded input sets -------------------------------------------
    val cadences = listOf(7.0, 15.0, 27.0, 45.0)
    val degradedDir = File(scenarioRootDir, "generated").apply { mkdirs() }
    println()
    println("--- Degraded sets ---")
    val denseSpec = DegradationSpec(targetCadenceSeconds = 1.0)
    val denseSet = Degradation.generate(referenceTrace, denseSpec, "dense", transportMode)
    writeDegradedSet(degradedDir, "dense", denseSet)
    for (cadence in cadences) {
        val spec = DegradationSpec(targetCadenceSeconds = cadence)
        val variantLabel = "${cadence.toInt()}s"
        val degraded = Degradation.generate(referenceTrace, spec, variantLabel, transportMode)
        writeDegradedSet(degradedDir, variantLabel, degraded)
    }

    // --- 6. H3 cells sampled from raw-GPS-evidence geometry -- PROVISIONAL, NOT truth cells ---
    val h3 = H3Comparison()
    val provisionalObservationCells = h3.sampleToH3Cells(verifiedGeometry.geometry, H3Comparison.DEFAULT_RESOLUTION)
    println()
    println("--- H3 cells from raw GPS evidence (resolution ${H3Comparison.DEFAULT_RESOLUTION}, step ${H3Comparison.DEFAULT_STEP_METERS}m) -- PROVISIONAL ONLY, not truth cells ---")
    println("provisionalObservationCellCount: ${provisionalObservationCells.size}")
    File(outputDir, "h3-provisional-observation-cells.txt").writeText(
        "status=PROVISIONAL (sampled from raw-GPS-evidence geometry, exactGeometryTruthStatus=PENDING -- NOT validated truth cells)\n" +
            "resolution=${H3Comparison.DEFAULT_RESOLUTION} stepMeters=${H3Comparison.DEFAULT_STEP_METERS} provisionalObservationCellCount=${provisionalObservationCells.size}\n" +
            "note: full cell address list omitted from this summary file by convention (still private); see reference-trace.json (private) if the full set is needed.\n" +
            "note: must never be passed to a benchmark computation as truthCells -- see TruthGuard.kt.\n",
    )

    println()
    println("=== DONE. All outputs written under ${outputDir.path} (private, gitignored). No network calls made. ===")
}

private fun writeDegradedSet(dir: File, label: String, set: DegradedObservationSet) {
    val file = File(dir, "degraded-$label.json")
    file.writeText(json.encodeToString(set))
    val sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    val durationSec = if (set.observations.size >= 2) (set.observations.last().timestampEpochMs - set.observations.first().timestampEpochMs) / 1000.0 else 0.0
    var distance = 0.0
    for (i in 1 until set.observations.size) {
        distance += GeometryComparison.haversineMeters(
            LatLon(set.observations[i - 1].lat, set.observations[i - 1].lon),
            LatLon(set.observations[i].lat, set.observations[i].lon),
        )
    }
    val stepDistances = (1 until set.observations.size).map {
        GeometryComparison.haversineMeters(
            LatLon(set.observations[it - 1].lat, set.observations[it - 1].lon),
            LatLon(set.observations[it].lat, set.observations[it].lon),
        )
    }
    val intervals = (1 until set.observations.size).map { (set.observations[it].timestampEpochMs - set.observations[it - 1].timestampEpochMs) / 1000.0 }
    val maxGap = intervals.maxOrNull() ?: 0.0
    val accs = set.observations.mapNotNull { it.accuracyMeters?.toDouble() }.sorted()
    val fmt = java.util.Locale.ROOT
    println(
        "[$label] observations=${set.observations.size} durationSec=$durationSec distanceMeters=${"%.2f".format(fmt, distance)} " +
            "maxGapSeconds=$maxGap accuracyRange=${accs.firstOrNull()}..${accs.lastOrNull()} " +
            "stepDistMeters(min/mean/max)=${"%.2f".format(fmt, stepDistances.minOrNull() ?: 0.0)}/${"%.2f".format(fmt, stepDistances.average())}/${"%.2f".format(fmt, stepDistances.maxOrNull() ?: 0.0)} " +
            "sha256=$sha256",
    )
}
