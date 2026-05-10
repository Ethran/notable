package com.ethran.notable.sync

import kotlinx.serialization.Serializable

const val SYNC_SETTINGS_KEY = "SYNC_SETTINGS"

@Serializable
data class SyncSettings(
    val syncEnabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val encryptedPassword: String = "", // Base64 encrypted in KV, decrypted in memory
    val autoSync: Boolean = true,
    val syncInterval: Int = 15, // minutes
    val lastSyncTime: String? = null,
    val syncOnNoteClose: Boolean = true,
    val wifiOnly: Boolean = false,
    val syncedNotebookIds: Set<String> = emptySet()
)

