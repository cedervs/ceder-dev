package worlddiscovery.benchmark

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Round 2B-5 driver: applies a FROZEN list of [ControlledGapSpec]s (supplied as CLI args, not
 * hardcoded here -- see `benchmark-private/phase2b/controlled-gap-study-005/
 * window-selection-rationale.md` for how the actual Scenario 3 window list was chosen, BEFORE any
 * matcher was run) to a real dense [ReferenceTrace], writing:
 * - `generated/gap-<gapId>.json` -- the matcher input (a [DegradedObservationSet]-shaped file, for
 *   compatibility with the existing, already-tested [GenerateEngineRequests] consumer -- its
 *   `spec` field is a schema-compatibility placeholder only; the REAL specification is the
 *   [ControlledGapSpec] recorded in `generated/gap-<gapId>-controlled-gap-spec.json`).
 * - `generated/gap-<gapId>-reference-window.json` -- the hidden observations, PRIVATE, never an
 *   engine input.
 *
 * Usage: `BuildControlledGapVariantsKt <referenceTraceJsonPath> <scenarioRootDir> <sourceTraceId>
 * <gapId>:<context>:<startIndex>:<endIndex>:<targetDurationSeconds> [...]`
 */
private val json = Json { prettyPrint = true; encodeDefaults = true }

fun main(args: Array<String>) {
    require(args.size >= 4) { "usage: BuildControlledGapVariantsKt <referenceTraceJsonPath> <scenarioRootDir> <sourceTraceId> <gapId:context:start:end:durationSec> [...]" }
    val referenceTrace = json.decodeFromString(ReferenceTrace.serializer(), File(args[0]).readText())
    val scenarioRootDir = File(args[1])
    val sourceTraceId = args[2]
    val generatedDir = File(scenarioRootDir, "generated").apply { mkdirs() }

    // The real ~389s stationary observation gap from Scenario 3 (raw indices 733/734) -- passed
    // defensively so no controlled window can accidentally overlap it, even though none of this
    // round's frozen windows are anywhere near it (see window-selection-rationale.md).
    val excludedRealGapRanges = listOf(733..734)

    for (arg in args.drop(3)) {
        val parts = arg.split(":")
        require(parts.size == 5) { "malformed gap arg '$arg', expected gapId:context:start:end:durationSec" }
        val (gapId, contextStr, startStr, endStr, durationStr) = parts
        val spec = ControlledGapSpec(
            gapId = gapId,
            sourceTraceId = sourceTraceId,
            hiddenStartIndex = startStr.toInt(),
            hiddenEndIndex = endStr.toInt(),
            context = GapContext.valueOf(contextStr),
            selectionRationale = "see benchmark-private/phase2b/controlled-gap-study-005/window-selection-rationale.md (frozen before any matcher run)",
            targetDurationSeconds = durationStr.toDouble(),
        )
        val variant = ControlledGapGenerator.generate(referenceTrace, spec, excludedRealGapRanges = excludedRealGapRanges)

        val degradedSet = DegradedObservationSet(
            referenceTraceId = sourceTraceId,
            variant = gapId,
            mode = TransportMode.CAR,
            // Placeholder for schema compatibility with the existing DegradedObservationSet-shaped
            // consumer (GenerateEngineRequests) -- NOT the real specification for this variant.
            // The real specification is the ControlledGapSpec written alongside this file.
            spec = DegradationSpec(targetCadenceSeconds = spec.targetDurationSeconds),
            observations = variant.matcherInputObservations,
        )
        File(generatedDir, "gap-$gapId.json").writeText(json.encodeToString(degradedSet))
        File(generatedDir, "gap-$gapId-controlled-gap-spec.json").writeText(json.encodeToString(spec))
        File(generatedDir, "gap-$gapId-reference-window.json").writeText(json.encodeToString(variant.referenceWindowObservations))

        val stats = ControlledGapGenerator.computeWindowStats(referenceTrace.rawObservations, spec.hiddenStartIndex, spec.hiddenEndIndex)
        println(
            "[$gapId] context=${spec.context} matcherInputCount=${variant.matcherInputObservations.size} " +
                "referenceWindowCount=${variant.referenceWindowObservations.size} " +
                "durationSec=${"%.1f".format(java.util.Locale.ROOT, stats.durationSeconds)} " +
                "distanceMeters=${"%.1f".format(java.util.Locale.ROOT, stats.cumulativeObservedDistanceMeters)}",
        )
    }
}
