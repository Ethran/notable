package com.ethran.notable.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages secure storage of WebDAV credentials using EncryptedSharedPreferences.
 * Credentials are stored separately from the KV database to ensure they're encrypted at rest.
 */
class CredentialManager(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "notable_sync_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Save WebDAV credentials securely.
     * @param username WebDAV username
     * @param password WebDAV password
     */
    fun saveCredentials(username: String, password: String) {
        encryptedPrefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    /**
     * Retrieve WebDAV credentials.
     * @return Pair of (username, password) or null if not set
     */
    fun getCredentials(): Pair<String, String>? {
        val username = encryptedPrefs.getString(KEY_USERNAME, null) ?: return null
        val password = encryptedPrefs.getString(KEY_PASSWORD, null) ?: return null
        return username to password
    }

    /**
     * Clear stored credentials (e.g., on logout or reset).
     */
    fun clearCredentials() {
        encryptedPrefs.edit().clear().apply()
    }

    /**
     * Check if credentials are stored.
     * @return true if both username and password are present
     */
    fun hasCredentials(): Boolean {
        return encryptedPrefs.contains(KEY_USERNAME) &&
               encryptedPrefs.contains(KEY_PASSWORD)
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
    }
}
