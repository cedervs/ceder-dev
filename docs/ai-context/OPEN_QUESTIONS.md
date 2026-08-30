# Open Questions

Only genuine unresolved product decisions belong in the first section. Already-decided behavior is summarized elsewhere and routed to its normative specification.

## NEEDS USER CONFIRMATION

### Final Map art direction and product-specific interaction
Known: Map is core, visualizes derived discovered territory, is not a breadcrumb viewer, and keeps trust distinguishable. Confirm the final illustrated/minimal visual language and any product-specific interaction behavior before locking final UX. Provider integration and data flow remain engineering design.

### Journey product semantics
Known: Journey presents understandable trips rather than raw GPS traces. Confirm start/end semantics, merging, editing, naming, manual overrides and timeline behavior before implementing the Journey domain.

### Account expansion and recovery
Confirm whether Apple/other providers are required, and define an explicit recovery/merge procedure for two already-distinct accounts before implementing either. Current Google/OTP linking behavior is implemented and is not open.

### Account deletion/export/privacy UX
Export and deletion are required before production, but their final UX, retention consequences and privacy workflow require confirmation before implementation.

### Certified policy parameters
Before implementing competitive Certified behavior, confirm evidence sufficiency, state-transition criteria, appeals/reclassification, offline deferred-validation policy and the product threshold for enabling rankings. The server-authority model, separation from Normal, recalculability and historical-evidence principles are already decided.

### Historical recovery policy
Confirm the evidence bar, assistance/appeal UX and AI guardrails beyond the already-decided principles that evidence proves only what it reasonably demonstrates, AI is not absolute truth, and no facial recognition/selfie is required.

### Community safety and release scope
Before a social release, confirm moderation, reporting, blocking, spam/abuse prevention, public/private profile behavior, content editing/deletion, minors/safety policy and release timing. The distinction among private memories, XP/notable places, community landmarks and geographic discussions is already decided.

### XP and community-landmark product values
Confirm XP values/selection policy and any spendability; confirm landmark-publication thresholds, anti-duplication and moderation policy. The rule that these systems never change exploration percentage or Certified score is already decided.

## ENGINEERING DESIGN REQUIRED — do not ask by default
- map provider abstraction, local-cell read API and rendering pipeline;
- Easy/Standard/Hard aggregation mechanism consistent with established targets;
- hybrid geographic ingestion and per-country hierarchy mapping;
- eligibility resolution pipeline and versioned recomputation;
- geographic sync protocol, outbox/cursors, conflict/idempotence and PostGIS schema;
- signal fusion and conservative reconstruction algorithm;
- geometric denominator construction and partial-boundary handling.

Escalate only if engineering exposes a genuine product tradeoff not covered by normative documents.

## CALIBRATION REQUIRED — determine through tests/measurements
- Easy/Standard/Hard coefficients, radii and thresholds;
- urban density and homogeneous-terrain generalization parameters;
- presence, movement, signal-quality and noise-rejection thresholds;
- acceptable GPS-gap duration/ambiguity for reconstruction;
- eligibility reclassification thresholds;
- background cadence/battery configuration beyond the current provisional values.

These values must be configurable/versioned where required and validated with reproducible and physical-device tests, not invented or automatically escalated as user decisions.
