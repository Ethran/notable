package com.ethran.notable.data

import com.ethran.notable.data.datastore.GlobalAppSettings
import androidx.room.withTransaction
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncState
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.db.getPageIndex
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.events.AppEvent
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
    val notebookSyncStateRepository: NotebookSyncStateRepository,
    val pageSyncStateRepository: PageSyncStateRepository,
    val kvProxy: KvProxy,
    private val db: AppDatabase
) {
    /**
     * The atomic sync commit point, at page granularity. In one Room
     * transaction: mark the notebook synced, upsert the freshly transferred pages' [PageSyncState]
     * rows, and delete rows for pages that left the notebook. Pages skipped this sync keep their
     * existing rows untouched. A crash before this commits leaves *no* per-page rows for the
     * transfer, so the next sync re-transfers exactly those pages.
     *
     * Network-only follow-ups (remote GC, tombstone cleanup) stay OUTSIDE this transaction — the
     * caller runs them after it returns.
     */
    suspend fun commitNotebookSync(
        notebookId: String,
        localUpdatedAt: Date,
        remoteUpdatedAt: Date?,
        manifestEtag: String?,
        committedPageRows: List<PageSyncState>,
        departedPageIds: List<String>,
    ) {
        db.withTransaction {
            notebookSyncStateRepository.markSynced(
                notebookId = notebookId,
                localUpdatedAt = localUpdatedAt,
                remoteUpdatedAt = remoteUpdatedAt,
                remoteEtag = manifestEtag,
            )
            pageSyncStateRepository.upsertAll(committedPageRows)
            pageSyncStateRepository.deleteByIds(departedPageIds)
        }
    }
    /**
     * Replace a downloaded page's content in a single transaction: an interrupted download can no
     * longer leave a page with its old strokes deleted but new ones not yet inserted (sync P5).
     * A null [pageRepository.getWithDataById] result means the page row is absent -> create it.
     */
    suspend fun replaceDownloadedPage(page: Page, strokes: List<Stroke>, images: List<Image>) {
        db.withTransaction {
            val existing = pageRepository.getWithDataById(page.id)
            if (existing != null) {
                strokeRepository.deleteAll(existing.strokes.map { it.id })
                imageRepository.deleteAll(existing.images.map { it.id })
                pageRepository.update(page)
            } else {
                pageRepository.create(page)
            }
            strokeRepository.create(strokes)
            imageRepository.create(images)
        }
    }

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
        if (pageWithData == null) {
            log.w("duplicatePage: Missing page Data.")
            return
        }
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

    /**
     * Clone [notebookId] into a new notebook in the same folder: same title (suffixed) and
     * background settings, with every page -- and its strokes and images -- copied under fresh
     * ids. Returns the new notebook's id, or null if [notebookId] doesn't exist.
     *
     * [Notebook.linkedExternalUri] is deliberately dropped on the copy: it names an external file
     * the app periodically overwrites on save (see [com.ethran.notable.io.ExportEngine.exportToLinkedFileAsync]),
     * and carrying it over would make the original and the copy race to overwrite that same file.
     * Background/image file paths are copied as-is (shared, not duplicated on disk), matching how
     * [duplicatePage] already shares per-page backgrounds and images.
     */
    suspend fun duplicateNotebook(notebookId: String): String? {
        val source = bookRepository.getById(notebookId) ?: return null
        val duplicatedNotebook = source.copy(
            id = UUID.randomUUID().toString(),
            title = "${source.title} (copy)",
            pageIds = listOf(),
            openPageId = null,
            linkedExternalUri = null,
            createdAt = Date(),
            updatedAt = Date()
        )
        db.withTransaction {
            // The notebook row must exist before any page can reference it (notebookId FK).
            bookRepository.createEmpty(duplicatedNotebook)
            val newPageIds = source.pageIds.mapNotNull { sourcePageId ->
                val pageWithData = pageRepository.getWithDataById(sourcePageId)
                if (pageWithData == null) {
                    log.w("duplicateNotebook: Missing page data for $sourcePageId, skipping.")
                    return@mapNotNull null
                }
                val duplicatedPage = pageWithData.page.copy(
                    id = UUID.randomUUID().toString(),
                    notebookId = duplicatedNotebook.id,
                    scroll = 0,
                    createdAt = Date(),
                    updatedAt = Date()
                )
                pageRepository.create(duplicatedPage)
                strokeRepository.create(pageWithData.strokes.map {
                    it.copy(
                        id = UUID.randomUUID().toString(),
                        pageId = duplicatedPage.id,
                        createdAt = Date(),
                        updatedAt = Date()
                    )
                })
                imageRepository.create(pageWithData.images.map {
                    it.copy(
                        id = UUID.randomUUID().toString(),
                        pageId = duplicatedPage.id,
                        createdAt = Date(),
                        updatedAt = Date()
                    )
                })
                duplicatedPage.id
            }
            bookRepository.update(
                duplicatedNotebook.copy(
                    pageIds = newPageIds,
                    openPageId = newPageIds.firstOrNull()
                )
            )
        }
        return duplicatedNotebook.id
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

    suspend fun createNewQuickPage(parentFolderId: String? = null): String? {
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