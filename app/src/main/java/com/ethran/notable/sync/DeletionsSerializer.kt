package com.ethran.notable.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Legacy deletion tracking format used by the old deletions.json approach.
 *
 * This class is only kept for migration purposes: [SyncEngine.migrateDeletionsJsonToTombstones]
 * reads any existing deletions.json file on the server, creates individual tombstone files for
 * each entry, and then deletes deletions.json. After migration this class is no longer written.
 *
 * New deletions are tracked via zero-byte tombstone files at tombstones/{notebookId}.
 */
@Serializable
data class DeletionsData(
    // Map of notebook ID to ISO8601 deletion timestamp
    val deletedNotebooks: Map<String, String> = emptyMap(),

    // Older legacy field (pre-timestamp format) — read during migration only
    val deletedNotebookIds: Set<String> = emptySet()
) {
    /**
     * Returns all deleted notebook IDs regardless of format.
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
