package com.ethran.notable.navigation

import android.graphics.Bitmap
import android.net.Uri
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.editor.utils.PreviewSaveMode
import com.ethran.notable.io.ImportEngine
import com.ethran.notable.io.ImportOptions
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.io.getPdfPageCount
import com.ethran.notable.io.renderPdfPageAndroid
import com.ethran.notable.utils.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Finds what a [DeepLink] points at in the database, and imports the PDF of a [DeepLink.Pdf]. */
class DeepLinkResolver @Inject constructor(
    private val appRepository: AppRepository,
    private val importEngine: ImportEngine,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    sealed interface Target {
        /** Open [pageId]; [note] is shown when the link asked for something slightly different. */
        data class Editor(val pageId: String, val bookId: String?, val note: String? = null) : Target
        data class Pages(val bookId: String) : Target

        /** No notebook links this PDF yet; importing it needs the user's consent. */
        data class AskToImport(val link: DeepLink.Pdf) : Target
        data class Failed(val message: String) : Target
    }

    suspend fun resolve(link: DeepLink): Target = withContext(Dispatchers.IO) {
        when (link) {
            is DeepLink.Page -> appRepository.pageRepository.getById(link.pageId)
                ?.let { Target.Editor(it.id, it.notebookId) }
                ?: Target.Failed("Page not found")

            is DeepLink.Book -> appRepository.bookRepository.getById(link.bookId)
                ?.let { openBook(it, page = null) }
                ?: Target.Failed("Notebook not found")

            is DeepLink.Pdf -> {
                val book = appRepository.bookRepository.getLinkedToPdf(link.path)
                when {
                    book != null -> openBook(book, link.page)
                    !isReadablePdf(link.path) -> Target.Failed("No PDF at ${link.path}")
                    else -> Target.AskToImport(link)
                }
            }
        }
    }

    /**
     * Imports the PDF into the library, observing the file where it is, so the notebook shows its
     * changes and later links find it. Runs on the application scope, so recreating the activity
     * doesn't stop it halfway.
     */
    suspend fun import(link: DeepLink.Pdf): Target = applicationScope.async(Dispatchers.IO) {
        // Another import may have made a notebook following the file meanwhile, e.g. the PDF
        // receiver's while the import dialog was open.
        appRepository.bookRepository.getLinkedToPdf(link.path)
            ?.let { return@async openBook(it, link.page) }
        val result = importEngine.import(
            Uri.fromFile(File(link.path)),
            ImportOptions(linkToExternalFile = true)
        )
        result.fold(
            onSuccess = { pageIds ->
                thumbnailBackfillQueue.enqueue(pageIds, PreviewSaveMode.STRICT_BW)
                val book = pageIds.firstOrNull()
                    ?.let { appRepository.pageRepository.getById(it)?.notebookId }
                    ?.let { appRepository.bookRepository.getById(it) }
                    ?: return@fold Target.Failed("Imported notebook not found")
                openBook(book, link.page)
            },
            onError = { Target.Failed("Import failed: ${it.userMessage}") }
        )
    }.await()

    /** What the import dialog shows of the PDF of [link]. */
    suspend fun preview(link: DeepLink.Pdf): PdfPreview = withContext(Dispatchers.IO) {
        PdfPreview(
            pageCount = getPdfPageCount(link.path),
            firstPage = renderPdfPageAndroid(File(link.path), 0, PREVIEW_WIDTH_PX),
        )
    }

    /** [firstPage] is null when it couldn't be drawn. */
    class PdfPreview(val pageCount: Int, val firstPage: Bitmap?)

    /** [page] is a PDF page; in a notebook that follows a PDF, page N shows PDF page N. */
    private fun openBook(book: Notebook, page: Int?): Target {
        val pageId = page?.let { book.pageIds.getOrNull(it - 1) }
            ?: book.openPageId
            ?: book.pageIds.firstOrNull()
            ?: return Target.Pages(book.id)
        val note = if (page != null && page > book.pageIds.size)
            "${book.title} has ${book.pageIds.size} pages" else null
        return Target.Editor(pageId, book.id, note)
    }

    private fun isReadablePdf(path: String): Boolean {
        val file = File(path)
        return file.extension.equals("pdf", ignoreCase = true) && file.isFile && file.canRead()
    }
}

private const val PREVIEW_WIDTH_PX = 300
