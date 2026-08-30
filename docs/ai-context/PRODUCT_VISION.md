# Product Vision

## IMPLEMENTED
World Discovery is a personal, long-lived geographic discovery application. The current foundation records physical observations locally, converts them to H3 cells and supports automatic foreground/background discovery.

## PLANNED product vision
The end product is a personal map of the world the user has **actually explored**, not a normal GPS history viewer. Physical presence progressively colors territory; exploration percentages measure how deeply an area has been explored. The experience should make discovery feel like a lifelong progression system while remaining credible, privacy-conscious and battery-conscious.

The geographic hierarchy is flexible: **World → Continent → Country → country-specific administrative/local levels**. It must not force every country into an identical subdivision model. Disconnected islands/territories should remain geographically distinct where appropriate. Disputed borders should be presented neutrally. Labels shown for travel context are visual information, not proof of discovery.

A single canonical discovery history should support multiple difficulty interpretations rather than storing separate histories. **Standard** is the default. Earlier product targets discussed were Easy ≈ 50–60% of Standard effort and Hard ≈ 200–250%; these are product targets, not finalized formulas.

Main product areas: **Map, Journey, Progress, Profile**. Map is the core visualization. Journey should turn travel/discovery history into understandable trips rather than raw GPS traces. Progress should expose geographic completion/progression. Profile owns account/settings/privacy/tracking consent.

Discovery can be passive (automatic tracking) and active (explicit user actions/import/recovery where later supported). Normal operation should be offline-first: collect locally, derive discovery state, then synchronize when connectivity exists. Temporary signal loss may eventually allow conservative route reconstruction. Historical recovery may eventually produce Certified or Non-certified results depending on evidence and server rules. IP address alone must never prove physical presence.

Future engagement systems are **DECIDED / NOT IMPLEMENTED** as distinct concepts: private travel memories; curated notable-place XP; community-recommended landmarks; and geographic discussions. They are non-MVP, independent from core discovery/Certified authority, must not expose a user's current or precise position through participation, and must not change exploration percentage or Certified score. Private memories remain private by default unless explicitly shared. Community popularity never automatically promotes a place into the official XP set. Detailed normative rules: `docs/product-spec.md` §7 and `docs/discovery-engine.md` §§15, 18, 21–22.

## Remaining-work classification
- **NEEDS USER CONFIRMATION:** final illustrated/minimal Map art direction; Journey detection/editing UX and trip boundaries; community moderation/safety/release policy; Certified evidence/appeal/offline policy where normative documents leave product choices open.
- **ENGINEERING DESIGN REQUIRED:** Easy/Standard/Hard aggregation mechanism; hybrid geographic dataset ingestion and country-specific hierarchy mapping; neutral disputed-border implementation; sync and certification architecture details consistent with established invariants.
- **CALIBRATION REQUIRED:** final progression coefficients/thresholds, terrain generalization, signal fusion and anti-cheat thresholds that normative documents explicitly designate for measurement/testing.
