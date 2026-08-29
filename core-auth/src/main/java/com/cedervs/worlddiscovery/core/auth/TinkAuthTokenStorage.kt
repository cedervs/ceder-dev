package com.cedervs.worlddiscovery.core.auth

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException

/**
 * Refresh-token-only secure storage backed by Android Keystore via Tink, replacing the now
 * deprecated androidx.security EncryptedSharedPreferences. The Tink keyset itself is wrapped
 * by a Keystore-resident key (never leaves the TEE/StrongBox), so the on-disk blob in
 * [PREFS_NAME] is unusable outside this device.
 */
class TinkAuthTokenStorage(context: Context) : AuthTokenStorage {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val aead by lazy {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, PREFS_NAME)
            .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate())
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        keysetHandle.getPrimitive(com.google.crypto.tink.Aead::class.java)
    }

    override fun saveRefreshToken(token: String) {
        val ciphertext = aead.encrypt(token.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        prefs.edit().putString(KEY_REFRESH_TOKEN, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply()
    }

    override fun readRefreshToken(): String? {
        val stored = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        return try {
            val plaintext = aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), ASSOCIATED_DATA)
            String(plaintext, Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // Ciphertext unreadable (e.g. Keystore key lost after a device wipe/restore) — treat
            // as signed-out rather than crashing.
            clearRefreshToken()
            null
        }
    }

    override fun clearRefreshToken() {
        prefs.edit().remove(KEY_REFRESH_TOKEN).apply()
    }

    private companion object {
        const val PREFS_NAME = "core_auth_secure_prefs"
        const val KEYSET_NAME = "core_auth_master_keyset"
        const val MASTER_KEY_URI = "android-keystore://core_auth_master_key"
        const val KEY_REFRESH_TOKEN = "refresh_token_ciphertext"
        val ASSOCIATED_DATA: ByteArray = "world_discovery_refresh_token".toByteArray(Charsets.UTF_8)
    }
}
