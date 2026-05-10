package com.ethran.notable.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ethran.notable.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SyncSettings(
    val syncEnabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val autoSync: Boolean = true,
    val syncInterval: Int = 15, // minutes
    val lastSyncTime: String? = null,
    val syncOnNoteClose: Boolean = true,
    val wifiOnly: Boolean = false,
    val syncedNotebookIds: Set<String> = emptySet()
)

/**
 * Manages storage of WebDAV credentials and sync settings.
 * It keeps sync settings in-memory (backed by a JSON file)
 * and stores credentials encrypted using AndroidKeyStore.
 */
@Singleton
class CredentialManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope
) {

    private val _settings = MutableStateFlow(SyncSettings())
    val settings: Flow<SyncSettings> = _settings.asStateFlow()

    private val settingsFile by lazy { File(context.filesDir, DATASTORE_FILE_NAME) }
    private val credFile by lazy { File(context.filesDir, CREDENTIALS_FILE_NAME) }

    init {
        appScope.launch {
            loadSettingsFromFile()
        }
    }

    suspend fun updateSettings(transform: suspend (SyncSettings) -> SyncSettings) {
        val current = _settings.value
        val updated = transform(current)
        _settings.value = updated

        withContext(Dispatchers.IO) {
            try {
                settingsFile.writeText(Json.encodeToString(SyncSettings.serializer(), updated))
            } catch (e: Exception) {
                SyncLogger.e("CredentialManager", "Failed to save settings: ${e.message}")
            }
        }
    }

    /**
     * Save WebDAV credentials securely.
     */
    suspend fun saveCredentials(username: String, password: String) {
        updateSettings { it.copy(username = username, password = "") }

        withContext(Dispatchers.IO) {
            try {
                ensureKeyExists()
                val creds = Credentials(username, password)
                val plain = Json.encodeToString(Credentials.serializer(), creds)
                val encrypted = encrypt(plain.toByteArray(Charsets.UTF_8))
                credFile.writeBytes(encrypted)
            } catch (e: Exception) {
                SyncLogger.e(
                    "CredentialManager",
                    "Failed to save encrypted credentials: ${e.message}"
                )
            }
        }
    }

    fun getPassword(): String? = try {
        readCredentialsFromFile()?.password
    } catch (e: Exception) {
        SyncLogger.e("CredentialManager", "Failed to read password: ${e.message}")
        null
    }

    fun getCredentials(): Pair<String, String>? =
        readCredentialsFromFile()?.let { it.username to it.password }

    fun hasCredentials(): Boolean = readCredentialsFromFile() != null

    private suspend fun loadSettingsFromFile() {
        withContext(Dispatchers.IO) {
            try {
                if (settingsFile.exists()) {
                    val text = settingsFile.readText()
                    _settings.value = Json.decodeFromString(SyncSettings.serializer(), text)
                }
            } catch (e: Exception) {
                SyncLogger.e("CredentialManager", "Failed to load settings: ${e.message}")
            }
        }
    }

    @Throws(Exception::class)
    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    @Throws(Exception::class)
    private fun getSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    @Throws(Exception::class)
    private fun encrypt(plain: ByteArray): ByteArray {
        val secret = getSecretKey() ?: throw IllegalStateException("Key not available")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        return iv + cipherText
    }

    @Throws(Exception::class)
    private fun decrypt(data: ByteArray): ByteArray {
        val secret = getSecretKey() ?: throw IllegalStateException("Key not available")
        val iv = data.copyOfRange(0, 12)
        val cipherText = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secret, spec)
        return cipher.doFinal(cipherText)
    }

    private fun readCredentialsFromFile(): Credentials? {
        try {
            if (!credFile.exists()) return null
            val bytes = credFile.readBytes()
            val decrypted = decrypt(bytes)
            val text = String(decrypted, Charsets.UTF_8)
            return try {
                Json.decodeFromString(Credentials.serializer(), text)
            } catch (e: Exception) {
                SyncLogger.e(
                    "CredentialManager",
                    "Failed to parse decrypted credentials: ${e.message}"
                )
                null
            }
        } catch (e: Exception) {
            SyncLogger.e("CredentialManager", "Failed to read credentials file: ${e.message}")
            return null
        }
    }

    @Serializable
    private data class Credentials(val username: String, val password: String)

    companion object {
        private const val KEY_ALIAS = "com.ethran.notable.sync_key"
        private const val CREDENTIALS_FILE_NAME = "sync_credentials.enc"
        private const val DATASTORE_FILE_NAME = "sync_settings.json"
    }
}
