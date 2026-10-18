package com.ethran.notable.editor


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toRect
import com.ethran.notable.R
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.CachedBackground
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
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
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.editor.utils.times
import com.ethran.notable.editor.utils.toIntOffset
import com.ethran.notable.gestures.MAX_ZOOM
import com.ethran.notable.gestures.MIN_ZOOM
import com.ethran.notable.gestures.ZOOM_SENSITIVITY
import com.ethran.notable.gestures.ZOOM_SNAP_THRESHOLD
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
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
     * Called with each page this view starts showing, once it holds the page's lease. It runs under
     * the view's page lock, so it must only hand the id on.
     */
    private val onPageShown: (pageId: String) -> Unit = {},
) {
    // TODO: unify width height variable

    val log = ShipBook.getLogger("PageView")
    private val logCache = ShipBook.getLogger("PageViewCache")

    private var loadingJob: Job? = null

    @Volatile
    var windowedBitmap = createBitmap(viewWidth, viewHeight)
        private set

    @Volatile
    var windowedCanvas = Canvas(windowedBitmap)
        private set

    // Spare screen-sized buffer reused by updateScroll to avoid allocating a new
    // bitmap on every scroll event. Ping-ponged with windowedBitmap; recreated only
    // when the canvas size/config changes (zoom, dimension change, page switch).
    private var scrollBackBuffer: Bitmap? = null

    val strokes: List<Stroke>
        get() = pageDataManager.getStrokes(currentPageId)

    val images: List<Image>
        get() = pageDataManager.getImages(currentPageId)

    // warning: The setter is delayed!
    private var currentBackground: CachedBackground
        get() = pageDataManager.getBackground(currentPageId)
        set(value) = pageDataManager.setBackground(currentPageId, value)

    private val openPageRef = AtomicReference(OpenPage(initialPageId))

    // The page [changePage] last asked for. Only the latest request is shown: one superseded while
    // a page change runs is skipped.
    private val requestedPageId = MutableStateFlow(initialPageId)

    // The lease on the shown page, taken by [setOpenPage] and dropped by [markDisposed].
    private val pageLock = Any()
    private var lease: PageLease? = null // guarded by pageLock
    private var disposed = false // guarded by pageLock

    /** Current snapshot of this view's page. Read it once into a local to use several fields together. */
    val openPage: OpenPage
        get() = openPageRef.get()

    val currentPageId: String
        get() = openPage.pageId


    // The shown page's viewport. [setOpenPage] replaces it together with the page. Scroll and zoom
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

    // Height the shown page's content needs, in page coordinates.
    private val contentHeightFlow = MutableStateFlow(SCREEN_HEIGHT)
    val contentHeight: StateFlow<Int> = contentHeightFlow.asStateFlow()


//    private var dbStrokes = appRepository.strokeRepository
//    private var dbImages = appRepository.imageRepository

    val currentPageNumber: Int
        get() = openPage.pageNumber

    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(pageId: String, filePath: String, pageNumber: Int, scale: Float): Bitmap? {
        log.i("getOrLoadBackground")
        val cached = pageDataManager.getBackground(pageId)
        if (cached.matches(filePath, pageNumber, scale)) {
            log.i("Background bitmap (cached): ${cached.bitmap}")
            return cached.bitmap
        }
        // Render a little larger than requested so small zoom-in steps reuse this bitmap instead of
        // forcing a re-render (matches() accepts any cached scale >= requested). Multiplicative so
        // the headroom stays proportional as you zoom; floored to the old additive margin near 1x.
        val renderScale = (scale * BACKGROUND_ZOOM_HEADROOM).coerceAtLeast(scale + 0.1f)
        val newBackground = CachedBackground(filePath, pageNumber, renderScale)
        pageDataManager.setBackground(pageId, newBackground)
        log.i("Background bitmap: ${newBackground.bitmap}")
        return newBackground.bitmap
    }

    fun getBackgroundPageNumber(): Int {
        // There might be a bug here -- check it again.
        return currentBackground.pageNumber
    }


    init {
        coroutineScope.launch(Dispatchers.IO) {
            val shown = showPage(initialPageId) ?: return@launch
            log.i("PageView init with initial pageId: $initialPageId" )
            if(currentPageId.isEmpty())
                log.e("Current page id is empty")

            pageDataManager.getCachedBitmap(currentPageId, viewport.value)?.let { cached ->
                log.i("PageView: using cached bitmap")
                windowedBitmap = cached
                windowedCanvas = Canvas(windowedBitmap)
            } ?: run {
                log.i("PageView.init: creating new bitmap")
                recreateCanvas()
                pageDataManager.cacheBitmap(currentPageId, windowedBitmap, viewport.value)
            }

            coroutineScope.launch(Dispatchers.Main) {
                // If we do it with main.immediate then it wont work.
                CanvasEventBus.refreshUiImmediately.emit(Unit)
            }
            loadPage(shown)
            log.d("Page loaded (Init with id: $currentPageId)")
            pageDataManager.collectAndPersistBitmapsBatch(context, coroutineScope)

            requestedPageId.collect { pageId ->
                if (pageId != currentPageId) switchToPage(pageId)
            }
        }
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
     * Shows [newPageId] in place of the current page:
     * 1.  Keeps the old page's bitmap in [PageDataManager] for its next showing and its preview.
     * 2.  Reads the new page's record and shows the page (see [setOpenPage]): it takes the page's
     *     lease and the viewport the page had when this view left it, or else its saved scroll.
     * 3.  Uses the page's cached bitmap if it shows that viewport, resizing the canvas if the view
     *     size changed; otherwise draws a new bitmap.
     * 4.  Loads the page's strokes and images in the background and redraws once they are in.
     *
     * Stops after step 1 once the view is disposed.
     */
    private suspend fun switchToPage(newPageId: String) {
        val oldId = currentPageId
        log.d("changePage Entry: $oldId -> $newPageId")
        pageDataManager.onExit(oldId, windowedBitmap, viewport.value, coroutineScope)
        // The spare buffer may be a bitmap the cache keeps for a page left earlier; scrolling would
        // draw over it.
        scrollBackBuffer = null
        // A disposed view draws and loads nothing more.
        val shown = showPage(newPageId) ?: return
        pageDataManager.getCachedBitmap(newPageId, viewport.value)?.let { cached ->
            log.i("PageView: using cached bitmap")
            windowedBitmap = cached
            windowedCanvas = Canvas(windowedBitmap)
            // Check if we have correct size of canvas
            if (windowedCanvas.width != viewWidth || windowedCanvas.height != viewHeight)
                updateCanvasDimensions()
        } ?: run {
            log.i("PageView.changePage: creating new bitmap")
            recreateCanvas()
            pageDataManager.cacheBitmap(newPageId, windowedBitmap, viewport.value)
        }

        log.d("New bitmap hash: ${windowedBitmap.hashCode()}, ID: $currentPageId")

        // Refresh UI without waiting for drawing.
        // TODO: Problem: Sometimes refreshUi had a problem with proper refreshing screen,
        //  using function that does not wait for drawing mostly solved the problem.
        //  but there might be still bugs with it.
        CanvasEventBus.refreshUiImmediately.emit(Unit)
        loadPage(shown)
        log.d("Page loaded (updatePageID($currentPageId))")
    }

    private fun recreateCanvas() {
        windowedBitmap = createBitmap(viewWidth, viewHeight)
        windowedCanvas = Canvas(windowedBitmap)
        loadInitialBitmap()
    }

    /*
        Cancel loading strokes, and save bitmap to disk
    */
    fun disposeOldPage() {
        log.d("Dispose old page")
        pageDataManager.onExit(currentPageId, windowedBitmap, viewport.value, coroutineScope)
        markDisposed()
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
        windowedCanvas.scale(zoom, zoom)
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


    // load background, fast, if it is accurate enough.
    private fun loadInitialBitmap(): Boolean {
        val shown = openPage
        val bitmapFromDisc = loadHQPagePreview(
            context = context,
            pageID = shown.pageId,
            scroll = scroll,
            zoom = zoom,
            pageUpdatedAtMs = shown.record?.updatedAt?.time,
            requireExactMatch = true,
        )
        if (bitmapFromDisc != null) {
            // let's control that the last preview fits the present orientation. Otherwise we'll ask for a redraw.
            if (bitmapFromDisc.height == windowedCanvas.height && bitmapFromDisc.width == windowedCanvas.width) {
                windowedCanvas.drawBitmap(bitmapFromDisc, 0f, 0f, Paint())
                log.d("loaded initial bitmap, drawing to canvas: ${windowedCanvas.hashCode()}, bitmap: ${windowedBitmap.hashCode()}, page: $currentPageId")
                return true
            } else
                log.i("Image preview does not fit canvas area - redrawing")
        }

        log.d("Drawing initial background.")
        // draw just background.
        val backgroundType = shown.backgroundType
        if (backgroundType == BackgroundType.Native) {
            drawBgToCanvas(null)
        } else
            windowedCanvas.drawColor(Color.WHITE)
        log.d("loaded initial bitmap, drawing to canvas: ${windowedCanvas.hashCode()}, bitmap: ${windowedBitmap.hashCode()}, page: $currentPageId")
        return false
    }


    private fun cleanJob() {
        //ensure that snack is canceled, even on dispose of the page.
        coroutineScope.launch(Dispatchers.IO) {
            pageDataManager.cancelLoadingPage(pageId = currentPageId)
        }
        loadingJob?.cancel()
        if (loadingJob?.isActive == true) {
            log.e("Strokes are still loading, trying to cancel and resume")
        }
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
        val activeCanvas = canvas ?: windowedCanvas
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

    suspend fun simpleUpdateScroll(dragDelta: Offset) {
        // Just update scroll, for debugging.
        // It will redraw whole screen, instead of trying to redraw only needed area.
        log.d("Simple update scroll")
        waitForDrawingWithSnack()

        val (pageId, start) = viewportStart()
        val delta = (dragDelta / start.zoom)
        val newScroll = Offset(
            (start.scroll.x + delta.x).coerceAtLeast(0f),
            (start.scroll.y + delta.y).coerceAtLeast(0f)
        )
        if (!commitViewport(pageId, start, start.copy(scroll = newScroll))) return

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

    suspend fun updateScroll(dragDelta: Offset) {
        val (pageId, start) = viewportStart()
        // drag delta is in screen coordinates,
        // so we have to scale it back to page coordinates.
        var deltaInPage = Offset(dragDelta.x / start.zoom, dragDelta.y / start.zoom)

        // Cut, so we won't shift outside the screen.
        if (start.scroll.x + deltaInPage.x < 0) {
            deltaInPage = deltaInPage.copy(x = -start.scroll.x)
        }
        if (start.scroll.y + deltaInPage.y < 0) {
            deltaInPage = deltaInPage.copy(y = -start.scroll.y)
        }

        // There is nothing to do, return.
        if (deltaInPage == Offset.Zero) return

        // before scrolling, make sure that strokes are drawn.
        waitForDrawingWithSnack()

        val next = start.copy(scroll = start.scroll + deltaInPage)
        if (!commitViewport(pageId, start, next)) return
        // To avoid rounding errors, we just calculate it again.
        val movement = (deltaInPage * start.zoom)
        if (movement.toIntOffset() == IntOffset.Zero) return

        val width = windowedBitmap.width
        val height = windowedBitmap.height
        // Shift the existing bitmap content into the spare buffer, reusing it across
        // scroll events. Recreate the spare only if it doesn't match current geometry.
        val shiftedBitmap = scrollBackBuffer?.takeIf {
            it.width == width && it.height == height && it.config == windowedBitmap.config
        } ?: createBitmap(width, height, windowedBitmap.config!!)
        val shiftedCanvas = Canvas(shiftedBitmap)
        shiftedCanvas.drawColor(Color.RED) //for debugging.
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

    suspend fun simpleUpdateZoom(scaleDelta: Float) {
        log.d("Simple Zoom updated, $scaleDelta")
        val (pageId, start) = viewportStart()
        // Update the zoom factor
        val newZoomLevel = calculateZoomLevel(scaleDelta, start.zoom)

        // If there's no actual zoom change, skip
        if (newZoomLevel == start.zoom) {
            log.d("Zoom unchanged. Current level: ${start.zoom}")
            return
        }
        log.d("New zoom level: $newZoomLevel")
        redrawAt(pageId, start, start.copy(zoom = newZoomLevel))
    }

    /** Unzooms the page and scrolls it back to its left edge, keeping its vertical scroll. */
    suspend fun resetView() {
        val (pageId, start) = viewportStart()
        redrawAt(pageId, start, PageViewport(scroll = Offset(0f, start.scroll.y)))
    }

    /** Moves [pageId]'s view from [start] to [next] and redraws it whole. */
    private suspend fun redrawAt(pageId: String, start: PageViewport, next: PageViewport) {
        waitForDrawingWithSnack()
        if (!commitViewport(pageId, start, next)) return
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
        windowedCanvas.drawColor(Color.GREEN)
        drawBgToCanvas(redrawRect)
        pageDataManager.cacheBitmap(pageId, windowedBitmap, next)

        drawAreaScreenCoordinates(redrawRect)

        log.i("Zoom and redraw completed")
    }


    /**
     * Update zoom by reusing the existing screen bitmap.
     * - Scales the snapshot around the given center (screen coords).
     * - Redraws only the uncovered bands when zooming out.
     * - When zooming in, keeps the upscaled snapshot (even if low-res) for now.
     * - Updates scroll (IntOffset) so that the top-left of the view is correct after zoom,
     *   keeping the content under the pinch center stationary on screen.
     */
    suspend fun updateZoom(scaleDelta: Float, center: Offset?) {
        log.d("Zoom(delta): $scaleDelta. Center: $center")

        val (pageId, start) = viewportStart()
        val oldZoom = start.zoom
        val newZoom = calculateZoomLevel(scaleDelta, oldZoom)
        if (newZoom == oldZoom) {
            log.d("Zoom unchanged. Current level: $oldZoom")
            return
        }

        // Flush pending strokes/background before snapshot-based operations
        waitForDrawingWithSnack()

        val scaleFactor = newZoom / oldZoom
        val screenW = windowedCanvas.width
        val screenH = windowedCanvas.height

        // Default pivot to screen center if none passed
        val pivotX = center?.x ?: (screenW / 2f)
        val pivotY = center?.y ?: (screenH / 2f)

        // Draw scaled snapshot into a fresh screen-sized bitmap
        val scaledBitmap = createBitmap(screenW, screenH, windowedBitmap.config!!)
        val scaledCanvas = Canvas(scaledBitmap)
        scaledCanvas.drawColor(Color.RED) // clear

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


        val deltaScrollPage = Offset(-dstRect.left / newZoom, -dstRect.top / newZoom)


        val newScrollX = (start.scroll.x + deltaScrollPage.x).coerceAtLeast(0f)
        val newScrollY = (start.scroll.y + deltaScrollPage.y).coerceAtLeast(0f)
        val next = PageViewport(Offset(newScrollX, newScrollY), newZoom)
        if (!commitViewport(pageId, start, next)) return

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


    /**
     * Makes [pageId] this view's page, published through [openPage], and returns its loaded
     * snapshot, or null once the view is disposed.
     */
    private suspend fun showPage(pageId: String): OpenPage? {
        val record = pageDataManager.getPageRecord(pageId)
        if (record == null)
            snackManager.showOrUpdateSnack(SnackConf(text = "Page not found", duration = 2000))
        if (!setOpenPage(pageId, savedScrollY = record?.scroll ?: 0)) return null
        val loaded = OpenPage.load(appRepository, pageId, record)
        publishLoaded(loaded)
        // The view may have been disposed while the page was being read.
        return loaded.takeUnless { synchronized(pageLock) { disposed } }
    }

    /**
     * Publishes [pageId] as this view's page and leases it in place of the page shown before, so
     * [PageDataManager] keeps it while it is shown, then reports it to [onPageShown]. Does nothing
     * and returns false once the view is disposed.
     *
     * The page comes with the viewport it had when this view left it, or else unzoomed at
     * [savedScrollY].
     */
    private fun setOpenPage(pageId: String, savedScrollY: Int): Boolean {
        synchronized(pageLock) {
            if (disposed) return false
            val previous = lease
            previous?.let { leftPages[it.pageId] = viewportFlow.value }
            lease = pageLeases.acquire(pageId)
            previous?.close()
            openPageRef.set(OpenPage(pageId))
            viewportFlow.value =
                leftPages.remove(pageId) ?: PageViewport(scroll = Offset(0f, savedScrollY.toFloat()))
            // Under the lock, so no page is reported after [disposeOldPage] returns.
            onPageShown(pageId)
        }
        updateContentHeight()
        return true
    }

    /**
     * Marks the view disposed and closes its lease. A disposed view shows, draws and loads nothing
     * more; [openPage] keeps the last page it showed.
     */
    private fun markDisposed() {
        synchronized(pageLock) {
            disposed = true
            lease?.close()
            lease = null
        }
    }

    /** Reads [pageId]'s record again and publishes it, for example after its background changed. */
    private suspend fun loadOpenPage(pageId: String) {
        val record = pageDataManager.getPageRecord(pageId)
        publishLoaded(OpenPage.load(appRepository, pageId, record))
    }

    /** Publishes [loaded], unless this view has moved to another page meanwhile. */
    private fun publishLoaded(loaded: OpenPage) {
        openPageRef.updateAndGet { it.withLoaded(loaded) }
    }

    // updates page setting in db, (for instance type of background)
    // and redraws page to view.
    suspend fun refreshCurrentPage() {
        val pageId = currentPageId
        log.d("Refresh page: $pageId")
        loadOpenPage(pageId)
        log.i("Refreshed page $pageId, background: ${openPage.backgroundName}")
        withContext(Dispatchers.Main) {
            drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
//            persistBitmapDebounced()
        }

    }

    fun drawBgToCanvas(clipRect: Rect?) {
        val shown = openPage
        val backgroundType = shown.backgroundType ?: BackgroundType.Native
        val bg = shown.backgroundName
        val pageNumber = shown.pageNumber
        val scale = zoom
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
            canvas = windowedCanvas,
            backgroundType = backgroundType,
            background = bg,
            scroll = scroll,
            resourceBitmap = bgImage,
            scale = scale,
            repeat = false,
            clipRect = clipRect
        )
    }


    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth != viewWidth || newHeight != viewHeight) {
            log.d("Updating dimensions: $newWidth x $newHeight")
            viewWidth = newWidth
            viewHeight = newHeight
            updateCanvasDimensions()
        }
    }

    private fun updateCanvasDimensions() {
        // A zoom fitted to the old size does not fit the new one, on any page.
        synchronized(pageLock) {
            viewportFlow.update { it.copy(zoom = 1f) }
            leftPages.replaceAll { _, left -> left.copy(zoom = 1f) }
        }
        // Recreate bitmap and canvas with new dimensions
        recreateCanvas()
        // TODO: it might be worth to do it
        //  by redrawing only part of the screen, like in scroll and zoom.
        coroutineScope.launch {
            CanvasEventBus.forceUpdate.emit(null)
        }
//        persistBitmapDebounced()
    }


    /** The shown page and its viewport, read together, for a scroll or zoom step to start from. */
    private fun viewportStart(): Pair<String, PageViewport> =
        synchronized(pageLock) { currentPageId to viewportFlow.value }

    /**
     * Sets [pageId]'s viewport to [next] and records its vertical scroll for saving, if [pageId] is
     * still shown at [start] and its record is loaded. Returns false, changing nothing, otherwise:
     * the page changed meanwhile, or it is still being read and gestures are not allowed yet.
     */
    private fun commitViewport(pageId: String, start: PageViewport, next: PageViewport): Boolean {
        synchronized(pageLock) {
            val shown = openPage
            if (shown.pageId != pageId || shown.record == null) return false
            if (!viewportFlow.compareAndSet(start, next)) return false
        }
        pageDataManager.recordScroll(pageId, next.scroll.y.roundToInt())
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

    fun toScreenCoordinates(rect: Rect): Rect {
        val shown = viewport.value
        return (rect - shown.scroll) * shown.zoom
    }

    private fun toPageCoordinates(rect: Rect): Rect {
        val shown = viewport.value
        return rect / shown.zoom + shown.scroll
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