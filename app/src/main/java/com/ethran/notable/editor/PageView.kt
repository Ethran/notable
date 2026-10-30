package com.ethran.notable.editor


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toRect
import com.ethran.notable.BuildConfig
import com.ethran.notable.R
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.PageMode
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.canvas.CanvasEventBus.drawingInProgress
import com.ethran.notable.editor.canvas.CanvasEventBus.waitForDrawing
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawOnCanvasFromPage
import com.ethran.notable.editor.utils.div
import com.ethran.notable.editor.utils.divideStrokesFromCut
import com.ethran.notable.editor.utils.loadHQPagePreview
import com.ethran.notable.editor.utils.minus
import com.ethran.notable.editor.utils.plus
import com.ethran.notable.editor.utils.scaleRect
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.editor.utils.times
import com.ethran.notable.editor.utils.toIntOffset
import com.ethran.notable.gestures.MAX_ZOOM
import com.ethran.notable.gestures.MIN_ZOOM
import com.ethran.notable.gestures.ZOOM_SENSITIVITY
import com.ethran.notable.gestures.ZOOM_SNAP_THRESHOLD
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.sheetAdjustedWidth
import com.ethran.notable.utils.onError
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

const val OVERLAP = 2

// Backgrounds are rendered this much larger than the current zoom so a small zoom-in reuses the
// cached bitmap instead of re-rendering (trades a little memory/render cost for fewer reloads).
private const val BACKGROUND_ZOOM_HEADROOM = 1.2f

data class PageCutMoveResult(
    val previousStrokes: List<Stroke>,
    val movedStrokes: List<Stroke>,
)

/**
 * Manages the state and rendering of a single page within the editor.
 * It delegates task to PageDataManager, which is responsible for loading data from db,
 * and caching it.
 *
 * Pages are read off the main thread but swapped in on it, where scroll and zoom steps run too, so
 * a step always sees the shown page together with its viewport and its screen bitmap.
 */
