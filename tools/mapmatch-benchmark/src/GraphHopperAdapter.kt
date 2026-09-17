package worlddiscovery.benchmark

import java.io.File

/**
 * Adapts a raw GraphHopper CLI `match` `.res.gpx` output into the engine-neutral
 * [EngineMatchResult] -- protocol §B.3/§F. As documented extensively in
 * `docs/ai-context/map-matching-spike/graphhopper/commands.md` §4 (Phase 2A/2B) and reconfirmed in
 * round 2B-2B against a real capture: this GPX output does NOT preserve a usable one-to-one
 * correspondence with input observations (points are resampled, only some carry a `<time>`, and
 * those don't reliably match input timestamps) -- so [EngineMatchResult.perObservation] is always
 * `null` here, never fabricated. `accuracyMeters` per point is also structurally unsupported by
 * GPX (no such field in the schema) -- recorded in [EngineMatchResult.unsupportedInputFields].
 */
object GraphHopperAdapter {

    fun adapt(
        resultGpxText: String,
        scenarioId: String,
        variant: String,
        mode: TransportMode,
        engineVersion: String,
        profile: String,
        benchmarkMode: BenchmarkMode,
        graphSource: String,
        requestFixturePath: String,
        rawResponsePath: String,
        runtimeMillis: Long,
    ): EngineMatchResult {
        val configIdentity = EngineConfigIdentity(
            engine = "graphhopper",
            engineVersion = engineVersion,
            profileOrCosting = profile,
            benchmarkMode = benchmarkMode,
            graphSource = graphSource,
            requestFixturePath = requestFixturePath,
            requestFixtureSha256 = Sha256.ofFile(File(requestFixturePath)),
            rawResponsePath = rawResponsePath,
            rawResponseSha256 = Sha256.ofFile(File(rawResponsePath)),
        )

        val trkptRegex = Regex("""<trkpt lat="([-0-9.]+)" lon="([-0-9.]+)"""")
        val matchedGeometry = trkptRegex.findAll(resultGpxText).map { m ->
            LatLon(lat = m.groupValues[1].toDouble(), lon = m.groupValues[2].toDouble())
        }.toList()

        return EngineMatchResult(
            configIdentity = configIdentity,
            scenarioId = scenarioId, variant = variant, mode = mode,
            matchedGeometry = matchedGeometry,
            perObservation = null, // structurally unavailable -- see class doc comment
            splits = emptyList(), // not derivable from the plain GPX output either
            engineNativeConfidence = emptyMap(), // CLI match text output (matches/gpx length) is parsed and reported separately, not squeezed in here
            unsupportedInputFields = listOf("accuracyMeters", "bearingDegrees"),
            runtimeMillis = runtimeMillis,
            errors = emptyList(),
        )
    }
}
