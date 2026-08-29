package com.cedervs.worlddiscovery.core.auth

import android.content.Context

/**
 * A method of proving identity to the World Discovery backend (Google now; email OTP and,
 * eventually, Apple are reserved future implementations — see architecture.md §4).
 */
interface AuthProvider {
    val providerId: String

    suspend fun signIn(activityContext: Context): AuthProviderResult
}

sealed interface AuthProviderResult {
    data class Success(val idToken: String, val displayEmail: String?) : AuthProviderResult
    data class Failure(val reason: String) : AuthProviderResult
}
