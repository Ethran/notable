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
import com.ethran.notable.data.ensureImagesFolder
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.ui.showHint
import com.ethran.notable.utils.ensureNotMainThread
import com.onyx.android.sdk.api.device.epd.EpdController
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
import java.io.StringWriter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

// I do not know what pressureFactor should be, I just guest it.
// it's used to get strokes look relatively good in xournal++
private const val PRESSURE_FACTOR = 0.5f

// https://github.com/xournalpp/xournalpp/issues/2124
class XoppFile(
    private val context: Context,
    private val pageRepo: PageRepository = PageRepository(context),
) {
    private val log = ShipBook.getLogger("XoppFile")
    private val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH
    private val maxPressure = EpdController.getMaxTouchPressure()

    fun writeToXoppStream(target: ExportTarget, output: OutputStream) {
        // Build a temporary plain-XML file using existing writePage(), then gzip it into 'output'
        val tmp = File(
            context.cacheDir, when (target) {
                is ExportTarget.Book -> "notable_xopp_book.xml"
                is ExportTarget.Page -> "notable_xopp_page.xml"
            }
        )

        BufferedWriter(OutputStreamWriter(FileOutputStream(tmp), Charsets.UTF_8)).use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<xournal creator=\"Notable ${BuildConfig.VERSION_NAME}\" version=\"0.4\">\n")
            when (target) {
                is ExportTarget.Book -> {
                    val book = BookRepository(context).getById(target.bookId)
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
        tmp.delete()
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
    private fun writePage(pageId: String, writer: BufferedWriter) {
        val (_, strokes) = pageRepo.getWithStrokeById(pageId)
        val (_, images) = pageRepo.getWithImageById(pageId)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()

        val root = doc.createElement("page")
        val strokeHeight = if (strokes.isEmpty()) 0 else strokes.maxOf(Stroke::bottom).toInt() + 50
        val height = strokeHeight.coerceAtLeast(SCREEN_HEIGHT) * scaleFactor

        root.setAttribute("width", A4_WIDTH.toString())
        root.setAttribute("height", height.toString())
        doc.appendChild(root)

        val bcgElement = doc.createElement("background")
        bcgElement.setAttribute("type", "solid")
        bcgElement.setAttribute("color", "#ffffffff")
        bcgElement.setAttribute("style", "plain")
        root.appendChild(bcgElement)


        val layer = doc.createElement("layer")
        root.appendChild(layer)



        for (stroke in strokes) {
            // skip the small strokes, to avoid error: Wrong count of points (2)
            if (stroke.points.size < 3)
                continue
            val strokeElement = doc.createElement("stroke")
            strokeElement.setAttribute("tool", stroke.pen.toString())
            strokeElement.setAttribute("color", getColorName(Color(stroke.color)))
            val widthValues = mutableListOf(stroke.size * scaleFactor)
            if (stroke.pen == Pen.FOUNTAIN || stroke.pen == Pen.BRUSH || stroke.pen == Pen.PENCIL) widthValues += stroke.points.map {
                it.pressure?.div(stroke.maxPressure * PRESSURE_FACTOR) ?: 1f
            }
            val widthString = widthValues.joinToString(" ")

            strokeElement.setAttribute("width", widthString)

            val pointsString =
                stroke.points.joinToString(" ") { "${it.x * scaleFactor} ${it.y * scaleFactor}" }
            strokeElement.textContent = pointsString
            layer.appendChild(strokeElement)
        }

        for (image in images) {
            val imgElement = doc.createElement("image")

            val left = image.x * scaleFactor
            val top = image.y * scaleFactor
            val right = (image.x + image.width) * scaleFactor
            val bottom = (image.y + image.height) * scaleFactor

            imgElement.setAttribute("left", left.toString())
            imgElement.setAttribute("top", top.toString())
            imgElement.setAttribute("right", right.toString())
            imgElement.setAttribute("bottom", bottom.toString())

            image.uri?.let { uri ->
                imgElement.setAttribute("filename", uri)
                imgElement.textContent = convertImageToBase64(image.uri, context)
            }
            if (imgElement.textContent.isNotBlank()) layer.appendChild(imgElement)
            else showHint("Image cannot be loaded.")
        }

        val xmlString = convertXmlToString(doc)
        writer.write(xmlString)
    }


    /**
     * Opens a file and converts it to a base64 string.
     */
    private fun convertImageToBase64(uri: String, context: Context): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri.toUri())
            val bytes = inputStream?.readBytes() ?: return ""
            Base64.encodeToString(bytes, Base64.DEFAULT)
        } catch (e: SecurityException) {
            log.e("convertImageToBase64:" + "Permission denied: ${e.message}")
            ""
        } catch (e: FileNotFoundException) {
            log.e("convertImageToBase64:" + "File not found: ${e.message}")
            ""
        } catch (e: IOException) {
            log.e("convertImageToBase64:" + "I/O error: ${e.message}")
            ""
        }
    }


    /**
     * Converts an XML Document to a formatted string without the XML declaration.
     *
     * This is used to convert an individual page's XML structure into a string
     * before writing it to the output file. The XML declaration is removed to
     * prevent duplicate headers when merging pages.
     *
     * @param document The XML Document to convert.
     * @return The formatted XML string without the XML declaration.
     */
    private fun convertXmlToString(document: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") // ❗ Omit XML header
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString().trim() // Remove extra spaces or newlines
    }


    /**
     * Imports a `.xopp` file, creating a new book and pages in the database.
     *
     * @param context The application context.
     * @param uri The URI of the `.xopp` file to import.
     */
    fun importBook(uri: Uri, savePageToDatabase: (PageContent) -> Unit) {
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
            val tool = Pen.Companion.fromString(toolName)

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
            "black" -> Color.Companion.Black
            "blue" -> Color.Companion.Blue
            "red" -> Color.Companion.Red
            "green" -> Color.Companion.Green
            "magenta" -> Color.Companion.Magenta
            "yellow" -> Color.Companion.Yellow
            // Convert "#RRGGBBAA" → "#AARRGGBB" → Android Color
            else -> {
                if (colorString.startsWith("#") && colorString.length == 9) Color(
                    ("#" + colorString.substring(7, 9) + colorString.substring(1, 7)).toColorInt()
                )
                else {
                    log.e("Unknown color: $colorString")
                    Color.Companion.Black
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
            Color.Companion.Black -> "black"
            Color.Companion.Blue -> "blue"
            Color.Companion.Red -> "red"
            Color.Companion.Green -> "green"
            Color.Companion.Magenta -> "magenta"
            Color.Companion.Yellow -> "yellow"
            Color.Companion.DarkGray, Color.Companion.Gray -> "gray"
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