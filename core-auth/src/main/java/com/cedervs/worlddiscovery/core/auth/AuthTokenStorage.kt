package com.cedervs.worlddiscovery.core.auth

/**
 * Persists the refresh token only. The access token is intentionally never persisted here —
 * it lives in memory in [AuthRepository] for the lifetime of the process.
 */
interface AuthTokenStorage {
    fun saveRefreshToken(token: String)
    fun readRefreshToken(): String?
    fun clearRefreshToken()
}
