package worlddiscovery.benchmark

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapts a raw Valhalla `trace_attributes` JSON response into the engine-neutral
 * [EngineMatchResult] -- protocol §B.3/§F.
 *
 * Confirmed field shapes empirically against
 * `docs/ai-context/map-matching-spike/valhalla/response.json` (Phase 2A/2B):
 * `matched_points[]` (one entry per input observation), `matched_points[].{type,edge_index,lat,
 * lon,distance_from_trace_point}` -- `distance_from_trace_point` is in **meters** (see Phase 2A
 * correction; do not reinterpret as kilometers regardless of the top-level `units` field, which
 * applies only to route/leg length fields). `edges[].names` resolves a human-readable street name
 * for a given `edge_index`, when present.
 */
object ValhallaAdapter {

    fun adapt(
        rawJson: String,
        scenarioId: String,
        variant: String,
        mode: TransportMode,
        engineVersion: String,
        costing: String,
        benchmarkMode: BenchmarkMode,
        graphSource: String,
        requestFixturePath: String,
        rawResponsePath: String,
        runtimeMillis: Long,
    ): EngineMatchResult {
        val configIdentity = EngineConfigIdentity(
            engine = "valhalla",
            engineVersion = engineVersion,
            profileOrCosting = costing,
            benchmarkMode = benchmarkMode,
            graphSource = graphSource,
            requestFixturePath = requestFixturePath,
            requestFixtureSha256 = Sha256.ofFile(File(requestFixturePath)),
            rawResponsePath = rawResponsePath,
            rawResponseSha256 = Sha256.ofFile(File(rawResponsePath)),
        )

        val root = Json.parseToJsonElement(rawJson).jsonObject
        val edges = root["edges"]?.jsonArray.orEmpty()
        val matchedPoints = root["matched_points"]?.jsonArray.orEmpty()

        fun edgeName(edgeIndex: Int?): String? {
            if (edgeIndex == null || edgeIndex < 0 || edgeIndex >= edges.size) return null
            val names = edges[edgeIndex].jsonObject["names"]?.jsonArray.orEmpty()
            return names.joinToString("/") { it.jsonPrimitive.content }.ifBlank { null }
        }

        val perObservation = matchedPoints.mapIndexed { index, mp ->
            val obj = mp.jsonObject
            val typeStr = obj["type"]?.jsonPrimitive?.content ?: "unmatched"
            val outcome = when (typeStr) {
                "matched" -> MatchOutcome.MATCHED
                "interpolated" -> MatchOutcome.INTERPOLATED
                "unmatched" -> MatchOutcome.UNMATCHED
                else -> MatchOutcome.UNKNOWN
            }
            val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull
            val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull
            val point = if (lat != null && lon != null) LatLon(lat, lon) else null
            val distanceMeters = obj["distance_from_trace_point"]?.jsonPrimitive?.doubleOrNull
            val edgeIndex = obj["edge_index"]?.jsonPrimitive?.intOrNull
            ObservationOutcome(index, outcome, point, distanceMeters, edgeName(edgeIndex) ?: edgeIndex?.toString())
        }

        // Valhalla's trace_attributes does not return one single "matched geometry" polyline
        // field in the attribute set we requested (we asked for matched_points, not `shape`) --
        // the per-observation matched points themselves are the closest available geometry.
        // A future run requesting the `shape` attribute would give a denser polyline; documented
        // here rather than silently substituted.
        //
        // 2B-2C correction (Codex-flagged, real correctness bug): matchedGeometry must contain
        // only points Valhalla actually classifies as matched to the road network -- MATCHED or
        // INTERPOLATED (interpolated-between-real-matches is still "on the route", just not a
        // directly observed match). UNMATCHED/UNKNOWN entries can still carry non-null lat/lon in
        // Valhalla's raw response (e.g. an echoed input coordinate that could not be snapped), and
        // the previous version of this adapter included them in matchedGeometry via a blanket
        // `mapNotNull { it.matchedPoint }` -- silently treating "Valhalla could not match this
        // point" as if it were a real match. Those entries are NOT discarded -- they remain fully
        // represented in `perObservation` (outcome=UNMATCHED/UNKNOWN, point/distance preserved) --
        // only excluded from the matched-geometry polyline itself.
        val matchedGeometry = perObservation
            .filter { it.outcome == MatchOutcome.MATCHED || it.outcome == MatchOutcome.INTERPOLATED }
            .mapNotNull { it.matchedPoint }

        val nativeConfidence = matchedPoints.mapIndexed { i, mp ->
            "matched_points[$i].distance_from_trace_point" to
                (mp.jsonObject["distance_from_trace_point"]?.jsonPrimitive?.content ?: "")
        }.toMap()

        return EngineMatchResult(
            configIdentity = configIdentity,
            scenarioId = scenarioId, variant = variant, mode = mode,
            matchedGeometry = matchedGeometry, perObservation = perObservation, splits = emptyList(),
            engineNativeConfidence = nativeConfidence,
            unsupportedInputFields = emptyList(),
            runtimeMillis = runtimeMillis, errors = emptyList(),
        )
    }
}
