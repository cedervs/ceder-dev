# Data and Discovery Rules

## Canonical geography
Current engine converts transient coordinates to **H3 resolution 12** cells. Resolution/engine version are historical semantics and must be versioned rather than silently changed.

## Observation semantics
An observation represents evidence that the user was associated with a location at a time. Current automatic foreground/background GPS observations are `OBSERVED` and `NON_CERTIFIED`. Future provenance vocabulary includes reconstructed, imported and manual observations; exact acceptance/certification rules for those are not fully decided.

## Timestamps
`firstDiscoveredAt` is monotonic toward the earliest observation; `lastObservedAt` toward the latest. Room persists these as `firstDiscoveredAtEpochMillis` and `lastObservedAtEpochMillis`. Background batches preserve each Android `Location.time`. Never collapse a batch to one reception-time timestamp.

## Trust
Certified and Non-certified are separate trust states. Same H3 + different trust status remains separate in persistence. Client-side GPS must not promote to Certified. Server authority/validation is planned. Users may eventually be able to view/toggle Non-certified data separately; exact UI is not finalized.

## Privacy/raw position
Raw latitude/longitude is transient input and is not persisted as long-term discovery history in the current design. IP address is never proof of discovery. Future sync should exchange the minimum data needed for discovery integrity rather than create an unnecessary precise-location archive.

## Merge
- create if no matching record exists;
- earliest first timestamp wins;
- latest last timestamp wins;
- latest chronological provenance wins, incoming wins exact tie;
- creation-time engine version and H3 resolution remain frozen;
- cross-trust merge is invalid.

## Offline and sync
**IMPLEMENTED:** local discovery works through Room without a network dependency. **DECIDED / NOT IMPLEMENTED:** offline observations must later synchronize without corrupting monotonic history. **ENGINEERING DESIGN REQUIRED:** exact sync protocol, conflict handling, outbox/cursors, server tables and PostGIS schema. No geographic sync endpoint or PostGIS discovery table exists at the baseline.

## Geographic progression
**DECIDED / NOT IMPLEMENTED:** derive progress upward from canonical discovery data across World → Continent → Country → country-specific lower levels. Physical presence colors territory; percentage expresses exploration depth, not merely whether a boundary was touched. Disconnected islands remain geographically meaningful. Geography follows the physical area first; disputed political borders are presented neutrally and never force the user to choose a claimant.

The hybrid reference direction (Natural Earth, geoBoundaries, OpenStreetMap, Overture where useful, plus a versioned World Discovery correction layer) is already established; GADM is not the primary foundation because of licensing suitability. Selecting and implementing the ingestion/mapping pipeline is **ENGINEERING DESIGN REQUIRED**, not automatically a new user decision. Territory-by-territory classification and numeric thresholds are **CALIBRATION REQUIRED** where applicable. See `docs/architecture.md` §8 and `docs/discovery-engine.md` §§11, 19–20.

## Difficulty
**DECIDED / NOT IMPLEMENTED:** one canonical history, multiple interpretations; Standard is the reference/default. Easy ≈50–60% and Hard/Precision ≈200–250% of Standard effort are product targets, not final coefficients. Translating this into an aggregation mechanism is **ENGINEERING DESIGN REQUIRED**; coefficients, radii and thresholds are **CALIBRATION REQUIRED** through reproducible tests.

## Eligibility and safety — DECIDED / NOT IMPLEMENTED
- Canonical eligibility states are `ELIGIBLE`, `RESTRICTED_EXCLUDED` and `UNKNOWN`.
- `RESTRICTED_EXCLUDED` contributes neither numerator nor denominator and is never required for 100%.
- `UNKNOWN` remains outside the denominator until classified: no requirement, no bonus, and no score above 100%.
- Eligibility and its signals are separate; Street View or vehicle coverage is never proof of legal access.
- Reclassification is versioned and never erases historical discovery.
- The product must never encourage entry into dangerous, prohibited or inaccessible areas, and grants no bonus merely for doing so.

## Terrain, movement and transport — DECIDED / NOT IMPLEMENTED
- Dense urban territory requires finer representative exploration; Standard must remain realistically completable without every alley.
- Large homogeneous areas (agricultural land, repetitive forest, desert, polar/glacial terrain) generalize more aggressively; users must not be encouraged to grid-walk or leave legitimate paths.
- Walking, road, rail and other physical ground movement may contribute according to the future calibrated model.
- A flight does not color the land or ocean overflown. Long-distance reconstruction must account for plausible transport and remain conservative.
- Water/sea traversal and islands follow the detailed normative rules in `docs/discovery-engine.md`; accessible islands remain independently meaningful and visiting a mainland does not automatically color disconnected islands.

## Monotonicity, versioning and recalculation — DECIDED
- Historical discovery never silently disappears.
- A rule/reference update may explicitly change a displayed contribution, but must be versioned and must preserve the underlying history.
- Engine version, precision aggregation and eligibility/geographic references are independently versioned.
- Percentages, Certified scores and future rankings are derived/recalculable projections, never mutable `score += x` counters.

## Certified and historical recovery — DECIDED / NOT IMPLEMENTED
- The server validation-event journal is the sole authority for Certified; the client cannot self-certify.
- Normal and Certified remain separate datasets and Normal history is never automatically promoted.
- A Certified candidate may originate from live tracking or sufficiently strong historical evidence, but always requires server validation.
- Historical recovery may produce visible Certified or Non-certified results. A proof certifies only what it reasonably demonstrates; no selfie/facial recognition is required; AI assistance is never absolute truth.
- Conservative reconstruction between reliable points may create a candidate while preserving provenance, but never bypasses validation.
- Detailed normative rules and genuine open parameters live in `docs/certified-mode.md` §§8–12 and `docs/discovery-engine.md` §§23–25.
