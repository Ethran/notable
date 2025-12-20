package com.ethran.notable.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Tracks deleted notebooks across devices.
 * Stored as deletions.json on the WebDAV server.
 */
@Serializable
data class DeletionsData(
    val deletedNotebookIds: Set<String> = emptySet()
)

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
