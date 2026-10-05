package com.ethran.notable.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.editor.utils.Pen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppRepositoryDuplicateNotebookTest {

    private lateinit var db: AppDatabase
    private lateinit var appRepository: AppRepository

    @Before
    fun createDb() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val bookRepository = BookRepository(db.notebookDao(), db.pageDao())
        val pageRepository = PageRepository(db.pageDao())
        val strokeRepository = StrokeRepository(db.strokeDao())
        val imageRepository = ImageRepository(db.ImageDao())
        val folderRepository = FolderRepository(db.folderDao())
        val kvProxy = KvProxy(KvRepository(db.kvDao(), context), CryptoHelper())
        val notebookSyncStateRepository = NotebookSyncStateRepository(db.notebookSyncStateDao())
        val pageSyncStateRepository = PageSyncStateRepository(db.pageSyncStateDao())

        appRepository = AppRepository(
            bookRepository = bookRepository,
            pageRepository = pageRepository,
            strokeRepository = strokeRepository,
            imageRepository = imageRepository,
            folderRepository = folderRepository,
            notebookSyncStateRepository = notebookSyncStateRepository,
            pageSyncStateRepository = pageSyncStateRepository,
            kvProxy = kvProxy,
            db = db,
        )
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun dummyStroke(pageId: String) = Stroke(
        id = UUID.randomUUID().toString(),
        size = 5f,
        pen = Pen.BALLPEN,
        top = 10f,
        bottom = 14f,
        left = 10f,
        right = 30f,
        maxPressure = MAX_PRESSURE_NORMALIZED,
        points = listOf(
            StrokePoint(x = 10f, y = 10f, pressure = 0.5f),
            StrokePoint(x = 30f, y = 14f, pressure = 0.75f),
        ),
        pageId = pageId,
    )

    private fun dummyImage(pageId: String) = Image(
        id = UUID.randomUUID().toString(),
        height = 100,
        width = 200,
        uri = "content://media/external/images/42",
        pageId = pageId,
    )

    @Test(timeout = 30000)
    fun duplicateNotebook_copiesTitlePagesStrokesAndImages() = runBlocking {
        val original = Notebook(title = "Lecture notes", parentFolderId = null)
        appRepository.bookRepository.create(original)
        val firstPageId = appRepository.bookRepository.getById(original.id)!!.pageIds.single()
        val secondPageId = appRepository.newPageInBook(original.id, 1)!!

        val strokeOnFirstPage = dummyStroke(firstPageId)
        appRepository.strokeRepository.create(strokeOnFirstPage)
        val imageOnSecondPage = dummyImage(secondPageId)
        appRepository.imageRepository.create(imageOnSecondPage)

        val newNotebookId = appRepository.duplicateNotebook(original.id)

        assertNotNull(newNotebookId)
        assertNotEquals(original.id, newNotebookId)

        val copy = appRepository.bookRepository.getById(newNotebookId!!)!!
        assertEquals("Lecture notes (copy)", copy.title)
        assertEquals(2, copy.pageIds.size)
        assertTrue(copy.pageIds.none { it in setOf(firstPageId, secondPageId) })
        assertEquals(copy.pageIds.first(), copy.openPageId)

        val sourceFirstPage = appRepository.pageRepository.getWithDataById(firstPageId)!!
        val copiedFirstPage = appRepository.pageRepository.getWithDataById(copy.pageIds[0])!!
        assertEquals(1, copiedFirstPage.strokes.size)
        assertNotEquals(sourceFirstPage.strokes[0].id, copiedFirstPage.strokes[0].id)
        assertEquals(sourceFirstPage.strokes[0].points, copiedFirstPage.strokes[0].points)

        val copiedSecondPage = appRepository.pageRepository.getWithDataById(copy.pageIds[1])!!
        assertEquals(1, copiedSecondPage.images.size)
        assertNotEquals(imageOnSecondPage.id, copiedSecondPage.images[0].id)
        assertEquals(imageOnSecondPage.uri, copiedSecondPage.images[0].uri)

        val sourceAfter = appRepository.bookRepository.getById(original.id)!!
        assertEquals(listOf(firstPageId, secondPageId), sourceAfter.pageIds)
        assertEquals(1, appRepository.pageRepository.getWithDataById(firstPageId)!!.strokes.size)
    }

    @Test(timeout = 30000)
    fun duplicateNotebook_dropsLinkedExternalUri() = runBlocking {
        val original = Notebook(title = "Linked notebook", linkedExternalUri = "content://export/target.xopp")
        appRepository.bookRepository.create(original)

        val newNotebookId = appRepository.duplicateNotebook(original.id)!!

        val copy = appRepository.bookRepository.getById(newNotebookId)!!
        assertNull(copy.linkedExternalUri)
        val sourceAfter = appRepository.bookRepository.getById(original.id)!!
        assertEquals("content://export/target.xopp", sourceAfter.linkedExternalUri)
    }

    @Test(timeout = 30000)
    fun duplicateNotebook_missingReferencedPage_rollsBackEntireCopy() = runBlocking {
        val original = Notebook(title = "Inconsistent notebook")
        appRepository.bookRepository.create(original)
        val firstPageId = appRepository.bookRepository.getById(original.id)!!.pageIds.single()
        val secondPageId = appRepository.newPageInBook(original.id, 1)!!
        val booksBefore = appRepository.bookRepository.getAll().size

        appRepository.pageRepository.delete(secondPageId)

        try {
            appRepository.duplicateNotebook(original.id)
            org.junit.Assert.fail("Expected duplicateNotebook to throw for a missing referenced page")
        } catch (e: IllegalStateException) {
        }

        assertEquals(booksBefore, appRepository.bookRepository.getAll().size)
        val sourceAfter = appRepository.bookRepository.getById(original.id)!!
        assertEquals(listOf(firstPageId, secondPageId), sourceAfter.pageIds)
    }

    @Test(timeout = 30000)
    fun duplicateNotebook_missingNotebook_returnsNullAndCreatesNothing() = runBlocking {
        val before = appRepository.bookRepository.getAll().size

        val result = appRepository.duplicateNotebook("does-not-exist")

        assertNull(result)
        assertEquals(before, appRepository.bookRepository.getAll().size)
    }

    @Test(timeout = 30000)
    fun duplicateNotebook_emptyNotebook_copiesWithNoPages() = runBlocking {
        val original = Notebook(title = "Empty shell")
        appRepository.bookRepository.createEmpty(original)

        val newNotebookId = appRepository.duplicateNotebook(original.id)!!

        val copy = appRepository.bookRepository.getById(newNotebookId)!!
        assertEquals("Empty shell (copy)", copy.title)
        assertTrue(copy.pageIds.isEmpty())
        assertNull(copy.openPageId)
    }
}
