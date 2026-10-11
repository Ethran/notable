package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
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
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.testing.createPageViewForTest
import com.ethran.notable.testing.sheetWidthInPixels
import com.ethran.notable.ui.SnackDispatcher
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorSimpleStateTests {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = TestDatabaseFactory.createInMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
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
    // ---------------------------------------------------------------------------

    /**
     * Regression 6050fdf2: for a fixed sheet (A4/A5/...) the drag delta used to be dropped
     * (`deltaOutX = 0f`) whenever the drag stayed inside the sheet bounds, which locked horizontal
     * panning completely. A drag that stays inside the bounds must move the view by that delta.
     */
    @Test(timeout = 60_000)
    fun updateScroll_appliesHorizontalDelta_whenSheetIsWiderThanViewport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A5, context)
        val viewWidth = (sheetWidth / 2f).toInt()

        val fixture = createPageViewForTest(
            context = context,
            pageMode = PageMode.A5,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.75f).toInt(),
        )
        fixture.awaitInitialization()

        runBlocking { fixture.page.updateScroll(Offset(100f, 0f)) }

        // The drag stays inside the sheet bounds, so it must be applied verbatim.
        verify { fixture.pageDataManager.setPageScroll(fixture.pageId, Offset(100f, 0f)) }
    }

    /**
     * The same clamping must still kick in when the drag would scroll past the point where the
     * sheet is centered in the viewport: the delta gets truncated, not discarded.
     */
    @Test(timeout = 60_000)
    fun updateScroll_clampsHorizontalDelta_atTheSheetCenteringBound() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A5, context)
        val viewWidth = (sheetWidth / 2f).toInt()

        val fixture = createPageViewForTest(
            context = context,
            pageMode = PageMode.A5,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.75f).toInt(),
        )
        fixture.awaitInitialization()

        // The sheet is wider than the viewport: scroll.x can never exceed (sheetWidth - viewWidth).
        val maxScrollX = sheetWidth - viewWidth

        runBlocking { fixture.page.updateScroll(Offset(1000f, 0f)) }

        // The delta is truncated at the bound, not discarded.
        verify { fixture.pageDataManager.setPageScroll(fixture.pageId, Offset(maxScrollX, 0f)) }
    }

    /** An infinite sheet has no bounds to clamp to, so the full delta is applied. */
    @Test(timeout = 60_000)
    fun updateScroll_doesNotClampHorizontalDelta_forInfinitePageMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val fixture = createPageViewForTest(
            context = context,
            pageMode = PageMode.INFINITE,
            viewWidth = 800,
            viewHeight = 600,
            scroll = Offset(50f, 0f),
        )
        fixture.awaitInitialization()

        runBlocking { fixture.page.updateScroll(Offset(-30f, 0f)) }

        verify { fixture.pageDataManager.setPageScroll(fixture.pageId, Offset(20f, 0f)) }
    }

    /**
     * Regression 5982b107: resetting the view used to force `scroll.x = 0f`, which pinned a sheet
     * narrower than the viewport to the left edge instead of centering it.
     */
    @Test(timeout = 60_000)
    fun resetZoomAndScroll_recentersNarrowSheetInsteadOfSnappingScrollToZero() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sheetWidth = sheetWidthInPixels(PageMode.A6, context)
        val viewWidth = (sheetWidth * 2f).toInt()

        val fixture = createPageViewForTest(
            context = context,
            pageMode = PageMode.A6,
            viewWidth = viewWidth,
            viewHeight = (viewWidth * 0.6f).toInt(),
            zoom = 1.5f,
            scroll = Offset(0f, 42f),
        )
        fixture.awaitInitialization()

        val controlTower = EditorControlTower(
            // Unconfined, so the coroutine started by resetZoomAndScroll() completes before we assert.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            page = fixture.page,
            history = mockk(relaxed = true),
            viewModel = createEditorViewModelForTest(context, db),
            clipboardStore = mockk(relaxed = true),
        )

        controlTower.resetZoomAndScroll()

        val page = fixture.page
        assertEquals(1f, page.zoomLevel.value, 0.001f)

        // A sheet narrower than the viewport is centered with a negative scroll, never 0.
        val centeredScrollX = page.residualScroll()
        assertNotEquals(0f, centeredScrollX, 0.01f)
        verify {
            fixture.pageDataManager.setPageScroll(fixture.pageId, Offset(centeredScrollX, 42f))
        }
    }

    /**
     * Regression 99fc4fca: opening a page that is still in the bitmap cache restored the zoom but
     * left the scroll at (0, 0), and it dropped the zoom when the canvas was recreated.
     */
    @Test(timeout = 60_000)
    fun init_restoresZoomScrollAndCachedBitmap_whenOpeningPageFromCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cached = Bitmap.createBitmap(900, 700, Bitmap.Config.ARGB_8888)

        val fixture = createPageViewForTest(
            context = context,
            pageMode = PageMode.INFINITE,
            viewWidth = 900,
            viewHeight = 700,
            zoom = 1.5f,
            scroll = Offset(100f, 200f),
            cachedBitmap = cached,
        )
        fixture.awaitInitialization()

        val page = fixture.page
        assertEquals(1.5f, page.zoomLevel.value, 0.001f)
        assertSame(
            "The cached bitmap must be reused instead of allocating a fresh one",
            cached,
            page.windowedBitmap,
        )
        // init must push the persisted scroll back into the page state, otherwise the page reopens
        // at the top-left corner.
        verify { fixture.pageDataManager.setPageScroll(fixture.pageId, Offset(100f, 200f)) }
    }

    /** Companion of the fix above: `recreateCanvas()` must re-apply the zoom to the new canvas. */
    @Test(timeout = 60_000)
    fun updateDimensions_recreatesCanvasScaledByCurrentZoom() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val fixture = createPageViewForTest(
            context = context,
            pageMode = PageMode.INFINITE,
            viewWidth = 800,
            viewHeight = 600,
            zoom = 1.5f,
        )
        fixture.awaitInitialization()

        fixture.page.updateDimensions(900, 700)

        @Suppress("DEPRECATION") // Canvas.getMatrix() is the only public way to read the transform.
        val values = FloatArray(9).also { fixture.page.windowedCanvas.matrix.getValues(it) }
        assertEquals(900, fixture.page.windowedBitmap.width)
        assertEquals(1.5f, values[Matrix.MSCALE_X], 0.001f)
        assertEquals(1.5f, values[Matrix.MSCALE_Y], 0.001f)
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
            syncOrchestrator = syncOrchestrator,
            snackDispatcher = snackDispatcher,
            historyFactory = historyFactory,
            appScope = appScope,
        )
    }
}
