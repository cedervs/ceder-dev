package com.cedervs.worlddiscovery.di

import android.content.Context
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
import com.cedervs.worlddiscovery.core.location.FusedLocationProvider
import com.cedervs.worlddiscovery.core.location.LocationProvider
import com.cedervs.worlddiscovery.core.location.SubmitCurrentLocationUseCase
import com.cedervs.worlddiscovery.core.network.ApiClient

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
}
