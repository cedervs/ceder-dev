package worlddiscovery.benchmark

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json

/**
 * Generates engine-specific request encodings (OSRM URL, Valhalla JSON body, GraphHopper GPX) from
 * ONE shared [DegradedObservationSet] -- protocol §10/§B.2 "identical input rule" and 2B-2B
 * correction §6 ("same degraded set -> engine-specific request encoding -> preserved native output
 * -> neutral normalization"). Every engine receives the exact same logical observations; only the
 * wire format differs. No engine-specific tuning of values -- accuracy/timestamp/coordinate come
 * straight from the shared [DegradedObservation] fields.
 *
 * Usage: `GenerateEngineRequestsKt <degradedSetJsonPath> <outputDir> <osrmHost:port> <valhallaHost:port>`
 */
private val json = Json { prettyPrint = true; encodeDefaults = true }

fun main(args: Array<String>) {
    require(args.size >= 4) { "usage: GenerateEngineRequestsKt <degradedSetJsonPath> <outputDir> <osrmBaseUrl> <valhallaBaseUrl>" }
    val setPath = File(args[0])
    val outputDir = File(args[1]).apply { mkdirs() }
    val osrmBaseUrl = args[2] // e.g. http://localhost:5001
    val valhallaBaseUrl = args[3] // e.g. http://localhost:8003

    val set = json.decodeFromString(DegradedObservationSet.serializer(), setPath.readText())
    val obs = set.observations
    require(obs.isNotEmpty()) { "degraded set ${set.variant} has no observations" }

    val defaultAccuracyMeters = 25.0f // used only when a point's own accuracyMeters is null

    // Engine profile/costing derived from the DegradedObservationSet's own TransportMode via the
    // single shared mapping in EngineProfiles.kt -- generic across scenarios (protocol round 2B-4
    // §16/§8: "implement it generically, not as hard-coded Scenario 3 behavior"). Previously
    // hardcoded to foot/pedestrian only, which was correct for Scenarios 1-2 (WALK) but wrong for a
    // CAR scenario -- fixed here, not scenario-specific.
    val osrmProfile = EngineProfiles.osrmProfile(set.mode)
    val valhallaCosting = EngineProfiles.valhallaCosting(set.mode)

    // --- OSRM: /match/v1/{profile}/{lon,lat;...}?radiuses=...&timestamps=... ---
    val coords = obs.joinToString(";") { "${it.lon},${it.lat}" }
    val radiuses = obs.joinToString(";") { (it.accuracyMeters ?: defaultAccuracyMeters).toString() }
    val timestamps = obs.joinToString(";") { (it.timestampEpochMs / 1000).toString() }
    val osrmUrl = "$osrmBaseUrl/match/v1/$osrmProfile/$coords?geometries=geojson&overview=full&annotations=true&radiuses=$radiuses&timestamps=$timestamps"
    File(outputDir, "osrm-request-${set.variant}.txt").writeText(osrmUrl)

    // --- Valhalla: POST /trace_attributes, shape[].{lat,lon,time,accuracy} ---
    // shape_match="walk_or_snap" is Valhalla's own documented DEFAULT for real/noisy GPS traces
    // (edge-walks where confident, falls back to map-matching where not) -- "map_snap" (which this
    // generator used at first, see round 2B-2B's own report) assumes the input is already
    // pre-matched/near-exact and fails outright ("failed to snap the shape points") on real noisy
    // GPS. Using the documented default is a correctness fix, not route-specific tuning.
    val shapeEntries = obs.joinToString(",\n") { o ->
        "    {\"lat\": ${o.lat}, \"lon\": ${o.lon}, \"time\": ${o.timestampEpochMs / 1000}, \"accuracy\": ${o.accuracyMeters ?: defaultAccuracyMeters}}"
    }
    val valhallaBody = """
        {
          "shape": [
        $shapeEntries
          ],
          "costing": "$valhallaCosting",
          "shape_match": "walk_or_snap",
          "filters": {
            "attributes": ["edge.names","matched.point","matched.type","matched.edge_index","matched.distance_from_trace_point"],
            "action": "include"
          }
        }
    """.trimIndent()
    File(outputDir, "valhalla-request-${set.variant}.json").writeText(valhallaBody)

    // --- GraphHopper: GPX 1.1 track (no per-point accuracy field -- documented structural gap,
    // same as Phase 2A/2B's graphhopper/commands.md §2) ---
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"world-discovery-phase2b-benchmark\">\n")
    sb.append("  <trk>\n    <trkseg>\n")
    for (o in obs) {
        val isoTime = Instant.ofEpochMilli(o.timestampEpochMs).toString()
        sb.append("      <trkpt lat=\"${o.lat}\" lon=\"${o.lon}\"><time>$isoTime</time></trkpt>\n")
    }
    sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
    File(outputDir, "graphhopper-request-${set.variant}.gpx").writeText(sb.toString())

    println("[${set.variant}] generated OSRM/Valhalla/GraphHopper requests for ${obs.size} observations (mode=${set.mode}, osrmProfile=$osrmProfile, valhallaCosting=$valhallaCosting)")
}
