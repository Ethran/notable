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
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toRect
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.CachedBackground
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawOnCanvasFromPage
import com.ethran.notable.editor.utils.div
import com.ethran.notable.editor.utils.loadPersistBitmap
import com.ethran.notable.editor.utils.minus
import com.ethran.notable.editor.utils.plus
import com.ethran.notable.editor.utils.times
import com.ethran.notable.editor.utils.toIntOffset
import com.ethran.notable.gestures.ZOOM_SNAP_THRESHOLD
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

const val OVERLAP = 2

/**
 * Manages the state and rendering of a single page within the editor.
 * It delegates task to PageDataManager, which is responsible for loading data from db,
 * and caching it.
 */
class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val pageDataManager: PageDataManager,
    val initialPageId: String,
    var viewWidth: Int,
    var viewHeight: Int,
    val snackManager: SnackState,
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

    //    var strokes = listOf<Stroke>()
    var strokes: List<Stroke>
        get() = pageDataManager.getStrokes(currentPageId)
        set(value) = pageDataManager.setStrokes(currentPageId, value)

    val strokesById: HashMap<String, Stroke>
        get() = pageDataManager.getStrokesById(currentPageId)

    var images: List<Image>
        get() = pageDataManager.getImages(currentPageId)
        set(value) = pageDataManager.setImages(currentPageId, value)

    // warning: The setter is delayed!
    private var currentBackground: CachedBackground
        get() = pageDataManager.getCurrentBackground()
        set(value) = pageDataManager.setCurrentBackground(value)

    val currentPageId: String
        get() = pageDataManager.getCurrentPageId()




    // scroll is observed by ui, represents top left corner
    var scroll: Offset
        get() = pageDataManager.getPageScroll(currentPageId)
        set(value) = pageDataManager.setPageScroll(currentPageId, value)


    val isTransformationAllowed: Boolean
        get() = pageDataManager.isTransformationAllowedForCurrentPage()


    // we need to observe zoom level, to adjust strokes size.
    val zoomLevel: MutableStateFlow<Float> =
        MutableStateFlow(pageDataManager.getPageZoom(currentPageId))

    var height: Int
        get() = pageDataManager.getPageHeight(currentPageId) ?: viewHeight
        set(value) {
            pageDataManager.setPageHeight(currentPageId, value)
        }


