package com.cedervs.worlddiscovery.core.auth

import android.content.Context
import java.util.Locale
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

    /**
     * Call once at process start: silently resumes a session from the stored refresh token.
     *
     * Idempotent by design — a no-op unless [sessionState] is still [SessionState.Unknown]. A
     * caller (e.g. `ProfileScreen` recomposing) may call this again during an already-established
     * session; without this guard, that would re-run the refresh-token exchange and could replace
     * an existing `SignedIn(email)` with `SignedIn(null)` for the exact same logical session —
     * emitting a spurious [SessionState] change that downstream observers (see `AppContainer`'s
     * `MapNavigationStateResetter` wiring) would otherwise mistake for a real session transition.
     */
    suspend fun initialize() {
        if (_sessionState.value != SessionState.Unknown) return

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

    /**
     * Step 1 of email sign-in: request a 6-digit code be sent to [email]. Does not touch
     * session state — only [verifyEmailCode] can sign the user in.
     */
    suspend fun requestEmailCode(email: String): Result<Unit> =
        runCatching { authApi.requestEmailCode(email, locale = Locale.getDefault().language) }

    /** Step 2 of email sign-in: verify the code the user received and typed in. */
    suspend fun verifyEmailCode(email: String, code: String): Result<Unit> =
        runCatching {
            val tokens = authApi.verifyEmailCode(email, code, deviceInfo = null)
            applySignedIn(tokens, displayEmail = email)
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
