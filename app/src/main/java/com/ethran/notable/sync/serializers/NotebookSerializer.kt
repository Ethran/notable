package com.ethran.notable.sync.serializers

import com.ethran.notable.data.datastore.PageMode
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.data.db.encodeStrokePoints
import com.ethran.notable.data.db.withNormalizedPressure
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.logCallStack
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
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

    // Compact (no pretty-print) JSON used by the streaming page serializer: smaller output and,
    // more importantly, each element is encoded on its own so a whole-page JSON string is never
    // materialised. deserializePage ignores whitespace, so compact output stays compatible.
    private val compactJson = Json {
        prettyPrint = false
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
                return AppResult.Error(
                    DomainError.UnexpectedState("Manifest contains corrupted timestamps", recoverable = false)
                )
            }

            AppResult.Success(
                Notebook(
                    id = manifestDto.notebookId,
                    title = manifestDto.title,
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
            AppResult.Error(
                DomainError.UnexpectedState("Failed to decode manifest JSON: ${e.message}", recoverable = false)
            )
        } catch (e: Exception) {
            AppResult.Error(
                DomainError.UnexpectedState(
                    "Unexpected error during manifest deserialization: ${e.message}", recoverable = false
                )
            )
        }
    }

    /**
     * Serialize a page with its strokes and images to JSON, **streaming** directly to [output].
     *
     * Strokes and images are encoded one element at a time and written immediately, so the whole
     * page is never held in memory as a `List<StrokeDto>` nor as a single giant JSON string.
     * Peak extra memory
     * here is one stroke's binary + base64 plus the IO buffer, independent of page size.
     *
     * The page's scalar fields are emitted via [PageHeaderDto]; the `strokes`/`images` arrays are
     * spliced in after it. The result parses back to [PageDto] unchanged.
     */
    fun serializePage(page: Page, strokes: List<Stroke>, images: List<Image>): String {
        val out = java.io.ByteArrayOutputStream()
        serializePage(page, strokes, images, out)
        return out.toString("UTF-8")
    }

    fun serializePage(page: Page, strokes: List<Stroke>, images: List<Image>, output: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))

        val header = PageHeaderDto(
            version = 1,
            id = page.id,
            notebookId = page.notebookId,
            background = page.background,
            backgroundType = page.backgroundType,
            parentFolderId = page.parentFolderId,
            pageMode = page.pageMode,
            scroll = page.scroll,
            createdAt = page.createdAt.toInstant().toString(),
            updatedAt = page.updatedAt.toInstant().toString()
        )
        // Write the page object's scalar fields, drop the closing brace, then splice the arrays.
        val headerJson = compactJson.encodeToString(header)
        writer.write(headerJson.substring(0, headerJson.length - 1))

        writer.write(",\"strokes\":[")
        var first = true
        for (stroke in strokes) {
            if (!first) writer.write(",")
            first = false
            val base64Data = Base64.getEncoder().encodeToString(encodeStrokePoints(stroke.points))
            val dto = StrokeDto(
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
            writer.write(compactJson.encodeToString(dto))
        }

        writer.write("],\"images\":[")
        first = true
        for (image in images) {
            if (!first) writer.write(",")
            first = false
            val dto = ImageDto(
                id = image.id,
                x = image.x,
                y = image.y,
                width = image.width,
                height = image.height,
                uri = convertToRelativeUri(image.uri),
                createdAt = image.createdAt.toInstant().toString(),
                updatedAt = image.updatedAt.toInstant().toString()
            )
            writer.write(compactJson.encodeToString(dto))
        }
        writer.write("]}")
        writer.flush()
    }

    /**
     * Deserialize page JSON with embedded base64-encoded SB binary stroke data safely.
     * Corrupted individual strokes or images are skipped to save the rest of the page.
     */
    fun deserializePage(jsonString: String): AppResult<Triple<Page, List<Stroke>, List<Image>>, DomainError> {
        return try {
            val pageDto = json.decodeFromString<PageDto>(jsonString)
            val pageCreated = parseIso8601(pageDto.createdAt)
            val pageUpdated = parseIso8601(pageDto.updatedAt)

            if (pageCreated == null || pageUpdated == null) {
                return AppResult.Error(
                    DomainError.UnexpectedState("Page contains corrupted timestamps", recoverable = false)
                )
            }

            val page = Page(
                id = pageDto.id,
                notebookId = pageDto.notebookId,
                background = pageDto.background,
                backgroundType = pageDto.backgroundType,
                parentFolderId = pageDto.parentFolderId,
                scroll = pageDto.scroll,
                createdAt = pageCreated,
                updatedAt = pageUpdated,
                pageMode = pageDto.pageMode
            )

            val strokes = pageDto.strokes.mapNotNull { strokeDto ->
                try {
                    val created = parseIso8601(strokeDto.createdAt)
                    val updated = parseIso8601(strokeDto.updatedAt)
                    if (created == null || updated == null) {
                        logCallStack(reason = "Skipping stroke ${strokeDto.id} due to corrupted timestamps.")
                        return@mapNotNull null
                    }

                    val binaryData = Base64.getDecoder().decode(strokeDto.pointsData)
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
                        // Remote data may carry raw-scale pressure; normalize to [0, 1].
                    ).withNormalizedPressure()
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
            // A parse failure is deterministic: the same server bytes will fail identically every
            // time (e.g. a truncated page from a torn upload), so it must not drive a retry loop.
            AppResult.Error(
                DomainError.UnexpectedState("Failed to decode page JSON: ${e.message}", recoverable = false)
            )
        } catch (e: Exception) {
            AppResult.Error(
                DomainError.UnexpectedState(
                    "Unexpected error during page deserialization: ${e.message}", recoverable = false
                )
            )
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
        val parentFolderId: String?,
        val defaultBackground: String,
        val defaultBackgroundType: String,
        val linkedExternalUri: String?,
        val createdAt: String,
        val updatedAt: String,
        val serverTimestamp: String
    )

    // Scalar page fields only — the streaming serializer emits this, then splices the
    // strokes/images arrays after it. Field names/order match [PageDto]'s scalar prefix so the
    // spliced result parses back as a [PageDto].
    @Serializable
    private data class PageHeaderDto(
        val version: Int,
        val id: String,
        val notebookId: String?,
        val background: String,
        val backgroundType: String,
        val parentFolderId: String?,
        val pageMode: PageMode?,
        val scroll: Int,
        val createdAt: String,
        val updatedAt: String
    )

    @Serializable
    private data class PageDto(
        val version: Int,
        val id: String,
        val notebookId: String?,
        val background: String,
        val backgroundType: String,
        val parentFolderId: String?,
        val pageMode: PageMode? = null,
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