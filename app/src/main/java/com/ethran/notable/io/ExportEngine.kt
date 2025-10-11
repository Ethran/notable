package com.ethran.notable.io

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
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
import java.io.File
import java.io.FileOutputStream
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
    val targetFolderUri: Uri? = null, // can be made to also get from it fileName.
    val overwrite: Boolean = false,
    val fileName: String? = null
)

class ExportEngine(
    private val context: Context,
    private val pageRepo: PageRepository = PageRepository(context),
    private val bookRepo: BookRepository = BookRepository(context)
) {
    private val log = ShipBook.getLogger("ExportEngine")

    suspend fun export(
        target: ExportTarget, format: ExportFormat, options: ExportOptions = ExportOptions()
    ): String {
        // prepare file name and folder
        val (folderUri, baseFileName) = createFileNameAndFolder(target, options)
        return when (format) {
            ExportFormat.PDF -> exportAsPdf(target, folderUri, baseFileName, options)
            ExportFormat.PNG, ExportFormat.JPEG -> exportAsImages(
                target, folderUri, baseFileName, format, options
            )

            ExportFormat.XOPP -> exportAsXopp(target, folderUri, baseFileName, options)
        }
    }


    /* -------------------- PDF EXPORT -------------------- */

    private suspend fun exportAsPdf(
        target: ExportTarget, folderUri: Uri, baseFileName: String, options: ExportOptions
    ): String {
        val writeAction: suspend (OutputStream) -> Unit
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
            folderUri = folderUri,
            fileName = baseFileName,
            extension = "pdf",
            mimeType = "application/pdf",
            writer = writeAction,
            overwrite = options.overwrite
        )
    }

    /* -------------------- IMAGE EXPORT (PNG / JPEG) -------------------- */

    private suspend fun exportAsImages(
        target: ExportTarget,
        folderUri: Uri,
        baseFileName: String,
        format: ExportFormat,
        options: ExportOptions
    ): String {
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
                    saveBytes(
                        folderUri, baseFileName,
                        ext, mime, options.overwrite, bytes
                    )
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
                        saveBytes(folderUri, fileName, ext, mime, options.overwrite, bytes)
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

    private suspend fun exportAsXopp(
        target: ExportTarget,
        folderUri: Uri,
        baseFileName: String,
        options: ExportOptions
    ): String {
        return saveStream(
            extension = "xopp",
            folderUri = folderUri,
            fileName = baseFileName,
            mimeType = "application/x-xopp",
            overwrite = options.overwrite
        ) { out ->
            XoppFile(context).writeToXoppStream(target, out)
        }
    }

    /* -------------------- File naming and folder path -------------------- */

    /**
     * Returns: Pair(folderUri, fileNameWithoutExtension)
     *
     * Rules:
     *  Book export:
     *      folder: Documents/notable/<folderHierarchy>/BookTitle
     *      file:   BookTitle
     *
     *  Page export (belongs to a book):
     *      folder: Documents/notable/<folderHierarchy>/BookTitle
     *      file:   BookTitle-p<PageNumber>   (falls back to BookTitle-p? if no number)
     *
     *  Page export (no book = quick page):
     *      folder: Documents/notable/<folderHierarchyFromPageParent?>
     *      file:   quickpage-<timestamp>
     *
     * - If options.saveToUri is provided, it must point to a directory (tree/document folder Uri or file:// directory).
     */
    fun createFileNameAndFolder(
        target: ExportTarget, options: ExportOptions
    ): Pair<Uri, String> {
        val fileName =
            options.fileName?.trim()?.takeIf { it.isNotBlank() } ?: createFileName(target)

        // If caller provided a directory Uri, accept both SAF directory and file:// directory.
        options.targetFolderUri?.let { provided ->
            if (!isDirectoryUri(provided) && !isFileDirectory(provided)) {
                throw IllegalArgumentException(
                    "ExportOptions.targetFolderUri must point to a directory (SAF tree/document folder or file:// directory)\n" +
                            "Maybe folder was deleted?"
                )
            }
            return provided to fileName
        }

        // Default export directory under Documents/notable/<subfolder>
        val subfolderPath = createSubfolderName(target)
        val folderUri = getDefaultExportDirectoryUri(subfolderPath)
        return folderUri to fileName
    }

    /**
     * Builds subfolder path relative to the export root ("notable").
     * - For a Book: "<folderHierarchy>"
     * - For a Page inside a book: "<folderHierarchy>"
     * - For a Quick page: "<folderHierarchyFromPageParent?>"
     *
     * Returns path without leading or trailing slashes. Returns empty string if nothing to add.
     */
    fun createSubfolderName(target: ExportTarget): String {
        fun buildFolderPath(parentFolderId: String?): String {
            return parentFolderId?.let {
                getFolderList(context, it)
                    .reversed()
                    .joinToString("/") { folder -> sanitizeFileName(folder.title) }
            }.orEmpty()
        }

        return when (target) {
            is ExportTarget.Book -> {
                val book = bookRepo.getById(target.bookId)
                    ?: run { log.e("Book ID not found"); return "" }
                buildFolderPath(book.parentFolderId)
            }

            is ExportTarget.Page -> {
                val page = pageRepo.getById(target.pageId)
                    ?: run { log.e("Page ID not found"); return "" }

                val book = page.notebookId?.let { bookRepo.getById(it) }

                if (book != null) {
                    val bookPath = buildFolderPath(book.parentFolderId)
                    val bookTitle = sanitizeFileName(book.title)
                    listOfNotNull(bookPath.takeIf { it.isNotEmpty() }, bookTitle)
                        .joinToString("/")
                } else {
                    buildFolderPath(page.parentFolderId)
                }
            }
        }
    }


    // Create a default directory Uri under Documents/notable/<subfolderPath> using file:// scheme.
    private fun getDefaultExportDirectoryUri(subfolderPath: String): Uri {
        val documentsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

        val targetPath = listOfNotBlank("notable", subfolderPath).joinToString(File.separator)
        val dir = File(documentsDir, targetPath)
        if (!dir.exists()) dir.mkdirs()
        return dir.toUri()
    }

    /**
     * Returns: fileNameWithoutExtension
     *
     * Book export: BookTitle
     * Page export in book: BookTitle-p<PageNumber> (or p?)
     * Quick page: quickpage-<timestamp>
     */
    fun createFileName(target: ExportTarget): String {
        return when (target) {
            is ExportTarget.Book -> {
                val book =
                    bookRepo.getById(target.bookId) ?: run { log.e("Book ID not found"); return "" }
                sanitizeFileName(book.title)
            }

            is ExportTarget.Page -> {
                val page =
                    pageRepo.getById(target.pageId) ?: run { log.e("Page ID not found"); return "" }

                val book = page.notebookId?.let { bookRepo.getById(it) }

                if (book != null) {
                    // Page inside a book
                    val bookTitle = sanitizeFileName(book.title)
                    val pageNumber = getPageNumber(page.notebookId, page.id)
                    val pageToken = if ((pageNumber ?: 0) >= 1) "p$pageNumber" else "p?"
                    "$bookTitle-$pageToken"
                } else {
                    val timeStamp = SimpleDateFormat.getDateTimeInstance().format(Date())
                    "quickpage-$timeStamp"
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

    /**
     * A convenience wrapper around [saveInternal] to save a raw [ByteArray] to a file.
     *
     * @param folderUri The URI of the directory where the file will be saved.
     *                  Can be a `file://` URI or a Storage Access Framework (SAF) tree/document URI.
     * @param fileName The name of the file, without the extension.
     * @param extension The file extension (e.g., "png", "jpg").
     * @param mimeType The MIME type of the file (e.g., "image/png").
     * @param overwrite If `true`, any existing file with the same name will be replaced.
     * @param bytes The raw byte data to write to the file.
     * @return A [String] indicating the result of the save operation, typically a success or error message.
     */
    private suspend fun saveBytes(
        folderUri: Uri,
        fileName: String,
        extension: String,
        mimeType: String,
        overwrite: Boolean,
        bytes: ByteArray
    ): String = saveInternal(
        folderUri = folderUri,
        fileName = fileName,
        extension = extension,
        mimeType = mimeType,
        overwrite = overwrite
    ) { out -> out.write(bytes) }

    /**
     * A convenience wrapper around [saveInternal] that accepts a suspendable [writer] lambda
     * to write content to an [OutputStream].
     *
     * @param folderUri The URI of the directory where the file will be saved.
     *                  Can be a `file://` URI or a Storage Access Framework (SAF) tree/document URI.
     * @param fileName The base name of the file, without the extension.
     * @param extension The file extension (e.g., "pdf", "png").
     * @param mimeType The MIME type of the file (e.g., "application/pdf").
     * @param overwrite If `true`, any existing file with the same name will be replaced.
     * @param writer A suspendable lambda that receives an [OutputStream] to write the file content into.
     * @return A user-facing message indicating the result of the save operation (e.g., "Saved file.pdf" or "Error saving...").
     */
    private suspend fun saveStream(
        folderUri: Uri,
        fileName: String,
        extension: String,
        mimeType: String,
        overwrite: Boolean,
        writer: suspend (OutputStream) -> Unit
    ): String = saveInternal(
        folderUri = folderUri,
        fileName = fileName,
        extension = extension,
        mimeType = mimeType,
        overwrite = overwrite,
        writer = writer
    )

    /**
     * Central writer that handles directory types:
     * - SAF directory Uris (tree/document) via DocumentsContract
     * - file:// directory Uris via java.io.File
     */
    private suspend fun saveInternal(
        folderUri: Uri,
        fileName: String,
        extension: String,
        mimeType: String,
        overwrite: Boolean,
        writer: suspend (OutputStream) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val displayName = if (extension.isBlank()) fileName else "$fileName.$extension"
        try {
            val dest = createOrGetFileInDir(folderUri, displayName, mimeType, overwrite)
                ?: throw IOException(
                    "Unable to create or access destination file in target directory, $folderUri"
                )

            when (dest.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    val resolver = context.contentResolver
                    resolver.openOutputStream(dest, "w")?.use { out -> writer(out) }
                        ?: throw IOException("Failed to open output stream for $displayName")
                }

                "file" -> {
                    val file = File(requireNotNull(dest.path) { "Missing file path" })
                    FileOutputStream(file, false).use { out -> writer(out) }
                }

                else -> throw IOException("Unsupported Uri scheme: ${dest.scheme}")
            }

            "Saved $displayName"
        } catch (e: Exception) {
            Log.e(TAG, "Save error: ${e.message}")
            "Error saving $displayName"
        }
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

    // Accepts SAF tree/document directory Uris OR file:// directory Uris
    private fun isDirectoryUri(uri: Uri): Boolean {
        // SAF tree directory
        if (android.provider.DocumentsContract.isTreeUri(uri)) return true

        // SAF document directory
        if (android.provider.DocumentsContract.isDocumentUri(context, uri)) {
            val resolver = context.contentResolver
            resolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val mime = c.getString(0)
                    if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        return true
                    }
                }
            }
        }
        // file:// directory
        return isFileDirectory(uri)
    }

    private fun isFileDirectory(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return File(path).isDirectory
    }

    /**
     * Create or get a file inside a directory Uri.
     * - For SAF directories: uses DocumentsContract and returns a content:// document Uri
     * - For file directories: creates a java.io.File and returns file:// Uri
     */
    private fun createOrGetFileInDir(
        dirUri: Uri, displayName: String, mimeType: String, overwrite: Boolean
    ): Uri? {
        return when {
            // SAF tree/doc directory
            android.provider.DocumentsContract.isTreeUri(dirUri) || android.provider.DocumentsContract.isDocumentUri(
                context,
                dirUri
            ) -> {
                createOrGetSafChild(dirUri, displayName, mimeType, overwrite)
            }

            // file:// directory
            isFileDirectory(dirUri) -> {
                val parent = File(requireNotNull(dirUri.path))
                if (!parent.exists()) parent.mkdirs()
                val target = File(parent, displayName)
                if (target.exists()) {
                    if (overwrite) {
                        if (!target.delete()) {
                            log.w("Failed to delete existing file for overwrite: ${target.absolutePath}")
                        }
                    } else {
                        return target.toUri()
                    }
                }
                try {
                    if (target.parentFile?.exists() != true) target.parentFile?.mkdirs()
                    if (!target.exists()) target.createNewFile()
                    target.toUri()
                } catch (e: Exception) {
                    log.e("File create failed: ${e.message}")
                    null
                }
            }

            else -> null
        }
    }

    private fun createOrGetSafChild(
        dirUri: Uri, displayName: String, mimeType: String, overwrite: Boolean
    ): Uri? {
        val resolver = context.contentResolver

        val parentDocUri: Uri
        val childrenUri: Uri
        val buildChildDocUri: (String) -> Uri

        if (android.provider.DocumentsContract.isTreeUri(dirUri)) {
            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(dirUri)
            parentDocUri =
                android.provider.DocumentsContract.buildDocumentUriUsingTree(dirUri, treeDocId)
            childrenUri =
                android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    dirUri,
                    treeDocId
                )
            buildChildDocUri = { docId ->
                android.provider.DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
            }
        } else {
            val docId = android.provider.DocumentsContract.getDocumentId(dirUri)
            parentDocUri = dirUri
            childrenUri =
                android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, docId)
            buildChildDocUri = { childDocId ->
                android.provider.DocumentsContract.buildDocumentUriUsingTree(dirUri, childDocId)
            }
        }

        var existingChildUri: Uri? = null
        resolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { cursor ->
            val idIdx =
                cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx =
                cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)
                if (name == displayName) {
                    val childDocId = cursor.getString(idIdx)
                    existingChildUri = buildChildDocUri(childDocId)
                    break
                }
            }
        }

        if (existingChildUri != null) {
            if (overwrite) {
                try {
                    android.provider.DocumentsContract.deleteDocument(resolver, existingChildUri)
                } catch (e: Exception) {
                    log.w("Failed to delete existing document before overwrite: ${e.message}")
                }
            } else {
                return existingChildUri
            }
        }

        return try {
            android.provider.DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                mimeType,
                displayName
            )
        } catch (e: Exception) {
            log.e("createDocument failed: ${e.message}")
            null
        }
    }

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

    private fun listOfNotBlank(vararg parts: String): List<String> =
        parts.filter { it.isNotBlank() }

    fun getPageNumber(bookId: String?, id: String): Int? =
        AppRepository(context).getPageNumber(bookId, id)
}