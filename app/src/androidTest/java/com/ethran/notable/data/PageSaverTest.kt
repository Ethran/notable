package com.ethran.notable.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.imageRows
import com.ethran.notable.testing.movedDown
import com.ethran.notable.testing.row
import com.ethran.notable.testing.rows
import com.ethran.notable.testing.testImage
import com.ethran.notable.testing.testStroke
import com.ethran.notable.testing.waitForCondition
import com.ethran.notable.utils.AppResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
                    assertEquals(AppResult.Success(Unit), pageSaver.saveAll())
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

        assertEquals(AppResult.Success(Unit), pageSaver.saveAll())
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
        assertTrue(pageSaver.saveAll() is AppResult.Error)

        data.db.pageDao().create(Page(id = pageCreatedLater))
        assertEquals(AppResult.Success(Unit), pageSaver.saveAll())

        assertEquals(listOf(newer.row()), data.storedStrokes(pageCreatedLater).map { it.row() })
    }

    /** Edits that can't be saved keep a warning up, so they never go unnoticed, until they are saved. */
    @Test(timeout = 30000)
    fun warningStaysUp_whileEditsCannotBeSaved() = runBlocking {
        val warnings = mutableListOf<AppEvent>()
        val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            data.appEventBus.events.collect {
                if (it is AppEvent.EditsNotSaved || it == AppEvent.DismissMessage(AppEvent.EDITS_NOT_SAVED_ID))
                    synchronized(warnings) { warnings += it }
            }
        }
        fun lastWarning() = synchronized(warnings) { warnings.lastOrNull() }
        try {
            val pageId = seedLoadedPage(strokes = 1)
            // A stroke whose row can't be written yet: it refers to a page not in the database.
            val pageCreatedLater = "page-created-later"
            pageDataManager.addStrokes(pageId, listOf(testStroke(pageCreatedLater)))

            assertTrue(pageSaver.saveAll() is AppResult.Error)
            assertTrue(waitForCondition(5000) { lastWarning() is AppEvent.EditsNotSaved })

            data.db.pageDao().create(Page(id = pageCreatedLater))
            assertEquals(AppResult.Success(Unit), pageSaver.saveAll())
            assertTrue(waitForCondition(5000) { lastWarning() is AppEvent.DismissMessage })
        } finally {
            collector.cancel()
        }
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
        assertEquals(AppResult.Success(Unit), pageSaver.saveAll())

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
            data.db.pageDao().update(data.db.pageDao().getById(id)!!.copy(updatedAt = longAgo))
            data.db.notebookDao().touchUpdatedAtForPage(id, longAgo)
        }
        suspend fun pageUpdatedAt(id: String) = data.db.pageDao().getById(id)!!.updatedAt
        suspend fun notebookUpdatedAt(id: String) = data.db.notebookDao().getById(id)!!.updatedAt

        pageDataManager.recordScroll(pageId, 750)
        assertEquals(AppResult.Success(Unit), pageSaver.saveAll())
        assertEquals(750, data.db.pageDao().getById(pageId)!!.scroll)
        assertEquals(longAgo, pageUpdatedAt(pageId))
        assertEquals(longAgo, notebookUpdatedAt(notebook.notebookId))

        pageDataManager.addStrokes(pageId, listOf(testStroke(pageId)))
        assertEquals(AppResult.Success(Unit), pageSaver.saveAll())
        assertTrue(pageUpdatedAt(pageId).after(longAgo))
        assertTrue(notebookUpdatedAt(notebook.notebookId).after(longAgo))
        assertEquals(750, data.db.pageDao().getById(pageId)!!.scroll)
        assertEquals(longAgo, pageUpdatedAt(otherNotebook.pageIds.single()))
        assertEquals(longAgo, notebookUpdatedAt(otherNotebook.notebookId))
    }

    /** Sync finds edited pages by their timestamp having moved past the one it last uploaded. */
    @Test(timeout = 30000)
    fun savedEdit_movesItsPageTimestampPastAnyStampedMeanwhile() = runBlocking {
        val pageId = seedLoadedPage(strokes = 5)
        pageDataManager.addStrokes(pageId, listOf(testStroke(pageId)))
        // Stamped after the edit by something else, e.g. a background change, then uploaded.
        val uploaded = Date(System.currentTimeMillis() + 60_000)
        data.db.pageDao().update(data.db.pageDao().getById(pageId)!!.copy(updatedAt = uploaded))

        assertEquals(AppResult.Success(Unit), pageSaver.saveAll())
        assertTrue(data.db.pageDao().getById(pageId)!!.updatedAt.after(uploaded))
    }
}
