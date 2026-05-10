package com.ethran.notable.sync.serializers

import android.util.Base64
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.data.db.encodeStrokePoints
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.logCallStack
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date

/**
 * Serializer for notebooks, pages, strokes, and images to/from JSON format for WebDAV sync.
 * Utilizing java.time.Instant for modern, thread-safe ISO 8601 parsing.
 */
object NotebookSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Serialize notebook metadata to manifest.json format.
     * @param notebook Notebook entity from database
     * @return JSON string for manifest.json
     */
    fun serializeManifest(notebook: Notebook): String {
        val manifestDto = NotebookManifestDto(
            version = 1,
            notebookId = notebook.id,
            title = notebook.title,
            pageIds = notebook.pageIds,
            openPageId = notebook.openPageId,
            parentFolderId = notebook.parentFolderId,
            defaultBackground = notebook.defaultBackground,
            defaultBackgroundType = notebook.defaultBackgroundType,
            linkedExternalUri = notebook.linkedExternalUri,
            createdAt = notebook.createdAt.toInstant().toString(),
            updatedAt = notebook.updatedAt.toInstant().toString(),
            serverTimestamp = Instant.now().toString()
        )

        return json.encodeToString(manifestDto)
    }

    /**
     * Deserialize manifest.json to Notebook entity safely.
     * @param jsonString JSON string in manifest.json format
     * @return AppResult containing Notebook entity or DomainError
     */
    fun deserializeManifest(jsonString: String): AppResult<Notebook, DomainError> {
        return try {
            val manifestDto = json.decodeFromString<NotebookManifestDto>(jsonString)
            val createdAt = parseIso8601(manifestDto.createdAt)
            val updatedAt = parseIso8601(manifestDto.updatedAt)

            if (createdAt == null || updatedAt == null) {
                return AppResult.Error(DomainError.UnexpectedState("Manifest contains corrupted timestamps"))
            }

            AppResult.Success(
                Notebook(
                    id = manifestDto.notebookId,
                    title = manifestDto.title,
                    openPageId = manifestDto.openPageId,
                    pageIds = manifestDto.pageIds,
                    parentFolderId = manifestDto.parentFolderId,
                    defaultBackground = manifestDto.defaultBackground,
                    defaultBackgroundType = manifestDto.defaultBackgroundType,
                    linkedExternalUri = manifestDto.linkedExternalUri,
                    createdAt = createdAt,
                    updatedAt = updatedAt
                )
            )
        } catch (e: SerializationException) {
            AppResult.Error(DomainError.UnexpectedState("Failed to decode manifest JSON: ${e.message}"))
        } catch (e: Exception) {
            AppResult.Error(DomainError.UnexpectedState("Unexpected error during manifest deserialization: ${e.message}"))
        }
    }

    /**
     * Serialize a page with its strokes and images to JSON format.
     * Stroke points are embedded as base64-encoded SB1 binary format.
     */
    fun serializePage(page: Page, strokes: List<Stroke>, images: List<Image>): String {
        val strokeDtos = strokes.map { stroke ->
            val binaryData = encodeStrokePoints(stroke.points)
            val base64Data = Base64.encodeToString(binaryData, Base64.NO_WRAP)

            StrokeDto(
                id = stroke.id,
                size = stroke.size,
                pen = stroke.pen.name,
                color = stroke.color,
                maxPressure = stroke.maxPressure,
                top = stroke.top,
                bottom = stroke.bottom,
                left = stroke.left,
                right = stroke.right,
                pointsData = base64Data,
                createdAt = stroke.createdAt.toInstant().toString(),
                updatedAt = stroke.updatedAt.toInstant().toString()
            )
        }

        val imageDtos = images.map { image ->
            ImageDto(
                id = image.id,
                x = image.x,
                y = image.y,
                width = image.width,
                height = image.height,
                uri = convertToRelativeUri(image.uri),
                createdAt = image.createdAt.toInstant().toString(),
                updatedAt = image.updatedAt.toInstant().toString()
            )
        }

        val pageDto = PageDto(
            version = 1,
            id = page.id,
            notebookId = page.notebookId,
            background = page.background,
            backgroundType = page.backgroundType,
            parentFolderId = page.parentFolderId,
            scroll = page.scroll,
            createdAt = page.createdAt.toInstant().toString(),
            updatedAt = page.updatedAt.toInstant().toString(),
            strokes = strokeDtos,
            images = imageDtos
        )

        return json.encodeToString(pageDto)
    }

    /**
     * Deserialize page JSON with embedded base64-encoded SB1 binary stroke data safely.
     * Corrupted individual strokes or images are skipped to save the rest of the page.
     */
    fun deserializePage(jsonString: String): AppResult<Triple<Page, List<Stroke>, List<Image>>, DomainError> {
        return try {
            val pageDto = json.decodeFromString<PageDto>(jsonString)
            val pageCreated = parseIso8601(pageDto.createdAt)
            val pageUpdated = parseIso8601(pageDto.updatedAt)

            if (pageCreated == null || pageUpdated == null) {
                return AppResult.Error(DomainError.UnexpectedState("Page contains corrupted timestamps"))
            }

            val page = Page(
                id = pageDto.id,
                notebookId = pageDto.notebookId,
                background = pageDto.background,
                backgroundType = pageDto.backgroundType,
                parentFolderId = pageDto.parentFolderId,
                scroll = pageDto.scroll,
                createdAt = pageCreated,
                updatedAt = pageUpdated
            )

            val strokes = pageDto.strokes.mapNotNull { strokeDto ->
                try {
                    val created = parseIso8601(strokeDto.createdAt)
                    val updated = parseIso8601(strokeDto.updatedAt)
                    if (created == null || updated == null) {
                        logCallStack(reason = "Skipping stroke ${strokeDto.id} due to corrupted timestamps.")
                        return@mapNotNull null
                    }

                    val binaryData = Base64.decode(strokeDto.pointsData, Base64.NO_WRAP)
                    val points = decodeStrokePoints(binaryData)
                    val penEnum = Pen.valueOf(strokeDto.pen)

                    Stroke(
                        id = strokeDto.id,
                        size = strokeDto.size,
                        pen = penEnum,
                        color = strokeDto.color,
                        maxPressure = strokeDto.maxPressure,
                        top = strokeDto.top,
                        bottom = strokeDto.bottom,
                        left = strokeDto.left,
                        right = strokeDto.right,
                        points = points,
                        pageId = pageDto.id,
                        createdAt = created,
                        updatedAt = updated
                    )
                } catch (e: Exception) {
                    logCallStack(reason = "Skipping corrupted stroke ${strokeDto.id}: ${e.message}")
                    null
                }
            }

            val images = pageDto.images.mapNotNull { imageDto ->
                try {
                    val created = parseIso8601(imageDto.createdAt)
                    val updated = parseIso8601(imageDto.updatedAt)
                    if (created == null || updated == null) {
                        logCallStack(reason = "Skipping image ${imageDto.id} due to corrupted timestamps.")
                        return@mapNotNull null
                    }

                    Image(
                        id = imageDto.id,
                        x = imageDto.x,
                        y = imageDto.y,
                        width = imageDto.width,
                        height = imageDto.height,
                        uri = imageDto.uri,
                        pageId = pageDto.id,
                        createdAt = created,
                        updatedAt = updated
                    )
                } catch (e: Exception) {
                    logCallStack(reason = "Skipping corrupted image ${imageDto.id}: ${e.message}")
                    null
                }
            }

            AppResult.Success(Triple(page, strokes, images))
        } catch (e: SerializationException) {
            AppResult.Error(DomainError.UnexpectedState("Failed to decode page JSON: ${e.message}"))
        } catch (e: Exception) {
            AppResult.Error(DomainError.UnexpectedState("Unexpected error during page deserialization: ${e.message}"))
        }
    }

    /**
     * Convert absolute file URI to relative path for WebDAV storage.
     * Example: /storage/emulated/0/Documents/notabledb/images/abc123.jpg -> images/abc123.jpg
     */
    private fun convertToRelativeUri(absoluteUri: String?): String? {
        if (absoluteUri == null) return null
        val file = File(absoluteUri)
        val parentDir = file.parentFile?.name ?: ""
        val filename = file.name
        return if (parentDir.isNotEmpty()) "$parentDir/$filename" else filename
    }

    /**
     * Parse ISO 8601 date string safely using modern java.time API.
     * Converts back to java.util.Date for Room DB compatibility.
     * Returns null on failure instead of a fake date.
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
     * Get updated timestamp from manifest JSON.
     */
    fun getManifestUpdatedAt(jsonString: String): Date? {
        return try {
            val manifestDto = json.decodeFromString<NotebookManifestDto>(jsonString)
            parseIso8601(manifestDto.updatedAt)
        } catch (e: Exception) {
            logCallStack(reason = "Failed to parse updated timestamp from manifest.json: ${e.message}")
            null
        }
    }

    // ===== Data Transfer Objects =====

    @Serializable
    private data class NotebookManifestDto(
        val version: Int,
        val notebookId: String,
        val title: String,
        val pageIds: List<String>,
        val openPageId: String?,
        val parentFolderId: String?,
        val defaultBackground: String,
        val defaultBackgroundType: String,
        val linkedExternalUri: String?,
        val createdAt: String,
        val updatedAt: String,
        val serverTimestamp: String
    )

    @Serializable
    private data class PageDto(
        val version: Int,
        val id: String,
        val notebookId: String?,
        val background: String,
        val backgroundType: String,
        val parentFolderId: String?,
        val scroll: Int,
        val createdAt: String,
        val updatedAt: String,
        val strokes: List<StrokeDto>,
        val images: List<ImageDto>
    )

    @Serializable
    private data class StrokeDto(
        val id: String,
        val size: Float,
        val pen: String,
        val color: Int,
        val maxPressure: Int,
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float,
        val pointsData: String,
        val createdAt: String,
        val updatedAt: String
    )

    @Serializable
    private data class ImageDto(
        val id: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val uri: String?,
        val createdAt: String,
        val updatedAt: String
    )
}