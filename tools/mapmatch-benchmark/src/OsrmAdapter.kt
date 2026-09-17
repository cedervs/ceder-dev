package worlddiscovery.benchmark

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapts a raw OSRM `/match` JSON response into the engine-neutral [EngineMatchResult] -- protocol
 * §B.3/§F. Parses with `kotlinx.serialization.json.JsonElement` (a real parser, never regex --
 * the same lesson already applied to the Phase 2A audit scripts).
 *
 * Confirmed field shapes empirically against `docs/ai-context/map-matching-spike/osrm/response.json`
 * (Phase 2A): `matchings[].confidence`, `tracepoints[]` (one entry per input observation, `null`
 * for an excluded outlier), `tracepoints[].{location,distance,matchings_index,waypoint_index,
 * alternatives_count}`.
 */
object OsrmAdapter {

    fun adapt(
        rawJson: String,
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
            engine = "osrm",
            engineVersion = engineVersion,
            profileOrCosting = profile,
            benchmarkMode = benchmarkMode,
            graphSource = graphSource,
            requestFixturePath = requestFixturePath,
            requestFixtureSha256 = Sha256.ofFile(File(requestFixturePath)),
            rawResponsePath = rawResponsePath,
            rawResponseSha256 = Sha256.ofFile(File(rawResponsePath)),
        )

        val root = Json.parseToJsonElement(rawJson).jsonObject
        val code = root["code"]?.jsonPrimitive?.content ?: "Unknown"
        if (code != "Ok") {
            return EngineMatchResult(
                configIdentity = configIdentity,
                scenarioId = scenarioId, variant = variant, mode = mode,
                matchedGeometry = emptyList(), perObservation = null, splits = emptyList(),
                engineNativeConfidence = mapOf("code" to code), unsupportedInputFields = emptyList(),
                runtimeMillis = runtimeMillis, errors = listOf("OSRM code != Ok: $code"),
            )
        }

        val matchings = root["matchings"]?.jsonArray.orEmpty()
        val confidences = matchings.mapIndexed { i, m ->
            "matchings[$i].confidence" to (m.jsonObject["confidence"]?.jsonPrimitive?.content ?: "")
        }.toMap()

        // Full matched geometry: concatenate every matching's own geometry, in order. OSRM's
        // "geometry" field (when requested as geojson) carries [lon, lat] pairs -- converted here
        // to our LatLon(lat, lon) convention.
        val matchedGeometry = matchings.flatMap { m ->
            val coords = m.jsonObject["geometry"]?.jsonObject?.get("coordinates")?.jsonArray.orEmpty()
            coords.map { pair ->
                val p = pair.jsonArray
                LatLon(lat = p[1].jsonPrimitive.double(), lon = p[0].jsonPrimitive.double())
            }
        }

        val tracepoints = root["tracepoints"]?.jsonArray.orEmpty()
        val perObservation = tracepoints.mapIndexed { index, tp ->
            if (tp is JsonNull) {
                ObservationOutcome(index, MatchOutcome.UNMATCHED, null, null, null)
            } else {
                val obj = tp.jsonObject
                val loc = obj["location"]?.jsonArray
                val point = loc?.let { LatLon(lat = it[1].jsonPrimitive.double(), lon = it[0].jsonPrimitive.double()) }
                val distance = obj["distance"]?.jsonPrimitive?.doubleOrNull
                val waypointIndex = obj["waypoint_index"]?.jsonPrimitive?.intOrNull
                ObservationOutcome(index, MatchOutcome.MATCHED, point, distance, waypointIndex?.toString())
            }
        }

        // Splits: OSRM reports multiple `matchings[]` when it split the trace. We don't have a
        // direct per-observation index range per matching without re-deriving it from
        // waypoint_index, so this remains an approximation: each split spans the full observation
        // index range (honest limitation, not a fabricated per-matching range).
        val splits = if (matchings.size > 1) {
            List(matchings.size - 1) { SplitRange(0, tracepoints.size - 1) }
        } else {
            emptyList()
        }

        return EngineMatchResult(
            configIdentity = configIdentity,
            scenarioId = scenarioId, variant = variant, mode = mode,
            matchedGeometry = matchedGeometry, perObservation = perObservation, splits = splits,
            engineNativeConfidence = confidences, unsupportedInputFields = emptyList(),
            runtimeMillis = runtimeMillis, errors = emptyList(),
        )
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.double(): Double =
    this.doubleOrNull ?: error("expected a JSON number")
