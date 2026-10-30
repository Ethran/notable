package com.ethran.notable.editor

import android.content.Context
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PagePreviewStore
import com.ethran.notable.data.PageSaver
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.PageMode
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.CryptoHelper
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.data.db.ImageRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.KvRepository
import com.ethran.notable.data.db.NotebookSyncStateRepository
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.data.db.PageSyncStateRepository
import com.ethran.notable.data.db.StrokeRepository
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.testing.PageViewFixture
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.createPageViewForTest
import com.ethran.notable.testing.seedLooseSheetPage
import com.ethran.notable.testing.sheetWidthInPixels
import com.ethran.notable.ui.SnackDispatcher
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EditorSimpleStateTests {

    private lateinit var db: AppDatabase
    private val fixtures = mutableListOf<PageViewFixture>()
    private val screen = SCREEN_WIDTH to SCREEN_HEIGHT

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
        // A portrait screen, so a pinch out snaps to a zoom other than 1 on any device.
        SCREEN_WIDTH = 1200
        SCREEN_HEIGHT = 1600
    }

    @After
    fun tearDown() {
        for (fixture in fixtures) fixture.close()
        fixtures.clear()
        db.close()
        SCREEN_WIDTH = screen.first
        SCREEN_HEIGHT = screen.second
    }

    @Test
    fun modeChange_updatesToolbarState() {
        val viewModel = createEditorViewModelForTest(
            context = ApplicationProvider.getApplicationContext(),
            db = db,
        )

        viewModel.onToolbarAction(ToolbarAction.ChangeMode(Mode.Erase))
        assertEquals(Mode.Erase, viewModel.toolbarState.value.mode)
    }

    // ---------------------------------------------------------------------------
    // PageView scroll / zoom regressions (branch betterLandScape)
    //
    // A view keeps one PageViewport — its scroll and its zoom — per page it shows, and it fits that
    // viewport to the page's sheet: centring the sheet when it is narrower than the view, and
    // clamping it to the sheet when the view is. These tests therefore read the viewport the view
    // shows (page.viewport, page.scroll, page.zoom) instead of the old zoomLevel flow and the
    // setPageScroll calls, and run their steps on the main thread, where the editor runs them.
    // ---------------------------------------------------------------------------

    /** A page on a [pageMode] sheet, viewed at [viewWidth] x [viewHeight]; closed with the test. */
    private fun createPageView(
        context: Context,
        pageMode: PageMode,
        viewWidth: Int,
        viewHeight: Int,
        savedScrollY: Int = 0,
        savedScrollX: Int = 0,
    ): PageViewFixture =
        createPageViewForTest(context, pageMode, viewWidth, viewHeight, savedScrollY, savedScrollX)
            .also { fixtures += it }

    /**
     * Regression 6050fdf2: for a fixed sheet (A4/A5/...) the drag delta used to be dropped
     * (`deltaOutX = 0f`) whenever the drag stayed inside the sheet bounds, which locked horizontal
     * panning completely. A drag that stays inside the bounds must move the view by that delta.
     */
    @Test(timeout = 60_000)
    fun updateScroll_appliesHorizontalDelta_whenSheetIsWiderThanViewport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A5, context)
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A5,
            viewWidth = (sheetWidth / 2f).toInt(),
            viewHeight = ((sheetWidth / 2f) * 0.75f).toInt(),
        )
        fixture.awaitInitialization()

        // The view is narrower than the sheet, so it opens at the sheet's left edge.
        assertEquals(PageViewport(), fixture.viewport)

        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(100f, 0f)) }

        // The drag stays inside the sheet bounds, so it is applied verbatim.
        assertEquals(PageViewport(scroll = Offset(100f, 0f)), fixture.viewport)
    }

    /**
     * The same clamping must still kick in when the drag would scroll past the sheet's far edge:
     * the delta gets truncated, not discarded.
     */
    @Test(timeout = 60_000)
    fun updateScroll_clampsHorizontalDelta_atTheSheetEdge() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A5, context)
        val viewWidth = (sheetWidth / 2f).toInt()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A5,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.75f).toInt(),
        )
        fixture.awaitInitialization()

        // The sheet is wider than the viewport: scroll x can never exceed (sheetWidth - viewWidth).
        val maxScrollX = sheetWidth - viewWidth

        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(1000f, 0f)) }

        // The delta is truncated at the bound, not discarded: the sheet's far edge ends up against
        // the viewport's far edge, and no further.
        assertTrue("scrolled to ${fixture.page.scroll.x}", fixture.page.scroll.x > 0f)
        assertEquals(maxScrollX, fixture.page.scroll.x, 0.01f)
        assertEquals(0f, fixture.page.scroll.y, 0.01f)
    }

    /** An infinite sheet has no bounds to clamp to, so the full delta is applied. */
    @Test(timeout = 60_000)
    fun updateScroll_doesNotClampHorizontalDelta_forInfinitePageMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.INFINITE,
            viewWidth = 800,
            viewHeight = 600,
        )
        fixture.awaitInitialization()
        assertEquals(PageViewport(), fixture.viewport)

        // Only the vertical scroll is stored, so the view is walked to x = 50 before walking back.
        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(50f, 0f)) }
        assertEquals(50f, fixture.page.scroll.x, 0.01f)

        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(-30f, 0f)) }
        assertEquals(PageViewport(scroll = Offset(20f, 0f)), fixture.viewport)
    }

    /**
     * Regression 5982b107: resetting the view used to force `scroll.x = 0f`, which pinned a sheet
     * narrower than the viewport to the left edge instead of centering it.
     *
     * The centering now happens when the page is shown (`viewportToShow`) as well as on a reset, and
     * a sideways drag can no longer undo it — so the reset starts from a centered sheet and leaves it
     * centered, with the vertical position it had.
     */
    @Test(timeout = 60_000)
    fun resetView_recentersNarrowSheetInsteadOfSnappingScrollToZero() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A6, context)
        val viewWidth = (sheetWidth * 2f).toInt()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A6,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.6f).toInt(),
        )
        fixture.awaitInitialization()

        // A sheet narrower than the viewport is centered with a negative scroll, never 0.
        val centeredScrollX = -(viewWidth - sheetWidth) / 2f
        assertEquals(
            "the sheet is centered when the page is shown",
            centeredScrollX, fixture.page.scroll.x, 0.01f,
        )
        assertTrue("scroll x is not 0: ${fixture.page.scroll.x}", abs(fixture.page.scroll.x) > 0.01f)

        // A drag cannot scroll the sheet past the point where it is centered, and it keeps its
        // vertical scroll, which is what the reset has to hold on to.
        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(-100f, 42f)) }
        assertEquals(
            "a sideways drag cannot undo the centering",
            centeredScrollX, fixture.page.scroll.x, 0.01f,
        )
        assertEquals(42f, fixture.page.scroll.y, 0.01f)

        // What EditorControlTower.resetZoomAndScroll() runs, on the thread it runs it on.
        fixture.onMain { fixture.page.resetView(fixture.pageId) }

        val page = fixture.page
        assertEquals("the view is unzoomed", 1f, page.zoom, 0.001f)
        assertEquals(PageViewport(scroll = Offset(centeredScrollX, 42f)), page.viewport.value)
    }

    /**
     * A sheet narrower than the view has one x at any zoom — the centring one — and a sideways drag
     * from either side cannot move it. The clamp that keeps it there is applied twice per drag, once
     * to the page's left edge and once to the sheet, so this pins what the pair produces together at
     * a zoom other than 1, where the two bounds differ.
     */
    @Test(timeout = 60_000)
    fun updateScroll_holdsZoomedOutNarrowSheetCentred_againstDragsFromEitherSide() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A6, context)
        val viewWidth = (sheetWidth * 2f).toInt()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A6,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.6f).toInt(),
        )
        fixture.awaitInitialization()

        // Zoomed out, so the page area the view covers is wider than the sheet, and the sheet has
        // even more slack at its sides than it did at zoom 1.
        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(0f, 40f)) }
        fixture.onMain { fixture.page.simpleUpdateZoom(fixture.pageId, -0.5f) }
        val zoom = fixture.page.zoom
        assertTrue("the page was zoomed: $zoom", zoom != 1f)
        val centered = -((viewWidth / zoom) - sheetWidth) / 2f
        assertEquals(centered, fixture.page.scroll.x, 0.01f)

        // Pulling the sheet right, then pushing it left: it stays centred either way, and the
        // vertical position takes the drag, a screen delta divided by the zoom into page space.
        val yAfterFirstDrag = 40f + 30f / zoom
        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(-150f, 30f)) }
        assertEquals("dragged left at zoom $zoom", centered, fixture.page.scroll.x, 0.01f)
        assertEquals(yAfterFirstDrag, fixture.page.scroll.y, 0.01f)

        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(150f, 0f)) }
        assertEquals("dragged right at zoom $zoom", centered, fixture.page.scroll.x, 0.01f)
        assertEquals(yAfterFirstDrag, fixture.page.scroll.y, 0.01f)
    }

    /**
     * Zooming re-fits the sheet: the slack at the sides depends on the zoom, so the view centers the
     * sheet again at the new zoom instead of holding the scroll it had, and keeps its vertical
     * position.
     */
    @Test(timeout = 60_000)
    fun simpleUpdateZoom_refitsNarrowSheetAtTheNewZoom() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A6, context)
        val viewWidth = (sheetWidth * 2f).toInt()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A6,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.6f).toInt(),
        )
        fixture.awaitInitialization()

        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(0f, 42f)) }
        val centeredAt1 = -(viewWidth - sheetWidth) / 2f
        assertEquals(centeredAt1, fixture.page.scroll.x, 0.01f)

        // A pinch out, as the gesture handler sends it.
        fixture.onMain { fixture.page.simpleUpdateZoom(fixture.pageId, -0.5f) }

        // Whatever zoom the app settles on, the sheet stays centered at that zoom: the slack halves
        // on each side of the viewport, and the top of the view stays where it was.
        val zoom = fixture.page.zoom
        assertTrue("the page was zoomed: $zoom", zoom != 1f)
        assertEquals(
            "zoomed to $zoom",
            -((viewWidth / zoom) - sheetWidth) / 2f, fixture.page.scroll.x, 0.01f,
        )
        assertEquals(42f, fixture.page.scroll.y, 0.01f)
    }

    // ---------------------------------------------------------------------------
    // The horizontal scroll in the page record
    //
    // A page's record keeps where it was last shown, on both axes, so closing the document and
    // opening it again — a fresh view over the same record — comes back where the reader left it.
    // Saving the axis is only meaningful where it is the reader's own: a sheet wider than the view
    // and an infinite page can be held anywhere in their bounds, while a sheet narrower than the
    // view has exactly one x, the centring one, which the fit derives whatever was saved.
    // ---------------------------------------------------------------------------

    /**
     * A sheet wider than the view can be panned up to its far edge; that position is the reader's,
     * so a reopened page has to be back there rather than at the sheet's left edge.
     */
    @Test(timeout = 60_000)
    fun reopeningAView_restoresSavedScrollX_whenSheetIsWiderThanViewport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A5, context)
        val viewWidth = (sheetWidth / 2f).toInt()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A5,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.75f).toInt(),
        )
        fixture.awaitInitialization()

        // Panned to the bound the sheet allows at this size.
        val maxScrollX = sheetWidth - viewWidth
        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(1000f, 0f)) }
        assertEquals(maxScrollX, fixture.page.scroll.x, 0.01f)

        // What closing the document does to the scroll: it gets saved.
        fixture.data.pageSaver.saveAll()

        fixture.page.disposeOldPage()
        val reopened = fixture.newPageView()
        fixture.awaitInitialization(reopened)
        assertEquals(
            "the reopened page is back at the sheet's far edge",
            maxScrollX, reopened.viewport.value.scroll.x, 0.01f,
        )
    }

    /** An infinite page has no bound at all, so its horizontal position is purely the reader's. */
    @Test(timeout = 60_000)
    fun reopeningAView_restoresSavedScrollX_forInfinitePageMode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.INFINITE,
            viewWidth = 900,
            viewHeight = 700,
            savedScrollY = 200,
        )
        fixture.awaitInitialization()

        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(300f, 0f)) }
        assertEquals(300f, fixture.page.scroll.x, 0.01f)

        fixture.data.pageSaver.saveAll()

        fixture.page.disposeOldPage()
        val reopened = fixture.newPageView()
        fixture.awaitInitialization(reopened)
        assertEquals(
            PageViewport(scroll = Offset(300f, 200f)),
            reopened.viewport.value,
        )
    }

    /**
     * The axis the fit owns: a sheet narrower than the view has exactly one x, the centring one, so
     * the saved value never gets to move the sheet. The page starts with an x saved far past its
     * centring value, and the reopened page is centred just the same.
     */
    @Test(timeout = 60_000)
    fun reopeningAView_keepsNarrowSheetCentred_afterItsScrollWasSaved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A6, context)
        val viewWidth = (sheetWidth * 2f).toInt()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A6,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.6f).toInt(),
            savedScrollX = 5_000,
        )
        fixture.awaitInitialization()

        val centeredScrollX = -(viewWidth - sheetWidth) / 2f
        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(-100f, 42f)) }
        assertEquals(centeredScrollX, fixture.page.scroll.x, 0.01f)
        assertEquals(42f, fixture.page.scroll.y, 0.01f)

        fixture.data.pageSaver.saveAll()

        fixture.page.disposeOldPage()
        val reopened = fixture.newPageView()
        fixture.awaitInitialization(reopened)
        assertEquals(PageViewport(scroll = Offset(centeredScrollX, 42f)), reopened.viewport.value)
    }

    /**
     * Regression 99fc4fca: opening a page whose screen bitmap was still cached restored its zoom but
     * left the scroll at (0, 0). A page's screen now sits on the page's shelf together with the
     * viewport it shows, and a view opening that page takes it, at that viewport.
     *
     * The zoom half of that fix is gone by design: a page's zoom is kept in the views showing it,
     * not in its record, while both of its scroll axes are saved — so a reopened page comes back
     * unzoomed at the scroll it was left at. `PageViewportTest` covers that per-page viewport
     * memory, so nothing here asserts a restored zoom any more.
     */
    @Test(timeout = 60_000)
    fun reopeningAView_restoresSavedScroll_andReusesTheCachedScreen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.INFINITE,
            viewWidth = 900,
            viewHeight = 700,
            savedScrollY = 200,
        )
        fixture.awaitInitialization()

        // The saved scroll is the view's starting point, not the top of the page.
        val savedViewport = PageViewport(scroll = Offset(0f, 200f))
        assertEquals(savedViewport, fixture.viewport)

        // Leaving the page puts its screen on the shelf, with the viewport it shows.
        fixture.page.disposeOldPage()
        val (cached, cachedViewport) = fixture.pageDataManager.peekCachedBitmap(fixture.pageId)
            ?: error("The page kept no screen bitmap")
        assertSame(fixture.page.windowedBitmap, cached)
        assertEquals(savedViewport, cachedViewport)

        val page = fixture.newPageView()
        fixture.awaitInitialization(page)
        assertEquals(savedViewport, page.viewport.value)
        assertSame(
            "The cached bitmap must be reused instead of allocating a fresh one",
            cached,
            page.windowedBitmap,
        )
        page.disposeOldPage()
    }

    /**
     * Companion of the fix above: recreating the canvas re-applied the zoom to it. A zoom fitted to
     * one viewport size fits no page at another, so a resize now drops it: the new screen is drawn
     * unzoomed, and the sheet is fitted — centered again — in the new width.
     */
    @Test(timeout = 60_000)
    fun updateDimensions_recreatesTheScreenUnzoomedAndRefitsTheSheet() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A6, context)
        val viewWidth = (sheetWidth * 2f).toInt()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A6,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.6f).toInt(),
        )
        fixture.awaitInitialization()

        fixture.onMain { fixture.page.updateScroll(fixture.pageId, Offset(0f, 60f)) }
        fixture.onMain { fixture.page.simpleUpdateZoom(fixture.pageId, -0.5f) }
        assertTrue("the page is zoomed", fixture.page.zoom != 1f)

        // The new viewport is still wider than the sheet, so the sheet stays centered in it.
        val newWidth = (sheetWidth * 1.2f).toInt()
        val newHeight = (sheetWidth * 0.9f).toInt()
        fixture.onMain { fixture.page.updateDimensions(newWidth, newHeight) }

        @Suppress("DEPRECATION") // Canvas.getMatrix() is the only public way to read the transform.
        val values = FloatArray(9).also { fixture.page.windowedCanvas.matrix.getValues(it) }
        assertEquals(newWidth, fixture.page.windowedBitmap.width)
        assertEquals(newHeight, fixture.page.windowedBitmap.height)
        assertEquals(newWidth, fixture.page.viewWidth)
        assertEquals("the zoom of the old size is dropped", 1f, fixture.page.zoom, 0.001f)
        assertEquals(1f, values[Matrix.MSCALE_X], 0.001f)
        assertEquals(1f, values[Matrix.MSCALE_Y], 0.001f)
        assertEquals(
            "the sheet is centered in the new width",
            -(newWidth - sheetWidth) / 2f, fixture.page.scroll.x, 0.01f,
        )
        assertEquals("the vertical scroll is kept", 60f, fixture.page.scroll.y, 0.01f)
    }

    /**
     * The resize re-fits the page it shows, and it must re-fit the pages it has shown and left too:
     * those keep the viewport they were left at, and a page coming back takes that viewport as it
     * stands rather than fitting it again. So a viewport kept for another page is fitted for the old
     * view width unless the resize re-fits it, and a narrow sheet ends up off centre in the new one.
     */
    @Test(timeout = 60_000)
    fun updateDimensions_refitsTheSheetOfPagesLeftBehindToo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A6, context)
        val viewWidth = (sheetWidth * 2f).toInt()
        val fixture = createPageView(
            context = context,
            pageMode = PageMode.A6,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.6f).toInt(),
        )
        fixture.awaitInitialization()

        // A second sheet page, shown once so the view holds the viewport it was left at, then left
        // for the first page again: the first page is the shown one when the size changes.
        val otherPageId = seedLooseSheetPage(fixture.data.db, PageMode.A6)
        fixture.showPage(otherPageId)
        fixture.showPage(fixture.pageId)

        val newWidth = (sheetWidth * 1.2f).toInt()
        val newHeight = (sheetWidth * 0.9f).toInt()
        fixture.onMain { fixture.page.updateDimensions(newWidth, newHeight) }
        val centeredInNew = -(newWidth - sheetWidth) / 2f
        assertEquals(
            "the shown page is centered in the new width",
            centeredInNew, fixture.page.scroll.x, 0.01f,
        )

        // The other page was never shown at the new size, so the view still holds its old viewport.
        fixture.showPage(otherPageId)
        assertEquals(
            "the page left behind is centered in the new width too",
            centeredInNew, fixture.page.scroll.x, 0.01f,
        )
    }

    private fun createEditorViewModelForTest(context: Context, db: AppDatabase): EditorViewModel {
        val bookRepository = BookRepository(db.notebookDao(), db.pageDao())
        val pageRepository = PageRepository(db.pageDao())
        val strokeRepository = StrokeRepository(db.strokeDao())
        val imageRepository = ImageRepository(db.ImageDao())
        val folderRepository = FolderRepository(db.folderDao())

        val kvRepository = KvRepository(db.kvDao(), context)
        val kvProxy = KvProxy(kvRepository, CryptoHelper())

        val notebookSyncStateRepository = NotebookSyncStateRepository(db.notebookSyncStateDao())
        val pageSyncStateRepository = PageSyncStateRepository(db.pageSyncStateDao())
        val appRepository = AppRepository(
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

        val editorSettingCacheManager = EditorSettingCacheManager(kvRepository)

        val exportEngine = mockk<ExportEngine>(relaxed = true)
        val pageDataManager = mockk<PageDataManager>(relaxed = true)
        val pageSaver = mockk<PageSaver>(relaxed = true)
        val syncOrchestrator = mockk<SyncOrchestrator>(relaxed = true)
        val snackDispatcher = mockk<SnackDispatcher>(relaxed = true)
        val historyFactory = mockk<History.Factory>(relaxed = true)

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        return EditorViewModel(
            context = context,
            appRepository = appRepository,
            editorSettingCacheManager = editorSettingCacheManager,
            exportEngine = exportEngine,
            pageDataManager = pageDataManager,
            pageLeases = PageLeases(pageDataManager),
            pageSaver = pageSaver,
            pagePreviewStore = PagePreviewStore(context, pageDataManager, appRepository),
            syncOrchestrator = syncOrchestrator,
            snackDispatcher = snackDispatcher,
            historyFactory = historyFactory,
            appScope = appScope,
        )
    }
}
