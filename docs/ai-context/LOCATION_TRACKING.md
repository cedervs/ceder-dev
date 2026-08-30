# Location Tracking

## Foreground — IMPLEMENTED
Process foreground lifecycle starts the automatic tracking session; leaving foreground stops it before background registration is considered. A **user-triggered one-shot current location** also exists from phase 2; it is not a manual discovery-entry workflow.

If the initial foreground session ends because permission was missing, a grant returned through `MapScreen`'s permission launcher explicitly calls `retryLocationTrackingAfterPermissionGranted()` and can restart the session without restarting the app. The code does not implement a general continuous observer for arbitrary permission changes made externally in Android Settings; lifecycle re-entry can provide another start opportunity.

## Background — IMPLEMENTED
Uses Google Fused Location Provider with a `PendingIntent`, not a permanent foreground service. Background tracking is off unless explicit application consent exists and Android permissions are sufficient. Registrar rechecks actual OS permission every time.

Current provisional configuration: Balanced Power; target interval 20 min; minimum 10 min; maximum batching delay 30 min. Android may delay/batch delivery; these are not exact scheduling guarantees.

## Batch behavior — IMPLEMENTED
A `LocationResult` can contain multiple locations. Process **all** of them, ordered safely, preserving each `Location.time`. Do not regress to `lastLocation` and do not stamp the whole batch with `Instant.now()`.

## Lifecycle/reboot — IMPLEMENTED
Foreground and background tracking are coordinated by the existing controller. `BootCompletedReceiver` may re-arm background tracking after reboot when consent and permissions permit.

## Permission changes — IMPLEMENTED
Consent and OS permission are different states. The registrar rechecks background permission before registration, and Android permission remains authoritative even when consent stays stored.

## Device validation — HISTORICALLY REPORTED / NOT REPOSITORY-PROVABLE
Foreground/background tracking, transitions, reboot re-arm, background permission downgrade and foreground permission revoke/re-grant recovery were reported as physically validated on a Samsung device before `7a906a9`. The implementation is consistent with those reports, but Git and source code alone cannot prove the physical executions occurred.

## Force-stop — platform limitation / accepted
Do not try to bypass Android force-stop. Tracking resumes only after manual relaunch.

## Privacy/trust
Coordinates are transient; derived H3 discovery state is persisted. Current automatic observations are `OBSERVED + NON_CERTIFIED`.

## Deliberately not used
- permanent foreground service for normal background discovery;
- WorkManager GPS polling;
- battery-optimization exemption prompt;
- continuous high-accuracy background GPS.

## Long-term behavior — PLANNED
Tracking should remain adaptive/battery-conscious. Conservative reconstruction after temporary signal loss is **DECIDED / NOT IMPLEMENTED** in principle, including preservation of provenance and server validation for any Certified result. The exact fusion design is **ENGINEERING DESIGN REQUIRED** and durations/confidence thresholds are **CALIBRATION REQUIRED**. IP alone is never proof.
