package com.ethran.notable.testing

import android.content.Context
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.PageMode
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.PageLeases
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.PageViewport
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.convertPointsToPixel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.UUID

/**
 * Fixtures for the [PageView] scroll/zoom regression tests, in the style of `PageViewportTest`: a
 * real [PageDataManager] and [PageLeases] over an in-memory notebook, and a [PageView] of a fixed
 * size. The view then reads its page's sheet size and saved scroll off the page record, exactly as
 * the editor's view does — a mocked [PageDataManager] can no longer answer `getPageRecord` and
 * `takeCachedBitmap` with a coherent page, which is what the viewport maths depends on.
 */

/**
 * Width in pixels of the [pageMode] sheet on the current display.
 *
 * The tests derive their viewport sizes from this value instead of hardcoding pixels, so the
 * "sheet is narrower/wider than the viewport" precondition holds on any screen density. The
 * fixtures keep the viewport's width a whole sheet width away from the sheet, well past the 5mm
 * tolerance [com.ethran.notable.ui.sheetAdjustedWidth] snaps at, so this is also the width [PageView]
 * lays the sheet out at.
 */
internal fun sheetWidthInPixels(pageMode: PageMode, context: Context): Float =
    convertPointsToPixel(pageMode.width, context)

/**
 * A notebook holding one page on a [pageMode] sheet, stored [savedScrollX] across and
 * [savedScrollY] down it, in page pixels; returns the page id. A page keeps no zoom in the
 * database: a view holds that in memory for as long as it shows the page.
 */
internal suspend fun seedSheetPage(
    db: AppDatabase,
    pageMode: PageMode,
    savedScrollY: Int = 0,
    savedScrollX: Int = 0,
): String {
    val notebookId = UUID.randomUUID().toString()
    val pageId = UUID.randomUUID().toString()
    db.notebookDao().create(Notebook(id = notebookId, title = "Sheet viewport test"))
    db.pageDao().create(
        Page(
            id = pageId,
            notebookId = notebookId,
            pageMode = pageMode,
            scrollY = savedScrollY,
            scrollX = savedScrollX,
        ),
    )
    db.notebookDao().setPageIds(notebookId, listOf(pageId), Date())
    db.notebookDao().setOpenPageId(notebookId, pageId)
    return pageId
}

/**
 * A [PageView] showing [pageId] on a [PageDataTestHarness] page, plus the layer it reads: the same
 * page data, repositories and leases the app shares between its views.
 */
internal class PageViewFixture internal constructor(
    val page: PageView,
    val pageDataManager: PageDataManager,
    val scope: CoroutineScope,
    val pageId: String,
    val data: PageDataTestHarness,
    private val pageLeases: PageLeases,
) {
    // Every view this fixture started, so close() disposes them all.
    private val views = mutableListOf(page)

    /** The viewport the view shows its page at: its scroll, in page coordinates, and its zoom. */
    val viewport: PageViewport get() = page.viewport.value

    /**
     * Blocks — off the main thread, so the view can still swap its page in — until [view] has shown
     * its page: its record read, its viewport fitted to the sheet, its screen bitmap in place — and
     * fetched its strokes and images.
     *
     * [PageView.awaitPageReady] is the supported wait for a load, and it is joined here; but the
     * loader for the initial page goes on collecting page-change requests for as long as the view
     * lives, so that join cannot finish on its own while the view is open. The wait is therefore
     * bounded, and the state the load produces is what confirms it.
     */
    fun awaitInitialization(view: PageView = page) {
        runBlocking { withTimeoutOrNull(JOIN_WAIT_MS) { view.awaitPageReady() } }
        val ready = waitForCondition(READY_TIMEOUT_MS) {
            view.isTransformationAllowed && view.isPageDataLoaded
        }
        check(ready) { "The view never showed and loaded page ${view.currentPageId}" }
    }

    /**
     * Another [PageView] over the same page, at the same size, as the editor has one per open
     * editor: it takes the screen bitmap another view left on the page's shelf, if that shows the
     * same viewport.
     */
    fun newPageView(): PageView = createView(page.viewWidth, page.viewHeight).also { views += it }

    /** Runs [step] on the main thread, where the app runs its scroll, zoom and resize steps. */
    suspend fun onMain(step: suspend () -> Unit) = withContext(Dispatchers.Main) { step() }

    /** Closes every view of this test and the page data layer behind them. */
    fun close() {
        for (view in views) view.disposeOldPage()
        scope.cancel()
        data.close()
    }

    private fun createView(viewWidth: Int, viewHeight: Int) = PageView(
        context = page.context,
        coroutineScope = scope,
        pageDataManager = pageDataManager,
        pageLeases = pageLeases,
        appRepository = data.appRepository,
        initialPageId = pageId,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        snackManager = SnackState(),
        onPageShown = {},
    )

    private companion object {
        /** How long [PageView.awaitPageReady] is given before the wait falls back to the state. */
        const val JOIN_WAIT_MS = 2_000L

        /** How long a page may take to be shown and loaded. */
        const val READY_TIMEOUT_MS = 10_000L
    }
}

/**
 * Starts the page data layer as the app wires it, seeds one page on a [pageMode] sheet stored
 * [savedScrollY] down, and opens a [PageView] of it at [viewWidth] x [viewHeight].
 *
 * The view opens its page unzoomed — with the sheet centred in the view when the sheet is narrower
 * than it, and at the page's saved scroll (`PageView.viewportToShow`). Nothing else can be given to
 * a fresh view: zoom lives in the view, not in the page record, and a screen bitmap worth reusing
 * only gets onto the page's shelf when another view leaves the page (`disposeOldPage`).
 */
internal fun createPageViewForTest(
    context: Context,
    pageMode: PageMode,
    viewWidth: Int,
    viewHeight: Int,
    savedScrollY: Int = 0,
    savedScrollX: Int = 0,
): PageViewFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val data = PageDataTestHarness(context)
    val pageId = runBlocking { seedSheetPage(data.db, pageMode, savedScrollY, savedScrollX) }
    val pageLeases = PageLeases(data.pageDataManager)
    val page = PageView(
        context = context,
        coroutineScope = scope,
        pageDataManager = data.pageDataManager,
        pageLeases = pageLeases,
        appRepository = data.appRepository,
        initialPageId = pageId,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        snackManager = SnackState(),
        onPageShown = {},
    )
    return PageViewFixture(page, data.pageDataManager, scope, pageId, data, pageLeases)
}
