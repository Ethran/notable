package com.ethran.notable.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Tracks deleted notebooks across devices with deletion timestamps.
 * Stored as deletions.json on the WebDAV server.
 *
 * The timestamp is used for conflict resolution: if a local notebook was modified
 * after it was deleted on the server, it should be resurrected (re-uploaded) rather
 * than deleted locally.
 */
@Serializable
data class DeletionsData(
    // Map of notebook ID to ISO8601 deletion timestamp
    val deletedNotebooks: Map<String, String> = emptyMap(),

    // Legacy field for backward compatibility - deprecated
    @Deprecated("Use deletedNotebooks with timestamps instead")
    val deletedNotebookIds: Set<String> = emptySet()
) {
    /**
     * Get all deleted notebook IDs (regardless of timestamp).
     */
    fun getAllDeletedIds(): Set<String> {
        return deletedNotebooks.keys + deletedNotebookIds
    }
}

object DeletionsSerializer {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun serialize(deletions: DeletionsData): String {
        return json.encodeToString(deletions)
    }

    fun deserialize(jsonString: String): DeletionsData {
        return try {
            json.decodeFromString<DeletionsData>(jsonString)
        } catch (e: Exception) {
            // If parsing fails, return empty
            DeletionsData()
        }
    }
}
