# Architecture Decisions

## Modular Android architecture
**Decision:** Keep `app`, `core-*` and `feature-*` responsibilities separated.
**Reason:** Location, domain, persistence, auth and UI evolve independently.
**Status:** IMPLEMENTED.
**Do not change unless:** a concrete dependency/maintainability problem justifies restructuring.

## One discovery-domain pipeline
**Decision:** foreground, background and one-shot observations converge on `SubmitDiscoveryObservation`/discovery-engine semantics.
**Reason:** prevent duplicated H3/merge rules.
**Status:** IMPLEMENTED.
**Do not change unless:** a new observation type genuinely requires a versioned domain rule.

## H3 canonical representation
**Decision:** H3 v4.5.0, canonical v1 resolution 12; Android uses `h3-android` and `H3Core.newSystemInstance()`.
**Reason:** stable fine-grained geographic identity; Android native loading issue was solved by the system-instance path.
**Status:** IMPLEMENTED / resolution policy provisional-versioned.
**Do not change unless:** migration/versioning and historical compatibility are designed.

## Local-first persistence
**Decision:** Room stores derived discovery state; raw GPS history is not persisted.
**Reason:** privacy, offline travel and smaller durable data model.
**Status:** PARTIALLY IMPLEMENTED: Room derived-state persistence exists; aggregation/reference layers remain future work.
**Do not change unless:** explicit privacy/data architecture approval.

## Backend
**Decision:** Authentication backend uses FastAPI/PostgreSQL according to the current repository audit; geographic sync/PostGIS discovery backend is future work.
**Reason:** separate account/auth foundation from later geographic synchronization.
**Status:** auth backend IMPLEMENTED; geographic sync DECIDED / NOT IMPLEMENTED.
**Do not change unless:** inspect repository first; code is authoritative.

## Authentication
**Decision:** preserve existing Google Sign-In / email OTP architecture and backend boundary rather than duplicating auth in feature code.
**Reason:** centralized identity/security.
**Status:** IMPLEMENTED for the current Google/OTP foundation; additional account surfaces remain future work.
**Do not change unless:** auth requirements explicitly change.

## Android background location
**Decision:** Fused Location Provider + PendingIntent, persisted explicit consent, real permission check, lifecycle coordination, boot receiver.
**Reason:** battery-conscious background delivery without permanent foreground service.
**Status:** IMPLEMENTED.
**Do not change unless:** measured platform reliability/product requirements demand a different model.

## Offline/online
**Decision:** discovery should work offline locally and synchronize later.
**Reason:** travel frequently has intermittent connectivity.
**Status:** Room-backed local side IMPLEMENTED; server/client geographic sync DECIDED / NOT IMPLEMENTED; concrete protocol ENGINEERING DESIGN REQUIRED.
**Do not change unless:** offline capability remains preserved.

## Trust/security boundary
**Decision:** client observations cannot self-certify.
**Reason:** integrity/anti-cheat.
**Status:** client Non-certified boundary IMPLEMENTED; server certification DECIDED / NOT IMPLEMENTED.
**Do not change unless:** never weaken the authority boundary.

## Derived state, monotonicity and versioning
**Decision:** historical discovery is monotonic; displayed scores/percentages are recalculable projections, never mutable counters. Engine, aggregation, eligibility and geographic references evolve explicitly and versionedly without silently deleting history.
**Reason:** rules and datasets must evolve without corrupting years of user discovery.
**Status:** merge/history foundation IMPLEMENTED; broader aggregation/reference system DECIDED / NOT IMPLEMENTED.

## External geographic data
**Decision:** keep the provider replaceable and use the established hybrid direction (Natural Earth, geoBoundaries, OpenStreetMap, Overture where appropriate, plus a World Discovery correction layer); GADM is not the primary foundation.
**Reason:** global coverage, local detail, correct licensing and versioned corrections.
**Status:** DECIDED / NOT IMPLEMENTED; ingestion and mapping pipeline ENGINEERING DESIGN REQUIRED.

## Social/engagement separation
**Decision:** private memories, official XP/notable places, community landmarks and geographic discussions are distinct future systems. None may alter exploration percentage or Certified score; participation must not reveal a user's current/precise position.
**Reason:** preserve privacy and the authority of geographic discovery.
**Status:** DECIDED / NOT IMPLEMENTED. See `docs/product-spec.md` §7 and `docs/discovery-engine.md` §§15, 18, 21–22.

## Internationalization and presentation boundary
**Decision:** user-facing text uses localized resources; language/theme/units never affect discovery, H3, scores or Certified semantics.
**Reason:** presentation must not leak into domain truth.
**Status:** English/French resource foundation IMPLEMENTED; synchronized language settings and broader locale behavior DECIDED / NOT IMPLEMENTED. See `docs/architecture.md` §9.
