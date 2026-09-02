package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Provenance
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.TrustStatus
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Provisional, **CALIBRATION REQUIRED** UI-only staleness window for the live current-position
 * marker (see [LocationTrackingSession.currentObservation]'s doc comment) — three configured
 * foreground acquisition cycles (`3 x 7_000L`), chosen to comfortably cover the ~16.4s legitimate
 * inter-fix delay physically observed on Trip 3, with margin. **Not** a GPS acceptance threshold,
 * a filtering threshold, a discovery threshold, or a reconstruction threshold — purely how long
 * the marker may keep showing the last successfully received fix before a sustained
 * [LocationAcquisitionResult.LocationUnavailable] makes it disappear. See
 * `docs/ai-context/LOCATION_TRACKING.md`.
 */
private const val POSITION_STALENESS_WINDOW_MILLIS = 21_000L

/**
 * Controls one in-app-session location tracking session: collects [LocationUpdatesProvider]'s
 * stream while active and forwards every successful observation to the existing
 * [SubmitDiscoveryObservation] entry point, unchanged (same [Provenance.OBSERVED] /
 * [TrustStatus.NON_CERTIFIED] as the one-shot debug path — nothing here can produce a Certified
 * record, per certified-mode.md §1). Submits with the fix's own [LocationObservation.observedAt]
 * (from `Location.time`), never `Instant.now()` at processing time — see
 * `docs/ai-context/LOCATION_TRACKING.md`.
 *
 * Deliberately lifecycle-agnostic: [start]/[stop] are the only surface, driven by whatever caller
 * owns the lifecycle decision (this phase: application-foreground, see
 * `AppForegroundTrackingController`). Not tied to any Android component, screen, or ViewModel, so
 * it's directly testable with a fake [LocationUpdatesProvider] and a plain [CoroutineScope].
 *
 * Permission/location-services state is never re-checked here — [LocationUpdatesProvider] is the
 * single authoritative source for that; this class only reacts to what it emits. A terminal
 * [LocationAcquisitionResult.PermissionDenied] relies on the provider closing its own flow
 * afterward (see [LocationUpdatesProvider]'s contract) rather than this class cancelling its own
 * collecting job from within itself.
 *
 * ## Transition diagnostics (debug-only)
 * If [transitionDiagnostics] is supplied, each collector started by [start] keeps its own local
 * "most recent observation" (see the `previousObservation` local variable inside [collectForGeneration])
 * purely so a second, later observation can be paired with it and logged as a transition — never
 * persisted, never shared across collectors, never treated as a validated "anchor": it exists only
 * to form a pair for [ForegroundTransitionDiagnostics], which itself never evaluates eligibility
 * and never produces a [ReconstructionCandidate]. Deliberately local to the coroutine launched by
 * each [start] call rather than a field on this class: a fresh collector always begins with no
 * previous observation by construction, so [stop] needs no separate cleanup step for it, and no
 * stop/restart interleaving can ever reinject a stale pre-stop observation into a new collector —
 * there is no shared mutable state left for such a race to act on.
 *
 * Submission to [submitDiscoveryObservation] always happens first and is never delayed, blocked,
 * or made conditional on either diagnostic: see [submitObservationSafely]'s ordering and its doc
 * comment for the [CancellationException] boundary this implies.
 *
 * ## Live current-position UI state — [currentObservation]
 * A second, independent read-side view of this session, entirely separate from the discovery
 * pipeline: "where am I right now" (this) versus "what have I discovered" (the H3/discovery
 * pipeline this class also feeds) are deliberately different concerns that must never be conflated
 * — `currentObservation` is never derived from, and never derives, any H3/discovered-cell state.
 * It is transient, in-memory UI state only: never persisted, never logged with its raw coordinate
 * (see `docs/ai-context/LOCATION_TRACKING.md`).
 *
 * Publication happens *before* [submitObservationSafely] is called on a [LocationAcquisitionResult.Success]
 * (see [collectForGeneration]) — the live marker is never delayed by, or coupled to, discovery
 * submission or diagnostic outcomes, the same independence principle already applied to submission
 * versus diagnostics.
 *
 * ## Staleness grace window (best-effort availability vs. immediate clears)
 * [LocationAcquisitionResult.LocationUnavailable] (see `FusedLocationUpdatesProvider`'s
 * `onLocationAvailability`) is a best-effort, transient FLP signal — it does **not** by itself
 * clear [currentObservation]. Physical testing showed FLP can report brief unavailability blips
 * between otherwise-healthy fixes even under stable conditions, which — with an immediate clear —
 * made the marker visibly flicker roughly every acquisition cycle. Instead, `LocationUnavailable`
 * schedules **at most one** clear at a *fixed deadline*: [POSITION_STALENESS_WINDOW_MILLIS] after
 * the most recently *received* `Success` (tracked via [lastSuccessReceiptMillis], measured with
 * [monotonicClock] — never wall-clock, and never [LocationObservation.observedAt], which is an
 * Android-reported fix timestamp that can itself be old, zero, or otherwise unreliable). Repeated
 * `LocationUnavailable` events never reschedule or extend that deadline — only a new `Success`
 * establishes a new one. If no `Success` has occurred yet this session, no timer is scheduled at
 * all (there is nothing to be stale relative to). `PermissionDenied`, `LocationServicesDisabled`,
 * [stop], and unexpected collector termination all still clear **immediately**, unaffected by any
 * pending grace window — see [publishCurrentObservation].
 *
 * ### Session-generation ownership (race safety)
 * Because [scope] dispatches onto a real thread pool (not necessarily the same thread that calls
 * [start]/[stop]), and coroutine cancellation is cooperative (a collector already past its last
 * suspension point keeps running until the next one, even after [stop] has been called), a plain
 * `job.cancel()` plus a direct `_currentObservation.value = null` is **not** sufficient to prevent
 * an obsolete collector from publishing a stale observation after [stop], or an old generation's
 * cleanup from clobbering a newer session's already-published position. [positionLock] is the sole
 * owner of `(activeGeneration, currentObservation, lastSuccessReceiptMillis, pendingClearJob,
 * job)`:
 * - [start] and [stop] are the *only* places allowed to write `_currentObservation.value` directly
 *   — both do so inside a `synchronized(positionLock)` block that also owns the generation/job
 *   transition, so all of this state can never be observed in an inconsistent combination.
 * - Every write a collector itself performs — on `Success`, `PermissionDenied`,
 *   `LocationServicesDisabled`, and the collector's own termination cleanup — goes through
 *   [publishCurrentObservation], which re-checks `generation == activeGeneration` inside the same
 *   lock immediately before writing. An old generation's call, however delayed, always compares
 *   against the *current* value of `activeGeneration` the instant it acquires the lock, so it can
 *   never observe or act on a stale pre-[stop] state.
 * - The delayed stale-clear scheduled by `LocationUnavailable` (see [scheduleStaleClearIfNeeded])
 *   needs a *finer-grained* guard than generation alone, since a superseding `Success` can arrive
 *   within the *same* generation: the delayed coroutine checks, atomically under [positionLock] at
 *   fire time, both `generation == activeGeneration` **and** that it is still reference-identical
 *   to [pendingClearJob] (via `coroutineContext[Job]`) before writing `null`. Any newer event —
 *   `Success`, an immediate clear, or [stop] — nulls or replaces [pendingClearJob] first (through
 *   [publishCurrentObservation] or directly), so a stale timer that races past cancellation and
 *   reaches the lock anyway will see it no longer owns the clear and correctly no-op.
 * - The pending stale-clear [Job] is never the collector [Job] — they are separate coroutines
 *   launched independently. Cancelling one can never cancel the other: acquisition, the location
 *   flow, discovery submission, and the collector itself are entirely unaffected by a stale-clear
 *   timer being scheduled, superseded, or cancelled.
 * - No suspending call is ever made while [positionLock] is held (`MutableStateFlow.value =` and
 *   `CoroutineScope.launch` are both non-suspending), so a plain JVM monitor is safe here — no risk
 *   of releasing it from a different thread than acquired it. Where a captured [Job] needs
 *   cancelling as a result of a lock-protected ownership change, that capture happens under the
 *   lock but the actual `.cancel()` call happens just after releasing it (see [stop] and
 *   [publishCurrentObservation]) — `.cancel()` itself never suspends either, so this split is a
 *   matter of keeping the critical section minimal, not a correctness requirement.
 */
class LocationTrackingSession(
    private val locationUpdatesProvider: LocationUpdatesProvider,
    private val submitDiscoveryObservation: SubmitDiscoveryObservation,
    private val scope: CoroutineScope,
    private val diagnosticLogger: LocationDiagnosticLogger = NoOpLocationDiagnosticLogger(),
    private val transitionDiagnostics: ForegroundTransitionDiagnostics? = null,
    private val monotonicClock: MonotonicClock = SystemMonotonicClock(),
) {
    private val _state = MutableStateFlow<TrackingSessionState>(TrackingSessionState.Idle)
    val state: StateFlow<TrackingSessionState> = _state.asStateFlow()

    private val _currentObservation = MutableStateFlow<LocationObservation?>(null)

    /** The most recent [LocationObservation] this session's active generation has published, or
     * `null` before the first fix, after [stop], or while location is transiently unavailable —
     * see the class doc comment's "Live current-position UI state" section for the full contract. */
    val currentObservation: StateFlow<LocationObservation?> = _currentObservation.asStateFlow()

    /** Sole owner of `(activeGeneration, currentObservation, lastSuccessReceiptMillis,
     * pendingClearJob, job)` — see the class doc comment. */
    private val positionLock = Any()
    private var activeGeneration: Long = 0L
    private var job: Job? = null

    /** [monotonicClock] time (never wall-clock, never [LocationObservation.observedAt]) of the
     * most recently *received* `Success` for the active generation, or `null` if none has arrived
     * yet — the basis [scheduleStaleClearIfNeeded] computes its fixed deadline from. Guarded by
     * [positionLock]; only ever written by [publishCurrentObservation] and [start]/[stop]. */
    private var lastSuccessReceiptMillis: Long? = null

    /** The single scheduled stale-clear coroutine, if any — see the class doc comment's
     * "Staleness grace window" and "Session-generation ownership" sections. Guarded by
     * [positionLock]; a non-null value means a clear is already scheduled for the current
     * [lastSuccessReceiptMillis] deadline, which is exactly what makes repeated
     * `LocationUnavailable` events a no-op rather than rescheduling anything. */
    private var pendingClearJob: Job? = null

    /** No-op if a session is already active. */
    fun start() {
        var staleClearJobToCancel: Job? = null
        synchronized(positionLock) {
            if (job?.isActive == true) return

            val generation = ++activeGeneration
            _currentObservation.value = null
            lastSuccessReceiptMillis = null
            staleClearJobToCancel = pendingClearJob
            pendingClearJob = null
            _state.value = TrackingSessionState.Active
            job = scope.launch {
                try {
                    collectForGeneration(generation)
                } finally {
                    // Catch-all: runs on normal flow completion, any thrown exception, and job
                    // cancellation alike. Generation-guarded via publishCurrentObservation, so an
                    // old generation's delayed finally can never clear a newer session's marker —
                    // see the class doc comment.
                    publishCurrentObservation(generation, null)
                }
            }
        }
        // Defensive — see the class doc comment: by construction this is already null by the time
        // start() runs again (stop()/the finally above already clear it), but every session begins
        // from a verifiably clean slate rather than one inferred from trusting prior cleanup.
        staleClearJobToCancel?.cancel()
    }

    /** Cancels the active collection, if any, and resets to [TrackingSessionState.Idle]. Nothing
     * else to clean up: the diagnostic pairing state lives inside the cancelled coroutine itself
     * (see [collectForGeneration]) and is simply discarded with it. */
    fun stop() {
        val jobToCancel: Job?
        val staleClearJobToCancel: Job?
        synchronized(positionLock) {
            activeGeneration++
            _currentObservation.value = null
            lastSuccessReceiptMillis = null
            staleClearJobToCancel = pendingClearJob
            pendingClearJob = null
            jobToCancel = job
            job = null
        }

        // Two independent Jobs, cancelled outside the lock — the pending stale-clear timer is
        // never the collector itself, so cancelling one can never affect location acquisition,
        // the location flow, discovery submission, or the other.
        staleClearJobToCancel?.cancel()
        jobToCancel?.cancel()
        _state.value = TrackingSessionState.Idle
    }

    /** Collects [locationUpdatesProvider]'s stream for one session generation. Every
     * `currentObservation` write below goes through [publishCurrentObservation] — never a direct
     * `_currentObservation.value =` — so it is always generation-guarded; see the class doc
     * comment for why that matters. [TrackingSessionState] is updated directly here (not
     * generation-gated in this increment — a separate, deliberately out-of-scope hardening item,
     * see `docs/ai-context/LOCATION_TRACKING.md`). */
    private suspend fun collectForGeneration(generation: Long) {
        var previousObservation: LocationObservation? = null

        locationUpdatesProvider.observeLocationUpdates().collect { result ->
            when (result) {
                is LocationAcquisitionResult.Success -> {
                    _state.value = TrackingSessionState.Active
                    publishCurrentObservation(generation, result.observation)
                    val submitted = submitObservationSafely(result.observation, previousObservation)
                    previousObservation = submitted
                }

                LocationAcquisitionResult.PermissionDenied -> {
                    _state.value = TrackingSessionState.PermissionDenied
                    publishCurrentObservation(generation, null)
                }

                LocationAcquisitionResult.LocationServicesDisabled -> {
                    _state.value = TrackingSessionState.LocationServicesDisabled
                    publishCurrentObservation(generation, null)
                }

                // Transient, best-effort FLP availability loss (see FusedLocationUpdatesProvider's
                // onLocationAvailability) — NOT equivalent to the user disabling Location Services
                // at the OS level (LocationServicesDisabled, above). A single dropped/unavailable
                // fix must not stop an otherwise active session (discovery-engine.md leaves
                // movement/sampling thresholds open — this is not the place to add filtering
                // logic), and discovery submission is entirely untouched here. Does NOT clear the
                // marker immediately — see the class doc comment's "Staleness grace window" and
                // scheduleStaleClearIfNeeded.
                LocationAcquisitionResult.LocationUnavailable -> {
                    scheduleStaleClearIfNeeded(generation)
                }

                is LocationAcquisitionResult.Error -> Unit
            }
        }
    }

    /**
     * The only function, besides [start]/[stop] themselves, permitted to write
     * `_currentObservation.value` — always inside [positionLock], always re-checking that
     * [generation] is still the active one immediately before writing. See the class doc comment's
     * "Session-generation ownership" section for the full race-safety argument.
     *
     * Every call — a fresh `Success` or any immediate clear — supersedes any pending stale-clear
     * timer: [pendingClearJob] is captured and nulled here so that timer can never later fire
     * against whatever this call just published (or cleared), and [lastSuccessReceiptMillis] is
     * updated to match: set to "now" for a real [observation], or `null` when clearing, since a
     * cleared marker has no freshness basis left until a new `Success` arrives.
     */
    private fun publishCurrentObservation(generation: Long, observation: LocationObservation?) {
        var staleClearJobToCancel: Job? = null
        synchronized(positionLock) {
            if (generation != activeGeneration) return
            staleClearJobToCancel = pendingClearJob
            pendingClearJob = null
            lastSuccessReceiptMillis = if (observation != null) monotonicClock.nowMillis() else null
            _currentObservation.value = observation
        }
        staleClearJobToCancel?.cancel()
    }

    /**
     * Schedules **at most one** clear at the fixed deadline `lastSuccessReceiptMillis +
     * `[POSITION_STALENESS_WINDOW_MILLIS] — never rescheduled or extended by a repeated
     * `LocationUnavailable` while one is already pending (that's exactly what makes repeated
     * events a no-op rather than an endless sequence of replacement timers, per the class doc
     * comment). Three cases, entirely decided and (where applicable) acted on inside **one**
     * [positionLock] acquisition each — never a check under the lock followed by an act after
     * releasing it, which would reopen exactly the same-generation race a later `Success` could
     * otherwise win or lose depending on scheduling luck (see [clearPositionStateLocked]):
     * - No `Success` has occurred yet this session ([lastSuccessReceiptMillis] is `null`): nothing
     *   to be stale relative to — no timer is invented, [currentObservation] simply stays `null`.
     * - The deadline has already passed (can happen if this event itself arrived late): clears
     *   immediately, atomically with the check that decided it — a `Success` for this same
     *   generation cannot arrive "in between" a check and a later act, because there is no gap.
     * - Otherwise: launch a coroutine that waits for exactly the remaining time and then, still
     *   under [positionLock], clears only if it is still both the active generation *and* still
     *   reference-identical to [pendingClearJob] — see the class doc comment for why the second
     *   check is necessary even though the first is already generation-safe.
     */
    private fun scheduleStaleClearIfNeeded(generation: Long) {
        synchronized(positionLock) {
            if (generation != activeGeneration) return
            if (pendingClearJob != null) return
            val lastSuccess = lastSuccessReceiptMillis ?: return
            val remainingMillis = (lastSuccess + POSITION_STALENESS_WINDOW_MILLIS) - monotonicClock.nowMillis()
            if (remainingMillis <= 0) {
                clearPositionStateLocked()
            } else {
                pendingClearJob = scope.launch {
                    delay(remainingMillis)
                    val myJob = coroutineContext[Job]
                    synchronized(positionLock) {
                        if (generation == activeGeneration && pendingClearJob === myJob) {
                            clearPositionStateLocked()
                        }
                    }
                }
            }
        }
    }

    /** Must only be called while already holding [positionLock] — clears [pendingClearJob],
     * [lastSuccessReceiptMillis], and [currentObservation] together, with no checks of its own.
     * Callers are responsible for verifying generation/ownership *before* calling this, inside the
     * *same* lock acquisition — that's what keeps a "should I clear" decision atomic with the
     * clear itself, closing the race a separate check-then-act-later split would reopen. There is
     * never a pending [Job] to cancel here: either this is called from [scheduleStaleClearIfNeeded]'s
     * already-expired branch, which only reaches this point after confirming `pendingClearJob ==
     * null`, or from the scheduled timer's own wake-up, which *is* that job and is simply left to
     * complete normally. */
    private fun clearPositionStateLocked() {
        pendingClearJob = null
        lastSuccessReceiptMillis = null
        _currentObservation.value = null
    }

    /**
     * Submits [observation] first, unconditionally, then runs both best-effort diagnostics — never
     * the other way around: neither diagnostic may delay, block, or gate the functional submission
     * it observes. Returns [observation] so the caller can advance its local pairing state
     * regardless of how the diagnostics below fared.
     *
     * [CancellationException] boundary: the `catch (e: CancellationException) { throw e }` below
     * belongs only to the `suspend` call to [submitDiscoveryObservation] — that call can genuinely
     * suspend, so a [CancellationException] surfacing from it can be real structured-concurrency
     * cancellation of this collector's job, and must propagate. Neither [diagnosticLogger] nor
     * [transitionDiagnostics] is ever called from within that `try` block, and neither of their
     * `log`/`record` calls is `suspend` — so nothing below can observe genuine job cancellation
     * either; any [CancellationException] a misbehaving diagnostic component throws there is
     * necessarily fabricated, not real cancellation, and both [LocationDiagnosticLogger.logSafely]
     * and [recordTransitionDiagnostic] swallow it exactly like any other diagnostic failure —
     * never stopping this collector, and never blocking the *next* observation from being
     * submitted either.
     */
    private suspend fun submitObservationSafely(
        observation: LocationObservation,
        previousObservation: LocationObservation?,
    ): LocationObservation {
        try {
            submitDiscoveryObservation(
                coordinate = observation.coordinate,
                timestamp = observation.observedAt,
                provenance = Provenance.OBSERVED,
                trustStatus = TrustStatus.NON_CERTIFIED,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A single failed submission must not stop an otherwise active tracking session.
        }

        diagnosticLogger.logSafely(observation)
        recordTransitionDiagnostic(previousObservation, observation)

        return observation
    }

    /** Best-effort only — see [submitObservationSafely]'s doc comment for why a
     * [CancellationException] from [transitionDiagnostics] here is deliberately swallowed rather
     * than rethrown. */
    private fun recordTransitionDiagnostic(previous: LocationObservation?, observation: LocationObservation) {
        if (previous == null) return
        try {
            transitionDiagnostics?.record(previous, observation)
        } catch (t: Throwable) {
            // Diagnostic-only, and record() never suspends — must never affect normal
            // tracking/submission, including a fabricated CancellationException.
        }
    }
}
