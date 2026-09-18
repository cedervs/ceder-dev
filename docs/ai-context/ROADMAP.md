# Roadmap

Stable starting point: `7a906a9fadb2f41129e3b5d326c634d11337ebfb` (historical Phase 1--4 baseline).
Current pushed baseline: `d54f0c77bb376c558778232cb28219ddd8fe1ef5` --- verify with `git log`/`git rev-parse HEAD` rather than assuming this stays current; see `PROJECT_STATUS.md` §19 for the full commit chain and what each stabilization commit landed.

## Completed
- **Phase 1:** local discovery engine + H3/Room foundation.
- **Phase 2:** foreground one-shot location discovery.
- **Phase 3:** automatic foreground tracking.
- **Phase 4:** background tracking, consent/permissions, reboot handling and batch correctness.
- **Map/discovery visualization layer:** MapLibre Native renders discovered H3 cells and mainland France's visited-country overlay; physically validated on-device for the items itemized in `docs/ai-context/ARCHITECTURE_DECISIONS.md`'s "Map rendering engine" entry.
- **France administrative hierarchy (Country → Region → Department) + derived first-discovery corridor:** classification, rendering, and hierarchy-aware navigation implemented and unit-tested for all 13 metropolitan regions and all 96 of their departments — full metropolitan coverage (see `PROJECT_STATUS.md` §17 Phase H and Phase FH-1 for exact scope and physical-validation status). Functional physical validation passed on-device: Nouvelle-Aquitaine/Haute-Vienne's existing visited behavior, plus navigation into an unvisited region (Grand Est) and an unvisited department within it, confirming unvisited administrative areas are genuinely navigable, not just the previously-visited one. Final visual/design calibration (labels, contrast, zoom thresholds, basemap) remains future work, not yet done.
- **Trajectory buffering + Reconstruction Safety Gate foundation:** accepted, inactive, not wired into live tracking (`PROJECT_STATUS.md` §20/§21). No matcher selected.
- **Map-matching benchmark study (Phase 2A/2B):** closed engineering evidence comparing OSRM/Valhalla/GraphHopper; no engine chosen, no integration.

Authentication (Google + email OTP and backend work) predates/exists alongside these discovery phases; consult Git for exact chronology.

## Next planned work
**Completed:** the France hierarchy/corridor's FUNCTIONAL behavior has passed physical validation on-device (see `PROJECT_STATUS.md` §17's Phase FH-1 "second physical validation" entry) — both the existing visited branch (Nouvelle-Aquitaine/Haute-Vienne) and an unvisited branch (Grand Est and one of its departments) were confirmed navigable, with visited styling and existing discovered H3 unaffected. This was a sampled functional check, not individual physical testing of every one of the 13 regions/96 departments (that complete coverage is validated automatically/by data, not by physical device testing of each area).

**Still open** (none of these are "re-validate the hierarchy" — that already passed):
- visual hierarchy calibration: Department-name labels, unvisited-area contrast, and deliberate per-level zoom-visibility thresholds (Country/Region/Department/H3/labels) — see `PROJECT_STATUS.md` §17's Phase FH-1 physical-validation UX observations for the exact items;
- final basemap/style system — blocked on a genuine product decision (`NEEDS USER CONFIRMATION` in `OPEN_QUESTIONS.md`'s "Final Map art direction" entry); the current OpenFreeMap Liberty style remains temporary/not final;
- corridor/tracking visual behavior (H3 cell density, raw observation clutter, route fragmentation) — observed during Phase FH-1's physical validation, not itself an FH-1 defect;
- trajectory reconstruction/map-matching (§20/§21 above) — accepted, inactive, no matcher selected, no current product urgency;
- other explicitly documented future phases below.

The next candidate PHASES beyond the visual-calibration/basemap work above, in dependency order (do not start more than one without an explicit decision — see `PROJECT_STATUS.md`'s own next-phase analysis for the full reasoning):

1. **Worldwide hierarchy generalization (first non-France country)** — proves the `GeographicArea`/`parentId` architecture is genuinely country-agnostic; `ENGINEERING DESIGN REQUIRED` for per-country ingestion/mapping (see `docs/ai-context/ARCHITECTURE_DECISIONS.md`'s "External geographic data" entry).
2. **World Discovery basemap/style system** — blocked on a genuine product decision (`NEEDS USER CONFIRMATION` in `OPEN_QUESTIONS.md`'s "Final Map art direction" entry) before implementation; the selection-relative color-tier logic is already built and basemap-independent.
3. **Local geographic area layer below Department** — `GeographicAreaType.LOCALITY`/`.ZONE` exist in the domain model with no data/classification/rendering yet; needs its own product+data-source decision, best attempted after (1) informs whether a single local-area model generalizes across countries.

Trajectory reconstruction (§20/§21 above) has no dependency on 1--3 and could proceed in parallel, but requires a significant, separate matcher-selection decision and has no current product urgency — not part of this near-term sequence.

## Future work already discussed (not assigned invented phase numbers)
- hierarchical progress: World → Continent → Country → country-specific levels;
- Standard/Easy/Hard derived from one canonical history;
- Journey/trip experience built from discovery history;
- geographic synchronization/backend and server authority for Certified discovery;
- offline-to-online reconciliation;
- historical import/recovery and conservative reconstruction rules;
- richer Progress and Profile experiences;
- future community layer (spots/memories/XP), explicitly non-MVP and separate from Certified exploration percentage;
- possible additional account providers such as Apple, if later confirmed.

Dependencies: progress/journeys depend on canonical history; certification and cross-device authoritative sync depend on backend rules; community should not be allowed to redefine the core discovery engine.
