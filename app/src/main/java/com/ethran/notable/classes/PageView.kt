package com.ethran.notable.classes


import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.toRect
import androidx.core.graphics.withClip
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.TAG
import com.ethran.notable.db.AppDatabase
import com.ethran.notable.db.BackgroundType
import com.ethran.notable.db.Image
import com.ethran.notable.db.Page
import com.ethran.notable.db.Stroke
import com.ethran.notable.db.getBackgroundType
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.drawBg
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.drawStroke
import com.ethran.notable.utils.imageBounds
import com.ethran.notable.utils.loadBackgroundBitmap
import com.ethran.notable.utils.strokeBounds
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.Thread.sleep
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.measureTimeMillis

class PageView(
    val context: Context,
    val coroutineScope: CoroutineScope,
    val id: String,
    val width: Int,
    var viewWidth: Int,
    var viewHeight: Int
) {
    private var loadingJob: Job? = null
    private var cacheJob: Job? = null

    private var snack: SnackConf? = null

    var windowedBitmap = createBitmap(viewWidth, viewHeight)
    var windowedCanvas = Canvas(windowedBitmap)
    var strokes = listOf<Stroke>()
    private var strokesById: HashMap<String, Stroke> = hashMapOf()
    var images = listOf<Image>()
    private var imagesById: HashMap<String, Image> = hashMapOf()
    var scroll by mutableIntStateOf(0) // is observed by ui
    val scrollable: Boolean
        get() = when (pageFromDb?.backgroundType) {
            "native", null -> true
            "coverImage" -> false
            else -> true
        }

    // we need to observe zoom level, to adjust strokes size.
    var zoomLevel = MutableStateFlow(1.0f)

    private val saveTopic = MutableSharedFlow<Unit>()

    var height by mutableIntStateOf(viewHeight) // is observed by ui

    var pageFromDb = AppRepository(context).pageRepository.getById(id)

    private var dbStrokes = AppDatabase.getDatabase(context).strokeDao()
    private var dbImages = AppDatabase.getDatabase(context).ImageDao()


    private var currentBackground = CachedBackground(null, "", 0, 1.0f)

    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(filePath: String, pageNumber: Int, scale: Float): Bitmap? {
        if (currentBackground.path != filePath || currentBackground.pageNumber != pageNumber || currentBackground.scale < scale) {
            currentBackground =
                CachedBackground(
                    loadBackgroundBitmap(filePath, pageNumber, scale),
                    filePath,
                    pageNumber,
                    scale
                )
        }
        return currentBackground.bitmap
    }

    fun getBackgroundPageNumber(): Int {
        // There might be a bug here -- check it again.
        return currentBackground.pageNumber
    }


    init {
        coroutineScope.launch {
            saveTopic.debounce(1000).collect {
                launch { persistBitmap() }
                launch { persistBitmapThumbnail() }
            }
        }

        windowedCanvas.drawColor(Color.WHITE)
        val isBitmapCached = loadInitialBitmap()
        loadPage(isBitmapCached)
    }

    private fun indexStrokes() {
        coroutineScope.launch {
            strokesById = hashMapOf(*strokes.map { s -> s.id to s }.toTypedArray())
        }
    }

    private fun indexImages() {
        coroutineScope.launch {
            imagesById = hashMapOf(*images.map { img -> img.id to img }.toTypedArray())
        }
    }

    // Loads all the strokes on page
    private fun loadFromPersistLayer() {
        Log.i(TAG, "Init from persist layer")
        loadingJob = coroutineScope.launch(Dispatchers.IO) {
            // Set duration as safety guard: in 60 s all strokes should be loaded
            snack = SnackConf(text = "Loading strokes...", duration = 60000)
            SnackState.globalSnackFlow.emit(snack!!)
            val timeToLoad = measureTimeMillis {

                val pageWithImages = AppRepository(context).pageRepository.getWithImageById(id)
                images = pageWithImages.images
                val pageWithStrokes =
                    AppRepository(context).pageRepository.getWithStrokeByIdSuspend(id)
                strokes = pageWithStrokes.strokes
//                Thread.sleep(50000)
                indexImages()
                indexStrokes()
                computeHeight()
                snack?.let { SnackState.cancelGlobalSnack.emit(it.id) }
            }
            Log.d(TAG + "Cache", "All strokes loaded in $timeToLoad ms")

            // Ensure strokes are loaded and visible before drawing (switching to Main guarantees visibility).
            // I'm not sure if it still required.
            sleep(10) // wait 10ms
            redrawAll(this)
        }
    }


    private fun hasEnoughMemory(requiredMemory: Int = 50): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        Log.i(TAG + "Cache", "Available memory: ${memoryInfo.availMem / (1024 * 1024)} MB")
        // Only cache if we have more than 50MB available
        return memoryInfo.availMem > requiredMemory * 1024 * 1024
    }

    private fun isPageCached(pageId: String): Boolean {
        return PageCacheManager.isPageCached(pageId)
    }

    private suspend fun cachePage(pageId: String) {
        if (isPageCached(pageId)) return
        val pageWithStrokes =
            AppRepository(context).pageRepository.getWithStrokeByIdSuspend(pageId)
        PageCacheManager.cacheStrokes(pageId, pageWithStrokes.strokes)
        val pageWithImages = AppRepository(context).pageRepository.getWithImageById(pageId)
        // Store in some cache manager
        PageCacheManager.cacheImages(pageId, pageWithImages.images)
    }

    private fun moveToCache() {
        Log.i(TAG + "Cache", "Moving page to cache")

        // Move background unconditionally
        PageCacheManager.cacheBackground(id, currentBackground)
        currentBackground = CachedBackground(null, "", 0, 1.0f)


        // Move strokes if present
        if (strokes.isNotEmpty()) {
            (strokes as? MutableList<Stroke>)?.let {
                PageCacheManager.moveStrokesToCache(id, it)
            } ?: Log.w(TAG + "Cache", "Strokes list is not mutable, skipping move.")
            strokes = emptyList()
        } else
            PageCacheManager.setCachedStrokesToEmpty(id)

        // Move images if present
        if (images.isNotEmpty()) {
            (images as? MutableList<Image>)?.let {
                PageCacheManager.moveImagesToCache(id, it)
            } ?: Log.w(TAG + "Cache", "Images list is not mutable, skipping move.")
            images = emptyList()
        } else
            PageCacheManager.setCachedImagesToEmpty(id)
    }


    private fun loadFromCache(): Boolean {
        val cachedStrokes = PageCacheManager.takeStrokes(id) ?: return false
        val cachedImages = PageCacheManager.takeImages(id) ?: return false

        strokes = cachedStrokes.toList()
        images = cachedImages.toList()
        currentBackground =
            PageCacheManager.getPageBackground(id) ?: CachedBackground(null, "", 0, 1.0f)

        return true
    }


    private fun redrawAll(scope: CoroutineScope) {
        scope.launch(Dispatchers.Main.immediate) {
            val viewRectangle = Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
            drawAreaScreenCoordinates(viewRectangle)
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    private fun loadPage(isBitmapCached: Boolean) {
        val isInCache = loadFromCache()
        if (!isBitmapCached)
            drawBg(
                context, windowedCanvas, pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
                pageFromDb?.background ?: "blank", scroll, 1f, this
            )


        val page = AppRepository(context).pageRepository.getById(id)
        scroll = page!!.scroll

        if (isInCache) {
            Log.i(TAG + "Cache", "Page loaded from cache")
            indexImages()
            indexStrokes()
            computeHeight()
            redrawAll(coroutineScope)
        } else {
            Log.i(TAG + "Cache", "Page not found in cache")
            cleanJob()
            // If cache is incomplete, load from persistent storage
            PageCacheManager.ensureEnoughMemory(50, ::hasEnoughMemory)
            loadFromPersistLayer()
        }
        PageCacheManager.ensureEnoughMemory(50, ::hasEnoughMemory)
        // Only attempt to cache neighbors if we have memory to spare.
        if (!hasEnoughMemory(100)) return

        Log.i(TAG + "Cache", "Caching neighbors")
        val appRepository = AppRepository(context)
        val bookId = pageFromDb?.notebookId ?: return

        cacheJob = coroutineScope.launch(Dispatchers.IO) {
            loadingJob?.join()
            PageCacheManager.estimateMemoryUsage()
            try {
                // Cache next page if not already cached
                val nextPageId =
                    appRepository.getNextPageIdFromBookAndPage(pageId = id, notebookId = bookId)
                nextPageId?.let { nextPage ->
                    cachePage(nextPage)
                }
                if (hasEnoughMemory(100)) {
                    // Cache previous page if not already cached
                    val prevPageId =
                        appRepository.getPreviousPageIdFromBookAndPage(
                            pageId = id,
                            notebookId = bookId
                        )
                    prevPageId?.let { prevPage ->
                        cachePage(prevPage)
                    }
                }
                PageCacheManager.estimateMemoryUsage()
                // Enforce cache limits
                PageCacheManager.reduceCache(20)
            } catch (e: CancellationException) {
                Log.i(TAG + "Cache", "Caching was cancelled: ${e.message}")
            } catch (e: Exception) {
                // All other unexpected exceptions
                Log.e(TAG + "Cache", "Error caching neighbor pages", e)
                showHint("Error encountered while caching neighbors", duration = 5000)

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
        indexStrokes()

        persistBitmapDebounced()
    }

    fun removeStrokes(strokeIds: List<String>) {
        strokes = strokes.filter { s -> !strokeIds.contains(s.id) }
        removeStrokesFromPersistLayer(strokeIds)
        indexStrokes()
        computeHeight()

        persistBitmapDebounced()
    }

    fun getStrokes(strokeIds: List<String>): List<Stroke?> {
        return strokeIds.map { s -> strokesById[s] }
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
        indexImages()

        persistBitmapDebounced()
    }

    fun addImage(imageToAdd: List<Image>) {
        images += imageToAdd
        imageToAdd.forEach {
            val bottomPlusPadding = it.x + it.height + 50
            if (bottomPlusPadding > height) height = bottomPlusPadding
        }
        saveImagesToPersistLayer(imageToAdd)
        indexImages()

        persistBitmapDebounced()
    }

    fun removeImages(imageIds: List<String>) {
        images = images.filter { s -> !imageIds.contains(s.id) }
        removeImagesFromPersistLayer(imageIds)
        indexImages()
        computeHeight()

        persistBitmapDebounced()
    }

    fun getImage(imageId: String): Image? {
        return imagesById[imageId]
    }

    fun getImages(imageIds: List<String>): List<Image?> {
        return imageIds.map { i -> imagesById[i] }
    }


    private fun computeHeight() {
        if (strokes.isEmpty()) {
            height = viewHeight
            return
        }
        val maxStrokeBottom = strokes.maxOf { it.bottom }.plus(50)
        height = max(maxStrokeBottom.toInt(), viewHeight)
    }

    fun computeWidth(): Int {
        if (strokes.isEmpty()) {
            return viewWidth
        }
        val maxStrokeRight = strokes.maxOf { it.right }.plus(50)
        return max(maxStrokeRight.toInt(), viewWidth)
    }

    private fun removeStrokesFromPersistLayer(strokeIds: List<String>) {
        AppRepository(context).strokeRepository.deleteAll(strokeIds)
    }

    private fun removeImagesFromPersistLayer(imageIds: List<String>) {
        AppRepository(context).imageRepository.deleteAll(imageIds)
    }

    private fun loadInitialBitmap(): Boolean {
        val imgFile = File(context.filesDir, "pages/previews/full/$id")
        val imgBitmap: Bitmap?
        if (imgFile.exists()) {
            imgBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            if (imgBitmap != null) {
                windowedCanvas.drawBitmap(imgBitmap, 0f, 0f, Paint())
                Log.i(TAG, "Initial Bitmap for page rendered from cache")
                // let's control that the last preview fits the present orientation. Otherwise we'll ask for a redraw.
                if (imgBitmap.height == windowedCanvas.height && imgBitmap.width == windowedCanvas.width) {
                    return true
                } else {
                    Log.i(TAG, "Image preview does not fit canvas area - redrawing")
                }
            } else {
                Log.i(TAG, "Cannot read cache image")
            }
        } else {
            Log.i(TAG, "Cannot find cache image")
        }
        return false
    }

    private fun persistBitmap() {
        val file = File(context.filesDir, "pages/previews/full/$id")
        Files.createDirectories(Path(file.absolutePath).parent)
        val os = BufferedOutputStream(FileOutputStream(file))
        windowedBitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        os.close()
    }

    private fun persistBitmapThumbnail() {
        val file = File(context.filesDir, "pages/previews/thumbs/$id")
        Files.createDirectories(Path(file.absolutePath).parent)
        val os = BufferedOutputStream(FileOutputStream(file))
        val ratio = windowedBitmap.height.toFloat() / windowedBitmap.width.toFloat()
        windowedBitmap.scale(500, (500 * ratio).toInt(), false)
            .compress(Bitmap.CompressFormat.JPEG, 80, os)
        os.close()
    }

    private fun cleanJob() {
        //ensure that snack is canceled, even on dispose of the page.
        CoroutineScope(Dispatchers.IO).launch {
            snack?.let { SnackState.cancelGlobalSnack.emit(it.id) }
        }
        loadingJob?.cancel()
        cacheJob?.cancel()
        if (loadingJob?.isActive == true) {
            Log.e(TAG, "Strokes are still loading, trying to cancel and resume")
            cleanJob()
        }
    }

    /*
        Cancel loading strokes, and save bitmap to disk
    */
    fun onDispose() {
        if (loadingJob?.isActive == false) {
            moveToCache()
        }
        cleanJob()
        persistBitmap()
        persistBitmapThumbnail()
        // TODO: if we exited the book, we should clear the cache.
    }


    private fun drawDebugRectWithLabels(
        canvas: Canvas,
        rect: RectF,
        rectColor: Int = Color.RED,
        labelColor: Int = Color.BLUE
    ) {
        val rectPaint = Paint().apply {
            color = rectColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        Log.w(TAG, "Drawing debug rect $rect")
        // Draw rectangle outline
        canvas.drawRect(rect, rectPaint)

        // Setup label paint
        val labelPaint = Paint().apply {
            color = labelColor
            textAlign = Paint.Align.LEFT
            textSize = 40f
            isAntiAlias = true
        }

        // Helper to format text
        fun format(x: Float, y: Float) = "(${x.toInt()}, ${y.toInt()})"

        val topLeftLabel = format(rect.left, rect.top)
        val topRightLabel = format(rect.right, rect.top)
        val bottomLeftLabel = format(rect.left, rect.bottom)
        val bottomRightLabel = format(rect.right, rect.bottom)

        val topRightTextWidth = labelPaint.measureText(topRightLabel)
        val bottomRightTextWidth = labelPaint.measureText(bottomRightLabel)

        // Draw coordinate labels at corners
        canvas.drawText(topLeftLabel, rect.left + 8f, rect.top + labelPaint.textSize, labelPaint)
        canvas.drawText(
            topRightLabel,
            rect.right - topRightTextWidth - 8f,
            rect.top + labelPaint.textSize,
            labelPaint
        )
        canvas.drawText(bottomLeftLabel, rect.left + 8f, rect.bottom - 8f, labelPaint)
        canvas.drawText(
            bottomRightLabel,
            rect.right - bottomRightTextWidth - 8f,
            rect.bottom - 8f,
            labelPaint
        )
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
        and redraws them.
     */
    fun drawAreaScreenCoordinates(
        screenArea: Rect,
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        // TODO: make sure that rounding errors are not happening
        val activeCanvas = canvas ?: windowedCanvas
        val pageArea = toPageCoordinates(screenArea)
        val pageAreaWithoutScroll = removeScroll(pageArea)

        // Canvas is scaled, it will scale page area.
        activeCanvas.withClip(pageAreaWithoutScroll) {
            drawColor(Color.BLACK)

            val timeToDraw = measureTimeMillis {
                drawBg(
                    context, this, pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
                    pageFromDb?.background ?: "blank", scroll, zoomLevel.value, this@PageView
                )
                if (GlobalAppSettings.current.debugMode) {
                    drawDebugRectWithLabels(activeCanvas, RectF(pageAreaWithoutScroll), Color.BLACK)
//                    drawDebugRectWithLabels(activeCanvas, RectF(screenArea))
                }
                // Trying to find what throws error when drawing quickly
                try {
                    images.forEach { image ->
                        if (ignoredImageIds.contains(image.id)) return@forEach
                        Log.i(TAG, "PageView.kt: drawing image!")
                        val bounds = imageBounds(image)
                        // if stroke is not inside page section
                        if (!bounds.toRect().intersect(pageArea)) return@forEach
                        drawImage(context, this, image, IntOffset(0, -scroll))

                    }
                } catch (e: Exception) {
                    Log.e(TAG, "PageView.kt: Drawing images failed: ${e.message}", e)

                    val errorMessage =
                        if (e.message?.contains("does not have permission") == true) {
                            "Permission error: Unable to access image."
                        } else {
                            "Failed to load images."
                        }
                    showHint(errorMessage, coroutineScope)
                }
                try {
                    strokes.forEach { stroke ->
                        if (ignoredStrokeIds.contains(stroke.id)) return@forEach
                        val bounds = strokeBounds(stroke)
                        // if stroke is not inside page section
                        if (!bounds.toRect().intersect(pageArea)) return@forEach

                        drawStroke(
                            this, stroke, IntOffset(0, -scroll)
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "PageView.kt: Drawing strokes failed: ${e.message}", e)
                    showHint("Error drawing strokes", coroutineScope)
                }

            }
            Log.i(TAG, "Drew area in ${timeToDraw}ms")
        }
    }

    suspend fun simpleUpdateScroll(dragDelta: Int) {
        // Just update scroll, for debugging.
        Log.d(TAG, "Simple update scroll")
        var delta = (dragDelta / zoomLevel.value).toInt()
        if (scroll + delta < 0) delta = 0 - scroll

        DrawCanvas.waitForDrawingWithSnack()

        scroll += delta

        val redrawRect = Rect(
            0, 0, SCREEN_WIDTH, SCREEN_HEIGHT
        )
        val scrolledBitmap = createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, windowedBitmap.config!!)

        // Swap in the new zoomed bitmap
        windowedBitmap.recycle()
        windowedBitmap = scrolledBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)
        drawAreaScreenCoordinates(redrawRect)
        persistBitmapDebounced()
        saveToPersistLayer()

    }

    suspend fun updateScroll(dragDelta: Int) {
        Log.d(
            TAG,
            "Update scroll, dragDelta: $dragDelta, scroll: $scroll, zoomLevel.value: $zoomLevel.value"
        )
        // drag delta is in screen coordinates,
        // so we have to scale it back to page coordinates.
        var deltaInPageCord = (dragDelta / zoomLevel.value).toInt()
        if (scroll + deltaInPageCord < 0) deltaInPageCord = 0 - scroll

        // There is nothing to do, return.
        if (deltaInPageCord == 0) return

        // before scrolling, make sure that strokes are drawn.
        DrawCanvas.waitForDrawingWithSnack()

        scroll += deltaInPageCord
        // To avoid rounding errors, we just calculate it again.
        val movement = (deltaInPageCord * zoomLevel.value).toInt()


        // Shift the existing bitmap content
        val shiftedBitmap =
            createBitmap(windowedBitmap.width, windowedBitmap.height, windowedBitmap.config!!)
        val shiftedCanvas = Canvas(shiftedBitmap)
        shiftedCanvas.drawColor(Color.RED) //for debugging.
        shiftedCanvas.drawBitmap(windowedBitmap, 0f, -movement.toFloat(), null)

        // Swap in the shifted bitmap
        windowedBitmap.recycle() // Recycle old bitmap
        windowedBitmap = shiftedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)

        //add 1 of overlap, to eliminate rounding errors.
        val redrawRect =
            if (deltaInPageCord > 0)
                Rect(0, SCREEN_HEIGHT - movement - 5, SCREEN_WIDTH, SCREEN_HEIGHT)
            else
                Rect(0, 0, SCREEN_WIDTH, -movement + 1)
//        windowedCanvas.drawRect(
//            removeScroll(toPageCoordinates(redrawRect)),
//            Paint().apply { color = Color.RED })

        drawAreaScreenCoordinates(redrawRect)
        persistBitmapDebounced()
        saveToPersistLayer()
    }


    private fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
    ): Float {
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

            if (abs(newZoom - snapTarget) < ZOOM_SNAP_THRESHOLD) snapTarget else newZoom
        }
    }

    suspend fun updateZoom(scaleDelta: Float) {
        // TODO:
        // - Update only effected area if possible
        // - Find a better way to represent how much to zoom.
        Log.d(TAG, "Zoom: $scaleDelta")

        // Update the zoom factor
        val newZoomLevel = calculateZoomLevel(scaleDelta, zoomLevel.value)

        // If there's no actual zoom change, skip
        if (newZoomLevel == zoomLevel.value) {
            Log.d(TAG, "Zoom unchanged. Current level: ${zoomLevel.value}")
            return
        }
        Log.d(TAG, "New zoom level: $newZoomLevel")
        zoomLevel.value = newZoomLevel


        DrawCanvas.waitForDrawingWithSnack()

        // Create a scaled bitmap to represent zoomed view
        val scaledWidth = windowedCanvas.width
        val scaledHeight = windowedCanvas.height
        Log.d(TAG, "Canvas dimensions: width=$scaledWidth, height=$scaledHeight")
        Log.d(TAG, "Screen dimensions: width=$SCREEN_WIDTH, height=$SCREEN_HEIGHT")


        val zoomedBitmap = createBitmap(scaledWidth, scaledHeight, windowedBitmap.config!!)

        // Swap in the new zoomed bitmap
//        windowedBitmap.recycle()
// It causes race condition with init from persistent layer
        windowedBitmap = zoomedBitmap
        windowedCanvas.setBitmap(windowedBitmap)
        windowedCanvas.scale(zoomLevel.value, zoomLevel.value)


        // Redraw everything at new zoom level
        val redrawRect = Rect(0, 0, windowedBitmap.width, windowedBitmap.height)

        Log.d(TAG, "Redrawing full logical rect: $redrawRect")
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

        drawAreaScreenCoordinates(redrawRect)

        persistBitmapDebounced()
        saveToPersistLayer()
        Log.i(TAG, "Zoom and redraw completed")
    }


    // updates page setting in db, (for instance type of background)
// and redraws page to vew.
    fun updatePageSettings(page: Page) {
        AppRepository(context).pageRepository.update(page)
        pageFromDb = AppRepository(context).pageRepository.getById(id)
        drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
        persistBitmapDebounced()
    }

    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth != viewWidth || newHeight != viewHeight) {
            viewWidth = newWidth
            viewHeight = newHeight

            // Recreate bitmap and canvas with new dimensions
            windowedBitmap = createBitmap(viewWidth, viewHeight)
            windowedCanvas = Canvas(windowedBitmap)

            //Reset zoom level.
            zoomLevel.value = 1.0f
            drawAreaScreenCoordinates(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
            persistBitmapDebounced()
        }
    }

    private fun persistBitmapDebounced() {
        coroutineScope.launch {
            saveTopic.emit(Unit)
        }
    }

    private fun saveToPersistLayer() {
        coroutineScope.launch {
            AppRepository(context).pageRepository.updateScroll(id, scroll)
            pageFromDb = AppRepository(context).pageRepository.getById(id)
        }
    }


    fun applyZoom(point: IntOffset): IntOffset {
        return IntOffset(
            (point.x * zoomLevel.value).toInt(),
            (point.y * zoomLevel.value).toInt()
        )
    }

    fun removeZoom(point: IntOffset): IntOffset {
        return IntOffset(
            (point.x / zoomLevel.value).toInt(),
            (point.y / zoomLevel.value).toInt()
        )
    }

    private fun removeScroll(rect: Rect): Rect {
        return Rect(
            (rect.left.toFloat()).toInt(),
            ((rect.top - scroll).toFloat()).toInt(),
            (rect.right.toFloat()).toInt(),
            ((rect.bottom - scroll).toFloat()).toInt()
        )
    }

    fun toScreenCoordinates(rect: Rect): Rect {
        return Rect(
            (rect.left.toFloat() * zoomLevel.value).toInt(),
            ((rect.top - scroll).toFloat() * zoomLevel.value).toInt(),
            (rect.right.toFloat() * zoomLevel.value).toInt(),
            ((rect.bottom - scroll).toFloat() * zoomLevel.value).toInt()
        )
    }

    private fun toPageCoordinates(rect: Rect): Rect {
        return Rect(
            (rect.left.toFloat() / zoomLevel.value).toInt(),
            (rect.top.toFloat() / zoomLevel.value).toInt() + scroll,
            (rect.right.toFloat() / zoomLevel.value).toInt(),
            (rect.bottom.toFloat() / zoomLevel.value).toInt() + scroll
        )
    }
}