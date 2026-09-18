import com.cedervs.worlddiscovery.core.discovery.GeographicAreaReferenceJson
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Regression proof for the FH-1 provenance micro-fix, round 2 (Codex re-review): the generator's
 * `generatedAt` value must be computed in the explicit `Europe/Paris` calendar-date domain
 * ([PROVENANCE_ZONE]), enforced by the implementation ([currentGenerationDate]'s own
 * `clock.withZone(PROVENANCE_ZONE)`) rather than left dependent on the host machine's default time
 * zone -- and the artifact-generation path that actually writes bundled resources
 * ([buildGeographicAreaReference], the function [main] calls) must be proven to use that same
 * mechanism, not merely the helper in isolation.
 *
 * Four things this file proves, each independently, none by reimplementing the production
 * timezone-conversion logic a second time here:
 * 1. [currentGenerationDate] returns the `Europe/Paris` calendar date, not the UTC one, at an
 *    instant deliberately chosen so the two dates genuinely differ (the midnight-boundary case).
 * 2. That same instant, wrapped in clocks tagged with several different, unrelated zones
 *    (simulating different possible host default zones), always produces the identical result --
 *    proving the caller's own clock zone cannot leak into the answer.
 * 3. [buildGeographicAreaReference] -- the real artifact-construction function, not a test-only
 *    stand-in -- is invoked TWICE, with two different deterministic clocks whose Europe/Paris
 *    calendar dates genuinely differ, against a small local synthetic GeoJSON fixture (no network
 *    access, no dependency on any previously-fetched OSM file). Each resulting artifact's own
 *    `generatedAt` is checked against its own explicit expected literal date (never recomputed via
 *    [currentGenerationDate] itself, so this is not a self-referential check), and the two results
 *    are asserted to differ from each other. A single-clock version of this test would still pass
 *    if [buildGeographicAreaReference] were changed to always return one fixed, hard-coded
 *    `generatedAt` regardless of its clock parameter; exercising it against two distinct dates and
 *    requiring both the individual matches AND the difference between them closes that gap.
 * 4. The obsolete pre-fix values (the original hard-coded `2026-09-02` literal, and the
 *    UTC-instead-of-Paris regression from the first provenance-fix attempt) can never come back
 *    silently.
 *
 * Deterministic by construction throughout: every clock here is [Clock.fixed] against an explicit
 * [Instant], never the real system clock, so this test can never become flaky or silently stop
 * proving anything as real time passes. Follows this repository's existing `tools` directory
 * smoke-test convention (a plain `fun main()` that throws via [check] on failure) rather than
 * pulling in a JUnit dependency for a standalone, no-build-file CLI tool directory.
 */
fun main() {
    verifyMidnightBoundary()
    verifyHostTimezoneIndependence()
    verifyProductionArtifactPath()
    verifyObsoleteLiteralsNeverReturn()

    println("GenerateFranceAdministrativeReferenceProvenanceTest PASSED")
}

/**
 * 2026-01-15T23:30:00Z is still 2026-01-15 in UTC, but Europe/Paris is UTC+1 in January (CET, no
 * DST) so the same instant is already 2026-01-16 there. Any implementation that used the clock's
 * own UTC zone (or ignored PROVENANCE_ZONE entirely) would answer 2026-01-15 here; only an
 * implementation that genuinely reinterprets through Europe/Paris answers 2026-01-16.
 */
private fun verifyMidnightBoundary() {
    val instant = Instant.parse("2026-01-15T23:30:00Z")
    val utcClock = Clock.fixed(instant, ZoneOffset.UTC)

    val result = currentGenerationDate(utcClock)
    check(result == "2026-01-16") {
        "expected the Europe/Paris calendar date (2026-01-16) for instant $instant, got $result " +
            "-- currentGenerationDate must reinterpret through PROVENANCE_ZONE, not trust the " +
            "input clock's own zone"
    }
}

/**
 * The same real instant, wrapped in clocks carrying several different, mutually unrelated zones
 * (standing in for "whatever zone this generator's host machine happens to default to"), must
 * always produce the identical Europe/Paris-derived calendar date -- proving the result depends
 * only on the instant, never on the clock's own attached zone.
 */
private fun verifyHostTimezoneIndependence() {
    val instant = Instant.parse("2026-06-10T12:00:00Z")
    val expected = "2026-06-10" // Europe/Paris is UTC+2 (CEST) in June: 12:00Z -> 14:00 Paris, same day.

    val zones = listOf(
        ZoneOffset.UTC,
        ZoneId.of("America/Los_Angeles"),
        ZoneId.of("Asia/Tokyo"),
        ZoneId.of("Australia/Sydney"),
        PROVENANCE_ZONE,
    )
    for (zone in zones) {
        val clock = Clock.fixed(instant, zone)
        val result = currentGenerationDate(clock)
        check(result == expected) {
            "expected currentGenerationDate() to return $expected for instant $instant regardless " +
                "of the input clock's own zone ($zone), got $result -- the host/caller zone must " +
                "never leak into the result"
        }
    }
}

