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
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellRepository
import com.cedervs.worlddiscovery.core.discovery.H3CellConverter
import com.cedervs.worlddiscovery.core.discovery.SubmitDiscoveryObservation
import com.cedervs.worlddiscovery.core.location.AppForegroundTrackingController
import com.cedervs.worlddiscovery.core.location.BackgroundLocationController
import com.cedervs.worlddiscovery.core.location.BackgroundLocationObservation
import com.cedervs.worlddiscovery.core.location.BackgroundLocationRegistrar
import com.cedervs.worlddiscovery.core.location.BackgroundTrackingConsent
import com.cedervs.worlddiscovery.core.location.DataStoreBackgroundTrackingConsent
import com.cedervs.worlddiscovery.core.location.FusedBackgroundLocationRegistrar
import com.cedervs.worlddiscovery.core.location.FusedLocationProvider
import com.cedervs.worlddiscovery.core.location.FusedLocationUpdatesProvider
import com.cedervs.worlddiscovery.core.location.LocationProvider
import com.cedervs.worlddiscovery.core.location.LocationTrackingSession
import com.cedervs.worlddiscovery.core.location.LocationUpdatesProvider
import com.cedervs.worlddiscovery.core.location.SubmitBackgroundLocationObservations
import com.cedervs.worlddiscovery.core.location.SubmitCurrentLocationUseCase
import com.cedervs.worlddiscovery.core.location.TrackingSessionState
import com.cedervs.worlddiscovery.core.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    private val locationProvider: LocationProvider = FusedLocationProvider(context)

    val submitCurrentLocationUseCase = SubmitCurrentLocationUseCase(locationProvider, submitDiscoveryObservation)
    private val submitBackgroundLocationObservationsUseCase =
        SubmitBackgroundLocationObservations(submitDiscoveryObservation)

    // Process-lifetime scope for the in-session tracking pipeline: AppContainer itself already
    // lives for the whole process, so no separate ViewModel is needed just to own this.
    private val trackingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locationUpdatesProvider: LocationUpdatesProvider = FusedLocationUpdatesProvider(context)
    private val locationTrackingSession = LocationTrackingSession(
        locationUpdatesProvider = locationUpdatesProvider,
        submitDiscoveryObservation = submitDiscoveryObservation,
        scope = trackingScope,
    )

    // Off-by-default, explicit user opt-in — never a substitute for the actual OS permission
    // (see BackgroundLocationController, the only place the two are combined).
    val backgroundTrackingConsent: BackgroundTrackingConsent = DataStoreBackgroundTrackingConsent(context)
    private val backgroundLocationRegistrar: BackgroundLocationRegistrar = FusedBackgroundLocationRegistrar(context)
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
    }

    /**
     * Called from `BackgroundLocationReceiver` (see `:app`'s manifest-declared receiver) after a
     * background delivery arrives — which can carry a batch of several locations, not just one
     * (see `BACKGROUND_PROVISIONAL`'s `maxUpdateDelayMillis`). Delegates to
     * [SubmitBackgroundLocationObservations], which preserves each location's own timestamp and
     * keeps this logic unit-testable independent of `Context`.
     */
    suspend fun submitBackgroundLocationObservations(observations: List<BackgroundLocationObservation>) {
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
