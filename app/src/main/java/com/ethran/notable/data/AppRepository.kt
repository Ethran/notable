package com.ethran.notable.data

import android.content.Context
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.model.BackgroundType
import com.onyx.android.sdk.extension.isNotNull
import java.util.Date
import java.util.UUID

class AppRepository(val context: Context) {
    val bookRepository = BookRepository(context)
    val pageRepository = PageRepository(context)
    val strokeRepository = StrokeRepository(context)
    val imageRepository = ImageRepository(context)
    val folderRepository = FolderRepository(context)
    val kvRepository = KvRepository(context)
    val kvProxy = KvProxy(context)

    fun getNextPageIdFromBookAndPageOrCreate(
        notebookId: String,
        pageId: String
    ): String {
        val index = getNextPageIdFromBookAndPage(notebookId, pageId)
        if (index.isNotNull())
            return index
        val book = bookRepository.getById(notebookId = notebookId)
        // creating a new page
        val page = book!!.newPage()
        pageRepository.create(page)
        bookRepository.addPage(notebookId, page.id)
        return page.id
    }

    fun getNextPageIdFromBookAndPage(
        notebookId: String,
        pageId: String
    ): String? {
        val book = bookRepository.getById(notebookId = notebookId)
        val pages = book!!.pageIds
        val index = pages.indexOf(pageId)
        if (index == pages.size - 1)
            return null
        return pages[index + 1]
    }

    fun getPreviousPageIdFromBookAndPage(
        notebookId: String,
        pageId: String
    ): String? {
        val book = bookRepository.getById(notebookId = notebookId)
        val pages = book!!.pageIds
        val index = pages.indexOf(pageId)
        if (index == 0 || index == -1) {
            return null
        }
        return pages[index - 1]
    }

    fun duplicatePage(pageId: String) {
        val pageWithStrokes = pageRepository.getWithStrokeById(pageId)
        val pageWithImages = pageRepository.getWithImageById(pageId)
        val duplicatedPage = pageWithStrokes.page.copy(
            id = UUID.randomUUID().toString(),
            scroll = 0,
            createdAt = Date(),
            updatedAt = Date()
        )
        pageRepository.create(duplicatedPage)
        strokeRepository.create(pageWithStrokes.strokes.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                pageId = duplicatedPage.id,
                updatedAt = Date(),
                createdAt = Date()
            )
        })
        imageRepository.create(pageWithImages.images.map {
            it.copy(
                id = UUID.randomUUID().toString(),
                pageId = duplicatedPage.id,
                updatedAt = Date(),
                createdAt = Date()
            )
        })
        require(pageWithStrokes.page.notebookId == pageWithImages.page.notebookId) { "pageWithStrokes.page.notebookId != pageWithImages.page.notebookId" }
        val notebookId = pageWithStrokes.page.notebookId
        if (notebookId != null) {
            val book = bookRepository.getById(notebookId) ?: return
            val pageIndex = book.pageIds.indexOf(pageWithImages.page.id)
            if (pageIndex == -1) return
            val pageIds = book.pageIds.toMutableList()
            pageIds.add(pageIndex + 1, duplicatedPage.id)
            bookRepository.update(book.copy(pageIds = pageIds))
        }
    }

    fun isObservable(notebookId: String?): Boolean {
        if (notebookId == null) return false
        val book = bookRepository.getById(notebookId = notebookId) ?: return false
        return BackgroundType.Companion.fromKey(book.defaultBackgroundType) == BackgroundType.AutoPdf
    }

    fun getPageNumber(
        notebookId: String?, pageId: String
    ): Int {
        if (notebookId == null) return -2
        val book = bookRepository.getById(notebookId = notebookId) ?: return -3
        return book.pageIds.indexOf(pageId)
    }
}