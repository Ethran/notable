package com.ethran.notable.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Base64
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
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.editor.utils.Pen

import com.ethran.notable.utils.ensureNotMainThread
import com.onyx.android.sdk.api.device.epd.EpdController
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

// I do not know what pressureFactor should be, I just guest it.
// it's used to get strokes look relatively good in xournal++
private const val PRESSURE_FACTOR = 0.5f

// https://github.com/xournalpp/xournalpp/issues/2124
class XoppFile @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pageRepo: PageRepository,
    private val bookRepo: BookRepository,
    private val appEventBus: AppEventBus
) {
    private val log = ShipBook.getLogger("XoppFile")
    private val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH
    private val maxPressure = EpdController.getMaxTouchPressure()

    suspend fun writeToXoppStream(target: ExportTarget, output: OutputStream) {
        // Build a temporary plain-XML file using existing writePage(), then gzip it into 'output'
        val tmp = File(
            context.cacheDir, when (target) {
                is ExportTarget.Book -> "notable_xopp_book.xml"
                is ExportTarget.Page -> "notable_xopp_page.xml"
            }
        )

        try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(tmp), Charsets.UTF_8)).use { writer ->
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


    /**
     * Writes a single page's XML data to the output stream.
     *
     * This method retrieves the strokes and images for the given page
     * and writes them to the provided BufferedWriter.
     *
     * @param pageId The ID of the page to process.
     * @param writer The BufferedWriter to write XML data to.
     */
    private suspend fun writePage(pageId: String, writer: BufferedWriter) {
        val pageWithData = pageRepo.getWithDataById(pageId)
        val strokes = pageWithData.strokes
        val images = pageWithData.images
        val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50
        val height = strokeHeight.coerceAtLeast(SCREEN_HEIGHT) * scaleFactor

        writer.write("<page width=\"")
        writer.write(A4_WIDTH.toString())
        writer.write("\" height=\"")
        writer.write(height.toString())
        writer.write("\">\n")
        writer.write("<background type=\"solid\" color=\"#ffffffff\" style=\"plain\"/>\n")
        writer.write("<layer>\n")

        for (stroke in strokes) {
            // skip the small strokes, to avoid error: Wrong count of points (2)
            if (stroke.points.size < 3) continue

            writer.write("<stroke tool=\"")
            writer.write(escapeXml(stroke.pen.toString()))
            writer.write("\" color=\"")
            writer.write(escapeXml(getColorName(Color(stroke.color))))
            writer.write("\" width=\"")
            writer.write((stroke.size * scaleFactor).toString())

            if (stroke.pen == Pen.FOUNTAIN || stroke.pen == Pen.BRUSH || stroke.pen == Pen.PENCIL) {
                stroke.points.forEach { point ->
                    writer.write(" ")
                    writer.write(
                        (point.pressure?.div(stroke.maxPressure * PRESSURE_FACTOR) ?: 1f).toString()
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


    /**
     * Opens a file and converts it to a base64 string.
     */
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
                            writer.write(Base64.encodeToString(tail, 0, 3, Base64.NO_WRAP))
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
                            Base64.encodeToString(
                                buffer,
                                offset,
                                encodableBytes,
                                Base64.NO_WRAP
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
                    writer.write(Base64.encodeToString(tail, 0, tailSize, Base64.NO_WRAP))
                    hasData = true
                }
                hasData
            } ?: false
        } catch (e: SecurityException) {
            log.e("convertImageToBase64:" + "Permission denied: ${e.message}")
            false
        } catch (e: FileNotFoundException) {
            log.e("convertImageToBase64:" + "File not found: ${e.message}")
            false
        } catch (e: IOException) {
            log.e("convertImageToBase64:" + "I/O error: ${e.message}")
            false
        } catch (e: OutOfMemoryError) {
            log.e("convertImageToBase64: Not enough memory for image export: ${e.message}")
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


    /**
     * Imports a `.xopp` file, creating a new book and pages in the database.
     *
     * @param context The application context.
     * @param uri The URI of the `.xopp` file to import.
     */
    suspend fun importBook(uri: Uri, savePageToDatabase: suspend (PageContent) -> Unit) {
        log.v("Importing book from $uri")
        ensureNotMainThread("xoppImportBook")
        val inputStream = context.contentResolver.openInputStream(uri) ?: return
        val xmlContent = extractXmlFromXopp(inputStream) ?: return

        val document = parseXml(xmlContent) ?: return

        val pages = document.getElementsByTagName("page")

        for (i in 0 until pages.length) {
            val pageElement = pages.item(i) as Element
            val page = Page()
            val strokes = parseStrokes(pageElement, page)
            val images = parseImages(pageElement, page)
            savePageToDatabase(PageContent(page, strokes, images))
        }
        log.i("Successfully imported book with ${pages.length} pages.")
    }

    /**
     * Extracts XML content from a `.xopp` file.
     */
    private fun extractXmlFromXopp(inputStream: InputStream): String? {
        return try {
            GzipCompressorInputStream(BufferedInputStream(inputStream)).bufferedReader()
                .use { it.readText() }
        } catch (e: IOException) {
            log.e("Error extracting XML from .xopp file: ${e.message}")
            null
        }
    }

    /**
     * Parses an XML string into a DOM Document.
     */
    private fun parseXml(xml: String): Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            log.e("Error parsing XML: ${e.message}")
            null
        }
    }

    /**
     * Extracts strokes from a page element and saves them.
     */
    private fun parseStrokes(pageElement: Element, page: Page): List<Stroke> {
        val strokeNodes = pageElement.getElementsByTagName("stroke")
        val strokes = mutableListOf<Stroke>()


        for (i in 0 until strokeNodes.length) {
            val strokeElement = strokeNodes.item(i) as Element
            val pointsString = strokeElement.textContent.trim()

            if (pointsString.isBlank()) continue // Skip empty strokes

            // Decode stroke attributes
//            val strokeSize = strokeElement.getAttribute("width").toFloatOrNull()?.div(scaleFactor) ?: 1.0f
            val color = parseColor(strokeElement.getAttribute("color"))


            // Decode width attribute
            val widthString = strokeElement.getAttribute("width").trim()
            val widthValues = widthString.split(" ").mapNotNull { it.toFloatOrNull() }

            val strokeSize =
                widthValues.firstOrNull()?.div(scaleFactor) ?: 1.0f // First value is stroke width
            val pressureValues = widthValues.drop(1) // Remaining values are pressure


            val points = pointsString.split(" ").chunked(2).mapIndexedNotNull { index, chunk ->
                try {
                    StrokePoint(
                        x = chunk[0].toFloat() / scaleFactor,
                        y = chunk[1].toFloat() / scaleFactor,
                        // pressure is shifted by one spot
                        pressure = pressureValues.getOrNull(index-1)
                            ?.times(maxPressure * PRESSURE_FACTOR) ?: 0f,
                        tiltX = 0,
                        tiltY = 0,
                    )
                } catch (e: Exception) {
                    log.e("Error parsing stroke point: ${e.message}")
                    null
                }
            }
            if (points.isEmpty()) continue // Skip strokes without valid points

            val boundingBox = RectF()

            val decodedPoints = points.mapIndexed { index, it ->
                if (index == 0) boundingBox.set(it.x, it.y, it.x, it.y) else boundingBox.union(
                    it.x, it.y
                )
                it
            }

            boundingBox.inset(-strokeSize, -strokeSize)
            val toolName = strokeElement.getAttribute("tool")
            val tool = Pen.fromString(toolName)

            val stroke = Stroke(
                size = strokeSize,
                pen = tool, // TODO: change this to proper pen
                pageId = page.id,
                top = boundingBox.top,
                bottom = boundingBox.bottom,
                left = boundingBox.left,
                right = boundingBox.right,
                points = decodedPoints,
                color = android.graphics.Color.argb(
                    (color.alpha * 255).toInt(),
                    (color.red * 255).toInt(),
                    (color.green * 255).toInt(),
                    (color.blue * 255).toInt()
                ),
                maxPressure = maxPressure.toInt()
            )
            strokes.add(stroke)
        }
        return strokes
    }


    /**
     * Extracts images from a page element and saves them.
     */
    private fun parseImages(pageElement: Element, page: Page): List<Image> {
        val imageNodes = pageElement.getElementsByTagName("image")
        val images = mutableListOf<Image>()

        for (i in 0 until imageNodes.length) {
            val imageElement = imageNodes.item(i) as? Element ?: continue
            val base64Data = imageElement.textContent.trim()

            if (base64Data.isBlank()) continue // Skip empty image data

            try {
                // Extract position attributes
                val left =
                    imageElement.getAttribute("left").toFloatOrNull()?.div(scaleFactor) ?: continue
                val top =
                    imageElement.getAttribute("top").toFloatOrNull()?.div(scaleFactor) ?: continue
                val right =
                    imageElement.getAttribute("right").toFloatOrNull()?.div(scaleFactor) ?: continue
                val bottom = imageElement.getAttribute("bottom").toFloatOrNull()?.div(scaleFactor)
                    ?: continue

                // Decode Base64 to Bitmap
                val imageUri = decodeAndSave(base64Data) ?: continue

                // Create Image object and add it to the list
                val image = Image(
                    x = left.toInt(),
                    y = top.toInt(),
                    width = (right - left).toInt(),
                    height = (bottom - top).toInt(),
                    uri = imageUri.toString(),
                    pageId = page.id
                )
                images.add(image)

            } catch (e: Exception) {
                log.e("ImageProcessing: Error parsing image: ${e.message}")
            }
        }
        return images
    }

    /**
     * Decodes a Base64 image string, saves it as a file, and returns the URI.
     */
    private fun decodeAndSave(base64String: String): Uri? {
        return try {
            // Decode Base64 to ByteArray
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            val bitmap =
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size) ?: return null

            // Ensure the directory exists
            val outputDir = ensureImagesFolder()

            // Generate a unique and safe file name
            val fileName = "image_${UUID.randomUUID()}.png"
            val outputFile = File(outputDir, fileName)

            // Save the bitmap to the file
            FileOutputStream(outputFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }

            // Return the file URI
            Uri.fromFile(outputFile)
        } catch (e: IOException) {
            log.e("Error decoding and saving image: ${e.message}")
            null
        }
    }


    /**
     * Parses an Xournal++ color string to a Compose Color.
     */
    private fun parseColor(colorString: String): Color {
        return when (colorString.lowercase()) {
            "black" -> Color.Black
            "blue" -> Color.Blue
            "red" -> Color.Red
            "green" -> Color.Green
            "magenta" -> Color.Magenta
            "yellow" -> Color.Yellow
            "gray" -> Color.Gray
            // Convert "#RRGGBBAA" → "#AARRGGBB" → Android Color
            else -> {
                if (colorString.startsWith("#") && colorString.length == 9) Color(
                    ("#" + colorString.substring(7, 9) + colorString.substring(1, 7)).toColorInt()
                )
                else {
                    log.e("Unknown color: $colorString")
                    Color.Black
                }
            }
        }
    }

    /**
     * Maps a Compose Color to an Xournal++ color name.
     *
     * @param color The Compose Color object.
     * @return The corresponding color name as a string.
     */
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
                // Convert ARGB (Android default) → RGBA
                String.format(
                    "#%02X%02X%02X%02X",
                    (argb shr 16) and 0xFF, // Red
                    (argb shr 8) and 0xFF,  // Green
                    (argb) and 0xFF,        // Blue
                    (argb shr 24) and 0xFF  // Alpha
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_IMAGE_CHUNK_SIZE = 16 * 1024

        // Helper functions to determine file type
        fun isXoppFile(mimeType: String?, fileName: String?): Boolean {
            val isXoppFile = mimeType in listOf(
                "application/x-xopp",
                "application/gzip",
                "application/octet-stream"
            ) ||
                    fileName?.endsWith(".xopp", ignoreCase = true) == true

            Log.d("XoppFile", "isXoppFile($isXoppFile): $mimeType, $fileName")
            return isXoppFile
        }
    }
}
