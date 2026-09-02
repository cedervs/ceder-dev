package com.cedervs.worlddiscovery.di

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.cedervs.worlddiscovery.BuildConfig
import com.cedervs.worlddiscovery.core.auth.AuthApi
import com.cedervs.worlddiscovery.core.auth.AuthApiImpl
import com.cedervs.worlddiscovery.core.auth.AuthProvider
import com.cedervs.worlddiscovery.core.auth.AuthRepository
import com.cedervs.worlddiscovery.core.auth.AuthTokenStorage
import com.cedervs.worlddiscovery.core.auth.GoogleAuthProvider
import com.cedervs.worlddiscovery.core.auth.TinkAuthTokenStorage
import com.cedervs.worlddiscovery.core.database.RoomDiscoveredCellRepository
import com.cedervs.worlddiscovery.core.database.WorldDiscoveryDatabase
import com.cedervs.worlddiscovery.core.discovery.ClassifyDiscoveredCellsByGeographicArea
import com.cedervs.worlddiscovery.core.discovery.ClassifyDiscoveredCellsByGeographicAreaComponents
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellRepository
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.ObserveMapReadState
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.discovery.loadFranceGeographicAreaReference
import com.cedervs.worlddiscovery.core.location.AndroidBackgroundLocationDiagnosticLogger
import com.cedervs.worlddiscovery.core.location.AndroidLocationDiagnosticLogger
import com.cedervs.worlddiscovery.core.location.AndroidTransitionDiagnosticLogger
import com.cedervs.worlddiscovery.core.location.AppForegroundTrackingController
import com.cedervs.worlddiscovery.core.location.BackgroundLocationController
import com.cedervs.worlddiscovery.core.location.ForegroundTransitionDiagnostics
import com.cedervs.worlddiscovery.core.location.LocationObservation
import com.cedervs.worlddiscovery.core.location.BackgroundLocationRegistrar
import com.cedervs.worlddiscovery.core.location.BackgroundTrackingConsent
import com.cedervs.worlddiscovery.core.location.DataStoreBackgroundTrackingConsent
import com.cedervs.worlddiscovery.core.location.FusedBackgroundLocationRegistrar
import com.cedervs.worlddiscovery.core.location.FusedLocationProvider
import com.cedervs.worlddiscovery.core.location.FusedLocationUpdatesProvider
import com.cedervs.worlddiscovery.core.location.LocationDiagnosticLogger
import com.cedervs.worlddiscovery.core.location.LocationProvider
import com.cedervs.worlddiscovery.core.location.LocationTrackingSession
import com.cedervs.worlddiscovery.core.location.LocationUpdatesProvider
import com.cedervs.worlddiscovery.core.location.SubmitBackgroundLocationObservations
import com.cedervs.worlddiscovery.core.location.SubmitCurrentLocationUseCase
import com.cedervs.worlddiscovery.core.location.TrackingSessionState
import com.cedervs.worlddiscovery.core.network.ApiClient
import com.cedervs.worlddiscovery.feature.map.MapNavigationStateResetter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Manual composition root (no Hilt, per Auth 2A decisions). Owned by [com.cedervs.worlddiscovery.WorldDiscoveryApplication]
 * and lives for the whole process.
 */
class AppContainer(context: Context) {

    private val apiClient = ApiClient(baseUrl = BuildConfig.API_BASE_URL)

    private val authApi: AuthApi = AuthApiImpl(apiClient)
    private val authTokenStorage: AuthTokenStorage = TinkAuthTokenStorage(context)
    private val googleAuthProvider: AuthProvider = GoogleAuthProvider(webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID)

    val authRepository = AuthRepository(
        googleAuthProvider = googleAuthProvider,
        authApi = authApi,
        tokenStorage = authTokenStorage,
    )

    // Single local Room database for the whole app (docs/discovery-engine.md §16: offline-first,
    // no backend on the critical path). Never an in-memory database here — only tests use that.
    private val database = Room.databaseBuilder(
        context.applicationContext,
        WorldDiscoveryDatabase::class.java,
        WorldDiscoveryDatabase.DATABASE_NAME,
    ).build()

    private val discoveredCellRepository: DiscoveredCellRepository =
        RoomDiscoveredCellRepository(database.discoveredCellDao())
    private val h3CellConverter: H3CellConverter = AndroidH3CellConverter()
    private val submitDiscoveryObservation = SubmitDiscoveryObservation(h3CellConverter, discoveredCellRepository)

