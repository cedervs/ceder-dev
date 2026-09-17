package worlddiscovery.benchmark

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * CLI runner for Phase 2B-2: ingests a raw GPSLogger CSV, builds the independent truth model,
 * generates standard degraded input sets, and computes H3 truth coverage -- all purely locally, no
 * network calls (no candidate matcher is invoked here; OSRM/Valhalla/GraphHopper execution is a
 * separate, still-blocked step, see the round's own report).
 *
 * Takes the raw CSV path and an output directory as CLI args so this file itself never contains or
 * hardcodes a private file path -- both must point under `benchmark-private/` by convention, but
 * that is the caller's responsibility (see the per-scenario README under `benchmark-private/phase2b/`
 * for the actual invocation used for the real capture).
 *
 * Usage: `IngestAndBuildScenarioKt <rawCsvPath> <outputDir> <scenarioId>`
 */
private val json = Json { prettyPrint = true; encodeDefaults = true }

fun main(args: Array<String>) {
    require(args.size >= 3) { "usage: IngestAndBuildScenarioKt <rawCsvPath> <scenarioRootDir> <scenarioId>" }
    val rawCsvPath = File(args[0])
    val scenarioRootDir = File(args[1])
    val scenarioId = args[2]
    val outputDir = File(scenarioRootDir, "truth")
    outputDir.mkdirs()

    println("=== Phase 2B-2 ingestion + truth-model build (all local, no network) ===")
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
    if (points.size < 2) {
        println("STOP: fewer than 2 usable points parsed -- cannot build a truth model or degrade this trace.")
        return
    }

    // --- 2. Independent computed cross-check of inter-point walking speed -------------------
    val interPointSpeeds = mutableListOf<Double>()
    val cumulativeDistanceFromStart = DoubleArray(points.size)
    for (i in 1 until points.size) {
        val d = GeometryComparison.haversineMeters(LatLon(points[i - 1].lat, points[i - 1].lon), LatLon(points[i].lat, points[i].lon))
        cumulativeDistanceFromStart[i] = GeometryComparison.haversineMeters(LatLon(points[0].lat, points[0].lon), LatLon(points[i].lat, points[i].lon))
        val dtSec = (points[i].timestampEpochMs - points[i - 1].timestampEpochMs) / 1000.0
        if (dtSec > 0) interPointSpeeds.add(d / dtSec)
    }
    val sortedInterPointSpeeds = interPointSpeeds.sorted()
    val medianInterPointSpeed = if (sortedInterPointSpeeds.isEmpty()) null else {
        val mid = sortedInterPointSpeeds.size / 2
        if (sortedInterPointSpeeds.size % 2 == 0) (sortedInterPointSpeeds[mid - 1] + sortedInterPointSpeeds[mid]) / 2.0 else sortedInterPointSpeeds[mid]
    }
    println("medianSpeedMps (independently computed from consecutive-point distance/time): $medianInterPointSpeed")

    // --- 3. Turnaround detection (out-and-back heuristic: index of max distance from start) --
    var turnaroundIndex = 0
    var maxDist = 0.0
    for (i in points.indices) {
        if (cumulativeDistanceFromStart[i] > maxDist) {
            maxDist = cumulativeDistanceFromStart[i]
            turnaroundIndex = i
        }
    }
    val turnaroundTimeOffsetSeconds = (points[turnaroundIndex].timestampEpochMs - points[0].timestampEpochMs) / 1000.0
    val finishDistanceFromStart = cumulativeDistanceFromStart.last()
    println()
    println("--- Out-and-back structure (independently derived) ---")
    println("turnaroundIndex: $turnaroundIndex / ${points.size - 1}")
    println("turnaroundDistanceFromStartMeters: ${"%.2f".format(java.util.Locale.ROOT, maxDist)}")
    println("turnaroundTimeOffsetSeconds: $turnaroundTimeOffsetSeconds")
    println("finishDistanceFromStartMeters: ${"%.2f".format(java.util.Locale.ROOT, finishDistanceFromStart)}")

    // --- 4. Build the truth model (protocol §B.1 / §C.3bis) --------------------------------
    // 2B-2A correction: CORRIDOR identity (which road) and EXACT GEOMETRY accuracy (is this
    // polyline itself metre-accurate) are now kept as two separate, independent claims -- see
    // `Models.kt`'s `VerifiedRouteGeometry` doc comment and `TruthGuard.kt`.
    //
    // corridorConfidence = HIGH is defensible: the annotated map screenshot independently confirms
    // a single isolated rural road with no parallel/alternative path was used outbound and inbound.
    //
    // exactGeometryTruthStatus stays PENDING: the dense raw GPS trace is used AS THE geometry field
    // (nothing stronger, like a manual trace from independent imagery, has been produced), so it
    // must NOT be treated as authoritative exact ground truth for metre-scale metrics. PENDING
    // (not NOT_EVALUABLE) because a stronger verification (e.g. manual imagery tracing) remains
    // possible later -- it has simply not been done yet.
    val corridorId = "rural-road-single-corridor" // deliberately not the real road/place name -- see report §C
    val verifiedSegments = listOf(
        VerifiedSegment(
            segmentId = "$scenarioId-outbound",
            startReferenceIndex = 0,
            endReferenceIndex = turnaroundIndex,
            corridorId = corridorId,
            verificationMethod = VerificationMethod.MANUAL_ROUTE_ANNOTATION,
            confidence = TruthConfidence.HIGH,
            ambiguityNote = null,
            roadOrPathDescription = "single rural mapped road, no parallel/alternative path visible in the annotated map evidence",
            verifiedGeometryRef = null,
        ),
        VerifiedSegment(
            segmentId = "$scenarioId-inbound",
            startReferenceIndex = turnaroundIndex,
            endReferenceIndex = points.size - 1,
            corridorId = corridorId,
            verificationMethod = VerificationMethod.MANUAL_ROUTE_ANNOTATION,
            confidence = TruthConfidence.HIGH,
            ambiguityNote = null,
            roadOrPathDescription = "same rural mapped road, return leg",
            verifiedGeometryRef = null,
        ),
    )
    val verifiedGeometry = VerifiedRouteGeometry(
        scenarioId = scenarioId,
        geometry = points.map { LatLon(it.lat, it.lon) }, // raw GPS evidence -- see comment above, NOT exact truth
        segments = verifiedSegments,
        corridorConfidence = TruthConfidence.HIGH,
        exactGeometryTruthStatus = GeometryTruthStatus.PENDING,
        sourceDescription = "Corridor identity confirmed by an independently annotated map screenshot (single unambiguous rural road, outbound and inbound -- no candidate matcher involved). Geometry field is the dense 1Hz raw GPS trace itself; exact metre-level accuracy is NOT independently verified (exactGeometryTruthStatus=PENDING) -- see protocol report round 2B-2A for the full distinction and why this construction is defensible for corridor identity only, not for exact-geometry metrics.",
    )
    val referenceTrace = ReferenceTrace(
        id = scenarioId,
        capturedAtEpochMs = points.first().timestampEpochMs,
        device = "Samsung SM-G998U1 (GPSLogger CSV export)",
        acquisitionMode = "foreground, screen on, GPSLogger 1s interval, 0m distance filter",
        rawObservations = points,
        verifiedGeometry = verifiedGeometry,
        corpusCategory = CorpusCategory.WALK,
        corpusStatus = CorpusStatus.VALIDATED,
    )
    File(outputDir, "reference-trace.json").writeText(json.encodeToString(referenceTrace))
    println()
    println("--- Truth model built: ReferenceTrace + VerifiedRouteGeometry (${verifiedSegments.size} segments) ---")
    println("corridorId (synthetic, non-identifying): $corridorId")
    println("corridorConfidence: ${verifiedGeometry.corridorConfidence}")
    println("exactGeometryTruthStatus: ${verifiedGeometry.exactGeometryTruthStatus}")
    println("--- TruthGuard demonstration on this real scenario's current status ---")
    println("exactGeometryEvaluability: ${TruthGuard.exactGeometryEvaluability(verifiedGeometry)} (expected NOT_EVALUABLE while status=PENDING)")
    println("h3Evaluability: ${TruthGuard.h3Evaluability(verifiedGeometry)} (expected NOT_EVALUABLE while status=PENDING)")

    // --- 5. Generate standard degraded input sets (protocol §D.5 / Degradation.kt) ----------
    val cadences = listOf(7.0, 15.0, 27.0, 45.0)
    val degradedDir = File(scenarioRootDir, "generated").apply { mkdirs() }
    println()
    println("--- Degraded sets ---")
    // Dense/reference set: the raw observations themselves, wrapped as a DegradedObservationSet
    // with an explicit spec expressing "no downsampling" (targetCadenceSeconds = the trace's own
    // median interval), for a uniform representation alongside the cadence variants.
    val denseSpec = DegradationSpec(targetCadenceSeconds = 1.0)
    val denseSet = Degradation.generate(referenceTrace, denseSpec, "dense", TransportMode.WALK)
    writeDegradedSet(degradedDir, "dense", denseSet)

    for (cadence in cadences) {
        val spec = DegradationSpec(targetCadenceSeconds = cadence)
        val variantLabel = "${cadence.toInt()}s"
        val degraded = Degradation.generate(referenceTrace, spec, variantLabel, TransportMode.WALK)
        writeDegradedSet(degradedDir, variantLabel, degraded)
    }

    // --- 6. H3 cells sampled from raw-GPS-evidence geometry -- PROVISIONAL, NOT truth cells ---
    // 2B-2A correction: renamed from "truthCellCount"/"H3 truth coverage". These cells are sampled
    // from `verifiedGeometry.geometry`, which is raw GPS evidence with exactGeometryTruthStatus=
    // PENDING (see §4 above) -- NOT validated exact truth. They have real diagnostic value (a
    // provisional estimate of which cells this walk plausibly touched) but must NEVER be passed
    // into a benchmark computation as `truthCells` -- `TruthGuard`/`BenchmarkEvaluation` enforce
    // this by returning NOT_EVALUABLE for h3Metrics whenever exactGeometryTruthStatus != VALIDATED
    // (demonstrated above; this block only reports the raw provisional count for corpus bookkeeping).
    val h3 = H3Comparison()
    val provisionalObservationCells = h3.sampleToH3Cells(verifiedGeometry.geometry, H3Comparison.DEFAULT_RESOLUTION)
    println()
    println("--- H3 cells from raw GPS evidence (resolution ${H3Comparison.DEFAULT_RESOLUTION}, step ${H3Comparison.DEFAULT_STEP_METERS}m) -- PROVISIONAL ONLY, not truth cells ---")
    println("provisionalObservationCellCount: ${provisionalObservationCells.size}")
    File(outputDir, "h3-provisional-observation-cells.txt").writeText(
        "status=PROVISIONAL (sampled from raw-GPS-evidence geometry, exactGeometryTruthStatus=PENDING -- NOT validated truth cells)\n" +
            "resolution=${H3Comparison.DEFAULT_RESOLUTION} stepMeters=${H3Comparison.DEFAULT_STEP_METERS} provisionalObservationCellCount=${provisionalObservationCells.size}\n" +
            "note: full cell address list omitted from this summary file by convention (still private -- H3 cell addresses at resolution 12 are effectively coordinates); see reference-trace.json (private) if the full set is needed.\n" +
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
    val stepDistances = (1 until set.observations.size).map {
        GeometryComparison.haversineMeters(
            LatLon(set.observations[it - 1].lat, set.observations[it - 1].lon),
            LatLon(set.observations[it].lat, set.observations[it].lon),
        )
    }
    val distance = stepDistances.sum()
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
