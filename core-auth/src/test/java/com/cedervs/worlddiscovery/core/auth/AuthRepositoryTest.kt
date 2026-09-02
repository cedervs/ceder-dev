package com.cedervs.worlddiscovery.core.auth

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `initialize while already SignedIn -- such as a ProfileScreen recomposition calling it again -- is a strict no-op`() = runTest {
        fakeAuthProvider.result = AuthProviderResult.Success(idToken = "google-id-token", displayEmail = "user@example.com")
        fakeAuthApi.loginResult = { Result.success(AuthTokens("access-1", "refresh-1", 900)) }
        repository.signInWithGoogle(context)
        val stateAfterSignIn = repository.sessionState.value
        assertEquals(SessionState.SignedIn("user@example.com"), stateAfterSignIn)

        // If the guard were missing, this would look like a fresh refresh, replacing the email
        // with null -- exactly the regression this test exists to catch.
        fakeAuthApi.refreshResult = { Result.success(AuthTokens("new-access", "new-refresh", 900)) }

        val recordedStates = mutableListOf<SessionState>()
        val collectorJob = launch { repository.sessionState.collect { recordedStates.add(it) } }
        // runTest's dispatcher is lazy: `launch` only schedules the collector, it doesn't run it
        // yet. runCurrent() lets it actually start and receive the StateFlow's initial replayed
        // value before initialize() runs below -- otherwise the assertion after can't distinguish
        // "no collector ran yet" from "no new emission happened".
        runCurrent()

        repository.initialize()

        collectorJob.cancel()

        assertEquals("initialize() must never call the refresh API for an already-established session", 0, fakeAuthApi.refreshCallCount)
        assertSame("the SessionState object itself must never be reassigned", stateAfterSignIn, repository.sessionState.value)
        assertEquals("user@example.com", (repository.sessionState.value as SessionState.SignedIn).displayEmail)
        assertEquals("the original refresh token must be left untouched, never rotated", "refresh-1", fakeTokenStorage.readRefreshToken())
        assertEquals(
            "no new SessionState transition may be emitted -- only the initial replay of the current value",
            listOf(stateAfterSignIn),
            recordedStates,
        )
    }

    @Test
    fun `initialize while already SignedOut is also a strict no-op`() = runTest {
        repository.initialize() // Unknown -> SignedOut (no stored token)
        assertEquals(SessionState.SignedOut, repository.sessionState.value)
        val readCountAfterFirstInitialize = fakeTokenStorage.readRefreshTokenCallCount

        val recordedStates = mutableListOf<SessionState>()
        val collectorJob = launch { repository.sessionState.collect { recordedStates.add(it) } }
        runCurrent() // let the collector actually start and receive the initial replayed value

        repository.initialize()

        collectorJob.cancel()

        assertEquals(SessionState.SignedOut, repository.sessionState.value)
        assertEquals(
            "the guard must return before ever consulting token storage again",
            readCountAfterFirstInitialize,
            fakeTokenStorage.readRefreshTokenCallCount,
        )
        assertEquals(listOf<SessionState>(SessionState.SignedOut), recordedStates)
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

    @Test
    fun `requestEmailCode success does not change session state`() = runTest {
        var requestedEmail: String? = null
        fakeAuthApi.requestEmailCodeAction = { email, _ -> requestedEmail = email }

        val result = repository.requestEmailCode("user@example.com")

        assertTrue(result.isSuccess)
        assertEquals("user@example.com", requestedEmail)
        assertEquals(SessionState.Unknown, repository.sessionState.value)
    }

    @Test
    fun `requestEmailCode failure is surfaced without touching storage`() = runTest {
        fakeAuthApi.requestEmailCodeAction = { _, _ -> throw AuthApiException("resend_cooldown") }

        val result = repository.requestEmailCode("user@example.com")

        assertTrue(result.isFailure)
        assertEquals("resend_cooldown", result.exceptionOrNull()?.message)
        assertNull(fakeTokenStorage.readRefreshToken())
    }

    @Test
    fun `verifyEmailCode success persists the refresh token and signs in with the email`() = runTest {
        fakeAuthApi.verifyEmailCodeResult = { Result.success(AuthTokens("access-1", "refresh-1", 900)) }

        val result = repository.verifyEmailCode("user@example.com", "123456")

        assertTrue(result.isSuccess)
        assertEquals(SessionState.SignedIn("user@example.com"), repository.sessionState.value)
        assertEquals("refresh-1", fakeTokenStorage.readRefreshToken())
    }

    @Test
    fun `verifyEmailCode failure does not touch storage or session state`() = runTest {
        fakeAuthApi.verifyEmailCodeResult = { Result.failure(AuthApiException("invalid_code")) }

        val result = repository.verifyEmailCode("user@example.com", "000000")

        assertTrue(result.isFailure)
        assertNull(fakeTokenStorage.readRefreshToken())
        assertEquals(SessionState.Unknown, repository.sessionState.value)
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
    var requestEmailCodeAction: (String, String?) -> Unit = { _, _ -> }
    var verifyEmailCodeResult: () -> Result<AuthTokens> = { Result.failure(AuthApiException("not_configured")) }
    var refreshCallCount = 0
        private set

    override suspend fun loginWithGoogle(idToken: String, deviceInfo: String?): AuthTokens =
        loginResult().getOrThrow()

    override suspend fun refresh(refreshToken: String): AuthTokens {
        refreshCallCount++
        return refreshResult().getOrThrow()
    }

    override suspend fun logout(refreshToken: String) {
        logoutAction(refreshToken)
    }

    override suspend fun requestEmailCode(email: String, locale: String?) {
        requestEmailCodeAction(email, locale)
    }

    override suspend fun verifyEmailCode(email: String, code: String, deviceInfo: String?): AuthTokens =
        verifyEmailCodeResult().getOrThrow()
}

private class FakeAuthTokenStorage : AuthTokenStorage {
    private var token: String? = null
    var readRefreshTokenCallCount = 0
        private set

    override fun saveRefreshToken(token: String) {
        this.token = token
    }

    override fun readRefreshToken(): String? {
        readRefreshTokenCallCount++
        return token
    }

    override fun clearRefreshToken() {
        token = null
    }
}
