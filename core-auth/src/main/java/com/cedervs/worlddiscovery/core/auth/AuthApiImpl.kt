package com.cedervs.worlddiscovery.core.auth

import com.cedervs.worlddiscovery.core.network.ApiClient
import com.cedervs.worlddiscovery.core.network.ApiException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AuthApiImpl(private val apiClient: ApiClient) : AuthApi {

    override suspend fun loginWithGoogle(idToken: String, deviceInfo: String?): AuthTokens =
        request {
            apiClient.post<GoogleAuthRequestDto, TokenResponseDto>(
                "/v1/auth/google",
                GoogleAuthRequestDto(idToken = idToken, deviceInfo = deviceInfo),
            )
        }.toAuthTokens()

    override suspend fun refresh(refreshToken: String): AuthTokens =
        request {
            apiClient.post<RefreshRequestDto, TokenResponseDto>(
                "/v1/auth/refresh",
                RefreshRequestDto(refreshToken = refreshToken),
            )
        }.toAuthTokens()

    override suspend fun logout(refreshToken: String) {
        request {
            apiClient.postForStatus("/v1/auth/logout", LogoutRequestDto(refreshToken = refreshToken))
        }
    }

    private suspend inline fun <T> request(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: ApiException) {
            throw AuthApiException(e.errorCode ?: "auth_request_failed", e)
        }
    }
}

private fun TokenResponseDto.toAuthTokens() = AuthTokens(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresInSeconds = expiresIn,
)

@Serializable
internal data class GoogleAuthRequestDto(
    @SerialName("id_token") val idToken: String,
    @SerialName("device_info") val deviceInfo: String? = null,
)

@Serializable
internal data class RefreshRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class LogoutRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
)
