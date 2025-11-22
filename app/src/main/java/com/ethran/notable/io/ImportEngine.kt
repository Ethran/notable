package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.BackgroundType
import io.shipbook.shipbooksdk.ShipBook


/**
 * A standardized data structure representing a page and its content,
 * as parsed from an import file. This is used to pass data from a
 * file parser to the ImportEngine.
 */
data class PageContent(
    val page: Page,
    val strokes: List<Stroke>,
    val images: List<Image>
)


/**
 * Defines the strategy to resolve conflicts when importing a book that already exists in the database.
 */
enum class ImportConflictStrategy {
    /** If a book with the same ID exists, overwrite it completely with the imported file. */
    OVERWRITE,

    /** On conflicts, merge changes, choosing the most recent change based on timestamps. */
    MERGE_TIME_BASED,

    /** On conflicts, prioritize the version of the data already in the database. */
    PRIORITIZE_DATABASE,

    /** On conflicts, prioritize the version of the data from the imported file. */
    PRIORITIZE_FILE,

    /** If conflicts are found, ask the user for resolution (not implemented). */
    ASK
}

/**
 * Configuration options for an import operation.
 * @param saveToBookId If specified, attempts to save the imported data into an existing book with this ID. If null, a new book is created.
 * @param folderId If specified, the new notebook will be created in this folder.
 * @param conflictStrategy The strategy to use for resolving conflicts.
 * @param linkToExternalFile If true, the app will link to the original file URI instead of copying it. This is applicable for file types like PDF.
 * @param fileType If specified, the app will only import files of this type.
 * @param bookTitle If specified, the filename will be overwritten to this value.
 */
data class ImportOptions(
    val saveToBookId: String? = null,
    val folderId: String? = null,
    val conflictStrategy: ImportConflictStrategy? = null,
    val linkToExternalFile: Boolean = false,
    val fileType: String? = null,
    val bookTitle: String? = null
)


/**
 * The engine responsible for handling the logic of importing files into the app.
 * It is agnostic of the UI and operates on URIs provided to it.
 */
class ImportEngine(
    private val context: Context,
    private val pageRepo: PageRepository = PageRepository(context),
    private val bookRepo: BookRepository = BookRepository(context)
) {
    private val log = ShipBook.getLogger("ImportEngine")

    /**
     * Imports a notebook from the given URI. It recognizes the file type and
     * executes the correct import function.
     *
     * @param uri The URI of the file to import.
     * @param options The options for the import process.
     * @return An [String] indicating success or failure.
     */
    @WorkerThread
    fun import(
        uri: Uri,
        options: ImportOptions = ImportOptions()
    ): String {
        val mimeType = context.contentResolver.getType(uri)
        if (options.fileType != null && mimeType != options.fileType)
            throw IllegalArgumentException("File type mismatch. Expected: ${options.fileType}, Actual: $mimeType")

        val bookTitle = sanitizeNotebookName(options.bookTitle ?: getFileName(uri))
        log.d("Starting import for uri: $uri, mimeType: $mimeType, fileName: $bookTitle")

        if (options.saveToBookId != null)
            TODO("Implement logic to save into an existing book (ID: ${options.saveToBookId})")


        val optionsWithTitle = options.copy(
            bookTitle = bookTitle,
        )


        return when {
            XoppFile.isXoppFile(mimeType, bookTitle) -> handleImportXopp(uri, optionsWithTitle)
            isPdfFile(mimeType, bookTitle) -> handleImportPDF(uri, optionsWithTitle)
            else -> {
                val errorMessage = "Unsupported file type: $mimeType"
                log.w(errorMessage)
                errorMessage
            }
        }
    }

    private fun handleImportXopp(uri: Uri, options: ImportOptions): String {
        log.d("Importing Xopp file...")
        require(options.bookTitle != null) { "bookTitle cannot be null when importing Xopp file" }
        val book = Notebook(
            title = options.bookTitle,
            parentFolderId = options.folderId,
            defaultBackground = "blank",
            defaultBackgroundType = BackgroundType.Native.key
        )
        bookRepo.createEmpty(book)


        val strokeRepo = AppDatabase.getDatabase(context).strokeDao()
        val imageRepo = AppDatabase.getDatabase(context).ImageDao()
        XoppFile(context).importBook(uri) { pageData ->
            // TODO: handle conflict with existing pages, make sure that we won't insert the same strokes that already exist.
            pageRepo.create(pageData.page.copy(notebookId = book.id))
            strokeRepo.create(pageData.strokes)
            imageRepo.create(pageData.images)
            bookRepo.addPage(book.id, pageData.page.id)

        }
        return "Imported Xopp file"
    }

    private fun handleImportPDF(uri: Uri, options: ImportOptions): String {
        log.d("Importing Pdf file...")
        require(options.bookTitle != null) { "bookTitle cannot be null when importing Pdf file" }

        val fileToSave = handleFileSaving(context, uri, options)
            ?: return "Couldn't determine file path. Does the app have permission to read external storage?"

        val filePath = fileToSave.toString()

        val book = Notebook(
            title = options.bookTitle,
            parentFolderId = options.folderId,
            defaultBackground = filePath,
            defaultBackgroundType = BackgroundType.AutoPdf.key
        )
        bookRepo.createEmpty(book)

        val strokeRepo = AppDatabase.getDatabase(context).strokeDao()
        val imageRepo = AppDatabase.getDatabase(context).ImageDao()
        importPdf(fileToSave, options) { pageData ->
            pageRepo.create(pageData.page.copy(notebookId = book.id))
            if (pageData.strokes.isNotEmpty())
                strokeRepo.create(pageData.strokes)
            if (pageData.images.isNotEmpty())
                imageRepo.create(pageData.images)

            bookRepo.addPage(book.id, pageData.page.id)

        }
        return "Imported Pdf file"
    }


    private fun merge(fileData: PageContent, options: ImportOptions) {
        require(options.saveToBookId != null) { "saveToBookId cannot be null when merging" }
        require(options.conflictStrategy != null) { "conflictStrategy cannot be null when merging" }
        log.d("Conflict detected. Strategy: ${options.conflictStrategy}")
        when (options.conflictStrategy) {
            ImportConflictStrategy.OVERWRITE -> TODO()
            ImportConflictStrategy.MERGE_TIME_BASED -> TODO()
            ImportConflictStrategy.PRIORITIZE_DATABASE -> TODO()
            ImportConflictStrategy.PRIORITIZE_FILE -> TODO()
            ImportConflictStrategy.ASK -> TODO()
        }
    }


    /**
     * Extracts the book title from a file URI.
     */
    private fun getFileName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast("/")?.removeSuffix(".xopp")
            ?: "Imported Book"
    }


    private fun sanitizeNotebookName(raw: String, maxLen: Int = 100): String {
        var name = raw

        // Allow only letters, numbers, spaces, and dots
        name = name.replace(Regex("[^A-Za-z0-9. ]"), " ")

        // Collapse multiple spaces
        name = name.replace(Regex("\\s+"), " ")

        // Reduce multiple consecutive dots to a single dot
        name = name.replace(Regex("\\.+"), ".")

        // Trim whitespace from start and end
        name = name.trim()

        // Remove leading dot if present
        if (name.startsWith(".")) {
            name = name.removePrefix(".")
        }

        // Cut if too long
        if (name.length > maxLen) {
            name = name.take(maxLen).trimEnd()
        }

        return name
    }

}