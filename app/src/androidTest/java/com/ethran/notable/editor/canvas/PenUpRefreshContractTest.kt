package com.ethran.notable.editor.canvas

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.editor.PageLeases
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.canvas.rectify.Point2D
import com.ethran.notable.editor.canvas.rectify.RectBounds
import com.ethran.notable.editor.canvas.rectify.RectifyCommit
import com.ethran.notable.testing.PageDataTestHarness
import com.ethran.notable.testing.TestNotebookSeeder
import com.ethran.notable.testing.createEditorViewModelForTest
import com.ethran.notable.testing.waitForCondition
import com.ethran.notable.ui.SnackState
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which refresh a pen-up path performs — the rule settled by commit 939a593f, and the one most
 * likely to regress silently.
 *
 * The stroke commits correctly whichever refresh is chosen, so nothing about the page reveals a
 * wrong call: pushing on the rectify path re-introduces the microfreeze and the toolbar blip, and
 * not pushing for the line tool leaves the committed line invisible until a scroll. Only the call
 * itself can be asserted, which is what these do.
 *
 * `refreshUi` is the call none of them may make: it ends in `resetScreenFreeze`, whose raw-drawing
 * toggle and settle were the microfreeze.
 *
 * Runs on device because a `CanvasRefreshManager` mock must stand in for the one a real `PageView`
 * and Room-backed page sit behind, and because the asserted calls take `Rect`.
 */
@RunWith(AndroidJUnit4::class)
class PenUpRefreshContractTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var data: PageDataTestHarness
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val band = RectBounds(40, 60, 520, 340)

    @Before
    fun setUp() {
        unmockkAll()
        data = PageDataTestHarness(app)
    }

    @After
    fun tearDown() {
        viewScope.cancel()
        unmockkAll()
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

    private suspend fun PageView.show(pageId: String) {
        withContext(Dispatchers.Main) { changePage(pageId) }
        val shown = waitForCondition(5000) {
            (currentPageId == pageId && isTransformationAllowed) &&
                data.pageDataManager.validatePageDataLoaded(pageId)
        }
        assertTrue("page $pageId never shown", shown)
    }

    private fun commit(pageId: String, dirty: RectBounds? = band): RectifyCommit = RectifyCommit(
        a = Point2D(100f, 100f, pressure = 1f),
        b = Point2D(500f, 300f, pressure = 1f),
        pageId = pageId,
        dirty = dirty,
    )

    /**
     * A handler wired to [page] with a real history and a real [EditorViewModel].
     *
     * `DrawCanvas` is a `SurfaceView` and its `refreshManager` is a `val`, so the commit under test
     * is reached through `commitRectifiedGesture`'s refresh parameter rather than by swapping the
     * canvas. The view model has to be real: the handler reads `toolbarState` off its `StateFlow`,
     * and a relaxed mock hands back something that cannot be cast to it.
     */
    private fun handlerFor(page: PageView): OnyxInputHandler {
        val viewModel = createEditorViewModelForTest(app, data.db, data.pageDataManager)
        val canvas = DrawCanvas(app, viewScope, viewModel, page, History(page, data.appEventBus))
        return canvas.inputHandler
    }

    /**
     * A rectified line notes the band as firmware ink and pushes nothing: the band is already on
     * the panel, and handleDraw has put the line in the page bitmap, so a push buys nothing and
     * costs the toggle plus settle.
     */
    @Test(timeout = 60000)
    fun rectifiedLine_notesFirmwareInkAndNeverCallsRefreshUi() = runBlocking {
        val pages = TestNotebookSeeder.seedNotebook(data.db, pageCount = 1, strokesPerPage = 2).pageIds
        val page = createPageView(pages.first())
        page.show(pages.first())

        val refresh = mockk<CanvasRefreshManager>(relaxed = true)
        val handler = handlerFor(page)

        withContext(Dispatchers.Main) {
            handler.commitRectifiedGesture(commit(pages.first()), refresh)
        }

        verify(exactly = 1) { refresh.addFirmwareInk(any()) }
        verify(exactly = 0) { refresh.refreshUi(any()) }
        verify(exactly = 0) { refresh.commitErase(any(), any()) }
        verify(exactly = 0) { refresh.drawCanvasToView(any(), any()) }

        // The line did land, as a single stroke on the page the pen went down on.
        assertEquals(
            "a rectified line commits one stroke",
            3,
            data.pageDataManager.getStrokes(pages.first()).size,
        )
    }

    /**
     * A rectified line whose page is no longer shown is dropped: the line is not drawn. The band is
     * still noted as firmware ink, because the panel really is showing it and something later has to
     * clear it — the note sits outside the page check, on purpose. What must not happen is any push
     * or refresh in the same step.
     */
    @Test(timeout = 60000)
    fun rectifiedLine_onAPageNoLongerShown_notesNoInkAndNeverCallsRefreshUi() = runBlocking {
        val pages = TestNotebookSeeder.seedNotebook(data.db, pageCount = 2, strokesPerPage = 2).pageIds
        val (a, b) = pages
        val page = createPageView(a)
        page.show(b) // the pen went down on a, and b is what is shown now

        val refresh = mockk<CanvasRefreshManager>(relaxed = true)
        val handler = handlerFor(page)

        withContext(Dispatchers.Main) {
            handler.commitRectifiedGesture(commit(a), refresh)
        }

        verify(exactly = 1) { refresh.addFirmwareInk(any()) }
        verify(exactly = 0) { refresh.refreshUi(any()) }
        verify(exactly = 0) { refresh.drawCanvasToView(any(), any()) }
        verify(exactly = 0) { refresh.commitErase(any(), any()) }

        // The line itself was dropped: the shown page still holds only what it was seeded with.
        assertEquals(
            "a dropped rectified line draws nothing",
            2,
            data.pageDataManager.getStrokes(b).size,
        )
    }

    /** A gesture whose band never reached the panel commits the line and notes nothing. */
    @Test(timeout = 60000)
    fun rectifiedLine_withoutAPushedBand_notesNoInk() = runBlocking {
        val pages = TestNotebookSeeder.seedNotebook(data.db, pageCount = 1, strokesPerPage = 2).pageIds
        val page = createPageView(pages.first())
        page.show(pages.first())

        val refresh = mockk<CanvasRefreshManager>(relaxed = true)
        val handler = handlerFor(page)

        withContext(Dispatchers.Main) {
            handler.commitRectifiedGesture(commit(pages.first(), dirty = null), refresh)
        }

        verify(exactly = 0) { refresh.addFirmwareInk(any()) }
        verify(exactly = 0) { refresh.refreshUi(any()) }
    }

}