class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val pageDataManager: PageDataManager,
    private val pageLeases: PageLeases,
    private val appRepository: AppRepository,
    val initialPageId: String,
    var viewWidth: Int,
    var viewHeight: Int,
    val snackManager: SnackState,
    /**
     * Called with each page this view starts showing, once it holds the page's lease. It runs on the
     * main thread under the view's page lock, so it must only hand the id on.
     */
    private val onPageShown: (pageId: String) -> Unit = {},
    /**
     * Called with each page this view leaves or is disposed on, once its bitmap is on the shelf.
     * Runs like [onPageShown].
     */
    private val onPageLeft: (pageId: String) -> Unit = {},
) {
    // TODO: unify width height variable

    val log = ShipBook.getLogger("PageView")
    private val logCache = ShipBook.getLogger("PageViewCache")

    private var loadingJob: Job? = null

    // The coroutine that loads the initial page. Read-only outside the class so tests can wait for
    // the page to be ready deterministically (see [awaitPageReady]) instead of polling internals.
    internal val initJob: Job

    @Volatile
    var windowedBitmap = createBitmap(viewWidth, viewHeight)
        private set

    @Volatile
    var windowedCanvas = Canvas(windowedBitmap)
        private set

    // Spare screen-sized buffer reused by updateScroll to avoid allocating a new
    // bitmap on every scroll event. Ping-ponged with windowedBitmap; recreated when
    // its size or config no longer matches, and dropped on a page switch.
    private var scrollBackBuffer: Bitmap? = null

    val strokes: List<Stroke>
        get() = pageDataManager.getStrokes(currentPageId)

    val images: List<Image>
        get() = pageDataManager.getImages(currentPageId)

    // Until the first page is shown, the initial page without its record.
    private val openPageRef = AtomicReference(OpenPage(initialPageId))

    // The page [changePage] last asked for. Only the latest request is shown: one superseded while
    // a page change runs is skipped.
    private val requestedPageId = MutableStateFlow(initialPageId)

    // The lease on the shown page, taken by [showPrepared] and dropped by [disposeOldPage].
    private val pageLock = Any()
    private var lease: PageLease? = null // guarded by pageLock
    private var disposed = false // guarded by pageLock

    /** Current snapshot of this view's page. Read it once into a local to use several fields together. */
    val openPage: OpenPage
        get() = openPageRef.get()

    val currentPageId: String
        get() = openPage.pageId


    // The shown page's viewport. [showPrepared] replaces it together with the page. Scroll and zoom
    // steps change it through [commitViewport]. A resize resets its zoom.
    private val viewportFlow = MutableStateFlow(PageViewport())
    val viewport: StateFlow<PageViewport> = viewportFlow.asStateFlow()

    // Top left corner of the view, in page coordinates.
    val scroll: Offset
        get() = viewportFlow.value.scroll

    val zoom: Float
        get() = viewportFlow.value.zoom

    // Pages this view has shown and left, with the viewport they had. Guarded by pageLock.
    private val leftPages = HashMap<String, PageViewport>()

    val isTransformationAllowed: Boolean
        get() = openPage.isTransformationAllowed

    /** Whether the shown page's strokes and images are loaded. */
    val isPageDataLoaded: Boolean
        get() = pageDataManager.validatePageDataLoaded(currentPageId)

    // Height the shown page's content needs, in page coordinates.
    private val contentHeightFlow = MutableStateFlow(SCREEN_HEIGHT)
    val contentHeight: StateFlow<Int> = contentHeightFlow.asStateFlow()


    val currentPageNumber: Int
        get() = openPage.pageNumber

    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(pageId: String, filePath: String, pageNumber: Int, scale: Float): Bitmap? {
        val cached = pageDataManager.getBackground(pageId)
        if (cached.matches(filePath, pageNumber, scale)) return cached.bitmap
        // Render a little larger than requested so small zoom-in steps reuse this bitmap instead of
        // forcing a re-render (matches() accepts any cached scale >= requested). Multiplicative so
        // the headroom stays proportional as you zoom, and at least 0.1 at small scales.
        val renderScale = (scale * BACKGROUND_ZOOM_HEADROOM).coerceAtLeast(scale + 0.1f)
        val rendered = pageDataManager.renderBackground(pageId, filePath, pageNumber, renderScale)
        // If rendering larger failed, the smaller bitmap of the same page beats a blank one.
        val sameSource = cached.path == filePath && cached.pageNumber == pageNumber
        return rendered ?: cached.bitmap?.takeIf { sameSource }
    }


    init {
        initJob = coroutineScope.launch(Dispatchers.IO) {
            log.i("PageView init with initial pageId: $initialPageId")
            if (!switchToPage(initialPageId)) return@launch

            requestedPageId.collect { pageId ->
                if (pageId != currentPageId) switchToPage(pageId)
            }
        }
    }

    /**
     * Suspends until the initial page load, and the stroke/image load it starts, have finished.
     *
     * Internal for tests: production code never needs it, because the UI is refreshed by the events
     * this class emits. [loadingJob] is only joined if [loadPage] already started it.
     */
    internal suspend fun awaitPageReady() {
        initJob.join()
        loadingJob?.join()
    }

    /**
     * Asks the view to show [newPageId]. **It doesn't notify the UI about the change.**
     *
     * Page changes run one at a time, in the order they are asked for; when several are asked for
     * while one runs, only the last of them is shown (see [switchToPage]).
     */
    fun changePage(newPageId: String) {
        log.d("changePage requested: $currentPageId -> $newPageId")
        requestedPageId.value = newPageId
    }

    /**
     * Shows [pageId] in place of the page shown before, and returns false once the view is disposed:
     * 1.  Off the main thread, reads the page's record and gets its bitmap for the viewport it will
     *     be shown at (see [preparePage]).
     * 2.  On the main thread, shows the page with that bitmap in one step (see [showPrepared]).
     * 3.  Loads the page's strokes and images in the background and redraws once they are in.
     *
     * A disposed view shows and loads nothing more, even if a page change was running.
     */
    private suspend fun switchToPage(pageId: String): Boolean {
        log.d("changePage Entry: $currentPageId -> $pageId")
        val prepared = preparePage(pageId) ?: return false
        val shown = try {
            withContext(Dispatchers.Main) { showPrepared(prepared) }
        } finally {
            // Not shown, as when the view was closed or its scope cancelled meanwhile.
            if (prepared.fromShelf && !prepared.inView)
                pageDataManager.putBackBitmap(pageId, prepared.bitmap, prepared.viewport)
        } ?: return false
        log.d("New bitmap hash: ${windowedBitmap.hashCode()}, ID: $pageId")

        // Refresh UI without waiting for drawing.
        // TODO: Problem: Sometimes refreshUi had a problem with proper refreshing screen,
        //  using function that does not wait for drawing mostly solved the problem.
        //  but there might be still bugs with it.
        coroutineScope.launch(Dispatchers.Main) {
            // If we do it with main.immediate then it wont work.
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
        loadPage(shown)
        return true
    }

    /**
     * A page read, with the bitmap to show it at [viewport], ready for [showPrepared]. A bitmap
     * taken off the shelf goes back there unless the view takes it in.
     */
    private class PreparedPage(
        val page: OpenPage,
        val viewport: PageViewport,
        val bitmap: Bitmap,
        val fromShelf: Boolean,
    ) {
        @Volatile
        var inView = false
    }

    /**
     * Reads [pageId]'s record and gets its bitmap for the viewport it will be shown at: the one on
     * [PageDataManager]'s shelf if that shows this viewport at the view's size, or else a new one
     * drawn from its preview or background. Returns null once the view is disposed.
     *
     * The bitmap taken off the shelf is the view's until it leaves the page and [showPrepared] puts
     * it back with [PageDataManager.onExit].
     */
    private suspend fun preparePage(pageId: String): PreparedPage? {
        val record = pageDataManager.getPageRecord(pageId)
        if (record == null)
            snackManager.showOrUpdateSnack(SnackConf(text = "Page not found", duration = 2000))
        val page = OpenPage.load(appRepository, pageId, record)
        if (synchronized(pageLock) { disposed }) return null
        val viewport = viewportToShow(page)
        val shelved = pageDataManager.takeCachedBitmap(pageId, viewport)
            ?.takeIf { it.width == viewWidth && it.height == viewHeight }
        return PreparedPage(page, viewport, shelved ?: newBitmap(page, viewport), fromShelf = shelved != null)
    }

    /**
     * The viewport [page] had when this view left it, or else unzoomed at its saved scroll.
     *
     * Its x is re-fitted either way rather than taken as it was. A sheet's x bound depends on the
     * view's width and the zoom it was kept at, and both can have moved since — a resize re-fits the
     * shown page and drops the zoom of the pages this view left, but the x kept for those was fitted
     * for the old width, so a narrow sheet would come back off centre. [fitScrollX] centres or
     * clamps it for the width and zoom it is being shown at, and leaves an infinite page, which has
     * no bound, where the reader left it.
     */
    private fun viewportToShow(page: OpenPage): PageViewport {
        val kept = synchronized(pageLock) { leftPages[page.pageId] }
            ?: return PageViewport(
                scroll = Offset(
                    x = fitScrollX(page.pageMode, (page.record?.scrollX ?: 0).toFloat(), 1f),
                    y = (page.record?.scrollY ?: 0).toFloat(),
                ),
            )
        return kept.copy(scroll = kept.scroll.copy(x = fitScrollX(page.pageMode, kept.scroll.x, kept.zoom)))
    }

    /**
     * Shows [prepared] in place of the page shown before, in one step under the page lock. It puts
     * the old page's bitmap on the shelf and keeps its viewport, takes the new page's lease and
     * closes the old one, puts in the new page with its viewport and bitmap, and reports it to
     * [onPageShown]. Returns the page, or null once the view is disposed.
     *
     * Runs on the main thread, as scroll and zoom steps do, so a step never sees one page with the
     * bitmap of another.
     */
    private fun showPrepared(prepared: PreparedPage): OpenPage? {
        checkMainThread("showPrepared")
        val page = prepared.page
        synchronized(pageLock) {
            if (disposed) return null
            val previous = lease
            if (previous != null) {
                leftPages[previous.pageId] = viewportFlow.value
                pageDataManager.onExit(previous.pageId, windowedBitmap, viewportFlow.value)
                onPageLeft(previous.pageId)
            }
            lease = pageLeases.acquire(page.pageId)
            previous?.close()

            val shown = viewportToShow(page)
            leftPages.remove(page.pageId)
            // A resize since the bitmap was drawn changed the view's size or the page's zoom. Left
            // blank then, rather than read from disk here; the redraw after loading fills it.
            val bitmap = prepared.bitmap.takeIf {
                prepared.viewport == shown && it.width == viewWidth && it.height == viewHeight
            }?.also { prepared.inView = true }
                ?: createBitmap(viewWidth, viewHeight).apply { eraseColor(Color.WHITE) }
            windowedBitmap = bitmap
            windowedCanvas = Canvas(bitmap).apply { scale(shown.zoom, shown.zoom) }
            // The spare buffer belongs to the page left.
            scrollBackBuffer = null

            openPageRef.set(page)
            viewportFlow.value = shown
            // Under the lock, so no page is reported after [disposeOldPage] returns.
            onPageShown(page.pageId)
            // [onPageShown] may have closed the view.
            if (disposed) return null
        }
        updateContentHeight()
        return page
    }

    /** A new bitmap of the view's size showing [page] at [viewport], drawn by [drawInitialBitmap]. */
    private fun newBitmap(page: OpenPage, viewport: PageViewport): Bitmap =
        createBitmap(viewWidth, viewHeight).also { drawInitialBitmap(Canvas(it), page, viewport) }

    /**
     * Puts the shown page's bitmap on the shelf for its next showing and its preview, closes its
     * lease and marks the view disposed, then stops loading the page. A disposed view shows, draws
     * and loads nothing more; [openPage] keeps the last page it showed.
     */
    fun disposeOldPage() {
        log.d("Dispose old page")
        synchronized(pageLock) {
            lease?.let {
                pageDataManager.onExit(it.pageId, windowedBitmap, viewportFlow.value)
                onPageLeft(it.pageId)
            }
            disposed = true
            lease?.close()
            lease = null
        }
        cleanJob()
    }


    // To be removed.
    private fun redrawAll(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main.immediate) {
            val viewRectangle = Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
            drawAreaScreenCoordinates(viewRectangle)
        }
    }

    /** Loads [page]'s strokes and images, and stops waiting for the page shown before. */
    private fun loadPage(page: OpenPage) {
        // Only the wait is cancelled; [PageDataManager] decides whether the load itself goes on.
        loadingJob?.cancel()
        val pageId = page.pageId
        logCache.i("Init from persist layer, pageId: $pageId")
        synchronized(pageLock) {
            // Nothing would cancel a wait started after [disposeOldPage], so none starts then.
            if (disposed) return
            loadingJob = coroutineScope.launch(Dispatchers.IO) { awaitPageData(pageId) }
        }
    }

    private suspend fun awaitPageData(pageId: String) {
        try {
            snackManager.showSnackDuring(text = "Loading strokes...") {
                val timeToLoad = measureTimeMillis {
                    logCache.d("Start page loading, id $pageId")
                    pageDataManager.requestPageLoadJoin(pageId)
                    logCache.d("Got page data (PageView.loadPage). id $pageId")
                }
                logCache.d("All strokes loaded in $timeToLoad ms")
            }
            updateContentHeight()
            // TODO: If we put it in loadPage(…) sometimes it will try to refresh
            //  without seeing strokes, I have no idea why.
            coroutineScope.launch(Dispatchers.Main) {
                CanvasEventBus.forceUpdate.emit(null)
            }

            logCache.d("Loaded page from persistent layer $pageId")
            if (!pageDataManager.validatePageDataLoaded(pageId))
                logCache.e("Page should be loaded, but it is not. $pageId")
        } catch (_: CancellationException) {
            val dataStatus = pageDataManager.validatePageDataLoaded(pageId)
            logCache.d("Page loading cancelled, data was loaded correctly: $dataStatus")
        } catch (e: Exception) {
            val dataStatus = pageDataManager.validatePageDataLoaded(pageId)
            logCache.e("Page loading cancelled, data was loaded correctly: $dataStatus", e)
        }
    }


    fun addStrokes(strokesToAdd: List<Stroke>) {
        pageDataManager.addStrokes(currentPageId, strokesToAdd)
        updateContentHeight()

//        persistBitmapDebounced()
    }

    fun applyPageCutOffset(cutLine: List<SimplePointF>, offset: Offset): PageCutMoveResult? {
        if (offset.x < 0 || offset.y < 0) return null

        val (_, previousStrokes) = divideStrokesFromCut(strokes, cutLine)
        if (previousStrokes.isEmpty()) return null

        val movedStrokes = previousStrokes.map { stroke ->
            stroke.copy(
                points = stroke.points.map { point ->
                    point.copy(x = point.x + offset.x, y = point.y + offset.y)
                },
                top = stroke.top + offset.y,
                bottom = stroke.bottom + offset.y,
                left = stroke.left + offset.x,
                right = stroke.right + offset.x
            )
        }

        removeStrokes(strokeIds = previousStrokes.map { it.id })
        addStrokes(movedStrokes)
        drawAreaScreenCoordinates(strokeBounds(previousStrokes + movedStrokes))

        return PageCutMoveResult(
            previousStrokes = previousStrokes,
            movedStrokes = movedStrokes,
        )
    }

    // Completely updates strokes
    fun updateStrokes(strokesToUpdate: List<Stroke>) {
        pageDataManager.updateStrokes(currentPageId, strokesToUpdate)
        updateContentHeight()
//        persistBitmapDebounced()
    }

    fun removeStrokes(strokeIds: List<String>) {
        pageDataManager.removeStrokes(currentPageId, strokeIds)
        updateContentHeight()

//        persistBitmapDebounced()
    }

    fun getStrokes(strokeIds: List<String>): List<Stroke?> {
        return pageDataManager.getStrokes(strokeIds, currentPageId)
    }

    fun addImage(imageToAdd: Image) {
        addImage(listOf(imageToAdd))
    }

    fun addImage(imageToAdd: List<Image>) {
        pageDataManager.addImages(currentPageId, imageToAdd)
        updateContentHeight()

//        persistBitmapDebounced()
    }

    fun removeImages(imageIds: List<String>) {
        pageDataManager.removeImages(currentPageId, imageIds)
        updateContentHeight()
//        persistBitmapDebounced()
    }

    // Moves/updates existing images in place (same ids). Mirrors updateStrokes — used for a Move
    // displacement.
    fun updateImages(imagesToUpdate: List<Image>) {
        pageDataManager.updateImages(currentPageId, imagesToUpdate)
        updateContentHeight()
    }

    // Measured and published in one step, so a measurement never overwrites a newer one.
    private fun updateContentHeight() {
        synchronized(pageLock) {
            contentHeightFlow.value = pageDataManager.contentHeight(currentPageId)
        }
    }

    fun getImages(imageIds: List<String>): List<Image?> =
        pageDataManager.getImages(imageIds, currentPageId)


    // Draws [page] at [viewport] into [canvas], fast: from its preview if it is accurate enough,
    // or else just its background.
    private fun drawInitialBitmap(canvas: Canvas, page: OpenPage, viewport: PageViewport) {
        val bitmapFromDisc = loadHQPagePreview(
            context = context,
            pageID = page.pageId,
            viewport = viewport,
            pageUpdatedAtMs = page.record?.updatedAt?.time,
        )
        if (bitmapFromDisc != null) {
            // let's control that the last preview fits the present orientation. Otherwise we'll ask for a redraw.
            if (bitmapFromDisc.height == canvas.height && bitmapFromDisc.width == canvas.width) {
                canvas.drawBitmap(bitmapFromDisc, 0f, 0f, Paint())
                log.d("loaded initial bitmap from preview, page: ${page.pageId}")
                return
            } else
                log.i("Image preview does not fit canvas area - redrawing")
        }

        log.d("Drawing initial background.")
        // draw just background.
        if (page.backgroundType == BackgroundType.Native) {
            // Native backgrounds are drawn in page units, scaled by the canvas.
            canvas.save()
            canvas.scale(viewport.zoom, viewport.zoom)
            drawBackground(canvas, page, viewport)
            canvas.restore()
        } else
            canvas.drawColor(Color.WHITE)
        log.d("loaded initial bitmap from background, page: ${page.pageId}")
    }


    private fun cleanJob() {
        pageDataManager.cancelLoadingPage(pageId = currentPageId)
        //ensure that snack is canceled, even on dispose of the page.
        loadingJob?.cancel()
    }


    fun drawAreaPageCoordinates(
        pageArea: Rect, // in page coordinates
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        val areaInScreen = toScreenCoordinates(pageArea)
        drawAreaScreenCoordinates(areaInScreen, ignoredStrokeIds, ignoredImageIds, canvas)
    }

    /*
        provided a rectangle, in screen coordinates, its check
        for all images intersecting it, excluding ones set to be ignored,
        and redraws them. Does not refresh screen/SurfaceView.
     */
    fun drawAreaScreenCoordinates(
        screenArea: Rect,
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        if (canvas == null) checkMainThread("drawAreaScreenCoordinates")
        // A disposed view has put its bitmap on the shelf, so it draws nothing more into it.
        val activeCanvas = canvas ?: synchronized(pageLock) { if (disposed) return; windowedCanvas }
        val pageArea = toPageCoordinates(screenArea)
        val pageAreaWithoutScroll = removeScroll(pageArea)
        drawOnCanvasFromPage(
            page = this,
            canvas = activeCanvas,
            canvasClipBounds = pageAreaWithoutScroll,
            pageArea = pageArea,
            ignoredStrokeIds = ignoredStrokeIds,
            ignoredImageIds = ignoredImageIds,
        ).onError {
            snackManager.showOrUpdateSnack(
                SnackConf(
                    text = "Error during drawing page area: ${it.userMessage}",
                    duration = 3000
                )
            )
        }
    }

    suspend fun simpleUpdateScroll(pageId: String, dragDelta: Offset) {
        // Just update scroll, for debugging.
        // It will redraw whole screen, instead of trying to redraw only needed area.
        log.d("Simple update scroll")
        waitForDrawingWithSnack()

        val start = viewportStart(pageId) ?: return
        val delta = (dragDelta / start.zoom)
        val newScroll = Offset(
            (start.scroll.x + delta.x).coerceAtLeast(0f),
            (start.scroll.y + delta.y).coerceAtLeast(0f)
        )
        if (!commitViewport(pageId, start.copy(scroll = newScroll))) return

        CanvasEventBus.forceUpdate.emit(null)
    }


    fun alreadyDrawnRectAfterShift(
        movement: IntOffset,
        screenW: Int,
        screenH: Int
    ): Rect {
        val dx = -movement.x
        val dy = -movement.y
        val left = max(0, dx)
        val top = max(0, dy)
        val right = min(screenW, dx + screenW)
        val bottom = min(screenH, dy + screenH)
        return Rect(left, top, right, bottom)
    }

    /** The sheet of the shown page, or null when it keeps the infinite canvas. */
    private val pageMode: PageMode?
        get() = openPage.pageMode

    /**
     * The largest scroll x that keeps [pageMode]'s sheet inside a view of this width at [zoom]:
     * with the sheet narrower than the view it is the negative half-slack that centres the sheet,
     * with the sheet wider it is the sheet's far edge, and for an infinite page it is 0.
     */
    private fun scrollToCenterSheet(pageMode: PageMode?, zoom: Float): Float {
        if (pageMode == null || pageMode == PageMode.INFINITE) return 0f
        val sheetWidth = sheetAdjustedWidth(viewWidth, pageMode.width, context)
        val viewportWidth = viewWidth / zoom
        val scrollAvailable = sheetWidth - viewportWidth
        val scrollToCenterSheet = if (scrollAvailable >= 0) scrollAvailable else scrollAvailable / 2f
        log.d(
            """
                Sheet width: $sheetWidth
                Viewport width: $viewportWidth
                Scroll x to centre sheet: $scrollToCenterSheet
                Zoom level: $zoom
            """.trimIndent()
        )
        return scrollToCenterSheet
    }

    /**
     * A scroll x that keeps [pageMode]'s sheet inside the view at [zoom], close to [scrollX]:
     * exactly centred when the sheet leaves slack at the sides, and clamped to the sheet when the
     * view does.
     */
    private fun fitScrollX(pageMode: PageMode?, scrollX: Float, zoom: Float): Float {
        if (pageMode == null || pageMode == PageMode.INFINITE) return scrollX
        val edge = scrollToCenterSheet(pageMode, zoom)
        return if (edge < 0f) edge else scrollX.coerceIn(0f, edge)
    }

    suspend fun updateScroll(pageId: String, dragDelta: Offset) {
        // before scrolling, make sure that strokes are drawn.
        waitForDrawingWithSnack()

        val start = viewportStart(pageId) ?: return
        // drag delta is in screen coordinates,
        // so we have to scale it back to page coordinates.
        val deltaInPage = Offset(dragDelta.x / start.zoom, dragDelta.y / start.zoom)

        log.d("Delta in page coordinates: $deltaInPage")

        // Where the drag is allowed to land the view, on both axes: never past the page's top left
        // corner, and with the sheet kept inside the view — centred when it leaves slack at the
        // sides, clamped to its edges when the view does, and free horizontally on an infinite page.
        val next = start.copy(
            scroll = Offset(
                x = fitScrollX(pageMode, start.scroll.x + deltaInPage.x, start.zoom),
                y = (start.scroll.y + deltaInPage.y).coerceAtLeast(0f),
            ),
        )
        // The shift below moves the screen by the drag, so the drag is cut to what the landing
        // allows: zero on a centred sheet, which cannot be moved sideways at all.
        val allowed = next.scroll - start.scroll

        // There is nothing to do, return.
        if (allowed == Offset.Zero) return

        if (!commitViewport(pageId, next)) return
        // To avoid rounding errors, we just calculate it again.
        val movement = (allowed * start.zoom)
        if (movement.toIntOffset() == IntOffset.Zero) return

        val width = windowedBitmap.width
        val height = windowedBitmap.height
        // Shift the existing bitmap content into the spare buffer, reusing it across
        // scroll events. Recreate the spare only if it doesn't match current geometry.
        val shiftedBitmap = scrollBackBuffer?.takeIf {
            it.width == width && it.height == height && it.config == windowedBitmap.config
        } ?: createBitmap(width, height, windowedBitmap.config!!)
        val shiftedCanvas = Canvas(shiftedBitmap)
//        shiftedCanvas.drawColor(Color.RED) // used for debug
        shiftedCanvas.drawBitmap(windowedBitmap, -movement.x, -movement.y, null)

        // Swap in the shifted bitmap; the old live buffer becomes the next spare.
        scrollBackBuffer = windowedBitmap
        windowedBitmap = shiftedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(start.zoom, start.zoom)

        redrawOutsideRect(
            alreadyDrawnRectAfterShift(movement.toIntOffset(), width, height),
            width,
            height
        )

//        persistBitmapDebounced()
    }


    private fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
    ): Float {
        // TODO: Better snapping logic
        val portraitRatio = SCREEN_WIDTH.toFloat() / SCREEN_HEIGHT

        return if (!GlobalAppSettings.current.continuousZoom) {
            // Discrete zoom mode - snap to either 1.0 or screen ratio.
            // scaleDelta is a growth ratio minus 1 (see PointerTracker.pinchRatio),
            // so it is negative when pinching in (zoom out) and positive when
            // spreading (zoom in); split on 0, not 1.
            if (scaleDelta <= 0f) {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) portraitRatio else 1.0f
            } else {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) 1.0f else portraitRatio
            }
        } else {
            // Continuous zoom: scaleDelta is the per-frame growth ratio minus 1,
            // so the zoom scales multiplicatively. ZOOM_SENSITIVITY damps how
            // hard the pinch drives the zoom (< 1 zooms more gently than the
            // fingers spread).
            val newZoom =
                (currentZoom * (1f + scaleDelta * ZOOM_SENSITIVITY)).coerceIn(MIN_ZOOM, MAX_ZOOM)

            // Snap to either 1.0 or screen ratio depending on which is closer
            val snapTarget = if (abs(newZoom - 1.0f) < abs(newZoom - portraitRatio)) {
                1.0f
            } else {
                portraitRatio
            }

            if (abs(newZoom - snapTarget) < ZOOM_SNAP_THRESHOLD) {
                log.d("Zoom snap to $snapTarget")
                snapTarget
            } else {
                log.d("Left zoom as is. $newZoom")
                newZoom
            }
        }
    }

    /** Zooms [pageId] by [scaleDelta], if it is still shown, and redraws it whole. */
    suspend fun simpleUpdateZoom(pageId: String, scaleDelta: Float) {
        log.d("Simple Zoom updated, $scaleDelta")
        waitForDrawingWithSnack()
        val start = viewportStart(pageId) ?: return
        // Update the zoom factor
        val newZoomLevel = calculateZoomLevel(scaleDelta, start.zoom)

        // If there's no actual zoom change, skip
        if (newZoomLevel == start.zoom) {
            log.d("Zoom unchanged. Current level: ${start.zoom}")
            return
        }
        log.d("New zoom level: $newZoomLevel")
        redrawAt(
            pageId,
            start.copy(
                scroll = Offset(fitScrollX(pageMode, start.scroll.x, newZoomLevel), start.scroll.y),
                zoom = newZoomLevel,
            ),
        )
    }

    /**
     * Unzooms the page and takes it back to its left edge, centring the sheet when it is narrower
     * than the view, and keeping its vertical scroll.
     */
    suspend fun resetView(pageId: String) {
        waitForDrawingWithSnack()
        val start = viewportStart(pageId) ?: return
        val sheetMode = pageMode
        redrawAt(
            pageId,
            PageViewport(
                scroll = Offset(fitScrollX(sheetMode, 0f, 1f), start.scroll.y),
            ),
        )
    }

    /** Moves [pageId]'s view to [next] and redraws it whole. */
    private fun redrawAt(pageId: String, next: PageViewport) {
        if (!commitViewport(pageId, next)) return
        // Create a scaled bitmap to represent zoomed view
        val scaledWidth = windowedCanvas.width
        val scaledHeight = windowedCanvas.height
        log.d("Canvas dimensions: width=$scaledWidth, height=$scaledHeight")
        log.d("Bitmap dimensions: width=${windowedBitmap.width}, height=${windowedBitmap.height}")
        log.d("Screen dimensions: width=$SCREEN_WIDTH, height=$SCREEN_HEIGHT")
        log.d("Page View dimension: width=${viewWidth}, height=${viewHeight}")


        val zoomedBitmap = createBitmap(scaledWidth, scaledHeight, windowedBitmap.config!!)

        // Swap in the new zoomed bitmap
//        windowedBitmap.recycle() -- It causes race condition with init from persistent layer
        windowedBitmap = zoomedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(next.zoom, next.zoom)


        // Redraw everything at new zoom level
        val redrawRect = Rect(0, 0, windowedBitmap.width, windowedBitmap.height)

        log.d("Redrawing full logical rect: $redrawRect")
        drawAreaScreenCoordinates(redrawRect)

        log.i("Zoom and redraw completed")
    }


    /**
     * Update zoom of [pageId], if it is still shown, by reusing the existing screen bitmap.
     * - Scales the snapshot around the given center (screen coords).
     * - Redraws only the uncovered bands when zooming out.
     * - When zooming in, keeps the upscaled snapshot (even if low-res) for now.
     * - Updates scroll (IntOffset) so that the top-left of the view is correct after zoom,
     *   keeping the content under the pinch center stationary on screen.
     */
    suspend fun updateZoom(pageId: String, scaleDelta: Float, center: Offset?) {
        log.d("Zoom(delta): $scaleDelta. Center: $center")
        // Flush pending strokes/background before snapshot-based operations
        waitForDrawingWithSnack()

        val start = viewportStart(pageId) ?: return
        val oldZoom = start.zoom
        val newZoom = calculateZoomLevel(scaleDelta, oldZoom)
        if (newZoom == oldZoom) {
            log.d("Zoom unchanged. Current level: $oldZoom")
            return
        }

        val scaleFactor = newZoom / oldZoom
        val screenW = windowedCanvas.width
        val screenH = windowedCanvas.height

        // Default pivot to screen center if none passed
        val pivotX = center?.x ?: (screenW / 2f)
        val pivotY = center?.y ?: (screenH / 2f)

        // Draw scaled snapshot into a fresh screen-sized bitmap
        val scaledBitmap = createBitmap(screenW, screenH, windowedBitmap.config!!)
        val scaledCanvas = Canvas(scaledBitmap)
//        scaledCanvas.drawColor(Color.RED) // used for debug

        val matrix = Matrix().apply {
            postScale(scaleFactor, scaleFactor, pivotX, pivotY)
        }

        // Calculate where the scaled snapshot ended up on screen.
        // Map the original screen rect through the same matrix to get content bounds.
        val srcRect = RectF(0f, 0f, screenW.toFloat(), screenH.toFloat())
        val dstRect = RectF()
        matrix.mapRect(dstRect, srcRect)

        //make sure that we won't go outside canvas.
        val dx = (start.scroll.x - dstRect.left).coerceAtMost(0f)
        val dy = (start.scroll.y - dstRect.top).coerceAtMost(0f)
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
            matrix.mapRect(dstRect, srcRect)
        }
        scaledCanvas.drawBitmap(windowedBitmap, matrix, null)


        // The sheet sits differently in the view at the new zoom, so the new scroll is fitted to the
        // sheet rather than only clamped to the page's left edge.
        val deltaScrollPage = Offset(-dstRect.left / newZoom, -dstRect.top / newZoom)
        val newScrollX = fitScrollX(pageMode, start.scroll.x + deltaScrollPage.x, newZoom)
        val newScrollY = (start.scroll.y + deltaScrollPage.y).coerceAtLeast(0f)
        val next = PageViewport(Offset(newScrollX, newScrollY), newZoom)
        if (!commitViewport(pageId, next)) return

        // Swap in the new bitmap and update zoom on the windowed canvas
        windowedBitmap = scaledBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(newZoom, newZoom)

        if (scaleFactor < 1f) redrawOutsideRect(dstRect.toRect(), screenW, screenH)

