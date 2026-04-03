package com.ethran.notable.sync

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SyncSettings(
    val syncEnabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val autoSync: Boolean = true,
    val syncInterval: Int = 15, // minutes
    val lastSyncTime: String? = null,
    val syncOnNoteClose: Boolean = true,
    val wifiOnly: Boolean = false,
    val syncedNotebookIds: Set<String> = emptySet()
)

/**
 * Manages secure storage of WebDAV credentials and sync settings.
 * Everything is stored in EncryptedSharedPreferences to ensure security at rest
 * and to keep sync state independent of general app settings.
 */
@Singleton
class CredentialManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<SyncSettings> = _settings.asStateFlow()

    private fun loadSettings(): SyncSettings {
        return SyncSettings(
            syncEnabled = encryptedPrefs.getBoolean(KEY_SYNC_ENABLED, false),
            serverUrl = encryptedPrefs.getString(KEY_SERVER_URL, "") ?: "",
            username = encryptedPrefs.getString(KEY_USERNAME, "") ?: "",
            autoSync = encryptedPrefs.getBoolean(KEY_AUTO_SYNC, true),
            syncInterval = encryptedPrefs.getInt(KEY_SYNC_INTERVAL, 15),
            lastSyncTime = encryptedPrefs.getString(KEY_LAST_SYNC_TIME, null),
            syncOnNoteClose = encryptedPrefs.getBoolean(KEY_SYNC_ON_CLOSE, true),
            wifiOnly = encryptedPrefs.getBoolean(KEY_WIFI_ONLY, false),
            syncedNotebookIds = encryptedPrefs.getStringSet(KEY_SYNCED_IDS, emptySet())
                ?: emptySet()
        )
    }

    fun updateSettings(transform: (SyncSettings) -> SyncSettings) {
        val newSettings = transform(_settings.value)
        encryptedPrefs.edit {
            putBoolean(KEY_SYNC_ENABLED, newSettings.syncEnabled)
            putString(KEY_SERVER_URL, newSettings.serverUrl)
            putString(KEY_USERNAME, newSettings.username)
            putBoolean(KEY_AUTO_SYNC, newSettings.autoSync)
            putInt(KEY_SYNC_INTERVAL, newSettings.syncInterval)
            putString(KEY_LAST_SYNC_TIME, newSettings.lastSyncTime)
            putBoolean(KEY_SYNC_ON_CLOSE, newSettings.syncOnNoteClose)
            putBoolean(KEY_WIFI_ONLY, newSettings.wifiOnly)
            putStringSet(KEY_SYNCED_IDS, newSettings.syncedNotebookIds)
        }
        _settings.value = newSettings
    }

    /**
     * Save WebDAV credentials securely.
     * @param username WebDAV username
     * @param password WebDAV password
     */
    fun saveCredentials(username: String, password: String) {
        encryptedPrefs.edit {
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
        }
        // Update the flow as well
        updateSettings { it.copy(username = username) }
    }

    /**
     * Retrieve WebDAV password.
     */
    fun getPassword(): String? {
        return encryptedPrefs.getString(KEY_PASSWORD, null)
    }

    /**
     * Retrieve full credentials.
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
        encryptedPrefs.edit {
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
        }
        updateSettings { it.copy(username = "") }
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
        private const val PREFS_FILE_NAME = "notable_sync_credentials"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_SYNC_INTERVAL = "sync_interval"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_SYNC_ON_CLOSE = "sync_on_close"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_SYNCED_IDS = "synced_ids"
    }
}
