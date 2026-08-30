# World Discovery — AI Context

Stable baseline: `7a906a9fadb2f41129e3b5d326c634d11337ebfb`.

This directory preserves product intent and durable decisions for Codex/Claude Code. It does **not** override the repository.

## Trust hierarchy
1. Code + tests + Git history determine what actually exists.
2. `/PROJECT_STATUS.md` records the verified implementation state at the stable baseline.
3. Tracked normative documents under `/docs` preserve already-established product and architecture decisions, even when not implemented yet. In particular consult `discovery-engine.md`, `product-spec.md`, `certified-mode.md`, `architecture.md` and `roadmap.md`.
4. `docs/ai-context/*` is the persistent synthesis and routing layer; it must not erase or reopen normative decisions.
5. Ask the user only when a genuine product decision remains unresolved after applying the sources above.

## Status classification
- **IMPLEMENTED**: present in code and supported by tests and/or Git.
- **PARTIALLY IMPLEMENTED**: a real foundation exists, but the stated surface is incomplete.
- **DECIDED / NOT IMPLEMENTED**: normative product behavior is established; implementation remains future work.
- **PLANNED / NOT IMPLEMENTED**: direction exists, but important product scope is not fully fixed.
- **ENGINEERING DESIGN REQUIRED**: product behavior is sufficiently decided; implementation design remains to be produced without automatically asking the user.
- **CALIBRATION REQUIRED**: values must be established through measurements/tests rather than invented or escalated as a new product decision.
- **NEEDS USER CONFIRMATION**: a genuine unresolved product choice blocks the relevant work.
- **HISTORICALLY REPORTED / NOT REPOSITORY-PROVABLE**: a past validation was communicated but cannot be independently demonstrated from the repository alone.

A feature being unimplemented does not make its established product rules undecided. Conversely, a documented future architecture is not evidence that code already exists.

## Where to look
- `PRODUCT_VISION.md`: final product direction and feature scope.
- `FUNCTIONAL_RULES.md`: implementable product rules.
- `UX_UI_SPEC.md`: navigation and interaction decisions.
- `ARCHITECTURE_DECISIONS.md`: durable technical decisions/rationale.
- `DATA_AND_DISCOVERY_RULES.md`: geographic/discovery data semantics.
- `AUTH_AND_ACCOUNTS.md`: account/authentication decisions.
- `LOCATION_TRACKING.md`: specialized tracking reference.
- `ROADMAP.md`: completed foundation and future work.
- `REJECTED_APPROACHES.md`: approaches not to reintroduce accidentally.
- `OPEN_QUESTIONS.md`: genuine unresolved decisions only.
- `GLOSSARY.md`: project vocabulary.

If documentation and code disagree about what is implemented, trust the code and tests. If product intent conflicts with implementation, do not silently reinterpret either: flag the discrepancy.
