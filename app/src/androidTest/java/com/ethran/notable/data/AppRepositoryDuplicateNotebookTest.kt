package com.ethran.notable.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.createAppRepositoryForTest
import com.ethran.notable.testing.row
import com.ethran.notable.testing.testImage
import com.ethran.notable.testing.testStroke
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppRepositoryDuplicateNotebookTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabaseFactory.createInMemory(context)
        repo = createAppRepositoryForTest(context, db)
    }

    @After
    fun tearDown() = db.close()

    /** A two-page notebook: two strokes on the first page, an image on the second. */
    private suspend fun createNotebook(notebook: Notebook = Notebook(title = "Lecture notes")): Notebook {
        repo.bookRepository.create(notebook)
        val firstPageId = repo.bookRepository.getById(notebook.id)!!.pageIds.single()
        val secondPageId = repo.newPageInBook(notebook.id, 1)!!
        repo.strokeRepository.create(listOf(testStroke(firstPageId), testStroke(firstPageId, y = 300f)))
        repo.imageRepository.create(listOf(testImage(secondPageId)))
        return repo.bookRepository.getById(notebook.id)!!
    }

    /** What the pages show, in order, regardless of which ids the rows have. */
    private suspend fun content(pageIds: List<String>) = pageIds.map { pageId ->
        val page = repo.pageRepository.getWithDataById(pageId)!!
        page.strokes.map { it.row().copy(id = "", pageId = "") }.toSet() to
            page.images.map { listOf(it.x, it.y, it.width, it.height, it.uri) }.toSet()
    }

    private fun rowCount(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    @Test(timeout = 30000)
    fun copy_showsTheSamePagesInOrder_withoutTheExternalLink() = runBlocking {
        val original = createNotebook(
            Notebook(title = "Lecture notes", linkedExternalUri = "content://export/notes.xopp")
        )

        val copy = repo.bookRepository.getById(repo.duplicateNotebook(original.id)!!)!!

        assertEquals("Lecture notes (copy)", copy.title)
        assertNull(copy.linkedExternalUri)
        assertEquals(copy.pageIds.first(), copy.openPageId)
        assertEquals(content(original.pageIds), content(copy.pageIds))
    }

    @Test(timeout = 30000)
    fun copy_keepsItsContent_whenTheOriginalIsDeleted() = runBlocking {
        val original = createNotebook()
        val copy = repo.bookRepository.getById(repo.duplicateNotebook(original.id)!!)!!
        val copied = content(copy.pageIds)

        repo.bookRepository.delete(original.id)

        assertEquals(copied, content(copy.pageIds))
    }

    @Test(timeout = 30000)
    fun failedCopy_leavesNothingBehind() = runBlocking {
        val original = createNotebook()
        // The notebook still lists its second page, which no longer exists.
        repo.pageRepository.delete(original.pageIds[1])
        val tables = listOf("Notebook", "Page", "Stroke", "Image")
        val before = tables.map(::rowCount)

        assertTrue(runCatching { repo.duplicateNotebook(original.id) }.isFailure)

        assertEquals(before, tables.map(::rowCount))
    }
}