//        persistBitmapDebounced()
        log.i(
            "Zoom updated using snapshot scaling. " +
                    "oldZoom=$oldZoom newZoom=$newZoom " +
                    "scaleFactor=$scaleFactor pivot=($pivotX,$pivotY) " +
                    "bounds=$dstRect" +
                    "scrollDelta=$deltaScrollPage newScroll=${next.scroll}"
        )
    }

    fun redrawOutsideRect(dstRect: Rect, screenW: Int, screenH: Int) {
        val scaledOverlap = ceil(OVERLAP * zoom.coerceAtLeast(1f)).toInt()

        // Uncovered top band
        if (dstRect.top > 0) {
            val r = Rect(
                0,
                0,
                screenW,
                (dstRect.top + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered bottom band
        if (dstRect.bottom < screenH) {
            val r = Rect(
                0, (dstRect.bottom - scaledOverlap).coerceAtLeast(0), screenW, screenH
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered left band
        if (dstRect.left > 0) {
            val r = Rect(
                0,
                (dstRect.top - scaledOverlap).coerceAtLeast(0),
                (dstRect.left + scaledOverlap).coerceAtMost(screenW),
                (dstRect.bottom + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
        // Uncovered right band
        if (dstRect.right < screenW) {
            val r = Rect(
                (dstRect.right - scaledOverlap).coerceAtLeast(0),
                (dstRect.top - scaledOverlap).coerceAtLeast(0),
                screenW,
                (dstRect.bottom + scaledOverlap).coerceAtMost(screenH)
            )
            if (!r.isEmpty) drawAreaScreenCoordinates(r)
        }
    }


    // updates page setting in db, (for instance type of background)
    // and redraws page to view.
    suspend fun refreshCurrentPage() {
        val pageId = currentPageId
        log.d("Refresh page: $pageId")
        val loaded = OpenPage.load(appRepository, pageId, pageDataManager.getPageRecord(pageId))
        // Dropped if this view has moved to another page meanwhile.
        openPageRef.updateAndGet { it.withLoaded(loaded) }
        log.i("Refreshed page $pageId, background: ${openPage.backgroundName}")
        withContext(Dispatchers.Main) {
            drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
//            persistBitmapDebounced()
        }

    }

    fun drawBgToCanvas(canvas: Canvas) = drawBackground(canvas, openPage, viewport.value)

    /** Draws the background of [shown] at [shownViewport] into [canvas]. */
    private fun drawBackground(canvas: Canvas, shown: OpenPage, shownViewport: PageViewport) {
        val backgroundType = shown.backgroundType ?: BackgroundType.Native
        val pageMode = shown.pageMode
        val bg = shown.backgroundName
        val pageNumber = shown.pageNumber
        val scale = shownViewport.zoom
        val bgImage: Bitmap? =
            when (backgroundType) {
                BackgroundType.Image, BackgroundType.CoverImage, BackgroundType.AutoPdf,
                is BackgroundType.Pdf, BackgroundType.ImageRepeating -> {
                    if (backgroundType is BackgroundType.Image && bg == "iris") {
                        val resId = R.drawable.iris
                        ImageBitmap.imageResource(context.resources, resId).asAndroidBitmap()
                    } else {
                        getOrLoadBackground(shown.pageId, bg, pageNumber, scale)
                    }
                }

                BackgroundType.Native -> {
                    null
                }
            }
        drawBg(
            canvas = canvas,
            backgroundType = backgroundType,
            background = bg,
            scroll = shownViewport.scroll,
            resourceBitmap = bgImage,
            scale = scale,
            pageMode = pageMode,
            repeat = false,
            context = context,
        )
    }

    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth != viewWidth || newHeight != viewHeight) {
            log.d("Updating dimensions from: $viewWidth x $viewHeight, to: $newWidth x $newHeight")

            viewWidth = newWidth
            viewHeight = newHeight
            updateCanvasDimensions()
        }
    }

    /**
     * Resizes the screen bitmap. The zoom that fitted the old size fits no page at the new size, and
     * a sheet needs re-centring in the new width, so every page this view holds is re-fitted.
     */
    private fun updateCanvasDimensions() {
        checkMainThread("updateDimensions")
        // A zoom fitted to the old size does not fit the new one, on any page.
        synchronized(pageLock) {
            val sheetMode = openPage.pageMode
            viewportFlow.update { shown ->
                shown.copy(
                    scroll = Offset(fitScrollX(sheetMode, shown.scroll.x, 1f), shown.scroll.y),
                    zoom = 1f,
                )
            }
            leftPages.replaceAll { _, left -> left.copy(zoom = 1f) }
        }
        // The re-fit moves the shown page's scroll, so it is recorded like any other step's: a
        // resize the reader never scrolls after must not leave the pre-resize scroll in the page.
        val shown = openPage
        if (shown.record != null) {
            val scroll = viewportFlow.value.scroll
            pageDataManager.recordScroll(
                shown.pageId,
                IntOffset(scroll.x.roundToInt(), scroll.y.roundToInt()),
            )
        }
        // Blank at the new size, rather than read from disk on the main thread; the redraw below
        // fills it.
        windowedBitmap = createBitmap(viewWidth, viewHeight).apply { eraseColor(Color.WHITE) }
        windowedCanvas = Canvas(windowedBitmap)
        scrollBackBuffer = null
        // TODO: it might be worth to do it
        //  by redrawing only part of the screen, like in scroll and zoom.
        coroutineScope.launch {
            CanvasEventBus.forceUpdate.emit(null)
        }
//        persistBitmapDebounced()
    }


    /**
     * [pageId]'s viewport for a scroll or zoom step to start from, or null if it is not shown or the
     * view is disposed.
     *
     * A step reads it once it has waited for drawing, and commits through [commitViewport] without
     * suspending again. Steps, page swaps and resizes all run on the main thread, so nothing changes
     * the viewport in between.
     */
    private fun viewportStart(pageId: String): PageViewport? =
        synchronized(pageLock) { viewportFlow.value.takeIf { !disposed && currentPageId == pageId } }

    /**
     * Sets [pageId]'s viewport to [next] and records its scroll, both axes, for saving, if [pageId] is
     * shown and has a record. Returns false, changing nothing, otherwise: another page is shown, no
     * page is shown yet, the page is missing, or the view is disposed.
     */
    private fun commitViewport(pageId: String, next: PageViewport): Boolean {
        checkMainThread("commitViewport")
        synchronized(pageLock) {
            val shown = openPage
            if (disposed || shown.pageId != pageId || shown.record == null) return false
            viewportFlow.value = next
        }
        pageDataManager.recordScroll(
            pageId,
            IntOffset(next.scroll.x.roundToInt(), next.scroll.y.roundToInt()),
        )
        return true
    }

    fun applyZoom(point: IntOffset): IntOffset {
        return point * zoom
    }

    fun removeZoom(point: IntOffset): IntOffset {
        return point / zoom
    }

    private fun removeScroll(rect: Rect): Rect {
        return rect - scroll
    }

    fun toScreenCoordinates(rect: Rect): Rect = viewport.value.toScreen(rect)

    private fun toPageCoordinates(rect: Rect): Rect = viewport.value.toPage(rect)

    // The screen bitmap, its canvas and the viewport change on the main thread only: page swaps,
    // scroll and zoom steps, resizes and drawing. Fails in debug builds, logs in release ones.
    private fun checkMainThread(what: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val message = "PageView.$what called off the main thread"
        if (BuildConfig.DEBUG) error(message) else log.e(message)
    }

    private suspend fun waitForDrawingWithSnack() {
        if (drawingInProgress.isLocked) {
            snackManager.runWithSnack("Waiting for drawing to finish…", resultDurationMs = 0) {
                waitForDrawing()
                "Drawing finished"
            }
        }
    }
}