# Functional Rules

| Rule | Reason | State |
|---|---|---|
| A physical observation is converted into the canonical discovery representation instead of being stored as a permanent raw GPS trail. | Privacy and stable geographic semantics. | IMPLEMENTED |
| Canonical current cell resolution is H3 res 12. | Fine-grained base representation from which higher-level progress can be derived. | IMPLEMENTED |
| Automatic client observations are `OBSERVED + NON_CERTIFIED`. | GPS received by the client is not sufficient authority for certification. | IMPLEMENTED |
| Certified status must come from an authoritative validation path, ultimately server-side. | Prevent client self-certification/cheating. | PLANNED |
| Same H3 cell under different trust statuses remains distinct; cross-trust merge is forbidden. | Preserve trust semantics. | IMPLEMENTED |
| Merge keeps earliest `firstDiscoveredAt` and latest `lastObservedAt`. | Discovery history must be monotonic and chronologically correct. | IMPLEMENTED |
| Provenance follows the chronologically latest observation; exact timestamp tie uses incoming provenance. | Deterministic merge behavior. | IMPLEMENTED |
| `engineVersion` and `h3Resolution` are frozen at record creation. | Prevent silent reinterpretation of historical data. | IMPLEMENTED |
| Every location in an Android batch is processed with its own timestamp. | Background APIs may batch observations. | IMPLEMENTED |
| One canonical history feeds Easy/Standard/Hard; never create separate discovery histories per difficulty. | Avoid divergent truth sets. | DECIDED / NOT IMPLEMENTED |
| Standard is the default/reference difficulty. | Established product decision. | DECIDED / NOT IMPLEMENTED |
| Physical presence colors territory; percentage represents exploration depth/completion, not merely whether an area was touched. | Makes progression meaningful. | DECIDED / NOT IMPLEMENTED |
| Geographic progress rolls up through World → Continent → Country → local country-specific levels. | Global consistency without imposing one administrative model. | DECIDED / NOT IMPLEMENTED |
| IP is never sufficient proof of physical discovery. | It does not prove physical presence. | DECIDED / NOT IMPLEMENTED |
| Local observations are retained offline in Room. | Normal discovery cannot depend on connectivity. | IMPLEMENTED |
| Local observations/cells synchronize later without destroying monotonic history. | Backup and multi-device use. | DECIDED / NOT IMPLEMENTED |
| Community/social activity must not alter exploration percentage, XP authority or Certified score. | Keep social and geographic proof layers separate. | DECIDED / NOT IMPLEMENTED |
| Disputed borders are handled neutrally and physical geography is preserved independently of political claims. | Avoid forcing a territorial position. | DECIDED / NOT IMPLEMENTED |
| `RESTRICTED_EXCLUDED` and `UNKNOWN` remain outside the percentage denominator; `UNKNOWN` gives no bonus. | Safety and a stable 100% ceiling. | DECIDED / NOT IMPLEMENTED |
| Scores and percentages are recalculable projections, never mutable counters. | Rules and references can evolve safely. | DECIDED / NOT IMPLEMENTED |
| Historical discovery is monotonic and reference/rule changes are explicit and versioned. | Never silently erase user history. | PARTIALLY IMPLEMENTED |

Journey-specific automatic trip-boundary/editing rules still need user decisions. Exact aggregation mechanisms are **ENGINEERING DESIGN REQUIRED** and their numeric parameters are **CALIBRATION REQUIRED**, not automatically user questions. See the normative detail in `docs/discovery-engine.md`.
