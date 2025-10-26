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
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.CachedBackground
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.PageDataManager.cacheNeighbors
import com.ethran.notable.data.PageDataManager.collectAndPersistBitmapsBatch
import com.ethran.notable.data.PageDataManager.updateOnExit
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.getBackgroundType
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.drawing.drawBg
import com.ethran.notable.editor.drawing.drawOnCanvasFromPage
import com.ethran.notable.editor.state.ZOOM_SNAP_THRESHOLD
import com.ethran.notable.editor.utils.div
import com.ethran.notable.editor.utils.loadPersistBitmap
import com.ethran.notable.editor.utils.minus
import com.ethran.notable.editor.utils.plus
import com.ethran.notable.editor.utils.times
import com.ethran.notable.editor.utils.toIntOffset
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.SnackState.Companion.logAndShowError
import com.onyx.android.sdk.extension.isNotNull
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

const val OVERLAP = 2

/**
 * Manages the state and rendering of a single page within the editor.
 * @param [id] Id of page assigned to it.
 */
class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    var id: String,
    var viewWidth: Int,
    var viewHeight: Int,
    val snackManager: SnackState
) {
    // TODO: unify width height variable

    val log = ShipBook.getLogger("PageView")
    private val logCache = ShipBook.getLogger("PageViewCache")

    private var loadingJob: Job? = null


    private val appRepository = AppRepository(context)

    @Volatile
    var windowedBitmap = createBitmap(viewWidth, viewHeight)
        private set

    @Volatile
    var windowedCanvas = Canvas(windowedBitmap)
        private set

    //    var strokes = listOf<Stroke>()
    var strokes: List<Stroke>
        get() = PageDataManager.getStrokes(id)
        set(value) = PageDataManager.setStrokes(id, value)

    var images: List<Image>
        get() = PageDataManager.getImages(id)
        set(value) = PageDataManager.setImages(id, value)

    private var currentBackground: CachedBackground
        get() = PageDataManager.getBackground(id)
        set(value) {
            val observeBg = appRepository.isObservable(pageFromDb?.notebookId)
            PageDataManager.setBackground(id, value, observeBg)
        }

    // scroll is observed by ui, represents top left corner
    var scroll: Offset
        get() = PageDataManager.getPageScroll(id) ?: run {
            val value = Offset(0f, pageFromDb?.scroll?.toFloat() ?: 0f)
            PageDataManager.setPageScroll(id, value)
            value
        }
        set(value) {
            PageDataManager.setPageScroll(id, value)
        }

    val isTransformationAllowed: Boolean
        get() = when (pageFromDb?.backgroundType) {
            "native", null -> true
            "coverImage" -> false
            else -> true
        }

    // we need to observe zoom level, to adjust strokes size.
    val zoomLevel: MutableStateFlow<Float> = MutableStateFlow(PageDataManager.getPageZoom(id))

    var height: Int
        get() = PageDataManager.getPageHeight(id) ?: viewHeight
        set(value) {
            PageDataManager.setPageHeight(id, value)
        }


    var pageFromDb = appRepository.pageRepository.getById(id)

    private var dbStrokes = AppDatabase.getDatabase(context).strokeDao()
    private var dbImages = AppDatabase.getDatabase(context).ImageDao()

    val currentPageNumber: Int
        get() = try {
            appRepository.getPageNumber(pageFromDb?.notebookId, id)
        } catch (e: Exception) {
            logAndShowError(
                "PageView.currentPageNumber",
                "Error getting page number: ${e.message}"
            )
            -1
        }


    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
        if (!currentBackground.matches(filePath, pageNumber, scale))
        // 0.1 to avoid constant rerender on zoom.
            currentBackground = CachedBackground(filePath, pageNumber, scale + 0.1f)
        return currentBackground.bitmap
    }

    fun getBackgroundPageNumber(): Int {
        // There might be a bug here -- check it again.
        return currentBackground.pageNumber
    }


    init {
        PageDataManager.setPage(id)
        log.i("PageView init")
        zoomLevel.value = PageDataManager.getPageZoom(id)
        PageDataManager.getCachedBitmap(id)?.let { cached ->
            log.i("PageView: using cached bitmap")
            windowedBitmap = cached
            windowedCanvas = Canvas(windowedBitmap)
        } ?: run {
            log.i("PageView.init: creating new bitmap")
            recreateCanvas()
            PageDataManager.cacheBitmap(id, windowedBitmap)
        }

        coroutineScope.launch {
            DrawCanvas.refreshUiImmediately.emit(Unit)
            loadPage()
            log.d("Page loaded (Init with id: $id)")
            collectAndPersistBitmapsBatch(context, coroutineScope)
        }
    }

    fun changePage(newPageId: String) {
        val oldId = id
        id = newPageId
        coroutineScope.launch {
            updateOnExit(oldId)
            persistBitmapDebounced(oldId)
        }
        pageFromDb = AppRepository(context).pageRepository.getById(id)
        PageDataManager.setPage(newPageId)
        zoomLevel.value = PageDataManager.getPageZoom(id)
        PageDataManager.getCachedBitmap(newPageId)?.let { cached ->
            log.i("PageView: using cached bitmap")
            windowedBitmap = cached
            windowedCanvas = Canvas(windowedBitmap)
            // Check if we have correct size of canvas
            if (windowedCanvas.width != viewWidth || windowedCanvas.height != viewHeight)
                updateCanvasDimensions()
        } ?: run {
            log.i("PageView.changePage: creating new bitmap")
            recreateCanvas()
            PageDataManager.cacheBitmap(newPageId, windowedBitmap)
        }

        log.d("New bitmap hash: ${windowedBitmap.hashCode()}, ID: $id")

        coroutineScope.launch {
            // Refresh UI without waiting for drawing.
            // TODO: Problem: Sometimes refreshUi had a problem with proper refreshing screen,
            //  using function that does not wait for drawing mostly solved the problem.
            //  but there might be still bugs with it.
            DrawCanvas.refreshUiImmediately.emit(Unit)
            loadPage()
            log.d("Page loaded (updatePageID($id))")
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
        updateOnExit(id)
        persistBitmapDebounced(id)
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
        logCache.i("Init from persist layer, pageId: $id")
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)
        val bookId = pageFromDb?.notebookId
        loadingJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                snackManager.showSnackDuring(text = "Loading strokes...") {
                    val timeToLoad = measureTimeMillis {
                        logCache.d("Start page, id $id")
                        PageDataManager.requestPageLoadJoin(appRepository, id, bookId)
                        logCache.d("Got page data (PageView.loadPage). id $id")
                    }
                    logCache.d("All strokes loaded in $timeToLoad ms")
                }
                // TODO: If we put it in loadPage(…) sometimes it will try to refresh
                //  without seeing strokes, I have no idea why.
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    DrawCanvas.forceUpdate.emit(null)
                }
                logCache.d("Loaded page from persistent layer $id")
                if (!PageDataManager.validatePageDataLoaded(id))
                    logCache.e("Page should be loaded, but it is not. $id")
                coroutineScope.launch(Dispatchers.Default) {
                    delay(10)
                    PageDataManager.reduceCache(20)
                    if (bookId.isNotNull())
                        cacheNeighbors(appRepository, id, bookId)
                }
            } catch (_: CancellationException) {
                val dataStatus = PageDataManager.validatePageDataLoaded(id)
                logCache.d("Page loading cancelled, data was loaded correctly: $dataStatus")
            } catch (e: Exception) {
                val dataStatus = PageDataManager.validatePageDataLoaded(id)
                logCache.e("Page loading cancelled, data was loaded correctly: $dataStatus", e)
            }
        }
    }


    fun addStrokes(strokesToAdd: List<Stroke>) {
        strokes += strokesToAdd
        strokesToAdd.forEach {
            val bottomPlusPadding = it.bottom + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding.toInt()
        }

        saveStrokesToPersistLayer(strokesToAdd)
        PageDataManager.indexStrokes(coroutineScope, id)

        persistBitmapDebounced()
    }

    fun removeStrokes(strokeIds: List<String>) {
        strokes = strokes.filter { s -> !strokeIds.contains(s.id) }
        removeStrokesFromPersistLayer(strokeIds)
        PageDataManager.indexStrokes(coroutineScope, id)
        PageDataManager.recomputeHeight(id)

        persistBitmapDebounced()
    }

    fun getStrokes(strokeIds: List<String>): List<Stroke?> {
        return PageDataManager.getStrokes(strokeIds, id)
    }

    private fun saveStrokesToPersistLayer(strokes: List<Stroke>) {
        dbStrokes.create(strokes)
    }

    private fun saveImagesToPersistLayer(image: List<Image>) {
        dbImages.create(image)
    }


    fun addImage(imageToAdd: Image) {
        images += listOf(imageToAdd)
        val bottomPlusPadding = imageToAdd.x + imageToAdd.height + 50
        if (bottomPlusPadding > height) height = bottomPlusPadding

        saveImagesToPersistLayer(listOf(imageToAdd))
        PageDataManager.indexImages(coroutineScope, id)

        persistBitmapDebounced()
    }

    fun addImage(imageToAdd: List<Image>) {
        images += imageToAdd
        imageToAdd.forEach {
            val bottomPlusPadding = it.x + it.height + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding
        }
        saveImagesToPersistLayer(imageToAdd)
        PageDataManager.indexImages(coroutineScope, id)

        persistBitmapDebounced()
    }

    fun removeImages(imageIds: List<String>) {
        images = images.filter { s -> !imageIds.contains(s.id) }
        removeImagesFromPersistLayer(imageIds)
        PageDataManager.indexImages(coroutineScope, id)
        PageDataManager.recomputeHeight(id)
        persistBitmapDebounced()
    }

    fun getImage(imageId: String): Image? = PageDataManager.getImage(imageId, id)


    fun getImages(imageIds: List<String>): List<Image?> = PageDataManager.getImages(imageIds, id)


    private fun removeStrokesFromPersistLayer(strokeIds: List<String>) {
        appRepository.strokeRepository.deleteAll(strokeIds)
    }

    private fun removeImagesFromPersistLayer(imageIds: List<String>) {
        appRepository.imageRepository.deleteAll(imageIds)
    }

    // load background, fast, if it is accurate enough.
    private fun loadInitialBitmap(): Boolean {
        val bitmapFromDisc = loadPersistBitmap(context, id, scroll, zoomLevel.value)
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
        val backgroundType = pageFromDb?.getBackgroundType()
        if (backgroundType == BackgroundType.Native)
            drawBg(
                context, windowedCanvas, backgroundType,
                pageFromDb?.background ?: "blank", scroll, 1f, this
            )
        else
            windowedCanvas.drawColor(Color.WHITE)
        return false
    }


    private fun cleanJob() {
        //ensure that snack is canceled, even on dispose of the page.
        CoroutineScope(Dispatchers.IO).launch {
            PageDataManager.cancelLoadingPage(pageId = id)
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

        DrawCanvas.waitForDrawingWithSnack()

        scroll =
            Offset((scroll.x + delta.x).coerceAtLeast(0f), (scroll.y + delta.y).coerceAtLeast(0f))

        DrawCanvas.forceUpdate.emit(null)
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
        DrawCanvas.waitForDrawingWithSnack()

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
        DrawCanvas.waitForDrawingWithSnack()
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

        drawBg(
            context,
            windowedCanvas,
            pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
            pageFromDb?.background ?: "blank",
            scroll,
            zoomLevel.value,
            this,
            redrawRect
        )
        PageDataManager.cacheBitmap(id, windowedBitmap)

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
        DrawCanvas.waitForDrawingWithSnack()

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
// and redraws page to vew.
    fun updatePageSettings(page: Page) {
        appRepository.pageRepository.update(page)
        pageFromDb = appRepository.pageRepository.getById(id)
        log.i("Page settings updated, ${pageFromDb?.background} | ${page.background}")
        drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
        persistBitmapDebounced()
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
            DrawCanvas.forceUpdate.emit(null)
        }
        persistBitmapDebounced()
    }

    // should be run after every modification of widowedBitmap.
    // Especially, on major ones -- this persistent bitmap will be used to reinitialize page.
    // if its not correct, might cause ghosting
    private fun persistBitmapDebounced(pageId: String = this.id) {
        coroutineScope.launch {
            // Make sure that persisting bitmap gets the newest possible bitmap
            // TODO: There might still be some nasty race conditions.
            PageDataManager.cacheBitmap(id, windowedBitmap)
            PageDataManager.saveTopic.emit(pageId)
        }
    }

    private fun saveToPersistLayer() {
        coroutineScope.launch {
            appRepository.pageRepository.updateScroll(id, scroll.y.toInt())
            pageFromDb = appRepository.pageRepository.getById(id)
        }
    }


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