//    private var dbStrokes = appRepository.strokeRepository
//    private var dbImages = appRepository.imageRepository

    val currentPageNumber: Int
        get() = pageDataManager.getCurrentPageNumber()

    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
        log.i("getOrLoadBackground")
        val cached = currentBackground
        if (cached.matches(filePath, pageNumber, scale)) {
            log.i("Background bitmap (cached): ${cached.bitmap}")
            return cached.bitmap
        }
        // 0.1 to avoid constant rerender on zoom.
        val newBackground = CachedBackground(filePath, pageNumber, scale + 0.1f)
        currentBackground = newBackground
        log.i("Background bitmap: ${newBackground.bitmap}")
        return newBackground.bitmap
    }

    fun getBackgroundPageNumber(): Int {
        // There might be a bug here -- check it again.
        return currentBackground.pageNumber
    }


    init {
        coroutineScope.launch(Dispatchers.IO) {
            // set page, and retrieve page data from db
            pageDataManager.setPage(initialPageId)
            log.i("PageView init")

            zoomLevel.value = pageDataManager.getPageZoom(currentPageId)
            pageDataManager.getCachedBitmap(currentPageId)?.let { cached ->
                log.i("PageView: using cached bitmap")
                windowedBitmap = cached
                windowedCanvas = Canvas(windowedBitmap)
            } ?: run {
                log.i("PageView.init: creating new bitmap")
                recreateCanvas()
                pageDataManager.cacheBitmap(currentPageId, windowedBitmap)
            }

            coroutineScope.launch(Dispatchers.Main) {
                // If we do it with main.immediate then it wont work.
                CanvasEventBus.refreshUiImmediately.emit(Unit)
            }
            loadPage()
            log.d("Page loaded (Init with id: $currentPageId)")
            pageDataManager.collectAndPersistBitmapsBatch(context, coroutineScope)
        }
    }

    /**
     * Switches the `PageView` to display a different page.
     * **It doesn't notify the UI about the change.**
     *
     * This function handles the entire process of transitioning from the current page to a new one specified by `newPageId`.
     * It performs the following steps:
     * 1.  Saves the state of the old page, including persisting its bitmap representation to disk.
     * 2.  Updates the internal `currentPageId` to `newPageId`.
     * 3.  Fetches the new page's data from the repository.
     * 4.  Updates the `pageDataManager` to the new page context.
     * 5.  Restores the zoom level for the new page.
     * 6.  Attempts to load a cached bitmap for the new page. If a cached bitmap exists and its dimensions
     *     match the current view, it's used directly. If dimensions differ, the canvas is resized.
     * 7.  If no cached bitmap is available, it creates a new bitmap and canvas from scratch.
     * 8.  Launches a coroutine to load the page's content (strokes, images) asynchronously and refreshes the UI.
     *
     * @param newPageId The unique identifier of the page to switch to.
     */
    fun changePage(newPageId: String) {
        val oldId = currentPageId
        coroutineScope.launch(Dispatchers.IO) {
            pageDataManager.updateOnExit(oldId)
            persistBitmapDebounced(oldId)
        }
        coroutineScope.launch(Dispatchers.IO) {
            pageDataManager.setPage(newPageId)
            zoomLevel.value = pageDataManager.getPageZoom(currentPageId)
            pageDataManager.getCachedBitmap(newPageId)?.let { cached ->
                log.i("PageView: using cached bitmap")
                windowedBitmap = cached
                windowedCanvas = Canvas(windowedBitmap)
                // Check if we have correct size of canvas
                if (windowedCanvas.width != viewWidth || windowedCanvas.height != viewHeight)
                    updateCanvasDimensions()
            } ?: run {
                log.i("PageView.changePage: creating new bitmap")
                recreateCanvas()
                pageDataManager.cacheBitmap(newPageId, windowedBitmap)
            }

            log.d("New bitmap hash: ${windowedBitmap.hashCode()}, ID: $currentPageId")

            // Refresh UI without waiting for drawing.
            // TODO: Problem: Sometimes refreshUi had a problem with proper refreshing screen,
            //  using function that does not wait for drawing mostly solved the problem.
            //  but there might be still bugs with it.
            CanvasEventBus.refreshUiImmediately.emit(Unit)
            loadPage()
            log.d("Page loaded (updatePageID($currentPageId))")
        }
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
        pageDataManager.updateOnExit(currentPageId)
        persistBitmapDebounced(currentPageId)
        cleanJob()
    }


    // To be removed.
    private fun redrawAll(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main.immediate) {
            val viewRectangle = Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
            drawAreaScreenCoordinates(viewRectangle)
        }
    }

    private fun loadPage() {
        logCache.i("Init from persist layer, pageId: $currentPageId")
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)
        loadingJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                snackManager.showSnackDuring(text = "Loading strokes...") {
                    val timeToLoad = measureTimeMillis {
                        logCache.d("Start page loading, id $currentPageId")
                        pageDataManager.requestCurrentPageLoadJoin()
                        logCache.d("Got page data (PageView.loadPage). id $currentPageId")
                    }
                    logCache.d("All strokes loaded in $timeToLoad ms")
                }
                // TODO: If we put it in loadPage(…) sometimes it will try to refresh
                //  without seeing strokes, I have no idea why.
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.forceUpdate.emit(null)
                }
//                sleep(5000)

                logCache.d("Loaded page from persistent layer $currentPageId")
                if (!pageDataManager.validatePageDataLoaded(currentPageId))
                    logCache.e("Page should be loaded, but it is not. $currentPageId")
                coroutineScope.launch(Dispatchers.Default) {
                    delay(10)
                    pageDataManager.reduceCache(20)
                    pageDataManager.cacheNeighbors()
                }
