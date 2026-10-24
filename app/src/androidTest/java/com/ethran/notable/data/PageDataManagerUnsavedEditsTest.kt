package com.ethran.notable.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.movedDown
import com.ethran.notable.testing.row
import com.ethran.notable.testing.rows
import com.ethran.notable.testing.testStroke
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page loaded from the database shows the edits the database doesn't have yet: those made before
 * it was evicted, those being saved, and those made while it was loading — however a save falls
 * around the load.
 *
 * No [PageSaver] runs here; the tests write the database and hand edits around themselves.
 */
@RunWith(AndroidJUnit4::class)
class PageDataManagerUnsavedEditsTest {

    private lateinit var data: PageDataTestHarness
    private val pageDataManager get() = data.pageDataManager

    @Before
    fun setUp() {
        data = PageDataTestHarness(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        data.close()
    }

    private suspend fun seedPage(strokes: Int): String =
        TestNotebookSeeder.seedNotebook(data.db, pageCount = 1, strokesPerPage = strokes)
            .pageIds.single()

    private suspend fun evictAndReload(pageId: String) {
        assertTrue(pageDataManager.removePage(pageId))
        assertTrue(pageDataManager.getStrokes(pageId).isEmpty())
        pageDataManager.requestPageLoadJoin(pageId)
    }

    /** Writes [changes] to the database as a save does. */
    private suspend fun write(changes: PageChanges) {
        data.db.strokeDao().upsert(changes.strokes.values.filterNotNull())
        data.db.strokeDao().deleteAll(changes.strokes.filterValues { it == null }.keys.toList())
    }

    @Test(timeout = 30000)
    fun pageReloadedAroundASave_showsEveryEdit() = runBlocking {
        val pageId = seedPage(strokes = 20)
        pageDataManager.requestPageLoadJoin(pageId)
        val (moved, erased) = pageDataManager.getStrokes(pageId)
        pageDataManager.updateStrokes(pageId, listOf(moved.movedDown(200f)))
        pageDataManager.removeStrokes(pageId, listOf(erased.id))
        pageDataManager.addStrokes(pageId, listOf(testStroke(pageId)))
        val saving = pageDataManager.takeUnsaved().getValue(pageId)
        // Moved again after the save took the first move.
        pageDataManager.updateStrokes(pageId, listOf(moved.movedDown(500f)))
        val edited = pageDataManager.getStrokes(pageId).rows()

        // While the save writes: the database has none of the edits.
        evictAndReload(pageId)
        assertEquals(edited, pageDataManager.getStrokes(pageId).rows())

        // The save finishes right after the load read the database: the rows it read don't have the
        // saved edits, and they are no longer pending once the read is done.
        data.afterPageRead = {
            data.afterPageRead = {}
            write(saving)
            pageDataManager.finishSaving(failedPageIds = emptySet())
        }
        evictAndReload(pageId)
        assertEquals(edited, pageDataManager.getStrokes(pageId).rows())

        // Saved: the database has the edits the save took, the cache the later move.
        evictAndReload(pageId)
        assertEquals(edited, pageDataManager.getStrokes(pageId).rows())
    }

    @Test(timeout = 30000)
    fun editsMadeWhileThePageLoads_mergeWithItsRowsOnce() = runBlocking {
        val pageId = seedPage(strokes = 20)
        val stored = data.storedStrokes(pageId)

        // The editor draws on a page whose rows are still loading. It can't see the stored
        // strokes yet, but undo can bring one back under its id, moved.
        val drawn = testStroke(pageId)
        val drawnThenErased = testStroke(pageId, y = 300f)
        val storedMoved = stored.first().movedDown(500f)
        pageDataManager.addStrokes(pageId, listOf(drawn, drawnThenErased, storedMoved))
        pageDataManager.removeStrokes(pageId, listOf(drawnThenErased.id))

        // Drawn as the load is about to read the database, and saved before the read: the rows
        // have it, and it is no longer pending.
        val drawnAndSaved = testStroke(pageId, y = 600f)
        data.beforePageRead = {
            pageDataManager.addStrokes(pageId, listOf(drawnAndSaved))
            write(pageDataManager.takeUnsaved().getValue(pageId))
            pageDataManager.finishSaving(failedPageIds = emptySet())
        }

        pageDataManager.requestPageLoadJoin(pageId)

        val loaded = pageDataManager.getStrokes(pageId)
        assertEquals("each stroke once", loaded.size, loaded.map { it.id }.toSet().size)
        val expected =
            stored.drop(1).rows() + drawn.row() + storedMoved.row() + drawnAndSaved.row()
        assertEquals(expected, loaded.rows())
    }

    private enum class SaveAt { BeforeLoad, BeforeRead, AfterRead }

    @Test(timeout = 60000)
    fun editsSavedWhileThePageLoads_showInTheLoadedPageAsInTheDatabase() = runBlocking {
        for (saveAt in SaveAt.entries) {
            val pageId = seedPage(strokes = 20)
            val stored = data.storedStrokes(pageId)

            // Drawn as the page starts loading, not saved yet.
            val drawnEarly = testStroke(pageId)
            pageDataManager.addStrokes(pageId, listOf(drawnEarly))

            // Erases, draws and moves, then saves them, at [saveAt].
            val drawnLater = testStroke(pageId, y = 300f)
            val storedMoved = stored[1].movedDown(500f)
            val editAndSave: suspend () -> Unit = {
                pageDataManager.removeStrokes(pageId, listOf(drawnEarly.id, stored[0].id))
                pageDataManager.addStrokes(pageId, listOf(drawnLater, storedMoved))
                pageDataManager.takeUnsaved().values.forEach { write(it) }
                pageDataManager.finishSaving(failedPageIds = emptySet())
            }
            when (saveAt) {
                SaveAt.BeforeLoad -> editAndSave()
                SaveAt.BeforeRead -> data.beforePageRead = { data.beforePageRead = {}; editAndSave() }
                SaveAt.AfterRead -> data.afterPageRead = { data.afterPageRead = {}; editAndSave() }
            }
            pageDataManager.requestPageLoadJoin(pageId)

            val expected = stored.drop(2).rows() + drawnLater.row() + storedMoved.row()
            assertEquals("cache, saved $saveAt", expected, pageDataManager.getStrokes(pageId).rows())
            assertEquals("database, saved $saveAt", expected, data.storedStrokes(pageId).rows())
        }
    }
}
