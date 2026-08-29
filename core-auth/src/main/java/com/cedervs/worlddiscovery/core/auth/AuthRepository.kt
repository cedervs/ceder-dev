package com.cedervs.worlddiscovery.core.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    data object Unknown : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val displayEmail: String?) : SessionState
}

/**
 * Orchestrates [AuthProvider] (proves identity), [AuthApi] (World Discovery session tokens)
 * and [AuthTokenStorage] (refresh token persistence). The access token never leaves this
 * class — it is kept in memory only, per architecture.md §7.
 */
class AuthRepository(
    private val googleAuthProvider: AuthProvider,
    private val authApi: AuthApi,
    private val tokenStorage: AuthTokenStorage,
) {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Unknown)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    @Volatile
    private var accessToken: String? = null

    /** Call once at process start: silently resumes a session from the stored refresh token. */
    suspend fun initialize() {
        val storedRefreshToken = tokenStorage.readRefreshToken()
        if (storedRefreshToken == null) {
            _sessionState.value = SessionState.SignedOut
            return
        }

        runCatching { authApi.refresh(storedRefreshToken) }
            .onSuccess { tokens -> applySignedIn(tokens, displayEmail = null) }
            .onFailure {
                tokenStorage.clearRefreshToken()
                _sessionState.value = SessionState.SignedOut
            }
    }

    suspend fun signInWithGoogle(activityContext: Context): Result<Unit> {
        val providerResult = googleAuthProvider.signIn(activityContext)
        val (idToken, displayEmail) = when (providerResult) {
            is AuthProviderResult.Success -> providerResult.idToken to providerResult.displayEmail
            is AuthProviderResult.Failure -> return Result.failure(AuthApiException(providerResult.reason))
        }

        return runCatching {
            val tokens = authApi.loginWithGoogle(idToken, deviceInfo = null)
            applySignedIn(tokens, displayEmail)
        }
    }

    suspend fun logout() {
        tokenStorage.readRefreshToken()?.let { refreshToken ->
            runCatching { authApi.logout(refreshToken) }
        }
        accessToken = null
        tokenStorage.clearRefreshToken()
        _sessionState.value = SessionState.SignedOut
    }

    private fun applySignedIn(tokens: AuthTokens, displayEmail: String?) {
        accessToken = tokens.accessToken
        tokenStorage.saveRefreshToken(tokens.refreshToken)
        _sessionState.value = SessionState.SignedIn(displayEmail)
    }
}