    // Map feature's single read-side entry point — mirrors submitDiscoveryObservation on the
    // write side. Read-only: never mutates discovery history. One discoveredCellRepository
    // subscription feeds both the fine (resolution-12) geometries and the France Country-level
    // VISITED overlay from the same emission — see ObserveMapReadState's doc comment for why
    // this is one Flow, not two. (An H3-parent-aggregate point visualization was previously wired
    // here, physically validated, and abandoned as product-invalid — see
    // docs/ai-context/LOCATION_TRACKING.md and git history. The generic
    // H3HierarchyConverter/AndroidH3HierarchyConverter capability it used is retained in
    // core-discovery-engine/:app for possible future reuse but has no current consumer, so it is
    // deliberately not instantiated here.)
    //
    // The France reference is a small, checked-in, versioned geographic artifact — see
    // tools/geo/README.md for its real source/license/generation steps — loaded once and reused
    // for the lifetime of the process; it is never persisted or treated as discovery truth.
    private val franceGeographicAreaReference = loadFranceGeographicAreaReference()
    val observeMapReadState = ObserveMapReadState(
        discoveredCellRepository,
        h3CellConverter,
        ClassifyDiscoveredCellsByGeographicArea(h3CellConverter),
        ClassifyDiscoveredCellsByGeographicAreaComponents(h3CellConverter),
        franceGeographicAreaReference,
    )

    private val locationProvider: LocationProvider = FusedLocationProvider(context)

    // Diagnostic-only, best-effort location-quality logging (see docs/ai-context/LOCATION_TRACKING.md)
    // — enabled only for debug builds, never verbose in release by default. A single shared
    // instance across all three submission paths (one-shot, foreground, background).
    private val locationDiagnosticLogger: LocationDiagnosticLogger = AndroidLocationDiagnosticLogger(
        isEnabled = BuildConfig.DEBUG,
    )

    val submitCurrentLocationUseCase =
        SubmitCurrentLocationUseCase(locationProvider, submitDiscoveryObservation, locationDiagnosticLogger)

    // Debug-only, part of the background acquisition calibration experiment (see
    // docs/ai-context/LOCATION_TRACKING.md's "BACKGROUND ACQUISITION CALIBRATION —
    // EXPERIMENTAL" section) — a dedicated Logcat tag distinct from locationDiagnosticLogger's
    // "LocationQuality" so a physical trip's background delivery can be captured in isolation.
    private val backgroundLocationDiagnosticLogger =
        AndroidBackgroundLocationDiagnosticLogger(isEnabled = BuildConfig.DEBUG)
    private val submitBackgroundLocationObservationsUseCase =
        SubmitBackgroundLocationObservations(
            submitDiscoveryObservation,
            locationDiagnosticLogger,
            backgroundLocationDiagnosticLogger,
        )

    // Debug-only field-calibration instrumentation for foreground transitions (see
    // docs/ai-context/LOCATION_TRACKING.md) — completely independent of
    // ReconstructionEligibilityPolicy/ForegroundReconstructionScheduler, which stay unused in
    // production. Never produces a ReconstructionCandidate, never persists.
    //
    // Deliberately not just a disabled/no-op logger: in a release build, neither
    // AndroidH3GridTraversal nor ForegroundTransitionDiagnostics is even constructed, so no H3
    // path/distance/transition computation for calibration ever runs — only the object graph
    // itself is absent, not merely silenced at the log call site.
    private val foregroundTransitionDiagnostics: ForegroundTransitionDiagnostics? =
        if (BuildConfig.DEBUG) {
            ForegroundTransitionDiagnostics(
                cellConverter = h3CellConverter,
                gridTraversal = AndroidH3GridTraversal(),
                diagnosticLogger = AndroidTransitionDiagnosticLogger(isEnabled = true),
            )
        } else {
            null
        }

    // Process-lifetime scope for the in-session tracking pipeline: AppContainer itself already
    // lives for the whole process, so no separate ViewModel is needed just to own this.
    private val trackingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locationUpdatesProvider: LocationUpdatesProvider = FusedLocationUpdatesProvider(context)
    private val locationTrackingSession = LocationTrackingSession(
        locationUpdatesProvider = locationUpdatesProvider,
        submitDiscoveryObservation = submitDiscoveryObservation,
        scope = trackingScope,
        diagnosticLogger = locationDiagnosticLogger,
        transitionDiagnostics = foregroundTransitionDiagnostics,
    )

    // Live current-position UI state ("where am I right now") — entirely separate from discovery
    // ("what have I discovered"): reuses the same foreground acquisition stream
    // [locationTrackingSession] already collects, never a second location request. Transient,
    // process-memory only; never persisted, never logged with its raw coordinate. See
    // [LocationTrackingSession.currentObservation]'s doc comment and
    // `docs/ai-context/LOCATION_TRACKING.md`.
    val currentLocationObservation: Flow<LocationObservation?> = locationTrackingSession.currentObservation

