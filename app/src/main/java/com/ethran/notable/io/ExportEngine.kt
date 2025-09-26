package com.ethran.notable.io

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.A4_HEIGHT
import com.ethran.notable.data.datastore.A4_WIDTH
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.drawing.drawStroke
import com.ethran.notable.ui.components.getFolderList
import com.ethran.notable.utils.ensureNotMainThread
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date


/* ---------------------------- Public API ---------------------------- */

enum class ExportFormat { PDF, PNG, JPEG, XOPP }

sealed class ExportTarget {
    data class Book(val bookId: String) : ExportTarget()
    data class Page(val pageId: String) : ExportTarget()
}

data class ExportOptions(
    val copyToClipboard: Boolean = true,
)

class ExportEngine(
    private val context: Context,
    private val pageRepo: PageRepository = PageRepository(context),
    private val bookRepo: BookRepository = BookRepository(context)
) {
    private val log = ShipBook.getLogger("ExportEngine")

    suspend fun export(
        target: ExportTarget, format: ExportFormat, options: ExportOptions = ExportOptions()
    ): String = when (format) {
        ExportFormat.PDF -> exportAsPdf(target, options)
        ExportFormat.PNG, ExportFormat.JPEG -> exportAsImages(target, format, options)
        ExportFormat.XOPP -> exportAsXopp(target)
    }


    /* -------------------- PDF EXPORT -------------------- */

    private suspend fun exportAsPdf(target: ExportTarget, options: ExportOptions): String {
        val writeAction: suspend (OutputStream) -> Unit
        val (filename, folder) = createFileNameAndFolder(target)
        when (target) {
            is ExportTarget.Book -> {
                val book = bookRepo.getById(target.bookId) ?: return "Book ID not found"
                writeAction = { out ->
                    PdfDocument().use { doc ->
                        book.pageIds.forEachIndexed { index, pageId ->
                            writePageToPdfDocument(doc, pageId, pageNumber = index + 1)
                        }
                        doc.writeTo(out)
                    }
                }
            }

            is ExportTarget.Page -> {
                writeAction = { out ->
                    PdfDocument().use { doc ->
                        writePageToPdfDocument(doc, target.pageId, pageNumber = 1)
                        doc.writeTo(out)
                    }
                }
                if (options.copyToClipboard) copyPagePngLink(
                    context, target.pageId
                ) // You may want a separate PDF variant
            }
        }

        return saveStream(
            fileName = filename,
            extension = "pdf",
            mimeType = "application/pdf",
            subfolder = folder,
            writer = writeAction
        )
    }

    /* -------------------- IMAGE EXPORT (PNG / JPEG) -------------------- */

    private suspend fun exportAsImages(
        target: ExportTarget, format: ExportFormat, options: ExportOptions
    ): String {
        val (baseFileName, folder) = createFileNameAndFolder(target)

        val (ext, mime, compressFormat) = when (format) {
            ExportFormat.PNG -> Triple("png", "image/png", Bitmap.CompressFormat.PNG)
            ExportFormat.JPEG -> Triple("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
            else -> error("Unsupported image format")
        }

        when (target) {
            is ExportTarget.Page -> {
                val pageId = target.pageId
                val bitmap = renderBitmapForPage(pageId)
                bitmap.useAndRecycle { bmp ->
                    val bytes = bmp.toBytes(compressFormat)
                    saveBytes(baseFileName, ext, mime, folder, bytes)
                }
                if (options.copyToClipboard && format == ExportFormat.PNG) {
                    copyPagePngLink(context, pageId)
                }
                return "Page exported: $baseFileName.$ext"
            }

            is ExportTarget.Book -> {
                val book = bookRepo.getById(target.bookId) ?: return "Book ID not found"
                // Export each page separately (same folder = book title)
                book.pageIds.forEachIndexed { index, pageId ->
                    val fileName = "$baseFileName${index + 1}"
                    val bitmap = renderBitmapForPage(pageId)
                    bitmap.useAndRecycle { bmp ->
                        val bytes = bmp.toBytes(compressFormat)
                        saveBytes(fileName, ext, mime, book.title, bytes)
                    }
                }
                if (options.copyToClipboard) {
                    Log.w(TAG, "Can't copy book links or images to clipboard -- batch export.")
                }
                return "Book exported: ${book.title} (${book.pageIds.size} pages)"
            }
        }
    }
    /* -------------------- XOPP export -------------------- */

    private suspend fun exportAsXopp(target: ExportTarget): String {
        val (filename, folder) = createFileNameAndFolder(target)
        return saveStream(
            fileName = filename,
            extension = "xopp",
            mimeType = "application/x-xopp",
            subfolder = folder
        ) { out ->
            XoppFile(context).writeToXoppStream(target, out)
        }
    }

    /* -------------------- File naming -------------------- */

    /**
     * Returns: Pair(fileNameWithoutExtension, folderPath)
     *
     * Rules:
     *  Book export:
     *      folder: notable/<folderHierarchy>/BookTitle
     *      file:   BookTitle
     *
     *  Page export (belongs to a book):
     *      folder: notable/<folderHierarchy>/BookTitle
     *      file:   BookTitle-p<PageNumber>   (falls back to BookTitle-p? if no number)
     *
     *  Page export (no book = quick page):
     *      folder: notable/<folderHierarchyFromPageParent?>
     *      file:   quickpage-<timestamp>
     *
     *  - Does NOT append extension.
     *  - folderHierarchy is derived from ancestor folders root→leaf (if any).
     */
    fun createFileNameAndFolder(target: ExportTarget): Pair<String, String> {

        val pageRepo = PageRepository(context)
        val bookRepo = BookRepository(context)
        val timeStamp = SimpleDateFormat.getDateTimeInstance().format(Date())


        return when (target) {
            is ExportTarget.Book -> {
                val book = bookRepo.getById(target.bookId)
                if (book == null) {
                    log.e("Book ID not found")
                    return "" to "ERROR"
                }

                val bookTitle = sanitizeFileName(book.title)

                // Build folder hierarchy (excluding the book title itself until the end)
                val folders = if (book.parentFolderId != null) getFolderList(
                    context,
                    book.parentFolderId
                ).reversed().map { sanitizeFileName(it.title) }
                else emptyList()

                val folderPath = buildString {
                    if (folders.isNotEmpty()) {
                        append(folders.joinToString("/"))
                    }
                    append("/")
                }
                bookTitle to folderPath
            }

            is ExportTarget.Page -> {
                val page = pageRepo.getById(target.pageId)
                if (page == null) {
                    log.e("Page ID not found")
                    return "" to "ERROR"
                }
                val book = page.notebookId?.let { bookRepo.getById(it) }

                if (book != null) {
                    // Page inside a book
                    val bookTitle = sanitizeFileName(book.title)
                    val pageNumber = getPageNumber(page.notebookId, page.id)
                    val pageToken = if ((pageNumber ?: 0) >= 1) "p$pageNumber" else "p?"
                    val fileName = "$bookTitle-$pageToken"

                    val folders = if (book.parentFolderId != null) getFolderList(
                        context,
                        book.parentFolderId
                    ).reversed().map { sanitizeFileName(it.title) }
                    else emptyList()

                    val folderPath = buildString {
                        if (folders.isNotEmpty()) {
                            append(folders.joinToString("/"))
                        }
                        append("/")
                    }

                    fileName to folderPath
                } else {
                    // Quick page
                    val folders = if (page.parentFolderId != null) getFolderList(
                        context,
                        page.parentFolderId
                    ).reversed().map { sanitizeFileName(it.title) }
                    else emptyList()

                    val folderPath = buildString {
                        if (folders.isNotEmpty()) {
                            append(folders.joinToString("/"))
                            append("/")
                        }
                    }
                    val fileName = "quickpage-$timeStamp"
                    fileName to folderPath
                }
            }
        }
    }

    /* -------------------- Shared Drawing & PDF Helpers -------------------- */

    private fun writePageToPdfDocument(doc: PdfDocument, pageId: String, pageNumber: Int) {
        ensureNotMainThread("ExportPdf")
        val data = fetchPageData(pageId)
        val (_, contentHeightPx) = computeContentDimensions(data)

        val scaleFactor = A4_WIDTH.toFloat() / SCREEN_WIDTH.toFloat()
        val scaledHeight = (contentHeightPx * scaleFactor).toInt()

        if (GlobalAppSettings.current.paginatePdf) {
            var currentTop = 0
            var logicalPageNumber = pageNumber
            while (currentTop < scaledHeight) {
                val pageInfo =
                    PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, logicalPageNumber).create()
                val page = doc.startPage(pageInfo)
                drawPage(
                    canvas = page.canvas,
                    data = data,
                    scroll = Offset(0f, currentTop.toFloat()),
                    scaleFactor = scaleFactor
                )
                doc.finishPage(page)
                currentTop += A4_HEIGHT
                logicalPageNumber++
            }
        } else {
            val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, scaledHeight, pageNumber).create()
            val page = doc.startPage(pageInfo)
            drawPage(
                canvas = page.canvas, data = data, scroll = Offset.Zero, scaleFactor = scaleFactor
            )
            doc.finishPage(page)
        }
    }

    private fun renderBitmapForPage(pageId: String): Bitmap {
        ensureNotMainThread("ExportBitmap")
        val data = fetchPageData(pageId)
        val (contentWidth, contentHeight) = computeContentDimensions(data)

        val bitmap = createBitmap(contentWidth, contentHeight)
        val canvas = Canvas(bitmap)

        // Scale = 1f (bitmap is native logical size)
        drawBg(context, canvas, data.page.getBackgroundType(), data.page.background)
        data.images.forEach { drawImage(context, canvas, it, Offset.Zero) }
        data.strokes.forEach { drawStroke(canvas, it, Offset.Zero) }

        return bitmap
    }

    private fun drawPage(
        canvas: Canvas, data: PageData, scroll: Offset, scaleFactor: Float
    ) {
        canvas.scale(scaleFactor, scaleFactor)
        val scaledScroll = scroll / scaleFactor
        drawBg(
            context,
            canvas,
            data.page.getBackgroundType()
                .resolveForExport(getPageNumber(data.page.notebookId, data.page.id)),
            data.page.background,
            scaledScroll,
            scaleFactor
        )
        data.images.forEach { drawImage(context, canvas, it, -scaledScroll) }
        data.strokes.forEach { drawStroke(canvas, it, -scaledScroll) }
    }

    /* -------------------- Data Fetch / Dimension Calculation -------------------- */

    private data class PageData(
        val page: Page, val strokes: List<Stroke>, val images: List<Image>
    )

    private fun fetchPageData(pageId: String): PageData {
        val (page, strokes) = pageRepo.getWithStrokeById(pageId)
        val (_, images) = pageRepo.getWithImageById(pageId)
        return PageData(page, strokes, images)
    }

    // Returns (width, height)
    private fun computeContentDimensions(data: PageData): Pair<Int, Int> {
        if (data.strokes.isEmpty() && data.images.isEmpty()) {
            return SCREEN_WIDTH to SCREEN_HEIGHT
        }
        val strokeBottom = data.strokes.maxOfOrNull { it.bottom.toInt() } ?: 0
        val strokeRight = data.strokes.maxOfOrNull { it.right.toInt() } ?: 0
        val imageBottom = data.images.maxOfOrNull { (it.y + it.height) } ?: 0
        val imageRight = data.images.maxOfOrNull { (it.x + it.width) } ?: 0

        val rawHeight = maxOf(
            strokeBottom, imageBottom
        ) + if (GlobalAppSettings.current.visualizePdfPagination) 0 else 50
        val rawWidth = maxOf(strokeRight, imageRight) + 50

        val height = rawHeight.coerceAtLeast(SCREEN_HEIGHT)
        val width = rawWidth.coerceAtLeast(SCREEN_WIDTH)
        return width to height
    }

    /* -------------------- Saving Helpers -------------------- */

    private suspend fun saveBytes(
        fileName: String, extension: String, mimeType: String, subfolder: String, bytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        try {
            val cv = buildContentValues(fileName, extension, mimeType, subfolder)
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), cv)
                ?: throw IOException("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            "Saved $fileName.$extension"
        } catch (e: Exception) {
            Log.e(TAG, "Save error: ${e.message}")
            "Error saving $fileName.$extension"
        }
    }

    private suspend fun saveStream(
        fileName: String,
        extension: String,
        mimeType: String,
        subfolder: String,
        writer: suspend (OutputStream) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            val cv = buildContentValues(fileName, extension, mimeType, subfolder)
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), cv)
                ?: throw IOException("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { out -> writer(out) }
            "Saved $fileName.$extension"
        } catch (e: Exception) {
            Log.e(TAG, "Save stream error: ${e.message}")
            "Error saving $fileName.$extension"
        }
    }

    private fun buildContentValues(
        fileName: String, extension: String, mimeType: String, subfolder: String
    ) = ContentValues().apply {
        put(MediaStore.Files.FileColumns.DISPLAY_NAME, "$fileName.$extension")
        put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
        put(
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOCUMENTS + "/Notable/" + subfolder
        )
    }

    /* -------------------- Clipboard Helpers -------------------- */

    private fun copyPagePngLink(context: Context, pageId: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = """
            [[../attachments/Notable/Pages/notable-page-$pageId.png]]
            [[Notable Link][notable://page-$pageId]]
        """.trimIndent()
        clipboard.setPrimaryClip(ClipData.newPlainText("Notable Page Link", text))
    }

    private fun copyBookPdfLink(context: Context, bookId: String, bookName: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = """
            [[../attachments/Notable/Notebooks/$bookName.pdf]]
            [[Notable Book Link][notable://book-$bookId]]
        """.trimIndent()
        clipboard.setPrimaryClip(ClipData.newPlainText("Notable Book PDF Link", text))
    }

    /* -------------------- Utilities -------------------- */

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "notable-export" }


    private fun Bitmap.toBytes(format: Bitmap.CompressFormat, quality: Int = 100): ByteArray {
        val bos = ByteArrayOutputStream()
        this.compress(format, quality, bos)
        return bos.toByteArray()
    }

    private inline fun Bitmap.useAndRecycle(block: (Bitmap) -> Unit) {
        try {
            block(this)
        } finally {
            try {
                recycle()
            } catch (_: Exception) {
            }
        }
    }

    // Simple PdfDocument.use extension
    private inline fun PdfDocument.use(block: (PdfDocument) -> Unit) {
        try {
            block(this)
        } finally {
            try {
                close()
            } catch (_: Exception) {
            }
        }
    }

    fun getPageNumber(bookId: String?, id: String): Int? =
        AppRepository(context).getPageNumber(bookId, id)
}
