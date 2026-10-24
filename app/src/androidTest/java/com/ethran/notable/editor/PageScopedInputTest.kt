package com.ethran.notable.editor

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.cleanAllStrokes
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.testStroke
import com.ethran.notable.testing.waitForCondition
import com.ethran.notable.ui.SnackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pen input and undo stay with the page they were made on, whatever pages are shown meanwhile.
 */
@RunWith(AndroidJUnit4::class)
class PageScopedInputTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var data: PageDataTestHarness
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        data = PageDataTestHarness(app)
    }

    @After
    fun tearDown() {
        viewScope.cancel()
        data.close()
    }

    private val pageLeases by lazy { PageLeases(data.pageDataManager) }

    private fun createPageView(pageId: String) = PageView(
        context = app,
        coroutineScope = viewScope,
        pageDataManager = data.pageDataManager,
        pageLeases = pageLeases,
        appRepository = data.appRepository,
        initialPageId = pageId,
        viewWidth = 400,
        viewHeight = 600,
        snackManager = SnackState(),
        onPageShown = {},
    )

    // Pen input and undo run on the main thread, as in the editor.
    private suspend fun <T> onMain(step: suspend () -> T): T = withContext(Dispatchers.Main) { step() }

    private fun PageView.showAndWait(pageId: String) {
        changePage(pageId)
        assertTrue(waitForCondition(5000) {
            currentPageId == pageId && isTransformationAllowed &&
                data.pageDataManager.validatePageDataLoaded(pageId)
        })
    }

    private fun strokeIds(pageId: String): Set<String> =
        data.pageDataManager.getStrokes(pageId).map { it.id }.toSet()

    @Test(timeout = 60000)
    fun penInputAndUndo_stayWithThePageTheyWereMadeOn() = runBlocking {
        val pages = TestNotebookSeeder.seedNotebook(data.db, pageCount = 3, strokesPerPage = 5).pageIds
        val (a, b, c) = pages
        val view = createPageView(a)
        val history = History(view, data.appEventBus)

        // Strokes drawn but not yet in the history, which undo first commits, as the editor does.
        val drawn = mutableListOf<Stroke>()
        viewScope.launch(Dispatchers.Main) {
            CanvasEventBus.commitHistorySignalImmediately.collect {
                history.addDrawnStrokes(drawn)
                drawn.clear()
                CanvasEventBus.commitCompletion.complete(Unit)
            }
        }

        // Runs [action] on the main thread. Checks that the shown page lost [removed] and gained
        // [added] (read after the action), that no other page changed, and that every stroke stored
        // on a page was made on that page.
        suspend fun expect(
            name: String,
            removed: Set<String> = emptySet(),
            added: () -> Set<String> = { emptySet() },
            action: suspend () -> Unit,
        ) {
            val shown = view.currentPageId
            val before = pages.associateWith { strokeIds(it) }
            onMain { action() }
            for (pageId in pages) {
                val expected = before.getValue(pageId).let { if (pageId == shown) it - removed + added() else it }
                assertEquals("$name: strokes of $pageId", expected, strokeIds(pageId))
                for (stroke in data.pageDataManager.getStrokes(pageId)) {
                    assertEquals("$name: stroke ${stroke.id} stored on $pageId", pageId, stroke.pageId)
                }
            }
        }

        var n = 0
        // A stroke with the pen put down on [pageId]. Returns its id, or null if it was dropped
        // because another page is shown by then.
        suspend fun draw(pageId: String): String? {
            n++
            val points = testStroke(pageId, x = 40f + n * 25, y = 300f + n * 25).points
            var id: String? = null
            expect("drawing on $pageId", added = { setOfNotNull(id) }) {
                if (handleDraw(view, pageId, drawn, 5f, Color.BLACK, Pen.BALLPEN, points)) id = drawn.last().id
            }
            assertEquals("stroke on $pageId kept", pageId == view.currentPageId, id != null)
            return id
        }

        // Drawn strokes reach the history a moment after drawing, as in the editor.
        suspend fun commitDrawn() = onMain {
            history.addDrawnStrokes(drawn)
            drawn.clear()
        }

        suspend fun undo(removed: Set<String> = emptySet()) =
            expect("undo on ${view.currentPageId}", removed = removed) { history.undo() }

        suspend fun redo(added: Set<String> = emptySet()) =
            expect("redo on ${view.currentPageId}", added = { added }) { history.redo() }

        view.showAndWait(a)
        val s1 = draw(a)!!
        val s2 = draw(a)!!
        undo(removed = setOf(s1, s2))
        redo(added = setOf(s1, s2))
        expect("erasing all of $a", removed = strokeIds(a)) { cleanAllStrokes(view, history) }

        // Edits made on a are neither undone on b nor put on it.
        view.showAndWait(b)
        undo()
        redo()
        // The pen went down on a just before b was shown.
        assertNull(draw(a))
        val t1 = draw(b)!!
        undo(removed = setOf(t1))
        undo()
        redo(added = setOf(t1))

        // A stroke whose step reaches the history only after its page was left still joins the
        // page's history, so undo back on that page removes it first.
        val t2 = draw(b)!!
        view.showAndWait(c)
        commitDrawn()
        view.showAndWait(b)
        undo(removed = setOf(t2))
        undo(removed = setOf(t1))

        // Leaving a page and coming back keeps its history.
        view.showAndWait(a)
        val u1 = draw(a)!!
        commitDrawn()
        view.showAndWait(b)
        view.showAndWait(a)
        undo(removed = setOf(u1))
        view.disposeOldPage()
    }
}
