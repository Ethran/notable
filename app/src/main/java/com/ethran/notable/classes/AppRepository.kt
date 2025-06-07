package com.ethran.notable.classes

import android.content.Context
import com.ethran.notable.db.BookRepository
import com.ethran.notable.db.FolderRepository
import com.ethran.notable.db.ImageRepository
import com.ethran.notable.db.KvProxy
import com.ethran.notable.db.KvRepository
import com.ethran.notable.db.Page
import com.ethran.notable.db.PageRepository
import com.ethran.notable.db.StrokeRepository
import com.onyx.android.sdk.extension.isNotNull
import java.util.Date
import java.util.UUID


class AppRepository(context: Context) {
    val context = context
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
        val page = Page(
            notebookId = notebookId,
            background = book!!.defaultNativeTemplate,
            backgroundType = "native"
        )
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
        if (pageWithStrokes.page.notebookId != null) {
            val book = bookRepository.getById(pageWithStrokes.page.notebookId) ?: return
            val pageIndex = book.pageIds.indexOf(pageWithStrokes.page.id)
            if (pageIndex == -1) return
            val pageIds = book.pageIds.toMutableList()
            pageIds.add(pageIndex + 1, duplicatedPage.id)
            bookRepository.update(book.copy(pageIds = pageIds))
        }
        if (pageWithImages.page.notebookId != null) {
            val book = bookRepository.getById(pageWithImages.page.notebookId) ?: return
            val pageIndex = book.pageIds.indexOf(pageWithImages.page.id)
            if (pageIndex == -1) return
            val pageIds = book.pageIds.toMutableList()
            pageIds.add(pageIndex + 1, duplicatedPage.id)
            bookRepository.update(book.copy(pageIds = pageIds))
        }
    }


}
