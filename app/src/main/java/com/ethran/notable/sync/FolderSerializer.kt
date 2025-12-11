package com.ethran.notable.sync

import com.ethran.notable.data.db.Folder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serializer for folder hierarchy to/from JSON format for WebDAV sync.
 */
object FolderSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val iso8601Format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
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
                createdAt = iso8601Format.format(folder.createdAt),
                updatedAt = iso8601Format.format(folder.updatedAt)
            )
        }

        val foldersJson = FoldersJson(
            version = 1,
            folders = folderDtos,
            serverTimestamp = iso8601Format.format(Date())
        )

        return json.encodeToString(foldersJson)
    }

    /**
     * Deserialize JSON string to list of Folder entities.
     * @param jsonString JSON string in folders.json format
     * @return List of Folder entities
     */
    fun deserializeFolders(jsonString: String): List<Folder> {
        val foldersJson = json.decodeFromString<FoldersJson>(jsonString)

        return foldersJson.folders.map { dto ->
            Folder(
                id = dto.id,
                title = dto.title,
                parentFolderId = dto.parentFolderId,
                createdAt = parseIso8601(dto.createdAt),
                updatedAt = parseIso8601(dto.updatedAt)
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
            null
        }
    }

    /**
     * Parse ISO 8601 date string to Date object.
     */
    private fun parseIso8601(dateString: String): Date {
        return iso8601Format.parse(dateString) ?: Date()
    }

    /**
     * Data transfer object for folder in JSON format.
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
