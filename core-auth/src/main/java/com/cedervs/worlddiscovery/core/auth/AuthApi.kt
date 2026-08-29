package com.cedervs.worlddiscovery.core.auth

interface AuthApi {
    suspend fun loginWithGoogle(idToken: String, deviceInfo: String?): AuthTokens
    suspend fun refresh(refreshToken: String): AuthTokens
    suspend fun logout(refreshToken: String)
}

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
)

class AuthApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
