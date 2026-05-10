package com.ethran.notable.sync.serializers

import com.ethran.notable.data.db.Folder
import com.ethran.notable.utils.logCallStack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * Serializer for folder hierarchy to/from JSON format for WebDAV sync.
 * Utilizing java.time.Instant for modern, thread-safe ISO 8601 parsing.
 */
object FolderSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Serialize list of folders to JSON string (folders.json format).
     * @param folders List of Folder entities from database
     * @return JSON string representation
     */
    fun serializeFolders(folders: List<Folder>): String {
        val folderDtos = folders.map { folder ->
            FolderDto(
                id = folder.id,
                title = folder.title,
                parentFolderId = folder.parentFolderId,
                // .toInstant().toString() natively outputs strict ISO 8601 UTC
                createdAt = folder.createdAt.toInstant().toString(),
                updatedAt = folder.updatedAt.toInstant().toString()
            )
        }

        val foldersJson = FoldersJson(
            version = 1,
            folders = folderDtos,
            serverTimestamp = Instant.now().toString()
        )

        return json.encodeToString(foldersJson)
    }

    /**
     * Deserialize JSON string to list of Folder entities.
     * Skips folders with corrupted dates.
     * @param jsonString JSON string in folders.json format
     * @return List of Folder entities
     */
    fun deserializeFolders(jsonString: String): List<Folder> {
        val foldersJson = json.decodeFromString<FoldersJson>(jsonString)

        return foldersJson.folders.mapNotNull { dto ->
            val created = parseIso8601(dto.createdAt)
            val updated = parseIso8601(dto.updatedAt)

            if (created == null || updated == null) {
                logCallStack(reason = "Skipping folder ${dto.id} due to corrupted timestamps.")
                return@mapNotNull null
            }

            Folder(
                id = dto.id,
                title = dto.title,
                parentFolderId = dto.parentFolderId,
                createdAt = created,
                updatedAt = updated
            )
        }
    }

    /**
     * Get server timestamp from folders.json.
     * @param jsonString JSON string in folders.json format
     * @return Server timestamp as Date, or null if parsing fails
     */
    fun getServerTimestamp(jsonString: String): Date? {
        return try {
            val foldersJson = json.decodeFromString<FoldersJson>(jsonString)
            parseIso8601(foldersJson.serverTimestamp)
        } catch (e: Exception) {
            logCallStack(reason = "Failed to parse server timestamp from folders.json: ${e.message}")
            null
        }
    }

    /**
     * Parse ISO 8601 date string safely using modern java.time API.
     * Converts back to java.util.Date for Room DB compatibility.
     */
    private fun parseIso8601(dateString: String): Date? {
        return try {
            Date.from(Instant.parse(dateString))
        } catch (e: DateTimeParseException) {
            logCallStack(reason = "Failed to parse ISO 8601 date '$dateString': ${e.message}")
            null
        }
    }

    /**
     * Data transfer object for folder in JSON format.
     *
     * This deliberately duplicates the fields of the Folder Room entity. The Room entity uses
     * Java Date for timestamps and carries Room annotations; FolderDto uses plain Strings so
     * it can be handled by kotlinx.serialization without a custom serializer.
     */
    @Serializable
    private data class FolderDto(
        val id: String,
        val title: String,
        val parentFolderId: String? = null,
        val createdAt: String,
        val updatedAt: String
    )

    /**
     * Root JSON structure for folders.json file.
     */
    @Serializable
    private data class FoldersJson(
        val version: Int,
        val folders: List<FolderDto>,
        val serverTimestamp: String
    )
}