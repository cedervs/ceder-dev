# Roadmap

Stable starting point: `7a906a9fadb2f41129e3b5d326c634d11337ebfb`.

## Completed
- **Phase 1:** local discovery engine + H3/Room foundation.
- **Phase 2:** foreground one-shot location discovery.
- **Phase 3:** automatic foreground tracking.
- **Phase 4:** background tracking, consent/permissions, reboot handling and batch correctness.

Authentication (Google + email OTP and backend work) predates/exists alongside these discovery phases; consult Git for exact chronology.

## Next planned work
**Map/discovery visualization layer:** read existing local discovery cells and render discovered territory in `feature-map` without changing collection semantics. Preserve H3 res 12, trust distinction and raw-GPS privacy. This depends on existing repository/DAO APIs and should begin by inspecting them.

Current factual constraints at the baseline:
- `DiscoveredCellDao.getAll()` exists and returns a one-shot list of entities;
- the domain `DiscoveredCellRepository` exposes only `find` and `upsert`;
- no reactive/query API intended for `feature-map` exists yet at the repository boundary;
- `MapScreen` is a location-test placeholder and no mapping/rendering engine is integrated.

These constraints do not prescribe the solution. Do not design the phase from this memory alone; inspect the APIs and produce a focused plan first.

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

Dependencies: visualization depends on stable local discovery data; progress/journeys depend on canonical history; certification and cross-device authoritative sync depend on backend rules; community should not be allowed to redefine the core discovery engine.
