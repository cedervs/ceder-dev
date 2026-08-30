# Rejected Approaches

## Permanent foreground service for ordinary background discovery
**Rejected approach:** always-on FGS/notification.
**Why:** unnecessary for the selected battery-conscious provisional model.
**Replacement/current decision:** Fused Location Provider PendingIntent background updates.
**Do not reintroduce unless:** measured Android reliability or a new explicit requirement proves it necessary.

## WorkManager GPS polling
**Why rejected:** wrong abstraction for repeated location polling and would duplicate platform location scheduling.
**Replacement:** Fused background updates.
**Do not reintroduce unless:** used for a genuinely different deferred job, not as the tracking engine.

## Battery-optimization exemption prompts
**Why rejected:** aggressive/unnecessary at current requirements.
**Replacement:** work within normal Android scheduling.
**Do not reintroduce unless:** evidence shows a critical supported use case cannot work otherwise.

## Continuous high-accuracy background GPS
**Why rejected:** battery cost conflicts with lifelong passive discovery.
**Replacement:** Balanced Power, sparse/adaptive direction.
**Do not reintroduce unless:** explicit mode/product requirement with battery/privacy review.

## Persistent raw GPS history
**Why rejected:** unnecessary privacy exposure and contrary to derived-cell architecture.
**Replacement:** transient coordinates → H3-derived discovery state.
**Do not reintroduce unless:** explicit data/privacy architecture decision.

## Client-side certification
**Why rejected:** client GPS cannot provide authoritative anti-cheat proof.
**Replacement:** current automatic observations remain Non-certified; server validation planned.
**Do not reintroduce unless:** never weaken the trust boundary.

## Default/problematic Android H3 initialization
**Why rejected:** caused Android native-loading/integration failure.
**Replacement:** `h3-android` 4.5.0 + `H3Core.newSystemInstance()`.
**Do not reintroduce unless:** verified library/API change removes the issue.

## `LocationResult.lastLocation` only
**Why rejected:** drops other positions in a batched background result.
**Replacement:** process every `result.locations` entry.
**Do not reintroduce unless:** never for batched delivery.

## `Instant.now()` for every location in a received batch
**Why rejected:** destroys real observation chronology.
**Replacement:** each `Location.time`.
**Do not reintroduce unless:** reception time is explicitly a different field, never a substitute for observation time.

## Separate histories for Easy/Standard/Hard
**Rejected approach:** divergent discovery truth per difficulty.
**Why:** one physical history should remain canonical.
**Replacement:** derive difficulty views/requirements from one history.
**Do not reintroduce unless:** product model is explicitly redesigned.

## GADM as the primary global geographic foundation
**Why rejected:** its licensing is not suitable as the principal foundation for the intended commercial/global use without additional permission.
**Replacement:** versioned hybrid direction using Natural Earth, geoBoundaries, OpenStreetMap, Overture where useful, and a World Discovery correction layer.
**Do not reintroduce unless:** licensing and product/legal suitability are explicitly reassessed.

## Mutable score counters
**Rejected approach:** directly incrementing persisted exploration/Certified scores (`score += x`) as authority.
**Why:** rule, evidence and reference changes must remain recalculable.
**Replacement:** derive projections from canonical discovery or authoritative Certified events.

## Automatic Normal-to-Certified promotion
**Why rejected:** Normal and Certified are separate datasets and client history cannot become authoritative retroactively.
**Replacement:** every Certified result follows server validation; historical evidence may create a candidate, never an automatic promotion.

## Public-profile data as recovery proof
**Why rejected:** pseudo, avatar, represented country and other public data do not establish account control.
**Replacement:** authentication identities and optional verified recovery contacts remain isolated from public profile data.

## Password-based World Discovery e-mail authentication
**Why rejected:** the established e-mail direction is one-time OTP, not a conventional application password.
**Replacement:** existing email OTP flow and centralized backend session architecture.
