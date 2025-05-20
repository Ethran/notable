package com.ethran.notable.classes


import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
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
    private var strokeInitialLoadingJob: Job? = null
    private var strokeRemainingLoadingJob: Job? = null

    private var snack: SnackConf? = null

    var windowedBitmap = createBitmap(viewWidth, viewHeight)
    var windowedCanvas = Canvas(windowedBitmap)
    var strokes = listOf<Stroke>()
    private var strokesById: HashMap<String, Stroke> = hashMapOf()
    var images = listOf<Image>()
    private var imagesById: HashMap<String, Image> = hashMapOf()
    var scroll by mutableIntStateOf(0) // is observed by ui
    val scrolable: Boolean
        get() = when (pageFromDb?.backgroundType) {
            "native", null -> true
            "coverImage" -> false
            else -> true
        }
    private val saveTopic = MutableSharedFlow<Unit>()

    var height by mutableIntStateOf(viewHeight) // is observed by ui

    var pageFromDb = AppRepository(context).pageRepository.getById(id)

    private var dbStrokes = AppDatabase.getDatabase(context).strokeDao()
    private var dbImages = AppDatabase.getDatabase(context).ImageDao()

    // Save bitmap, to avoid loading from disk every time.
    data class CachedBackground(val bitmap: Bitmap?, val path: String, val pageNumber: Int)

    private var currentBackground = CachedBackground(null, "", 0)

    /*
        If pageNumber is -1, its assumed that the background is image type.
     */
    fun getOrLoadBackground(filePath: String, pageNumber: Int): Bitmap? {
        if (currentBackground.path != filePath || currentBackground.pageNumber != pageNumber) {
            currentBackground =
                CachedBackground(loadBackgroundBitmap(filePath, pageNumber), filePath, pageNumber)
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

        drawBg(
            context, windowedCanvas, pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
            pageFromDb?.background ?: "blank", scroll, 1f, this
        )
        val isCached = loadBitmap()
        initFromPersistLayer(isCached)
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

    private fun initFromPersistLayer(isCached: Boolean) {
        cleanJob()
        // pageInfos
        // TODO page might not exists yet
        val page = AppRepository(context).pageRepository.getById(id)
        scroll = page!!.scroll
        if (strokeInitialLoadingJob?.isActive == true || strokeRemainingLoadingJob?.isActive == true) {
            Log.w(TAG, "Strokes are still loading, trying to cancel and resume")
            cleanJob()
        }
        strokeInitialLoadingJob = coroutineScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val pageWithImages = AppRepository(context).pageRepository.getWithImageById(id)
            val viewRectangle = Rect(0, 0, windowedCanvas.width, windowedCanvas.height)
            //for some reason, scroll is always 0 hare, so take it directly from db
            val viewRectangleWithScroll = Rect(
                viewRectangle.left,
                viewRectangle.top + page.scroll,
                viewRectangle.right,
                viewRectangle.bottom + page.scroll
            )
            strokes =
                AppRepository(context).strokeRepository.getStrokesInRectangle(
                    viewRectangleWithScroll,
                    id
                )

            images = pageWithImages.images
            indexImages()
            val indexingJob = coroutineScope.launch(Dispatchers.Default) {
                indexStrokes()
            }


            val fromDatabase = System.currentTimeMillis()
            Log.d(TAG, "Strokes fetch from database, in ${fromDatabase - startTime}}")
            if (!isCached) {
                // we draw and cache
//                Log.d(TAG, "We do not have cashed.")
                drawBg(
                    context,
                    windowedCanvas,
                    pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
                    pageFromDb?.background ?: "blank",
                    scroll, 1f, this@PageView
                )
                drawArea(viewRectangle)
                persistBitmap()
                persistBitmapThumbnail()
                DrawCanvas.refreshUi.emit(Unit)
            }

            // Fetch all remaining strokes
            strokeRemainingLoadingJob = coroutineScope.launch(Dispatchers.IO) {
                val timeToLoad = measureTimeMillis {
                    // Set duration as safety guard: in 60 s all strokes should be loaded
                    snack = SnackConf(text = "Loading strokes...", duration = 60000)
                    SnackState.globalSnackFlow.emit(snack!!)
//                    Thread.sleep(50000)
                    val pageWithStrokes =
                        AppRepository(context).pageRepository.getWithStrokeByIdSuspend(id)
                    strokes = pageWithStrokes.strokes
                    indexingJob.cancelAndJoin()
                    indexStrokes()
                    computeHeight()
                    snack?.let { SnackState.cancelGlobalSnack.emit(it.id) }
                }
                Log.d(TAG, "All strokes loaded in $timeToLoad ms")
                // Ensure strokes are fully loaded and visible before drawing them
                // Switching to the Main thread guarantees that `strokes = pageWithStrokes.strokes`
                // has completed and is accessible for rendering.
                // Or at least I hope it does.
                launch(Dispatchers.Main) {
                    //required to ensure that everything is visible by draw area.
                    launch(Dispatchers.Default) {
                        Log.d(TAG, "Strokes remaining loaded")
                        drawArea(viewRectangle)
                        DrawCanvas.refreshUi.emit(Unit)
                    }
                }
            }
            Log.d(TAG, "Strokes drawn, in ${System.currentTimeMillis() - fromDatabase}")
        }

        //TODO: Images loading
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

    private fun loadBitmap(): Boolean {
        val imgFile = File(context.filesDir, "pages/previews/full/$id")
        val imgBitmap: Bitmap?
        if (imgFile.exists()) {
            imgBitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            if (imgBitmap != null) {
                windowedCanvas.drawBitmap(imgBitmap, 0f, 0f, Paint())
                Log.i(TAG, "Page rendered from cache")
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
        strokeInitialLoadingJob?.cancel()
        strokeRemainingLoadingJob?.cancel()
    }

    /*
        Cancel loading strokes, and save bitmap to disk
    */
    fun onDispose() {
        cleanJob()
        persistBitmap()
        persistBitmapThumbnail()
    }

    // ignored strokes are used in handleSelect
    fun drawArea(
        area: Rect,
        ignoredStrokeIds: List<String> = listOf(),
        ignoredImageIds: List<String> = listOf(),
        canvas: Canvas? = null
    ) {
        val activeCanvas = canvas ?: windowedCanvas
        val pageArea = Rect(
            area.left,
            area.top + scroll,
            area.right,
            area.bottom + scroll
        )


        activeCanvas.withClip(area) {
            drawColor(Color.BLACK)


            val timeToDraw = measureTimeMillis {
                drawBg(
                    context, this, pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
                    pageFromDb?.background ?: "blank", scroll, 1f, this@PageView
                )
                val appSettings = GlobalAppSettings.current

                if (appSettings?.debugMode == true) {
//              Draw the gray edge of the rectangle
                    val redPaint = Paint().apply {
                        color = Color.GRAY
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                    }
                    drawRect(area, redPaint)
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

    suspend fun updateScroll(_delta: Int) {
        var delta = _delta
        if (scroll + delta < 0) delta = 0 - scroll

        // There is nothing to do, return.
        if (delta == 0) return

        // before scrolling, make sure that strokes are drawn.
        DrawCanvas.waitForDrawingWithSnack()

        scroll += delta


        // Shift the existing bitmap content
        val shiftedBitmap =
            createBitmap(windowedBitmap.width, windowedBitmap.height, windowedBitmap.config!!)
        val shiftedCanvas = Canvas(shiftedBitmap)
        shiftedCanvas.drawBitmap(windowedBitmap, 0f, -delta.toFloat(), null)

        // Swap in the shifted bitmap
        windowedBitmap.recycle() // Recycle old bitmap
        windowedBitmap = shiftedBitmap
        windowedCanvas.setBitmap(windowedBitmap)


        // Calculate area to redraw
        val redrawTop = if (delta > 0) windowedBitmap.height - delta else 0
        val redrawBottom = redrawTop + abs(delta)

        val redrawRect = Rect(
            0,
            redrawTop,
            windowedBitmap.width,
            redrawBottom.coerceAtMost(windowedBitmap.height)
        )

        // Redraw the new/invalidated area (background + strokes)
        drawBg(
            context,
            windowedCanvas,
            pageFromDb?.getBackgroundType() ?: BackgroundType.Native,
            pageFromDb?.background ?: "blank",
            scroll,
            1f,
            this,
            redrawRect
        )

        drawArea(redrawRect)

        persistBitmapDebounced()
        saveToPersistLayer()
    }

    // updates page setting in db, (for instance type of background)
    // and redraws page to vew.
    fun updatePageSettings(page: Page) {
        AppRepository(context).pageRepository.update(page)
        pageFromDb = AppRepository(context).pageRepository.getById(id)
        drawArea(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
        persistBitmapDebounced()
    }

    fun updateDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth != viewWidth || newHeight != viewHeight) {
            viewWidth = newWidth
            viewHeight = newHeight

            // Recreate bitmap and canvas with new dimensions
            windowedBitmap = createBitmap(viewWidth, viewHeight)
            windowedCanvas = Canvas(windowedBitmap)
            drawArea(Rect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT))
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
}

