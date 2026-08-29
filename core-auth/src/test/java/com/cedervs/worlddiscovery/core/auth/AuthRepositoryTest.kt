package com.cedervs.worlddiscovery.core.auth

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class AuthRepositoryTest {

    private lateinit var fakeAuthApi: FakeAuthApi
    private lateinit var fakeTokenStorage: FakeAuthTokenStorage
    private lateinit var fakeAuthProvider: FakeAuthProvider
    private lateinit var repository: AuthRepository
    private val context: Context = mock(Context::class.java)

    @Before
    fun setUp() {
        fakeAuthApi = FakeAuthApi()
        fakeTokenStorage = FakeAuthTokenStorage()
        fakeAuthProvider = FakeAuthProvider()
        repository = AuthRepository(fakeAuthProvider, fakeAuthApi, fakeTokenStorage)
    }

    @Test
    fun `initialize with no stored refresh token results in signed out`() = runTest {
        repository.initialize()

        assertEquals(SessionState.SignedOut, repository.sessionState.value)
    }

    @Test
    fun `initialize with a valid stored refresh token resumes the session`() = runTest {
        fakeTokenStorage.saveRefreshToken("stored-refresh-token")
        fakeAuthApi.refreshResult = { Result.success(AuthTokens("new-access", "rotated-refresh", 900)) }

        repository.initialize()

        assertTrue(repository.sessionState.value is SessionState.SignedIn)
        assertEquals("rotated-refresh", fakeTokenStorage.readRefreshToken())
    }

    @Test
    fun `initialize with an expired or revoked stored token clears storage and signs out`() = runTest {
        fakeTokenStorage.saveRefreshToken("stale-refresh-token")
        fakeAuthApi.refreshResult = { Result.failure(AuthApiException("refresh_token_expired")) }

        repository.initialize()

        assertEquals(SessionState.SignedOut, repository.sessionState.value)
        assertNull(fakeTokenStorage.readRefreshToken())
    }

    @Test
    fun `signInWithGoogle persists the refresh token and exposes signed in state`() = runTest {
        fakeAuthProvider.result = AuthProviderResult.Success(idToken = "google-id-token", displayEmail = "user@example.com")
        fakeAuthApi.loginResult = { Result.success(AuthTokens("access-1", "refresh-1", 900)) }

        val result = repository.signInWithGoogle(context)

        assertTrue(result.isSuccess)
        assertEquals(SessionState.SignedIn("user@example.com"), repository.sessionState.value)
        assertEquals("refresh-1", fakeTokenStorage.readRefreshToken())
    }

    @Test
    fun `signInWithGoogle failure from the provider does not touch storage`() = runTest {
        fakeAuthProvider.result = AuthProviderResult.Failure("user_cancelled")

        val result = repository.signInWithGoogle(context)

        assertTrue(result.isFailure)
        assertNull(fakeTokenStorage.readRefreshToken())
        assertEquals(SessionState.Unknown, repository.sessionState.value)
    }

    @Test
    fun `logout clears storage and calls the backend with the stored refresh token`() = runTest {
        fakeTokenStorage.saveRefreshToken("refresh-to-revoke")
        var logoutCalledWith: String? = null
        fakeAuthApi.logoutAction = { token -> logoutCalledWith = token }

        repository.logout()

        assertEquals("refresh-to-revoke", logoutCalledWith)
        assertNull(fakeTokenStorage.readRefreshToken())
        assertEquals(SessionState.SignedOut, repository.sessionState.value)
    }

    @Test
    fun `logout with nothing stored does not call the backend and still signs out`() = runTest {
        var backendCalled = false
        fakeAuthApi.logoutAction = { backendCalled = true }

        repository.logout()

        assertTrue(!backendCalled)
        assertEquals(SessionState.SignedOut, repository.sessionState.value)
    }
}

private class FakeAuthProvider : AuthProvider {
    override val providerId = "google"
    var result: AuthProviderResult = AuthProviderResult.Failure("not_configured")

    override suspend fun signIn(activityContext: Context): AuthProviderResult = result
}

private class FakeAuthApi : AuthApi {
    var loginResult: () -> Result<AuthTokens> = { Result.failure(AuthApiException("not_configured")) }
    var refreshResult: () -> Result<AuthTokens> = { Result.failure(AuthApiException("not_configured")) }
    var logoutAction: (String) -> Unit = {}

    override suspend fun loginWithGoogle(idToken: String, deviceInfo: String?): AuthTokens =
        loginResult().getOrThrow()

    override suspend fun refresh(refreshToken: String): AuthTokens = refreshResult().getOrThrow()

    override suspend fun logout(refreshToken: String) {
        logoutAction(refreshToken)
    }
}

private class FakeAuthTokenStorage : AuthTokenStorage {
    private var token: String? = null

    override fun saveRefreshToken(token: String) {
        this.token = token
    }

    override fun readRefreshToken(): String? = token

    override fun clearRefreshToken() {
        token = null
    }
}