    // Off-by-default, explicit user opt-in — never a substitute for the actual OS permission
    // (see BackgroundLocationController, the only place the two are combined).
    val backgroundTrackingConsent: BackgroundTrackingConsent = DataStoreBackgroundTrackingConsent(context)
    private val backgroundLocationRegistrar: BackgroundLocationRegistrar =
        FusedBackgroundLocationRegistrar(context, diagnosticLogger = backgroundLocationDiagnosticLogger)
    private val backgroundLocationController = BackgroundLocationController(
        consent = backgroundTrackingConsent,
        registrar = backgroundLocationRegistrar,
        scope = trackingScope,
    )

    // Application-foreground lifecycle, not any single screen/Activity (docs: tracking must run
    // regardless of which tab is visible, and stop the moment the whole app is backgrounded).
    // Owns both foreground and background tracking so the two are never simultaneously active.
    private val appForegroundTrackingController = AppForegroundTrackingController(
        foregroundSession = locationTrackingSession,
        backgroundController = backgroundLocationController,
    )

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundTrackingController)

        // A real authentication/session transition (logout, a different login, an account/session
        // replacement) must not let a new session inherit the previous one's map camera or active
        // component focus -- see MapNavigationStateResetter's own doc comment for why the two
        // holders it clears must never be reset independently. Reuses the existing
        // authRepository.sessionState flow (the app's one real session-transition signal) rather
        // than inventing a second observation mechanism, and trackingScope (already process-lifetime,
        // already owned by this class) rather than a dedicated new scope. `drop(1)` skips the
        // CURRENT value every StateFlow replays to a brand-new collector -- process start resuming
        // an existing session (or the very first SignedOut) is not a live transition, and nothing
        // stale exists in the map holders yet at that point regardless. Ordinary MapView recreation,
        // tab switches, and Compose recomposition never touch this flow at all, so they can never
        // trigger a reset.
        trackingScope.launch {
            authRepository.sessionState.drop(1).collect { MapNavigationStateResetter.reset() }
        }
    }

    /**
     * Called from `BackgroundLocationReceiver` (see `:app`'s manifest-declared receiver) after a
     * background delivery arrives — which can carry a batch of several locations, not just one
     * (see `BACKGROUND_PROVISIONAL`'s `maxUpdateDelayMillis`). Delegates to
     * [SubmitBackgroundLocationObservations], which preserves each location's own timestamp and
     * keeps this logic unit-testable independent of `Context`.
     */
    suspend fun submitBackgroundLocationObservations(observations: List<LocationObservation>) {
        submitBackgroundLocationObservationsUseCase(observations)
    }

    /**
     * Called from `BootCompletedReceiver` (see `:app`'s manifest-declared receiver). A device
     * reboot does not preserve any `requestLocationUpdates` `PendingIntent` registration, and
     * `ProcessLifecycleOwner` never reaches STARTED in a process woken only by `BOOT_COMPLETED`
     * (no Activity has started), so [appForegroundTrackingController] never runs here — this
     * calls the same [BackgroundLocationController.armSuspending] logic directly. Still only
     * re-arms if persisted consent is enabled *and* the current OS permission is actually
     * granted — [BackgroundLocationController] and [FusedBackgroundLocationRegistrar] own those
     * two checks respectively; this adds no third one.
     */
    suspend fun rearmBackgroundTrackingAfterBoot() {
        backgroundLocationController.armSuspending()
    }

    /**
     * Called from the UI after a location-permission request completes with at least one grant
     * (see [com.cedervs.worlddiscovery.feature.map.MapScreen]'s existing permission launcher).
     *
     * On first launch, `ProcessLifecycleOwner`'s ON_START — and so the automatic foreground
     * session's first [LocationTrackingSession.start] — can happen before permission is granted,
     * ending that attempt in [TrackingSessionState.PermissionDenied]. Because the app never
     * leaves the foreground while the permission dialog is up, no further ON_START ever arrives
     * to retry it. This gives that same session an explicit nudge once permission changes,
     * without polling and without a second, competing permission check: [LocationTrackingSession]
     * still owns the single authoritative check (inside [FusedLocationUpdatesProvider]), and
     * [LocationTrackingSession.start] is already idempotent — a no-op if a collector is already
     * running, and safe to call even if permission turns out to still be denied.
     */
    fun retryLocationTrackingAfterPermissionGranted() {
        locationTrackingSession.start()
    }
}
