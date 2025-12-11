package com.ethran.notable.sync

import android.content.Context
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serializer for notebooks, pages, strokes, and images to/from JSON format for WebDAV sync.
 */
class NotebookSerializer(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val iso8601Format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
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
            createdAt = iso8601Format.format(notebook.createdAt),
            updatedAt = iso8601Format.format(notebook.updatedAt),
            serverTimestamp = iso8601Format.format(Date())
        )

        return json.encodeToString(manifestDto)
    }

    /**
     * Deserialize manifest.json to Notebook entity.
     * @param jsonString JSON string in manifest.json format
     * @return Notebook entity
     */
    fun deserializeManifest(jsonString: String): Notebook {
        val manifestDto = json.decodeFromString<NotebookManifestDto>(jsonString)

        return Notebook(
            id = manifestDto.notebookId,
            title = manifestDto.title,
            openPageId = manifestDto.openPageId,
            pageIds = manifestDto.pageIds,
            parentFolderId = manifestDto.parentFolderId,
            defaultBackground = manifestDto.defaultBackground,
            defaultBackgroundType = manifestDto.defaultBackgroundType,
            linkedExternalUri = manifestDto.linkedExternalUri,
            createdAt = parseIso8601(manifestDto.createdAt),
            updatedAt = parseIso8601(manifestDto.updatedAt)
        )
    }

    /**
     * Serialize a page with its strokes and images to JSON format.
     * @param page Page entity
     * @param strokes List of Stroke entities for this page
     * @param images List of Image entities for this page
     * @return JSON string for {page-id}.json
     */
    fun serializePage(page: Page, strokes: List<Stroke>, images: List<Image>): String {
        val strokeDtos = strokes.map { stroke ->
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
                points = stroke.points.map { point ->
                    StrokePointDto(
                        x = point.x,
                        y = point.y,
                        pressure = point.pressure,
                        tiltX = point.tiltX,
                        tiltY = point.tiltY,
                        dt = point.dt?.toInt()
                    )
                },
                createdAt = iso8601Format.format(stroke.createdAt),
                updatedAt = iso8601Format.format(stroke.updatedAt)
            )
        }

        val imageDtos = images.map { image ->
            ImageDto(
                id = image.id,
                x = image.x,
                y = image.y,
                width = image.width,
                height = image.height,
                uri = convertToRelativeUri(image.uri),  // Convert to relative path
                createdAt = iso8601Format.format(image.createdAt),
                updatedAt = iso8601Format.format(image.updatedAt)
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
            createdAt = iso8601Format.format(page.createdAt),
            updatedAt = iso8601Format.format(page.updatedAt),
            strokes = strokeDtos,
            images = imageDtos
        )

        return json.encodeToString(pageDto)
    }

    /**
     * Deserialize page JSON to Page, Strokes, and Images.
     * @param jsonString JSON string in page format
     * @return Triple of (Page, List<Stroke>, List<Image>)
     */
    fun deserializePage(jsonString: String): Triple<Page, List<Stroke>, List<Image>> {
        val pageDto = json.decodeFromString<PageDto>(jsonString)

        val page = Page(
            id = pageDto.id,
            notebookId = pageDto.notebookId,
            background = pageDto.background,
            backgroundType = pageDto.backgroundType,
            parentFolderId = pageDto.parentFolderId,
            scroll = pageDto.scroll,
            createdAt = parseIso8601(pageDto.createdAt),
            updatedAt = parseIso8601(pageDto.updatedAt)
        )

        val strokes = pageDto.strokes.map { strokeDto ->
            Stroke(
                id = strokeDto.id,
                size = strokeDto.size,
                pen = Pen.valueOf(strokeDto.pen),
                color = strokeDto.color,
                maxPressure = strokeDto.maxPressure,
                top = strokeDto.top,
                bottom = strokeDto.bottom,
                left = strokeDto.left,
                right = strokeDto.right,
                points = strokeDto.points.map { pointDto ->
                    StrokePoint(
                        x = pointDto.x,
                        y = pointDto.y,
                        pressure = pointDto.pressure,
                        tiltX = pointDto.tiltX,
                        tiltY = pointDto.tiltY,
                        dt = pointDto.dt?.toUShort()
                    )
                },
                pageId = pageDto.id,
                createdAt = parseIso8601(strokeDto.createdAt),
                updatedAt = parseIso8601(strokeDto.updatedAt)
            )
        }

        val images = pageDto.images.map { imageDto ->
            Image(
                id = imageDto.id,
                x = imageDto.x,
                y = imageDto.y,
                width = imageDto.width,
                height = imageDto.height,
                uri = imageDto.uri,  // Will be converted to absolute path when restored
                pageId = pageDto.id,
                createdAt = parseIso8601(imageDto.createdAt),
                updatedAt = parseIso8601(imageDto.updatedAt)
            )
        }

        return Triple(page, strokes, images)
    }

    /**
     * Convert absolute file URI to relative path for WebDAV storage.
     * Example: /storage/emulated/0/Documents/notabledb/images/abc123.jpg -> images/abc123.jpg
     */
    private fun convertToRelativeUri(absoluteUri: String?): String {
        if (absoluteUri == null) return ""

        // Extract just the filename and parent directory
        val file = File(absoluteUri)
        val parentDir = file.parentFile?.name ?: ""
        val filename = file.name

        return if (parentDir.isNotEmpty()) {
            "$parentDir/$filename"
        } else {
            filename
        }
    }

    /**
     * Parse ISO 8601 date string to Date object.
     */
    private fun parseIso8601(dateString: String): Date {
        return try {
            iso8601Format.parse(dateString) ?: Date()
        } catch (e: Exception) {
            Date()
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
            null
        }
    }

    /**
     * Get updated timestamp from page JSON.
     */
    fun getPageUpdatedAt(jsonString: String): Date? {
        return try {
            val pageDto = json.decodeFromString<PageDto>(jsonString)
            parseIso8601(pageDto.updatedAt)
        } catch (e: Exception) {
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
        val points: List<StrokePointDto>,
        val createdAt: String,
        val updatedAt: String
    )

    @Serializable
    private data class StrokePointDto(
        val x: Float,
        val y: Float,
        val pressure: Float? = null,
        val tiltX: Int? = null,
        val tiltY: Int? = null,
        val dt: Int? = null
    )

    @Serializable
    private data class ImageDto(
        val id: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val uri: String,
        val createdAt: String,
        val updatedAt: String
    )
}
