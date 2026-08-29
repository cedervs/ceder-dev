package com.cedervs.worlddiscovery.core.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Thin, Ktor-free-facing HTTP client for the World Discovery backend. Callers outside this
 * module only ever see plain Kotlin types (String/T/exceptions) — no io.ktor.* type appears
 * in a public signature, so the transport layer stays swappable and invisible to callers.
 */
class ApiClient(baseUrl: String) {

    @PublishedApi
    internal val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        defaultRequest {
            url(baseUrl)
        }
    }

    suspend inline fun <reified TRequest, reified TResponse> post(path: String, body: TRequest): TResponse {
        return postInternal(path, body).body()
    }

    suspend inline fun <reified TRequest> postForStatus(path: String, body: TRequest) {
        postInternal(path, body)
    }

    @PublishedApi
    internal suspend inline fun <reified TRequest> postInternal(path: String, body: TRequest): HttpResponse {
        val response: HttpResponse = try {
            httpClient.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: IOException) {
            // Connection-level failure (DNS, refused, timeout, etc.) — never carries the
            // request body/tokens, only the target path is safe to log.
            if (BuildConfig.DEBUG) {
                Log.w("ApiClient", "Network error calling $path: ${e.javaClass.simpleName}: ${e.message}")
            }
            throw e
        }
        if (!response.status.isSuccess()) {
            val errorCode = runCatching { response.body<ApiErrorBody>().detail }.getOrNull()
            if (BuildConfig.DEBUG) {
                Log.w("ApiClient", "HTTP error from $path: ${response.status.value} ($errorCode)")
            }
            throw ApiException(statusCode = response.status.value, errorCode = errorCode)
        }
        return response
    }
}

class ApiException(val statusCode: Int, val errorCode: String?) :
    Exception("API request failed: $statusCode${errorCode?.let { " ($it)" } ?: ""}")

@Serializable
data class ApiErrorBody(@SerialName("detail") val detail: String? = null)
