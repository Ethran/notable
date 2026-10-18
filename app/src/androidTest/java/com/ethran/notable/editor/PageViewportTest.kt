package com.ethran.notable.editor

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.movedDown
import com.ethran.notable.testing.testImage
import com.ethran.notable.testing.testStroke
import com.ethran.notable.testing.waitForCondition
import com.ethran.notable.utils.AppResult
import com.ethran.notable.ui.SnackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How a [PageView] views its pages: each page keeps its own scroll and zoom while the view is
 * open, its vertical scroll is saved, and the content height covers everything on the page.
 */
@RunWith(AndroidJUnit4::class)
class PageViewportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var data: PageDataTestHarness
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screen = SCREEN_WIDTH to SCREEN_HEIGHT

    @Before
    fun setUp() {
        // A portrait screen, so a pinch out snaps to a zoom other than 1 on any device.
        SCREEN_WIDTH = 1200
        SCREEN_HEIGHT = 1600
        data = PageDataTestHarness(app)
    }

    @After
    fun tearDown() {
        viewScope.cancel()
        data.close()
        SCREEN_WIDTH = screen.first
        SCREEN_HEIGHT = screen.second
    }

    private suspend fun seedPages(count: Int): List<String> =
        TestNotebookSeeder.seedNotebook(data.db, pageCount = count, strokesPerPage = 5).pageIds

    private fun createPageView(pageId: String, snack: SnackState = SnackState()) = PageView(
        context = app,
        coroutineScope = viewScope,
        pageDataManager = data.pageDataManager,
        appRepository = data.appRepository,
        initialPageId = pageId,
        viewWidth = 400,
        viewHeight = 600,
        snackManager = snack,
        onPageShown = {},
    )

    private fun PageView.showAndWait(pageId: String) {
        changePage(pageId)
        assertTrue(waitForCondition(5000) { currentPageId == pageId && isTransformationAllowed })
    }

    @Test(timeout = 60000)
    fun eachPageKeepsItsOwnViewport_andOnlyItsVerticalScrollOutlivesTheView() = runBlocking {
        val pages = seedPages(4)
        val view = createPageView(pages[0])
        val left = HashMap<String, PageViewport>()
        for ((i, pageId) in pages.withIndex()) {
            view.showAndWait(pageId)
            assertEquals("first show of $pageId", 1f, view.zoom)
            view.updateScroll(Offset(30f * (i + 1), 200.4f * (i + 1)))
            if (i % 2 == 0) {
                view.simpleUpdateZoom(-0.5f)
                assertNotEquals("zoom of $pageId", 1f, view.zoom)
            }
            left[pageId] = view.viewport.value
        }
        assertEquals("every page has its own viewport", pages.size, left.values.toSet().size)

        for (pageId in listOf(pages[0], pages[2], pages[1], pages[3], pages[0])) {
            view.showAndWait(pageId)
            assertEquals(pageId, left[pageId], view.viewport.value)
        }

        view.disposeOldPage()
        assertEquals(AppResult.Success(Unit), data.pageSaver.saveAll())
        for (pageId in pages) {
            assertEquals(
                "saved scroll of $pageId",
                left.getValue(pageId).scroll.y.roundToInt(),
                data.db.pageDao().getById(pageId)!!.scroll,
            )
        }

        val reopened = createPageView(pages[0])
        for (pageId in pages) {
            reopened.showAndWait(pageId)
            val savedY = left.getValue(pageId).scroll.y.roundToInt().toFloat()
            assertEquals("reopened $pageId", PageViewport(scroll = Offset(0f, savedY)), reopened.viewport.value)
        }
        reopened.disposeOldPage()
    }

    @Test(timeout = 30000)
    fun stepRunningWhileThePageChanges_changesNoPage() = runBlocking {
        // Both pages start at the top, unzoomed, so their viewports are equal.
        val (first, second) = seedPages(2)
        val snack = SnackState()
        val view = createPageView(first, snack)
        view.showAndWait(first)

        // Holding the drawing lock keeps the zoom step waiting until the page has changed.
        val step = try {
            CanvasEventBus.drawingInProgress.lock()
            val step = viewScope.launch { view.simpleUpdateZoom(-0.5f) }
            assertTrue("the step waits for drawing", waitForCondition(5000) {
                snack.activeSnacks.value.any { it.text?.startsWith("Waiting for drawing") == true }
            })
            view.showAndWait(second)
            step
        } finally {
            if (CanvasEventBus.drawingInProgress.isLocked) CanvasEventBus.drawingInProgress.unlock()
        }
        withTimeout(5000) { step.join() }

        assertEquals(PageViewport(), view.viewport.value)
        view.showAndWait(first)
        assertEquals(PageViewport(), view.viewport.value)
        view.disposeOldPage()
    }

    @Test(timeout = 30000)
    fun gestureBeforeThePageIsShown_changesNothing() = runBlocking {
        val (pageId) = seedPages(1)
        data.db.pageDao().updateScroll(pageId, 300)
        val mayRead = CompletableDeferred<Unit>()
        data.beforePageRecordRead = { mayRead.await() }
        val view = createPageView(pageId)

        assertFalse(view.isTransformationAllowed)
        view.updateScroll(Offset(0f, 500f))
        view.simpleUpdateZoom(-0.5f)
        mayRead.complete(Unit)
        assertTrue(waitForCondition(5000) { view.isTransformationAllowed })

        assertEquals(PageViewport(scroll = Offset(0f, 300f)), view.viewport.value)
        view.disposeOldPage()
        assertEquals(AppResult.Success(Unit), data.pageSaver.saveAll())
        assertEquals(300, data.db.pageDao().getById(pageId)!!.scroll)
    }

    @Test(timeout = 30000)
    fun contentHeightCoversEveryStrokeAndImage_afterAnyEdit() = runBlocking {
        val (pageId) = seedPages(1)
        val view = createPageView(pageId)
        assertTrue(waitForCondition(5000) { view.isTransformationAllowed })
        fun expected(): Int {
            val bottom = max(
                view.strokes.maxOfOrNull { it.bottom.toInt() } ?: 0,
                view.images.maxOfOrNull { it.y + it.height } ?: 0,
            )
            return max(bottom + 50, SCREEN_HEIGHT)
        }
        // The seeded strokes are loaded and measured before any edit.
        assertTrue(waitForCondition(5000) {
            view.strokes.size == 5 && view.contentHeight.value == expected()
        })

        val low = SCREEN_HEIGHT + 2000
        val stroke = testStroke(pageId, y = low.toFloat())
        val image = testImage(pageId, x = 10, y = low + 500)
        val edits: List<Pair<String, () -> Unit>> = listOf(
            "add stroke" to { view.addStrokes(listOf(stroke)) },
            "add image" to { view.addImage(image) },
            "move image down" to { view.updateImages(listOf(image.copy(y = low + 1500))) },
            "move stroke down" to { view.updateStrokes(listOf(stroke.movedDown(3000f))) },
            "remove stroke" to { view.removeStrokes(listOf(stroke.id)) },
            "remove image" to { view.removeImages(listOf(image.id)) },
        )
        for ((name, edit) in edits) {
            edit()
            assertEquals(name, expected(), view.contentHeight.value)
        }
        view.disposeOldPage()
    }
}
