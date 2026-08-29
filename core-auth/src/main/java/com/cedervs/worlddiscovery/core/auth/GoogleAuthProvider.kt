package com.cedervs.worlddiscovery.core.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

class GoogleAuthProvider(private val webClientId: String) : AuthProvider {

    override val providerId: String = "google"

    override suspend fun signIn(activityContext: Context): AuthProviderResult {
        val credentialManager = CredentialManager.create(activityContext)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val response = credentialManager.getCredential(activityContext, request)
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                AuthProviderResult.Success(
                    idToken = googleIdTokenCredential.idToken,
                    displayEmail = googleIdTokenCredential.id,
                )
            } else {
                AuthProviderResult.Failure("unexpected_credential_type")
            }
        } catch (e: GetCredentialException) {
            if (BuildConfig.DEBUG) {
                // e is a Credential Manager/Play Services error (e.g. no account, user
                // cancelled, unauthorized test user) — never carries a token at this stage.
                Log.w(TAG, "Credential Manager error: ${e.javaClass.simpleName} (${e.type})", e)
            }
            AuthProviderResult.Failure(e.message ?: e.javaClass.simpleName)
        } catch (e: GoogleIdTokenParsingException) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Google ID token parsing error: ${e.javaClass.simpleName}", e)
            }
            AuthProviderResult.Failure("google_id_token_parsing_error")
        }
    }

    private companion object {
        const val TAG = "GoogleAuthProvider"
    }
}
