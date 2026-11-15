package com.ethran.notable.testing

import android.content.Context
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.FitMode
import com.ethran.notable.data.model.SheetSize
import com.ethran.notable.editor.PageLeases
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.PageViewport
import com.ethran.notable.ui.SnackState
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
 * Fixtures for the [PageView] scroll/zoom tests, in the style of `PageViewportTest`: a real
 * [PageDataManager] and [PageLeases] over an in-memory notebook, and a [PageView] of a fixed size.
 * The view reads its page's sheet and saved position off the page record, as the editor's does.
 *
 * Sheets are in page pixels, so the tests size their views against them directly and hold on any
 * screen. They stay small, so the screen bitmaps do too.
 */

/** An A5-proportioned portrait sheet. */
internal val A5_SHEET = SheetSize(840, 1188)

/** An A6-proportioned portrait sheet, narrower than [A5_SHEET]. */
internal val A6_SHEET = SheetSize(600, 849)

/** An A4-proportioned portrait sheet, wider than [A5_SHEET]. */
internal val A4_SHEET = SheetSize(1190, 1683)

/**
 * A notebook holding one page with [sheet] (null: an infinite page), stored [savedScrollX] across
 * and [savedScrollY] down it, in page pixels; returns the page id. A page keeps no zoom in the
 * database: a view holds that in memory for as long as it shows the page.
 */
internal suspend fun seedSheetPage(
    db: AppDatabase,
    sheet: SheetSize?,
    savedScrollY: Int = 0,
    savedScrollX: Int = 0,
): String {
    val notebookId = UUID.randomUUID().toString()
    val pageId = UUID.randomUUID().toString()
    db.notebookDao().create(Notebook(id = notebookId, title = "Sheet viewport test", defaultSheet = sheet))
    db.pageDao().create(
        Page(
            id = pageId,
            notebookId = notebookId,
            sheet = sheet,
            positionY = savedScrollY,
            positionX = savedScrollX,
        ),
    )
    db.notebookDao().setPageIds(notebookId, listOf(pageId), Date())
    db.notebookDao().setOpenPageId(notebookId, pageId)
    return pageId
}

/**
 * A loose page — belonging to no notebook — with [sheet]; returns the page id. A view pages to it
 * with [PageViewFixture.showPage], for tests that leave one page and come back.
 */
internal suspend fun seedLooseSheetPage(db: AppDatabase, sheet: SheetSize?): String {
    val pageId = UUID.randomUUID().toString()
    db.pageDao().create(Page(id = pageId, sheet = sheet))
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
    private val restoreSettings: () -> Unit,
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
     * [PageView.awaitPageReady] is the supported wait for a load. It is bounded so a hang shows up
     * as this clear failure rather than as the suite's own test timeout, and the state the load
     * produces is checked afterwards to confirm the page really is shown and loaded.
     */
    fun awaitInitialization(view: PageView = page) {
        val outcome = runBlocking { withTimeoutOrNull(JOIN_WAIT_MS) { view.awaitPageReady() } }
        check(outcome != null) {
            "awaitPageReady did not return within ${JOIN_WAIT_MS}ms for page ${view.currentPageId}"
        }
        val ready = waitForCondition(READY_TIMEOUT_MS) {
            view.isTransformationAllowed && view.isPageDataLoaded
        }
        check(ready) { "The view never showed and loaded page ${view.currentPageId}" }
    }

    /**
     * Shows [toPageId] in [view] and waits until it is shown and loaded, as the editor's pager does.
     */
    suspend fun showPage(toPageId: String, view: PageView = page) {
        view.changePage(toPageId)
        val shown = waitForCondition(READY_TIMEOUT_MS) {
            view.currentPageId == toPageId && view.isTransformationAllowed && view.isPageDataLoaded
        }
        check(shown) { "The view never showed page $toPageId" }
    }

    /**
     * Another [PageView] over the same page, at the same size, as the editor has one per open
     * editor: it takes the screen bitmap another view left on the page's shelf, if that shows the
     * same viewport.
     */
    fun newPageView(): PageView = createView(page.viewWidth, page.viewHeight).also { views += it }

    /** Runs [step] on the main thread, where the app runs its scroll, zoom and resize steps. */
    suspend fun onMain(step: suspend () -> Unit) = withContext(Dispatchers.Main) { step() }

    /** Closes every view of this test and the page data layer behind them, and restores the fit. */
    fun close() {
        for (view in views) view.disposeOldPage()
        scope.cancel()
        data.close()
        restoreSettings()
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
        /** How long [PageView.awaitPageReady] may take before the wait fails. */
        const val JOIN_WAIT_MS = 2_000L

        /** How long a page may take to be shown and loaded. */
        const val READY_TIMEOUT_MS = 10_000L
    }
}

/**
 * Starts the page data layer as the app wires it, seeds one page with [sheet] stored [savedScrollY]
 * down and [savedScrollX] across, sets this device's fit to [fitPortrait] / [fitLandscape] (restored
 * by [PageViewFixture.close]), and opens a [PageView] of it at [viewWidth] x [viewHeight].
 *
 * The view opens its page at the zoom its fit prescribes for that size — unzoomed with the sheet
 * centred under REAL, sheet spanning the width under FIT_TO_WIDTH — at the page's saved position.
 */
internal fun createPageViewForTest(
    context: Context,
    sheet: SheetSize?,
    viewWidth: Int,
    viewHeight: Int,
    savedScrollY: Int = 0,
    savedScrollX: Int = 0,
    fitPortrait: FitMode = FitMode.REAL,
    fitLandscape: FitMode = FitMode.REAL,
): PageViewFixture {
    val previousSettings = GlobalAppSettings.current
    GlobalAppSettings.update(previousSettings.copy(fitPortrait = fitPortrait, fitLandscape = fitLandscape))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val data = PageDataTestHarness(context)
    val pageId = runBlocking { seedSheetPage(data.db, sheet, savedScrollY, savedScrollX) }
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
    return PageViewFixture(page, data.pageDataManager, scope, pageId, data, pageLeases) {
        GlobalAppSettings.update(previousSettings)
    }
}