/**
 * Exercises [buildGeographicAreaReference] itself -- the function [main] actually calls to produce
 * every bundled department/region artifact -- TWICE, against the same small local synthetic Polygon
 * fixture (never fetched over the network, never one of this repository's real OSM-derived files),
 * with two deterministic clocks whose Europe/Paris calendar dates genuinely differ
 * ([EXPECTED_ARTIFACT_DATE_A] / [EXPECTED_ARTIFACT_DATE_B]). Each expected date is an explicit
 * literal, never recomputed by calling [currentGenerationDate] -- this test does not duplicate the
 * production timezone-conversion logic, it only checks the real builder's output against two
 * independently-known-correct answers.
 *
 * A single-clock version of this test would still pass if [buildGeographicAreaReference] were
 * changed to always return one fixed, hard-coded `generatedAt` that happened to equal that one
 * clock's expected date -- asserting BOTH individual matches AND that the two results differ from
 * each other closes that gap: any single hard-coded `generatedAt` can match at most one of the two
 * expected literals, so at least one assertion below fails.
 */
private fun verifyProductionArtifactPath() {
    val clockA = Clock.fixed(Instant.parse("2026-01-16T10:00:00Z"), ZoneOffset.UTC) // Paris CET (UTC+1): 11:00, same day.
    val clockB = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC) // Paris CEST (UTC+2): 12:00, same day.

    val referenceA = buildTestArtifact(clockA)
    val referenceB = buildTestArtifact(clockB)

    check(referenceA.generatedAt == EXPECTED_ARTIFACT_DATE_A) {
        "expected buildGeographicAreaReference(...) with clock A to produce " +
            "generatedAt=$EXPECTED_ARTIFACT_DATE_A, got ${referenceA.generatedAt} -- the production " +
            "artifact-construction path must use the real provenance-date mechanism, not a " +
            "different or hard-coded value"
    }
    check(referenceB.generatedAt == EXPECTED_ARTIFACT_DATE_B) {
        "expected buildGeographicAreaReference(...) with clock B to produce " +
            "generatedAt=$EXPECTED_ARTIFACT_DATE_B, got ${referenceB.generatedAt} -- the production " +
            "artifact-construction path must use the real provenance-date mechanism, not a " +
            "different or hard-coded value"
    }
    check(referenceA.generatedAt != referenceB.generatedAt) {
        "expected the two artifacts' generatedAt values to differ (clock A -> " +
            "$EXPECTED_ARTIFACT_DATE_A, clock B -> $EXPECTED_ARTIFACT_DATE_B), got the same value " +
            "${referenceA.generatedAt} for both -- a single hard-coded generatedAt in the production " +
            "builder would produce this exact failure mode"
    }
    check(referenceA.polygons.isNotEmpty() && referenceB.polygons.isNotEmpty()) {
        "the synthetic fixture must survive the real filtering/simplification pipeline and " +
            "produce at least one polygon for both artifacts, or this test is not exercising the " +
            "real path"
    }
}

private const val EXPECTED_ARTIFACT_DATE_A = "2026-01-16"
private const val EXPECTED_ARTIFACT_DATE_B = "2026-07-20"

/** Builds one artifact through the real production path ([buildGeographicAreaReference]) against a
 * small, deliberately synthetic, non-administrative ~100 km² square fixture -- comfortably above
 * `MIN_STRUCTURAL_NOISE_AREA_KM2` and coarse enough that Douglas-Peucker simplification leaves its 4
 * corners intact -- written to a local temp file and removed immediately after, so this never
 * touches the network or any real OSM-derived data. */
private fun buildTestArtifact(clock: Clock): GeographicAreaReferenceJson {
    val fixtureFile = File.createTempFile("provenance-test-fixture", ".geojson")
    try {
        fixtureFile.writeText(
            """{"type":"Polygon","coordinates":[[[2.0,48.0],[2.1,48.0],[2.1,48.1],[2.0,48.1],[2.0,48.0]]]}""",
        )
        return buildGeographicAreaReference(
            sourceFile = fixtureFile,
            id = "test:provenance-fixture",
            type = "ADMIN_2",
            displayName = "Provenance Test Fixture",
            parentId = "test:parent",
            sourceVersionNote = "synthetic fixture, not a real OSM relation, provenance test only",
            clock = clock,
        )
    } finally {
        fixtureFile.delete()
    }
}

/** The obsolete pre-fix values must never come back as a silent fallback or coincidental match. */
private fun verifyObsoleteLiteralsNeverReturn() {
    val boundaryResult = currentGenerationDate(Clock.fixed(Instant.parse("2026-01-15T23:30:00Z"), ZoneOffset.UTC))
    val independenceResult = currentGenerationDate(Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC))

    check(boundaryResult != "2026-09-02" && independenceResult != "2026-09-02") {
        "currentGenerationDate() must never fall back to the original hard-coded 2026-09-02 literal"
    }
    // The first provenance-fix attempt's regression: Clock.systemDefaultZone() (or trusting the
    // input clock's own UTC zone outright) would have answered 2026-01-15 here, not 2026-01-16.
    check(boundaryResult != "2026-01-15") {
        "currentGenerationDate() must not answer the UTC calendar date at the Europe/Paris " +
            "midnight boundary -- that is the exact class of bug PROVENANCE_ZONE fixes"
    }
}
