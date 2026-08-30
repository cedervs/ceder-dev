# Glossary

**World Discovery** — the application/product: a lifelong personal map of physically explored geography.

**Discovery** — durable geographic knowledge that the user has explored an area, represented canonically by derived geographic data rather than a permanent raw GPS breadcrumb trail.

**Observation** — time-associated evidence submitted to the discovery engine. Current automatic GPS observations use `OBSERVED` provenance and `NON_CERTIFIED` trust.

**H3** — hierarchical hexagonal geospatial indexing system used for canonical discovery cells. Current canonical resolution is 12.

**H3 cell / discovered cell** — canonical geographic cell used to represent discovered territory.

**Certified** — authoritative trust state intended for discoveries validated by the appropriate server/evidence rules. Client GPS alone cannot assign it.

**Non-certified** — discovery not authoritatively certified. Current automatic client tracking produces this state.

**Provenance** — how an observation/discovery originated. Vocabulary discussed includes observed, reconstructed, imported and manual.

**Observed** — provenance for direct current location observations.

**Reconstructed** — future provenance for conservatively inferred missing route/location segments; exact rules undecided.

**Journey** — product representation of a trip/travel episode derived from discovery history; not synonymous with a raw GPS trace.

**Progress / progression** — exploration completion/depth derived from canonical discovery history and aggregated geographically.

**Standard / Easy / Hard** — planned difficulty interpretations of the same canonical discovery history; Standard is default. Exact formulas remain undecided.

**Foreground tracking** — automatic location collection while the app/process is foregrounded.

**Background tracking** — consented low-frequency Fused Location delivery while the app is not foregrounded.

**Consent** — persisted application-level opt-in for background discovery; distinct from Android OS permission.

**Trust status** — Certified vs Non-certified classification. It participates in persistence identity and must not be silently merged across states.

**Canonical history** — single underlying discovery truth from which map/progress/difficulty views should be derived.

**Eligible** — canonical area allowed to participate in exploration progress under a versioned eligibility reference.

**Restricted excluded** — unsafe, prohibited or inaccessible area excluded from both numerator and denominator; never required for 100%.

**Unknown eligibility** — not yet classified; contributes neither requirement nor bonus and remains outside the denominator.

**Derived/recalculable projection** — score, percentage, statistic or Certified cell view computed from canonical/authoritative history rather than treated as a mutable source-of-truth counter.

**Engineering design required** — product behavior is established, but the technical mechanism still needs design.

**Calibration required** — a configurable/versioned value must be selected through reproducible measurement or testing.
