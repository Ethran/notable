package com.ethran.notable.data

import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.imageRows
import com.ethran.notable.testing.movedDown
import com.ethran.notable.testing.row
import com.ethran.notable.testing.rows
import com.ethran.notable.testing.testImage
import com.ethran.notable.testing.testStroke
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import kotlin.random.Random

/**
 * Edits made through [PageDataManager] reach the database through [PageSaver] exactly as they were
 * made, whatever order they come in and however saves and page reloads fall between them.
 */
@RunWith(AndroidJUnit4::class)
class PageSaverTest {

    private lateinit var data: PageDataTestHarness
    private val pageDataManager get() = data.pageDataManager
    private val pageSaver get() = data.pageSaver

    @Before
    fun setUp() {
        data = PageDataTestHarness(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        data.close()
    }

    private suspend fun seedLoadedPage(strokes: Int): String {
        val pageId = TestNotebookSeeder.seedNotebook(
            data.db, pageCount = 1, strokesPerPage = strokes
        ).pageIds.single()
        pageDataManager.requestPageLoadJoin(pageId)
        return pageId
    }

    @Test(timeout = 60000)
    fun randomEditsSavesAndReloads_keepTheCacheAndTheDatabaseAsEdited() = runBlocking {
        val pageId = seedLoadedPage(strokes = 40)
        val random = Random(7)
        // What the page should hold, kept apart from the code under test.
        val expectedStrokes = pageDataManager.getStrokes(pageId).associateByTo(HashMap()) { it.id }
        val expectedImages = HashMap<String, Image>()
        val erasedStrokes = mutableListOf<Stroke>()
        val removedImages = mutableListOf<Image>()

        fun <T> Collection<T>.pick(max: Int): List<T> =
            shuffled(random).take(random.nextInt(1, max + 1))

        fun addStrokes(strokes: List<Stroke>) {
            pageDataManager.addStrokes(pageId, strokes)
            strokes.forEach { expectedStrokes[it.id] = it }
        }

        fun removeStrokes(strokes: List<Stroke>) {
            pageDataManager.removeStrokes(pageId, strokes.map { it.id })
            strokes.forEach { expectedStrokes.remove(it.id) }
        }

        fun addImages(images: List<Image>) {
            pageDataManager.addImages(pageId, images)
            images.forEach { expectedImages[it.id] = it }
        }

        repeat(300) { step ->
            when (random.nextInt(11)) {
                0 -> addStrokes(listOf(testStroke(pageId, x = random.nextFloat() * 800, y = step * 5f)))

                1 -> if (expectedStrokes.isNotEmpty()) expectedStrokes.values.pick(3).let {
                    removeStrokes(it)
                    erasedStrokes += it
                }

                2 -> if (expectedStrokes.isNotEmpty()) expectedStrokes.values.pick(5)
                    .map { it.movedDown(random.nextFloat() * 50) }
                    .let { moved ->
                        pageDataManager.updateStrokes(pageId, moved)
                        moved.forEach { expectedStrokes[it.id] = it }
                    }

                // A page cut: strokes removed and added back moved, under the same ids.
                3 -> if (expectedStrokes.isNotEmpty()) expectedStrokes.values.pick(8).let { cut ->
                    removeStrokes(cut)
                    addStrokes(cut.map { it.movedDown(300f) })
                }

                // Undo of an erase: the stroke comes back under its old id.
                4 -> erasedStrokes.removeLastOrNull()?.let { addStrokes(listOf(it)) }

                // A move of a stroke the page no longer has changes nothing.
                5 -> erasedStrokes.lastOrNull()?.let {
                    pageDataManager.updateStrokes(pageId, listOf(it.movedDown(30f)))
                }

                6 -> addImages(listOf(testImage(pageId, y = step * 3)))

                7 -> if (expectedImages.isNotEmpty()) expectedImages.values.pick(2).let { removed ->
                    pageDataManager.removeImages(pageId, removed.map { it.id })
                    removed.forEach { expectedImages.remove(it.id) }
                    removedImages += removed
                }

                8 -> if (expectedImages.isNotEmpty()) expectedImages.values.pick(2)
                    .map { it.copy(y = it.y + 40) }
                    .let { moved ->
                        pageDataManager.updateImages(pageId, moved)
                        moved.forEach { expectedImages[it.id] = it }
                    }
                else removedImages.removeLastOrNull()?.let { addImages(listOf(it)) }

                9 -> {
                    assertTrue(pageSaver.saveAll())
                    assertEquals(expectedStrokes.values.toList().rows(), data.storedStrokes(pageId).rows())
                    assertEquals(expectedImages.values.toList().imageRows(), data.storedImages(pageId).imageRows())
                }

                10 -> {
                    assertTrue(pageDataManager.removePage(pageId))
                    pageDataManager.requestPageLoadJoin(pageId)
                }
            }
            assertEquals("step $step", expectedStrokes.values.toList().rows(), pageDataManager.getStrokes(pageId).rows())
            assertEquals("step $step", expectedImages.values.toList().imageRows(), pageDataManager.getImages(pageId).imageRows())
        }

        assertTrue(pageSaver.saveAll())
        assertEquals(expectedStrokes.values.toList().rows(), data.storedStrokes(pageId).rows())
        assertEquals(expectedImages.values.toList().imageRows(), data.storedImages(pageId).imageRows())
    }

    @Test(timeout = 30000)
    fun failedSave_keepsItsChangesUnderThoseMadeMeanwhile_andStoresTheNewestLater() = runBlocking {
        val pageId = seedLoadedPage(strokes = 5)
        // A stroke whose row can't be written yet: it refers to a page not in the database.
        val pageCreatedLater = "page-created-later"
        val stroke = testStroke(pageCreatedLater)
        pageDataManager.addStrokes(pageId, listOf(stroke))
        // Moved while the save is writing it.
        val newer = stroke.movedDown(100f)
        data.beforeStrokeWrite = {
            data.beforeStrokeWrite = {}
            pageDataManager.updateStrokes(pageId, listOf(newer))
        }
        assertFalse(pageSaver.saveAll())

        data.db.pageDao().create(Page(id = pageCreatedLater))
        assertTrue(pageSaver.saveAll())

        assertEquals(listOf(newer.row()), data.storedStrokes(pageCreatedLater).map { it.row() })
    }

    @Test(timeout = 30000)
    fun changesToADeletedPage_areDropped_andOtherPagesAreStillSaved() = runBlocking {
        val (deletedPage, keptPage) = TestNotebookSeeder.seedNotebook(
            data.db, pageCount = 2, strokesPerPage = 5
        ).pageIds
        pageDataManager.requestPageLoadJoin(deletedPage)
        pageDataManager.requestPageLoadJoin(keptPage)
        pageDataManager.addStrokes(deletedPage, listOf(testStroke(deletedPage)))
        val kept = testStroke(keptPage)
        pageDataManager.addStrokes(keptPage, listOf(kept))

        data.db.pageDao().delete(deletedPage)
        assertTrue(pageSaver.saveAll())

        assertTrue(kept.row() in data.storedStrokes(keptPage).rows())
        // Not kept around for a retry that could never succeed.
        assertTrue(pageDataManager.takeUnsaved().isEmpty())
    }

    @Test(timeout = 30000)
    fun editsAdvanceTheirPageAndNotebookTimestamps_scrollAloneDoesNot() = runBlocking {
        val notebook = TestNotebookSeeder.seedNotebook(data.db, pageCount = 1, strokesPerPage = 5)
        val otherNotebook = TestNotebookSeeder.seedNotebook(data.db, pageCount = 1, strokesPerPage = 5)
        val pageId = notebook.pageIds.single()
        pageDataManager.requestPageLoadJoin(pageId)
        val longAgo = Date(1_000L)
        for (id in notebook.pageIds + otherNotebook.pageIds) {
            data.db.pageDao().touchUpdatedAt(id, longAgo.time)
            data.db.notebookDao().touchUpdatedAtForPage(id, longAgo)
        }
        suspend fun pageUpdatedAt(id: String) = data.db.pageDao().getById(id)!!.updatedAt
        suspend fun notebookUpdatedAt(id: String) = data.db.notebookDao().getById(id)!!.updatedAt

        pageDataManager.setPageScroll(pageId, Offset(0f, 750f))
        pageDataManager.saveScroll(pageId)
        assertTrue(pageSaver.saveAll())
        assertEquals(750, data.db.pageDao().getById(pageId)!!.scroll)
        assertEquals(longAgo, pageUpdatedAt(pageId))
        assertEquals(longAgo, notebookUpdatedAt(notebook.notebookId))

        pageDataManager.addStrokes(pageId, listOf(testStroke(pageId)))
        assertTrue(pageSaver.saveAll())
        assertTrue(pageUpdatedAt(pageId).after(longAgo))
        assertTrue(notebookUpdatedAt(notebook.notebookId).after(longAgo))
        assertEquals(750, data.db.pageDao().getById(pageId)!!.scroll)
        assertEquals(longAgo, pageUpdatedAt(otherNotebook.pageIds.single()))
        assertEquals(longAgo, notebookUpdatedAt(otherNotebook.notebookId))
    }
}
