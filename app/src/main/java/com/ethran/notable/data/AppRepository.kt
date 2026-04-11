package com.ethran.notable.data

import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.db.getPageIndex
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.model.BackgroundType
import io.shipbook.shipbooksdk.ShipBook
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val log = ShipBook.getLogger("appRepository")

@Singleton
class AppRepository @Inject constructor(
    val bookRepository: BookRepository,
    val pageRepository: PageRepository,
    val strokeRepository: StrokeRepository,
    val imageRepository: ImageRepository,
    val folderRepository: FolderRepository,
    val kvProxy: KvProxy
) {
    suspend fun getNextPageIdFromBookAndPageOrCreate(
        notebookId: String,
        pageId: String
    ): String {
        val index = getNextPageIdFromBookAndPage(notebookId, pageId)
        if (index != null)
            return index
        val book = bookRepository.getById(notebookId = notebookId)
        // creating a new page
        val page = book!!.newPage()
        pageRepository.create(page)
        bookRepository.addPage(notebookId, page.id)
        return page.id
    }

    suspend fun getNextPageIdFromBookAndPage(
        notebookId: String,
        pageId: String
    ): String? {
        val book = bookRepository.getById(notebookId = notebookId) ?: return null
        val index = book.getPageIndex(pageId)
        if (index == -1 || index == book.pageIds.size - 1)
            return null
        return book.pageIds[index + 1]
    }

    suspend fun getPreviousPageIdFromBookAndPage(
        notebookId: String,
        pageId: String
    ): String? {
        val book = bookRepository.getById(notebookId = notebookId) ?: return null
        val index = book.getPageIndex(pageId)
        if (index <= 0) { // handles -1 and 0
            return null
        }
        return book.pageIds[index - 1]
    }

    suspend fun duplicatePage(pageId: String) {
        val pageWithData = pageRepository.getWithDataById(pageId)
        val duplicatedPage = pageWithData.page.copy(
            id = UUID.randomUUID().toString(),
            scroll = 0,
            createdAt = Date(),
            updatedAt = Date()
        )
        pageRepository.create(duplicatedPage)
        strokeRepository.create(pageWithData.strokes.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                pageId = duplicatedPage.id,
                updatedAt = Date(),
                createdAt = Date()
            )
        })
        imageRepository.create(pageWithData.images.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                pageId = duplicatedPage.id,
                updatedAt = Date(),
                createdAt = Date()
            )
        })
        val notebookId = pageWithData.page.notebookId
        if (notebookId != null) {
            val book = bookRepository.getById(notebookId) ?: return
            val pageIndex = book.getPageIndex(pageWithData.page.id)
            if (pageIndex == -1) return
            val pageIds = book.pageIds.toMutableList()
            pageIds.add(pageIndex + 1, duplicatedPage.id)
            bookRepository.update(book.copy(pageIds = pageIds))
        }
    }

    suspend fun isObservable(notebookId: String?): Boolean {
        if (notebookId == null) return false
        val book = bookRepository.getById(notebookId = notebookId) ?: return false
        return BackgroundType.fromKey(book.defaultBackgroundType) == BackgroundType.AutoPdf
    }

    /**
     * Retrieves the 0-based index of a page within a notebook.
     *
     * @param notebookId The ID of the notebook containing the page (must not be null).
     * @param pageId The ID of the page to find.
     * @return The 0-based index of the page within the notebook's page list. Returns -1 if the page is not found.
     * @throws NoSuchElementException if the notebook with the given notebookId is not found.
     */
    suspend fun getPageNumber(notebookId: String, pageId: String): Int {
        // Fetch the book or throw an exception if it doesn't exist.
        val book = bookRepository.getById(notebookId)
            ?: throw NoSuchElementException("Notebook with ID '$notebookId' not found.")

        return book.getPageIndex(pageId)
    }

    suspend fun createNewQuickPage(parentFolderId: String? = null) : String? {
        val page = Page(
            notebookId = null,
            background = GlobalAppSettings.current.defaultNativeTemplate,
            backgroundType = BackgroundType.Native.key,
            parentFolderId = parentFolderId
        )
        try {
            pageRepository.create(page)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            log.e("Failed to create page: ${e.message}")
            // it should return something like a result
            return null
        }
        return page.id
    }

    suspend fun newPageInBook(notebookId: String, index: Int = 0): String? {
        try {
            val book = bookRepository.getById(notebookId)
                ?: return null
            val page = book.newPage()
            pageRepository.create(page)
            bookRepository.addPage(notebookId, page.id, index)
            return page.id
        } catch (e: Exception) {
            log.e("Failed to create page in book: ${e.message}")
            return null
        }
    }

}