package com.cedervs.worlddiscovery.di

import android.content.Context
import com.cedervs.worlddiscovery.BuildConfig
import com.cedervs.worlddiscovery.core.auth.AuthApi
import com.cedervs.worlddiscovery.core.auth.AuthApiImpl
import com.cedervs.worlddiscovery.core.auth.AuthProvider
import com.cedervs.worlddiscovery.core.auth.AuthRepository
import com.cedervs.worlddiscovery.core.auth.AuthTokenStorage
import com.cedervs.worlddiscovery.core.auth.GoogleAuthProvider
import com.cedervs.worlddiscovery.core.auth.TinkAuthTokenStorage
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
}
