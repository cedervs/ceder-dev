# UX / UI Specification

## Navigation — IMPLEMENTED
Compose navigation between **Map, Journey, Progress and Profile** is implemented in `WorldDiscoveryApp`, including the bottom navigation bar and destination routes. This status covers navigation infrastructure, not feature completeness.

## Map
**PARTIALLY IMPLEMENTED:** `MapScreen` exists but is currently a location-test placeholder with permission handling and a user-triggered current-location action; it has no map engine and does not render cells. **DECIDED / NOT IMPLEMENTED:** Map is the primary surface and visualizes discovered territory rather than a raw GPS breadcrumb trace; Certified and Non-certified remain distinguishable where exposed; labels/borders are contextual only. **NEEDS USER CONFIRMATION:** final art direction and product-specific interaction treatment. Rendering/provider integration is **ENGINEERING DESIGN REQUIRED** after that scope is bounded.

## Progress
**PARTIALLY IMPLEMENTED:** destination/module placeholder exists. **DECIDED / NOT IMPLEMENTED:** show recalculable exploration depth derived from canonical history, with Standard as default/reference and Easy/Hard interpretations. Widgets/achievement UX may need confirmation when scheduled; aggregation design is an engineering task and coefficients require calibration.

## Journey
**PARTIALLY IMPLEMENTED:** destination/module placeholder exists. **DECIDED / NOT IMPLEMENTED:** present discovery as understandable journeys/trips, not permanent raw GPS traces. **NEEDS USER CONFIRMATION:** automatic segmentation, merging, editing, naming, overrides and timeline UX.

## Profile
**PARTIALLY IMPLEMENTED:** profile contains Google/OTP authentication, logout and background-discovery consent UI/disclosure. Consent is distinct from Android OS permission. When foreground permission is absent, the toggle is disabled and an explanatory error is shown. Broader account/settings/privacy/profile surfaces are not implemented.

## Permissions and consent
IMPLEMENTED: background discovery is opt-in and disclosed. Android permission remains the OS authority; persisted consent alone cannot enable tracking. Revocation/downgrade must safely stop effective background tracking. Force-stop is respected.

## Authentication / onboarding
Existing Google Sign-In and email OTP flows exist; consult code/`PROJECT_STATUS.md` for exact current screens. Broader onboarding, first-run education and final account-selection UX are **NEEDS USER CONFIRMATION** when that work is scheduled.

## Empty/error states
**PARTIALLY IMPLEMENTED:** current auth, location-test and permission flows have localized states/copy. No complete final cross-feature specification exists. Use existing resource conventions and request user confirmation only when an unresolved product behavior—not routine UX engineering—matters.