//                sleep(10000)

            } catch (_: CancellationException) {
                val dataStatus = pageDataManager.validatePageDataLoaded(currentPageId)
                logCache.d("Page loading cancelled, data was loaded correctly: $dataStatus")
            } catch (e: Exception) {
                val dataStatus = pageDataManager.validatePageDataLoaded(currentPageId)
                logCache.e("Page loading cancelled, data was loaded correctly: $dataStatus", e)
            }
        }
    }


    fun addStrokes(strokesToAdd: List<Stroke>) {
        strokes += strokesToAdd
        updateHeightForChange(strokesToAdd)

        saveStrokesToPersistLayer(strokesToAdd)
        pageDataManager.indexStrokes(coroutineScope, currentPageId)

        persistBitmapDebounced()
    }

    // Completely updates strokes
    fun updateStrokes(strokesToUpdate: List<Stroke>) {
        // TODO: Clean it up, move some logic to pageDataManager
        val strokeUpdateById = strokesToUpdate.associateBy { it.id }
        strokes = strokes.map { stroke ->
            strokeUpdateById[stroke.id] ?: stroke
        }
        updateHeightForChange(strokesToUpdate)
        pageDataManager.updateStrokesInDb(strokesToUpdate)
        pageDataManager.indexStrokes(coroutineScope, currentPageId)
        persistBitmapDebounced()
    }

    fun removeStrokes(strokeIds: List<String>) {
        strokes = strokes.filter { s -> !strokeIds.contains(s.id) }
        removeStrokesFromPersistLayer(strokeIds)
        pageDataManager.indexStrokes(coroutineScope, currentPageId)
        pageDataManager.recomputeHeight(currentPageId)

        persistBitmapDebounced()
    }

    fun getStrokes(strokeIds: List<String>): List<Stroke?> {
        return pageDataManager.getStrokes(strokeIds, currentPageId)
    }

    fun updateHeightForChange(strokesChanged: List<Stroke>) {
        strokesChanged.forEach {
            val bottomPlusPadding = it.bottom + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding.toInt()
        }
    }

    private fun saveStrokesToPersistLayer(strokes: List<Stroke>) =
        pageDataManager.saveStrokesToDb(strokes)


    private fun saveImagesToPersistLayer(image: List<Image>) = pageDataManager.saveImagesToDb(image)


    fun addImage(imageToAdd: Image) {
        images += listOf(imageToAdd)
        val bottomPlusPadding = imageToAdd.x + imageToAdd.height + 50
        if (bottomPlusPadding > height) height = bottomPlusPadding

        saveImagesToPersistLayer(listOf(imageToAdd))
        pageDataManager.indexImages(coroutineScope, currentPageId)

        persistBitmapDebounced()
    }

    fun addImage(imageToAdd: List<Image>) {
        images += imageToAdd
        imageToAdd.forEach {
            val bottomPlusPadding = it.x + it.height + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding
        }
        saveImagesToPersistLayer(imageToAdd)
        pageDataManager.indexImages(coroutineScope, currentPageId)

        persistBitmapDebounced()
    }

    fun removeImages(imageIds: List<String>) {
        images = images.filter { s -> !imageIds.contains(s.id) }
        removeImagesFromPersistLayer(imageIds)
        pageDataManager.indexImages(coroutineScope, currentPageId)
        pageDataManager.recomputeHeight(currentPageId)
        persistBitmapDebounced()
    }

    fun getImage(imageId: String): Image? = pageDataManager.getImage(imageId, currentPageId)
    fun getImages(imageIds: List<String>): List<Image?> =
        pageDataManager.getImages(imageIds, currentPageId)


    private fun removeStrokesFromPersistLayer(strokeIds: List<String>) =
        pageDataManager.removeStrokesFromDb(strokeIds)

    private fun removeImagesFromPersistLayer(imageIds: List<String>) =
        pageDataManager.removeImagesFromDb(imageIds)

    // load background, fast, if it is accurate enough.
    private fun loadInitialBitmap(): Boolean {
        val bitmapFromDisc = loadPersistBitmap(context, currentPageId, scroll, zoomLevel.value, true)
        if (bitmapFromDisc != null) {
            // let's control that the last preview fits the present orientation. Otherwise we'll ask for a redraw.
            if (bitmapFromDisc.height == windowedCanvas.height && bitmapFromDisc.width == windowedCanvas.width) {
                windowedCanvas.drawBitmap(bitmapFromDisc, 0f, 0f, Paint())
                log.d("loaded initial bitmap")
                return true
            } else
                log.i("Image preview does not fit canvas area - redrawing")
        }

        log.d("Drawing initial background.")
        // draw just background.
        val backgroundType = pageDataManager.getBackgroundType()
        if (backgroundType == BackgroundType.Native) {
            val bg = pageDataManager.getBackgroundName()
            drawBg(
                context, windowedCanvas, backgroundType, bg, scroll, 1f, this
            )
        }
        else
            windowedCanvas.drawColor(Color.WHITE)
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
        )
    }

    suspend fun simpleUpdateScroll(dragDelta: Offset) {
        // Just update scroll, for debugging.
        // It will redraw whole screen, instead of trying to redraw only needed area.
        log.d("Simple update scroll")
        val delta = (dragDelta / zoomLevel.value)

        CanvasEventBus.waitForDrawingWithSnack()

        scroll =
            Offset((scroll.x + delta.x).coerceAtLeast(0f), (scroll.y + delta.y).coerceAtLeast(0f))

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
//        log.d("Update scroll, dragDelta: $dragDelta, scroll: $scroll, zoomLevel.value: $zoomLevel.value")
        // drag delta is in screen coordinates,
        // so we have to scale it back to page coordinates.
        var deltaInPage = Offset(dragDelta.x / zoomLevel.value, dragDelta.y / zoomLevel.value)

        // Cut, so we won't shift outside the screen.
        if (scroll.x + deltaInPage.x < 0) {
            deltaInPage = deltaInPage.copy(x = -scroll.x)
        }
        if (scroll.y + deltaInPage.y < 0) {
            deltaInPage = deltaInPage.copy(y = -scroll.y)
        }

        // There is nothing to do, return.
        if (deltaInPage == Offset.Zero) return

        // before scrolling, make sure that strokes are drawn.
        CanvasEventBus.waitForDrawingWithSnack()

        scroll += deltaInPage
        // To avoid rounding errors, we just calculate it again.
        val movement = (deltaInPage * zoomLevel.value)
        if (movement.toIntOffset() == IntOffset.Zero) return

        val width = windowedBitmap.width
        val height = windowedBitmap.height
        // Shift the existing bitmap content
        val shiftedBitmap = createBitmap(width, height, windowedBitmap.config!!)
        val shiftedCanvas = Canvas(shiftedBitmap)
        shiftedCanvas.drawColor(Color.RED) //for debugging.
        shiftedCanvas.drawBitmap(windowedBitmap, -movement.x, -movement.y, null)

        // Swap in the shifted bitmap
        windowedBitmap = shiftedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)

        redrawOutsideRect(
            alreadyDrawnRectAfterShift(movement.toIntOffset(), width, height),
            width,
            height
        )

        persistBitmapDebounced()
        saveToPersistLayer()
    }


    private fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
    ): Float {
        // TODO: Better snapping logic
        val portraitRatio = SCREEN_WIDTH.toFloat() / SCREEN_HEIGHT

        return if (!GlobalAppSettings.current.continuousZoom) {
            // Discrete zoom mode - snap to either 1.0 or screen ratio
            if (scaleDelta <= 1.0f) {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) portraitRatio else 1.0f
            } else {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) 1.0f else portraitRatio
            }
        } else {
            // Continuous zoom mode with snap behavior
            val newZoom = (scaleDelta / 3 + currentZoom).coerceIn(0.1f, 10.0f)

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
        // Update the zoom factor
        val newZoomLevel = calculateZoomLevel(scaleDelta, zoomLevel.value)

        // If there's no actual zoom change, skip
        if (newZoomLevel == zoomLevel.value) {
            log.d("Zoom unchanged. Current level: ${zoomLevel.value}")
            return
        }
        log.d("New zoom level: $newZoomLevel")
        applyZoomAndRedraw(newZoomLevel)
    }

    suspend fun applyZoomAndRedraw(newZoom: Float) {
        zoomLevel.value = newZoom
        CanvasEventBus.waitForDrawingWithSnack()
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
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)


        // Redraw everything at new zoom level
        val redrawRect = Rect(0, 0, windowedBitmap.width, windowedBitmap.height)

        log.d("Redrawing full logical rect: $redrawRect")
        windowedCanvas.drawColor(Color.BLACK)
        val backgroundType = pageDataManager.getBackgroundType() ?: BackgroundType.Native
        val bg = pageDataManager.getBackgroundName()
        drawBg(
            context,
            windowedCanvas,
            backgroundType,
            bg,
            scroll,
            zoomLevel.value,
            this,
            redrawRect
        )
        pageDataManager.cacheBitmap(currentPageId, windowedBitmap)

        drawAreaScreenCoordinates(redrawRect)

        saveToPersistLayer()
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

        val oldZoom = zoomLevel.value
        val newZoom = calculateZoomLevel(scaleDelta, oldZoom)
        if (newZoom == oldZoom) {
            log.d("Zoom unchanged. Current level: $oldZoom")
            return
        }

        // Flush pending strokes/background before snapshot-based operations
        CanvasEventBus.waitForDrawingWithSnack()

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
        val dx = (scroll.x - dstRect.left).coerceAtMost(0f)
        val dy = (scroll.y - dstRect.top).coerceAtMost(0f)
        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
            matrix.mapRect(dstRect, srcRect)
        }
        scaledCanvas.drawBitmap(windowedBitmap, matrix, null)


        val deltaScrollPage = Offset(-dstRect.left / newZoom, -dstRect.top / newZoom)


        val newScrollX = (scroll.x + deltaScrollPage.x).coerceAtLeast(0f)
        val newScrollY = (scroll.y + deltaScrollPage.y).coerceAtLeast(0f)
        scroll = Offset(newScrollX, newScrollY)

        // Swap in the new bitmap and update zoom on the windowed canvas
        windowedBitmap = scaledBitmap
        windowedCanvas.setBitmap(windowedBitmap)

        zoomLevel.value = newZoom
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)

        if (scaleFactor < 1f) redrawOutsideRect(dstRect.toRect(), screenW, screenH)

        persistBitmapDebounced()
        saveToPersistLayer()
        log.i(
            "Zoom updated using snapshot scaling. " +
                    "oldZoom=$oldZoom newZoom=$newZoom " +
                    "scaleFactor=$scaleFactor pivot=($pivotX,$pivotY) " +
                    "bounds=$dstRect" +
                    "scrollDelta=$deltaScrollPage newScroll=$scroll"
        )
    }

    fun redrawOutsideRect(dstRect: Rect, screenW: Int, screenH: Int) {
        val scaledOverlap = ceil(OVERLAP * zoomLevel.value.coerceAtLeast(1f)).toInt()

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
        pageDataManager.refreshPageFromDb()
        withContext(Dispatchers.Main) {
            drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
            persistBitmapDebounced()
        }

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
        // Recreate bitmap and canvas with new dimensions
        recreateCanvas()
        //Reset zoom level.
        zoomLevel.value = 1.0f
        // TODO: it might be worth to do it
        //  by redrawing only part of the screen, like in scroll and zoom.
        coroutineScope.launch {
            CanvasEventBus.forceUpdate.emit(null)
        }
        persistBitmapDebounced()
    }

    // should be run after every modification of widowedBitmap.
    // Especially, on major ones -- this persistent bitmap will be used to reinitialize page.
    // if its not correct, might cause ghosting
    private fun persistBitmapDebounced(pageId: String = this.currentPageId) {
        coroutineScope.launch {
            // Make sure that persisting bitmap gets the newest possible bitmap
            // TODO: There might still be some nasty race conditions.
            pageDataManager.cacheBitmap(currentPageId, windowedBitmap)
            pageDataManager.saveTopic.emit(pageId)
        }
    }

    private fun saveToPersistLayer() = pageDataManager.setScrollInDb()

    fun applyZoom(point: IntOffset): IntOffset {
        return point * zoomLevel.value
    }

    fun removeZoom(point: IntOffset): IntOffset {
        return point / zoomLevel.value
    }

    private fun removeScroll(rect: Rect): Rect {
        return rect - scroll
    }

    fun toScreenCoordinates(rect: Rect): Rect {
        return (rect - scroll) * zoomLevel.value
    }

    private fun toPageCoordinates(rect: Rect): Rect {
        return rect / zoomLevel.value + scroll
    }
}