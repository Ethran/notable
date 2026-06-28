package com.ethran.notable.io

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Xml
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.ethran.notable.BuildConfig
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.datastore.A4_WIDTH
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.utils.ensureNotMainThread
import com.onyx.android.sdk.api.device.epd.EpdController
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

private const val PRESSURE_FACTOR = 0.5f

/**
 * How many strokes are handed off to [importBook]'s onStrokeBatch callback before the next
 * batch starts. Keeping this bounded means peak memory during import is proportional to one
 * batch, not one full page.
 */
private const val STROKE_SAVE_BATCH_SIZE = 500

class XoppFile @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageRepo: PageRepository,
    private val bookRepo: BookRepository,
    private val appEventBus: AppEventBus,
) {
    private val log = ShipBook.getLogger("XoppFile")
    private val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH
    private val maxPressure = EpdController.getMaxTouchPressure()

    /**
     * Holds mutable buffers that are allocated once per import operation and reused across
     * every stroke on every page. This eliminates the per-stroke heap churn that would
     * otherwise cause repeated GC cycles when importing notebooks with many strokes.
     *
     * Kept local to importBook (not a class field) so concurrent imports each get their own
     * independent state with no risk of data races.
     */
    private class ParseState {
        /** Accumulates the raw text content of a <stroke> element. Cleared, never re-created. */
        val textBuffer = StringBuilder(256)

        /**
         * Reusable float storage for stroke point coordinates.
         * Grows to fit the largest stroke seen so far, then stays at that size.
         */
        var coordsBuffer = FloatArray(128)

        /**
         * Reusable float storage for the width attribute (strokeWidth + per-point pressures).
         * Grows to fit the largest pressure array seen, then stays at that size.
         */
        var widthsBuffer = FloatArray(16)
    }

    // -----------------------------------------------------------------------------------------
    // Export
    // -----------------------------------------------------------------------------------------

    suspend fun writeToXoppStream(target: ExportTarget, output: OutputStream) =
        withContext(Dispatchers.IO) {
            val tmp = File(
                context.cacheDir, when (target) {
                    is ExportTarget.Book -> "notable_xopp_book.xml"
                    is ExportTarget.Page -> "notable_xopp_page.xml"
                }
            )

            try {
                BufferedWriter(
                    OutputStreamWriter(
                        FileOutputStream(tmp),
                        Charsets.UTF_8
                    )
                ).use { writer ->
                    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    writer.write("<xournal creator=\"Notable ${BuildConfig.VERSION_NAME}\" version=\"0.4\">\n")
                    when (target) {
                        is ExportTarget.Book -> {
                            val book = bookRepo.getById(target.bookId)
                                ?: throw IOException("Book not found: ${target.bookId}")
                            book.pageIds.forEach { pageId ->
                                writePage(pageId, writer)
                            }
                        }

                        is ExportTarget.Page -> {
                            writePage(target.pageId, writer)
                        }
                    }
                    writer.write("</xournal>\n")
                }

                GzipCompressorOutputStream(BufferedOutputStream(output)).use { gz ->
                    tmp.inputStream().use { it.copyTo(gz) }
                }
            } finally {
                if (tmp.exists() && !tmp.delete()) {
                    log.w("Failed to delete temporary export file: ${tmp.absolutePath}")
                }
            }
        }

    private suspend fun writePage(pageId: String, writer: BufferedWriter) =
        withContext(Dispatchers.IO) {
            val pageWithData = pageRepo.getWithDataById(pageId) ?: return@withContext
            val strokes = pageWithData.strokes
            val images = pageWithData.images
            val strokeHeight =
                if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50
            val height = strokeHeight.coerceAtLeast(SCREEN_HEIGHT) * scaleFactor

            writer.write("<page width=\"")
            writer.write(A4_WIDTH.toString())
            writer.write("\" height=\"")
            writer.write(height.toString())
            writer.write("\">\n")
            writer.write("<background type=\"solid\" color=\"#ffffffff\" style=\"plain\"/>\n")
            writer.write("<layer>\n")

            for (stroke in strokes) {
                if (stroke.points.size < 3) continue

                writer.write("<stroke tool=\"")
                writer.write(escapeXml(stroke.pen.toString()))
                writer.write("\" color=\"")
                writer.write(escapeXml(getColorName(Color(stroke.color))))
                writer.write("\" width=\"")
                writer.write((stroke.size * scaleFactor).toString())

                if ((stroke.pen == Pen.FOUNTAIN) || (stroke.pen == Pen.BRUSH) || (stroke.pen == Pen.PENCIL)) {
                    stroke.points.forEach { point ->
                        writer.write(" ")
                        writer.write(
                            (point.pressure?.div(stroke.maxPressure * PRESSURE_FACTOR)
                                ?: 1f).toString()
                        )
                    }
                }

                writer.write("\">")
                var firstPoint = true
                stroke.points.forEach { point ->
                    if (!firstPoint) writer.write(" ")
                    writer.write((point.x * scaleFactor).toString())
                    writer.write(" ")
                    writer.write((point.y * scaleFactor).toString())
                    firstPoint = false
                }
                writer.write("</stroke>\n")
            }

            for (image in images) {
                val left = image.x * scaleFactor
                val top = image.y * scaleFactor
                val right = (image.x + image.width) * scaleFactor
                val bottom = (image.y + image.height) * scaleFactor

                val uri = image.uri
                if (uri.isNullOrBlank()) {
                    appEventBus.tryEmit(AppEvent.ActionHint("Image cannot be loaded."))
                    continue
                }

                writer.write("<image left=\"")
                writer.write(left.toString())
                writer.write("\" top=\"")
                writer.write(top.toString())
                writer.write("\" right=\"")
                writer.write(right.toString())
                writer.write("\" bottom=\"")
                writer.write(bottom.toString())
                writer.write("\" filename=\"")
                writer.write(escapeXml(uri))
                writer.write("\">")

                val imageWasWritten = writeImageBase64ToWriter(uri, writer)
                writer.write("</image>\n")

                if (!imageWasWritten) {
                    appEventBus.tryEmit(AppEvent.ActionHint("Image cannot be loaded."))
                }
            }

            writer.write("</layer>\n")
            writer.write("</page>\n")
        }

    private fun writeImageBase64ToWriter(uri: String, writer: BufferedWriter): Boolean {
        return try {
            context.contentResolver.openInputStream(uri.toUri())?.use { inputStream ->
                val buffer = ByteArray(DEFAULT_IMAGE_CHUNK_SIZE)
                val tail = ByteArray(3)
                var tailSize = 0
                var hasData = false

                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break

                    var offset = 0
                    if (tailSize > 0) {
                        val needed = 3 - tailSize
                        if (bytesRead >= needed) {
                            System.arraycopy(buffer, 0, tail, tailSize, needed)
                            writer.write(Base64.getEncoder().encodeToString(tail))
                            hasData = true
                            tailSize = 0
                            offset = needed
                        } else {
                            System.arraycopy(buffer, 0, tail, tailSize, bytesRead)
                            tailSize += bytesRead
                            continue
                        }
                    }

                    val encodableBytes = ((bytesRead - offset) / 3) * 3
                    if (encodableBytes > 0) {
                        writer.write(
                            Base64.getEncoder().encodeToString(
                                buffer.copyOfRange(offset, offset + encodableBytes)
                            )
                        )
                        hasData = true
                        offset += encodableBytes
                    }

                    val remainder = bytesRead - offset
                    if (remainder > 0) {
                        System.arraycopy(buffer, offset, tail, 0, remainder)
                        tailSize = remainder
                    }
                }

                if (tailSize > 0) {
                    writer.write(
                        Base64.getEncoder().encodeToString(tail.copyOfRange(0, tailSize))
                    )
                    hasData = true
                }
                hasData
            } ?: false
        } catch (e: Exception) {
            log.e("convertImageToBase64: ${e.message}")
            false
        }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Import
    // -----------------------------------------------------------------------------------------

    /**
     * Imports a .xopp file as a book, streaming strokes to the caller in bounded batches so
     * that peak memory is proportional to [STROKE_SAVE_BATCH_SIZE], not to an entire page.
     *
     * **Caller contract** (replacing the old single `savePageToDatabase` lambda):
     *
     * 1. [onPageCreated]  — called once when a `<page>` element opens. Insert the [Page]
     *    record into the database here so strokes (which reference `page.id`) can follow.
     *
     * 2. [onStrokeBatch]  — called one or more times per page with up to
     *    [STROKE_SAVE_BATCH_SIZE] strokes. Use a bulk/batch Room insert here. Each call hands
     *    off ownership of the list; the caller must not hold a reference after returning.
     *
     * 3. [onPageFinalized] — called once when all strokes and images for the page have been
     *    delivered. The [images] list is complete at this point.
     *
     * Example migration in ImportEngine (or wherever importBook is called):
     * ```
     * // OLD:
     * xoppFile.importBook(uri) { pageWithData ->
     *     pageRepo.insertPageWithData(pageWithData)
     * }
     *
     * // NEW:
     * xoppFile.importBook(
     *     uri,
     *     onPageCreated   = { page   -> pageRepo.insertPage(page) },
     *     onStrokeBatch   = { batch  -> strokeRepo.insertAll(batch) },
     *     onPageFinalized = { pageId, images -> imageRepo.insertAll(images) },
     * )
     * ```
     */
    suspend fun importBook(
        uri: Uri,
        onPageCreated: suspend (Page) -> Unit,
        onStrokeBatch: suspend (List<Stroke>) -> Unit,
        onPageFinalized: suspend (pageId: String, images: List<Image>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        log.v("Importing book from $uri")
        ensureNotMainThread("xoppImportBook")

        val parseState = ParseState()

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                GzipCompressorInputStream(BufferedInputStream(inputStream)).use { gzipIn ->
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(gzipIn, null)

                    var eventType = parser.eventType
                    var pageCount = 0
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && parser.name == "page") {
                            val page = Page()
                            onPageCreated(page)
                            val images = parsePageContentStreaming(
                                parser, page, parseState, onStrokeBatch
                            )
                            onPageFinalized(page.id, images)
                            pageCount++
                        }
                        eventType = parser.next()
                    }
                    log.i("Successfully imported book with $pageCount pages.")
                }
            }
        } catch (e: Exception) {
            log.e("Error importing book from $uri: ${e.message}")
        }
    }

    /**
     * Parses the content of one `<page>` element, flushing strokes to [onStrokeBatch] in
     * batches of [STROKE_SAVE_BATCH_SIZE]. Returns the complete list of images for the page
     * (images are few so collecting them is fine).
     *
     * Ownership of each batch ArrayList is transferred to the caller on each [onStrokeBatch]
     * invocation; a fresh list is started immediately after, so old stroke objects become
     * unreachable as soon as the caller's suspend function returns.
     */
    private suspend fun parsePageContentStreaming(
        parser: XmlPullParser,
        page: Page,
        state: ParseState,
        onStrokeBatch: suspend (List<Stroke>) -> Unit,
    ): List<Image> {
        val images = mutableListOf<Image>()
        // Pre-sized to the batch limit so the backing array is never re-allocated mid-batch.
        var strokeBatch = ArrayList<Stroke>(STROKE_SAVE_BATCH_SIZE)

        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT &&
            !(eventType == XmlPullParser.END_TAG && parser.name == "page")
        ) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "stroke" -> {
                        parseStrokeStreaming(parser, page, state)?.let { stroke ->
                            strokeBatch.add(stroke)
                            if (strokeBatch.size >= STROKE_SAVE_BATCH_SIZE) {
                                // Hand off ownership of this batch to the caller, then start
                                // a fresh list. Old Stroke/StrokePoint objects become
                                // unreachable as soon as onStrokeBatch returns.
                                onStrokeBatch(strokeBatch)
                                strokeBatch = ArrayList(STROKE_SAVE_BATCH_SIZE)
                            }
                        }
                    }

                    "image" -> parseImageStreaming(parser, page)?.let { images.add(it) }
                }
            }
            eventType = parser.next()
        }

        // Flush the final partial batch (if any).
        if (strokeBatch.isNotEmpty()) {
            onStrokeBatch(strokeBatch)
        }

        return images
    }

    // -----------------------------------------------------------------------------------------
    // Float parsing — zero per-stroke allocation after warm-up
    // -----------------------------------------------------------------------------------------

    /**
     * Parses whitespace-separated floats from [input] into [buf], growing [buf] only when
     * the current capacity is exhausted. Returns the (possibly grown) buffer and the count
     * of values written.
     *
     * Single-pass, no preliminary space-count scan, no intermediate String or List
     * allocation. The caller keeps the returned buffer reference across calls so it survives
     * as a long-lived object and is never re-allocated once it has grown to the maximum
     * stroke size encountered.
     */
    private fun extractFloatsInto(input: CharSequence, buf: FloatArray): Pair<FloatArray, Int> {
        var result = buf
        var resultIdx = 0
        val len = input.length
        var start = 0

        while (start < len) {
            while (start < len && input[start].isWhitespace()) start++
            if (start >= len) break

            var end = start
            while (end < len && !input[end].isWhitespace()) end++

            try {
                val value = parseCoordinateFast(input, start, end)
                if (resultIdx == result.size) {
                    result = result.copyOf(result.size * 2)
                }
                result[resultIdx++] = value
            } catch (_: Exception) {
                // Ignore malformed tokens
            }
            start = end
        }
        return result to resultIdx
    }

    /**
     * Parses a standard decimal float directly from a [CharSequence] slice [[start], [end])
     * without allocating an intermediate String. Falls back to [String.toFloat] only for
     * rare scientific-notation values (e.g. "1.5e-3").
     */
    private fun parseCoordinateFast(input: CharSequence, start: Int, end: Int): Float {
        var isNegative = false
        var i = start
        if (i < end && input[i] == '-') {
            isNegative = true
            i++
        } else if (i < end && input[i] == '+') {
            i++
        }

        var intPart = 0.0
        var fraction = 0.0
        var divisor = 1.0
        var isFraction = false

        while (i < end) {
            when (val c = input[i]) {
                '.' -> isFraction = true
                in '0'..'9' -> {
                    val digit = c - '0'
                    if (isFraction) {
                        divisor *= 10.0
                        fraction += digit / divisor
                    } else {
                        intPart = intPart * 10.0 + digit
                    }
                }
                // Scientific notation is rare; only then pay the allocation cost
                'e', 'E' -> return input.subSequence(start, end).toString().toFloat()
                else -> return input.subSequence(start, end).toString().toFloat()
            }
            i++
        }

        val finalValue = (intPart + fraction).toFloat()
        return if (isNegative) -finalValue else finalValue
    }

    // -----------------------------------------------------------------------------------------
    // Stroke parsing
    // -----------------------------------------------------------------------------------------

    private fun parseStrokeStreaming(
        parser: XmlPullParser,
        page: Page,
        state: ParseState
    ): Stroke? {
        val toolName = parser.getAttributeValue(null, "tool") ?: ""
        val colorString = parser.getAttributeValue(null, "color") ?: "black"
        val widthString = parser.getAttributeValue(null, "width") ?: "1"

        val color = parseColor(colorString)

        // Parse width attribute (strokeWidth [pressure0 pressure1 …]) into reusable buffer.
        val (newWidthsBuf, widthCount) = extractFloatsInto(widthString, state.widthsBuffer)
        state.widthsBuffer = newWidthsBuf
        val strokeSize = if (widthCount > 0) state.widthsBuffer[0] / scaleFactor else 1.0f

        // Accumulate all TEXT children of <stroke> into the reusable buffer.
        // setLength(0) resets the internal counter with zero allocation.
        state.textBuffer.setLength(0)
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT &&
            !(eventType == XmlPullParser.END_TAG && parser.name == "stroke")
        ) {
            if (eventType == XmlPullParser.TEXT) {
                state.textBuffer.append(parser.text)
            }
            eventType = parser.next()
        }

        // Parse coordinate pairs into reusable buffer. copyOf only occurs when this stroke
        // is larger than any previously seen — after warm-up, no allocation at all.
        val (newCoordsBuf, coordCount) = extractFloatsInto(state.textBuffer, state.coordsBuffer)
        state.coordsBuffer = newCoordsBuf
        val pointsCount = coordCount / 2

        if (pointsCount == 0) return null

        val points = ArrayList<StrokePoint>(pointsCount)
        val boundingBox = RectF()

        for (i in 0 until pointsCount) {
            val x = state.coordsBuffer[i * 2] / scaleFactor
            val y = state.coordsBuffer[i * 2 + 1] / scaleFactor

            // Width attribute layout: index 0 = stroke width, indices 1..N = per-point pressure
            val pressureIdx = i + 1
            val pressure = if (pressureIdx < widthCount) {
                state.widthsBuffer[pressureIdx] * (maxPressure * PRESSURE_FACTOR)
            } else {
                0f
            }

            points.add(StrokePoint(x, y, pressure, 0, 0))
            if (i == 0) boundingBox.set(x, y, x, y) else boundingBox.union(x, y)
        }

        boundingBox.inset(-strokeSize, -strokeSize)

        return Stroke(
            size = strokeSize,
            pen = Pen.fromString(toolName),
            pageId = page.id,
            top = boundingBox.top,
            bottom = boundingBox.bottom,
            left = boundingBox.left,
            right = boundingBox.right,
            points = points,
            color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(),
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt()
            ),
            maxPressure = maxPressure.toInt()
        )
    }

    // -----------------------------------------------------------------------------------------
    // Image parsing
    // -----------------------------------------------------------------------------------------

    private fun parseImageStreaming(parser: XmlPullParser, page: Page): Image? {
        val left =
            parser.getAttributeValue(null, "left")?.toFloatOrNull()?.div(scaleFactor) ?: return null
        val top =
            parser.getAttributeValue(null, "top")?.toFloatOrNull()?.div(scaleFactor) ?: return null
        val right =
            parser.getAttributeValue(null, "right")?.toFloatOrNull()?.div(scaleFactor)
                ?: return null
        val bottom =
            parser.getAttributeValue(null, "bottom")?.toFloatOrNull()?.div(scaleFactor)
                ?: return null

        val outputDir = ensureImagesFolder()
        val fileName = "image_${UUID.randomUUID()}.png"
        val outputFile = File(outputDir, fileName)

        try {
            FileOutputStream(outputFile).use { fos ->
                val base64In = Base64.getMimeDecoder().wrap(XmlTextInputStream(parser, "image"))
                base64In.use { it.copyTo(fos) }
            }

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(outputFile.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                log.e("ImageProcessing: Invalid image data received.")
                outputFile.delete()
                return null
            }
        } catch (e: Exception) {
            log.e("ImageProcessing: Error decoding and saving image: ${e.message}")
            if (outputFile.exists()) outputFile.delete()
            return null
        }

        return Image(
            x = left.toInt(),
            y = top.toInt(),
            width = (right - left).toInt(),
            height = (bottom - top).toInt(),
            uri = Uri.fromFile(outputFile).toString(),
            pageId = page.id
        )
    }

    private class XmlTextInputStream(
        private val parser: XmlPullParser,
        private val tagName: String
    ) : InputStream() {
        private var currentText: String? = null
        private var offset = 0
        private var eof = false

        override fun read(): Int {
            if (eof) return -1
            if (currentText == null || offset >= currentText!!.length) {
                if (!fetchNextChunk()) return -1
            }
            return currentText!![offset++].code and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (currentText == null || offset >= currentText!!.length) {
                if (!fetchNextChunk()) return -1
            }

            val available = currentText!!.length - offset
            val toRead = minOf(len, available)
            for (i in 0 until toRead) {
                b[off + i] = currentText!![offset + i].code.toByte()
            }
            offset += toRead
            return toRead
        }

        private fun fetchNextChunk(): Boolean {
            while (true) {
                val eventType = parser.next()
                if (eventType == XmlPullParser.TEXT || eventType == XmlPullParser.CDSECT) {
                    currentText = parser.text
                    offset = 0
                    if (currentText!!.isNotEmpty()) return true
                } else if (eventType == XmlPullParser.END_TAG && parser.name == tagName) {
                    eof = true
                    return false
                } else if (eventType == XmlPullParser.END_DOCUMENT) {
                    eof = true
                    return false
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Color helpers
    // -----------------------------------------------------------------------------------------

    private fun parseColor(colorString: String): Color {
        return when (colorString.lowercase()) {
            "black" -> Color.Black
            "blue" -> Color.Blue
            "red" -> Color.Red
            "green" -> Color.Green
            "magenta" -> Color.Magenta
            "yellow" -> Color.Yellow
            "gray" -> Color.Gray
            else -> {
                if (colorString.startsWith("#") && colorString.length == 9) {
                    Color(
                        ("#" + colorString.substring(7, 9) + colorString.substring(
                            1,
                            7
                        )).toColorInt()
                    )
                } else {
                    log.e("Unknown color: $colorString")
                    Color.Black
                }
            }
        }
    }

    private fun getColorName(color: Color): String {
        return when (color) {
            Color.Black -> "black"
            Color.Blue -> "blue"
            Color.Red -> "red"
            Color.Green -> "green"
            Color.Magenta -> "magenta"
            Color.Yellow -> "yellow"
            Color.DarkGray, Color.Gray -> "gray"
            else -> {
                val argb = color.toArgb()
                String.format(
                    "#%02X%02X%02X%02X",
                    (argb shr 16) and 0xFF,
                    (argb shr 8) and 0xFF,
                    (argb) and 0xFF,
                    (argb shr 24) and 0xFF
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_IMAGE_CHUNK_SIZE = 16 * 1024

        fun isXoppFile(mimeType: String?, fileName: String?): Boolean {
            val isXoppFile = mimeType in listOf(
                "application/x-xopp",
                "application/gzip",
                "application/octet-stream"
            ) || fileName?.endsWith(".xopp", ignoreCase = true) == true
            Log.d("XoppFile", "isXoppFile($isXoppFile): $mimeType, $fileName")
            return isXoppFile
        }
    }
}
