package com.ethran.notable.testing

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.PageMode
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.PageView
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.convertPointsToPixel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/**
 * Fixtures for the [PageView] scroll/zoom regression tests.
 *
 * [PageView] only talks to [PageDataManager] through a small set of accessors, so a relaxed mock
 * with a couple of stateful stubs is enough to drive it without a notebook, a datastore or a
 * SurfaceView.
 */

/**
 * Width in pixels of the [pageMode] sheet on the current display.
 *
 * The tests derive their viewport sizes from this value instead of hardcoding pixels, so the
 * "sheet is narrower/wider than the viewport" precondition holds on any screen density.
 */
internal fun sheetWidthInPixels(pageMode: PageMode, context: Context): Float =
    convertPointsToPixel(pageMode.width, context)

internal class PageViewFixture(
    val page: PageView,
    val pageDataManager: PageDataManager,
    val scope: CoroutineScope,
    val pageId: String,
) {
    /**
     * Blocks until [PageView] has finished loading the page: the canvas, the zoom level and the
     * scroll position are restored, and the strokes/images have been fetched.
     *
     * Joining the loader jobs is deterministic - no polling, and no dependence on the order of the
     * statements inside `PageView.init`.
     */
    fun awaitInitialization() {
        runBlocking { page.awaitPageReady() }
    }
}

/**
 * Builds a [PageView] rendering [pageId] with the given viewport size, zoom and scroll.
 *
 * The current page id and the page scroll are backed by real state so [PageView] sees a coherent
 * page; everything else stays a relaxed mock. Note that `setPageScroll` is deliberately *not*
 * stubbed with an answer: `Offset` is a value class, so MockK only sees its packed `Long` form and
 * cannot hand back an `Offset`. Tests therefore assert what was written with `verify { ... }`,
 * which compares the packed values produced by the compiler on both sides.
 */
internal fun createPageViewForTest(
    context: Context,
    pageMode: PageMode,
    viewWidth: Int,
    viewHeight: Int,
    pageId: String = "page-1",
    zoom: Float = 1f,
    scroll: Offset = Offset.Zero,
    cachedBitmap: Bitmap? = null,
): PageViewFixture {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pageDataManager = mockk<PageDataManager>(relaxed = true)

    val currentPageId = AtomicReference(pageId)
    val currentScroll = AtomicReference(scroll)

    every { pageDataManager.getCurrentPageId() } answers { currentPageId.get() }
    coEvery { pageDataManager.setPage(any()) } coAnswers { currentPageId.set(firstArg()) }
    every { pageDataManager.getPageScroll(any()) } answers { currentScroll.get() }
    every { pageDataManager.getPageZoom(any()) } answers { zoom }
    every { pageDataManager.getPageHeight(any()) } answers { viewHeight }
    every { pageDataManager.getPageMode() } answers { pageMode }
    every { pageDataManager.getBackgroundType() } returns BackgroundType.Native
    every { pageDataManager.getBackgroundName() } returns "blank"
    every { pageDataManager.getCachedBitmap(any()) } returns cachedBitmap
    every { pageDataManager.getStrokes(any()) } returns emptyList()
    every { pageDataManager.getImages(any()) } returns emptyList()
    every { pageDataManager.validatePageDataLoaded(any()) } returns true

    val page = PageView(
        context = context,
        coroutineScope = scope,
        pageDataManager = pageDataManager,
        initialPageId = pageId,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        snackManager = SnackState(),
    )

    return PageViewFixture(page, pageDataManager, scope, pageId)
}
