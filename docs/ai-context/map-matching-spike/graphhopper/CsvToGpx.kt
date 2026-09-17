import java.io.File
import java.time.Instant

// Converts ../shared/input-trace.csv (index,lon,lat,timestamp_epoch_s,radius_or_accuracy_m,intent)
// into a GPX 1.1 track, the input format GraphHopper's map-matching CLI/REST endpoint consumes.
// GPX's standard schema has no per-point accuracy/radius field, so that column is NOT carried
// into the GPX -- an explicit, documented input-fidelity gap for GraphHopper vs. OSRM/Valhalla
// (see ../graphhopper/commands.md).
fun main() {
    // Run this script from docs/ai-context/map-matching-spike/graphhopper/ (its own directory) so
    // this relative path resolves -- matches the ../shared/ convention used by the OSRM/Valhalla
    // fixtures in this same directory tree.
    val lines = File("../shared/input-trace.csv").readLines().drop(1).filter { it.isNotBlank() }
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
    sb.append("""<gpx version="1.1" creator="world-discovery-phase2b-benchmark">""" + "\n")
    sb.append("  <trk>\n    <trkseg>\n")
    for (line in lines) {
        // Simple split: fields with embedded commas are quoted in the CSV (the "intent" column) --
        // but we only need the first 4 columns here, which never contain commas or quotes.
        val firstFour = line.split(",").take(4)
        val (_, lon, lat, epoch) = firstFour
        val isoTime = Instant.ofEpochSecond(epoch.trim().toLong()).toString()
        sb.append("      <trkpt lat=\"$lat\" lon=\"$lon\"><time>$isoTime</time></trkpt>\n")
    }
    sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
    File("input-trace.gpx").writeText(sb.toString())
    println("Wrote input-trace.gpx (${lines.size} trackpoints)")
}
