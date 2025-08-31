package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.SimplePointF
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.handleSelect
import com.ethran.notable.data.db.selectImage
import com.ethran.notable.data.db.selectImagesAndStrokes
import com.ethran.notable.editor.drawing.OpenGLRenderer
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.calculateBoundingBox
import com.ethran.notable.editor.utils.copyInput
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.getModifiedStrokeEndpoints
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.onSurfaceChanged
import com.ethran.notable.editor.utils.onSurfaceDestroy
import com.ethran.notable.editor.utils.onSurfaceInit
import com.ethran.notable.editor.utils.partialRefreshRegionOnce
import com.ethran.notable.editor.utils.penToStroke
import com.ethran.notable.editor.utils.pointsToPath
import com.ethran.notable.editor.utils.prepareForPartialUpdate
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.ethran.notable.editor.utils.resetScreenFreeze
import com.ethran.notable.editor.utils.restoreDefaults
import com.ethran.notable.editor.utils.setupSurface
import com.ethran.notable.editor.utils.toPageCoordinates
import com.ethran.notable.editor.utils.transformToLine
import com.ethran.notable.editor.utils.waitForEpdRefresh
import com.ethran.notable.io.uriToBitmap
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.showHint
import com.ethran.notable.utils.logCallStack
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.extension.isNotNull
import com.onyx.android.sdk.extension.isNull
import com.onyx.android.sdk.extension.isNullOrEmpty
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis


val pressure = EpdController.getMaxTouchPressure()

// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""

// TODO: Do not recreate surface on every page change
class DrawCanvas(
    context: Context,
    val coroutineScope: CoroutineScope,
    val state: EditorState,
    val page: PageView,
    val history: History
) : SurfaceView(context) {
    private val strokeHistoryBatch = mutableListOf<String>()
    private val logCanvasObserver = ShipBook.getLogger("CanvasObservers")
    private val log = ShipBook.getLogger("DrawCanvas")
    var lastStrokeEndTime: Long = 0
    //private val commitHistorySignal = MutableSharedFlow<Unit>()

    private var glRenderer = OpenGLRenderer(this)
    override fun onAttachedToWindow() {
        log.d("Attached to window")
        glRenderer = OpenGLRenderer(this@DrawCanvas)
        super.onAttachedToWindow()
        glRenderer.attachSurfaceView(this)
    }

    override fun onDetachedFromWindow() {
        log.d("Detached from window")
        glRenderer.release()
        super.onDetachedFromWindow()
    }

    var isErasing: Boolean = false

    companion object {
        var forceUpdate = MutableSharedFlow<Rect?>() // null for full redraw
        var refreshUi = MutableSharedFlow<Unit>()
        var refreshUiImmediately = MutableSharedFlow<Unit>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        var isDrawing = MutableSharedFlow<Boolean>()
        var restartAfterConfChange = MutableSharedFlow<Unit>()
        var eraserTouchPoint = MutableSharedFlow<Offset?>() //TODO: replace with proper solution

        // used for managing drawing state on regain focus
        val onFocusChange = MutableSharedFlow<Boolean>()

        // before undo we need to commit changes
        val commitHistorySignal = MutableSharedFlow<Unit>()
        val commitHistorySignalImmediately = MutableSharedFlow<Unit>()

        // used for checking if commit was completed
        var commitCompletion = CompletableDeferred<Unit>()

        // It might be bad idea, but plan is to insert graphic in this, and then take it from it
        // There is probably better way
        var addImageByUri = MutableStateFlow<Uri?>(null)
        var rectangleToSelect = MutableStateFlow<Rect?>(null)
        var drawingInProgress = Mutex()

        private suspend fun waitForDrawing() {
            Log.d(
                "DrawCanvas.waitForDrawing", "waiting"
            )
            val elapsed = measureTimeMillis {
                withTimeoutOrNull(3000) {
                    // Just to make sure wait 1ms before checking lock.
                    delay(1)
                    // Wait until drawingInProgress is unlocked before proceeding
                    while (drawingInProgress.isLocked) {
                        delay(5)
                    }
                } ?: Log.e(
                    "DrawCanvas.waitForDrawing",
                    "Timeout while waiting for drawing lock. Potential deadlock."
                )

            }
            when {
                elapsed > 3000 -> Log.e(
                    "DrawCanvas.waitForDrawing", "Exceeded timeout ($elapsed ms)"
                )

                elapsed > 100 -> Log.w("DrawCanvas.waitForDrawing", "Took too long: $elapsed ms")
                else -> Log.d("DrawCanvas.waitForDrawing", "Finished waiting in $elapsed ms")
            }

        }

        suspend fun waitForDrawingWithSnack() {
            if (drawingInProgress.isLocked) {
                val snack = SnackConf(text = "Waiting for drawing to finish…", duration = 60000)
                SnackState.Companion.globalSnackFlow.emit(snack)
                waitForDrawing()
                SnackState.Companion.cancelGlobalSnack.emit(snack.id)
            }
        }
    }

    fun getActualState(): EditorState {
        return this.state
    }

    @Suppress("RedundantOverride")
    private val inputCallback: RawInputCallback = object : RawInputCallback() {
        // Documentation: https://github.com/onyx-intl/OnyxAndroidDemo/blob/d3a1ffd3af231fe4de60a2a0da692c17cb35ce31/doc/Onyx-Pen-SDK.md#L40-L62
        // - pen : `onBeginRawDrawing()` -> `onRawDrawingTouchPointMoveReceived()` -> `onRawDrawingTouchPointListReceived()` -> `onEndRawDrawing()`
        // - erase :  `onBeginRawErasing()` -> `onRawErasingTouchPointMoveReceived()` -> `onRawErasingTouchPointListReceived()` -> `onEndRawErasing()`

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) {
            val currentLastStrokeEndTime = lastStrokeEndTime
            lastStrokeEndTime = System.currentTimeMillis()
            val startTime = System.currentTimeMillis()
            // sometimes UI will get refreshed and frozen before we draw all the strokes.
            // I think, its because of doing it in separate thread. Commented it for now, to
            // observe app behavior, and determine if it fixed this bug,
            // as I do not know reliable way to reproduce it
            // Need testing if it will be better to do in main thread on, in separate.
            // thread(start = true, isDaemon = false, priority = Thread.MAX_PRIORITY) {

            when (getActualState().mode) {
                Mode.Erase -> onRawErasingTouchPointListReceived(plist)
                Mode.Select -> {
                    thread {
                        val points =
                            copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)
                        handleSelect(
                            coroutineScope, this@DrawCanvas.page, getActualState(), points
                        )
                        val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }.toRect()
                        val padding = 10
                        val dirtyRect = Rect(
                            boundingBox.left - padding,
                            boundingBox.top - padding,
                            boundingBox.right + padding,
                            boundingBox.bottom + padding
                        )
                        refreshUi(dirtyRect)
                    }
                }

                // After each stroke ends, we draw it on our canvas.
                // This way, when screen unfreezes the strokes are shown.
                // When in scribble mode, ui want be refreshed.
                // If we UI will be refreshed and frozen before we manage to draw
                // strokes want be visible, so we need to ensure that it will be done
                // before anything else happens.
                Mode.Line -> {
                    coroutineScope.launch(Dispatchers.Main.immediate) {
                        drawingInProgress.withLock {
                            val lock = System.currentTimeMillis()
                            log.d("lock obtained in ${lock - startTime} ms")


                            val (startPoint, endPoint) = getModifiedStrokeEndpoints(
                                plist.points,
                                page.scroll,
                                page.zoomLevel.value
                            )
                            val linePoints = transformToLine(startPoint, endPoint)

                            handleDraw(
                                this@DrawCanvas.page,
                                strokeHistoryBatch,
                                getActualState().penSettings[getActualState().pen.penName]!!.strokeSize,
                                getActualState().penSettings[getActualState().pen.penName]!!.color,
                                getActualState().pen,
                                linePoints
                            )

                            coroutineScope.launch(Dispatchers.Default) {
                                val dirtyRect = Rect(
                                    min(startPoint.x, endPoint.x).toInt(),
                                    min(startPoint.y, endPoint.y).toInt(),
                                    max(startPoint.x, endPoint.x).toInt(),
                                    max(startPoint.y, endPoint.y).toInt()
                                )
//                                partialRefreshRegionOnce(this@DrawCanvas, dirtyRect)
                                refreshUi(dirtyRect)
                                commitHistorySignal.emit(Unit)
                            }
                        }

                    }
                }

                Mode.Draw -> {
                    coroutineScope.launch(Dispatchers.Main.immediate) {
                        drawingInProgress.withLock {
                            val lock = System.currentTimeMillis()
                            log.d("lock obtained in ${lock - startTime} ms")

                            // Thread.sleep(1000)
                            // transform points to page space
                            val scaledPoints =
                                copyInput(plist.points, page.scroll, page.zoomLevel.value)

                            val erasedByScribbleDirtyRect = handleScribbleToErase(
                                page,
                                scaledPoints,
                                history,
                                getActualState().pen,
                                currentLastStrokeEndTime
                            )
                            if (erasedByScribbleDirtyRect.isNullOrEmpty()) {
                                log.d("Drawing...")
                                // draw the stroke
                                handleDraw(
                                    this@DrawCanvas.page,
                                    strokeHistoryBatch,
                                    getActualState().penSettings[getActualState().pen.penName]!!.strokeSize,
                                    getActualState().penSettings[getActualState().pen.penName]!!.color,
                                    getActualState().pen,
                                    scaledPoints
                                )
                            } else {
                                log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                                drawCanvasToView(erasedByScribbleDirtyRect)
                                // we need to wait before refreshing, as onyx library has its own buffer that needs to be updated. Otherwise we will refresh to correct, then  incorrect and then correct state.
                                awaitFrame()
                                awaitFrame()
                                partialRefreshRegionOnce(
                                    this@DrawCanvas,
                                    erasedByScribbleDirtyRect,
                                    touchHelper
                                )

                            }

                        }
                        coroutineScope.launch(Dispatchers.Default) {
                            commitHistorySignal.emit(Unit)
                        }
                    }
                }
            }
        }

        // Handle button/eraser tip of the pen:
        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            prepareForPartialUpdate(this@DrawCanvas, touchHelper)
            log.d("Eraser Mode")
            isErasing = true
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            restoreDefaults(this@DrawCanvas)
            glRenderer.clearPointBuffer()
            glRenderer.frontBufferRenderer?.cancel()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) {
            isErasing = false

            if (plist == null) return
            plist.points

            val points = copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)

            val padding = 10
            val boundingBox = (calculateBoundingBox(plist.points) { Pair(it.x, it.y) }).toRect()
            val strokeArea = Rect(
                boundingBox.left - padding,
                boundingBox.top - padding,
                boundingBox.right + padding,
                boundingBox.bottom + padding
            )
            refreshUi(strokeArea)

            val zoneEffected = handleErase(
                this@DrawCanvas.page,
                history,
                points,
                eraser = getActualState().eraser
            )
            if (zoneEffected != null)
                refreshUi(zoneEffected)
        }

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
//            if (p0 == null) return
        }

        override fun onPenUpRefresh(refreshRect: RectF?) {
            super.onPenUpRefresh(refreshRect)
        }

        override fun onPenActive(point: TouchPoint?) {
            super.onPenActive(point)
        }
    }

    private val touchHelper by lazy {
        referencedSurfaceView = this.hashCode().toString()
        TouchHelper.create(this, inputCallback)
    }

    fun init() {
        log.i("Initializing Canvas")
        glRenderer.attachSurfaceView(this)

        // This does not work, as EditorGestureReceiver is stealing all the events.
        setOnTouchListener(glRenderer.onTouchListener)

        val surfaceCallback: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                log.i("surface created $holder")
                // set up the drawing surface
                updateActiveSurface()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
                // Only act if actual dimensions changed
                if (page.viewWidth == width && page.viewHeight == height) return

                logCanvasObserver.v("Surface dimension changed!")

                // Update page dimensions, redraw and refresh
                page.updateDimensions(width, height)
                updateActiveSurface()
                onSurfaceChanged(this@DrawCanvas)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                log.i(
                    "surface destroyed ${
                        this@DrawCanvas.hashCode()
                    } - ref $referencedSurfaceView"
                )
                holder.removeCallback(this)
                if (referencedSurfaceView == this@DrawCanvas.hashCode().toString()) {
                    touchHelper.closeRawDrawing()
                }
                onSurfaceDestroy(this@DrawCanvas, touchHelper)
            }
        }

        this.holder.addCallback(surfaceCallback)

    }

    fun registerObservers() {

        coroutineScope.launch {
            refreshUiImmediately.collect {
                logCanvasObserver.v("Refreshing UI!")
                val zoneToRedraw = Rect(0, 0, page.viewWidth, page.viewHeight)
                refreshUi(zoneToRedraw)
            }
        }

        // observe forceUpdate, takes rect in screen coordinates
        // given null it will redraw whole page
        // BE CAREFUL: partial update is not tested fairly -- might not work in some situations.
        coroutineScope.launch(Dispatchers.Main.immediate) {
            forceUpdate.collect { dirtyRectangle ->
                // On loading, make sure that the loaded strokes are visible to it.
                logCanvasObserver.v("Force update, zone: $dirtyRectangle")
                val zoneToRedraw = dirtyRectangle ?: Rect(0, 0, page.viewWidth, page.viewHeight)
                page.drawAreaScreenCoordinates(zoneToRedraw)
                launch(Dispatchers.Default) {
                    if (dirtyRectangle.isNull()) refreshUiSuspend()
                    else {
                        partialRefreshRegionOnce(this@DrawCanvas, zoneToRedraw, touchHelper)
                    }
                }
            }
        }

        // observe refreshUi
        coroutineScope.launch(Dispatchers.Default) {
            refreshUi.collect {
                logCanvasObserver.v("Refreshing UI!")
                refreshUiSuspend()
            }
        }

        coroutineScope.launch {
            onFocusChange.collect { hasFocus ->
                logCanvasObserver.v("App has focus: $hasFocus")
                if (hasFocus) {
                    state.checkForSelectionsAndMenus()
                    drawCanvasToView(null)
                } else {
                    isDrawing.emit(false)
                }
            }
        }
        coroutineScope.launch {
            page.zoomLevel.drop(1).collect {
                logCanvasObserver.v("zoom level change: ${page.zoomLevel.value}")
                updatePenAndStroke()
            }
        }

        coroutineScope.launch {
            isDrawing.collect {
                logCanvasObserver.v("drawing state changed to $it!")
                state.isDrawing = it
            }
        }


        coroutineScope.launch {
            addImageByUri.drop(1).collect { imageUri ->
                if (imageUri != null) {
                    logCanvasObserver.v("Received image: $imageUri")
                    handleImage(imageUri)
                } //else
//                    log.i(  "Image uri is empty")
            }
        }
        coroutineScope.launch {
            rectangleToSelect.drop(1).collect {
                if (it != null) {
                    logCanvasObserver.v("Area to Select (screen): $it")
                    selectRectangle(it)
                }
            }
        }

        coroutineScope.launch {
            restartAfterConfChange.collect {
                logCanvasObserver.v("Configuration changed!")
                init()
                drawCanvasToView(null)
            }
        }
        coroutineScope.launch {
            eraserTouchPoint.collect { p ->
                if (!isErasing) {
                    logCanvasObserver.v("Didn't draw point: $p -- eraser is not active")
                    return@collect
                }
                logCanvasObserver.v("collected: $p")
                if (p == null) return@collect
                val strokePoint = StrokePoint(
                    x = p.x,
                    y = p.y,
                    pressure = 1f,
                    size = 10f,
                    tiltX = 0,
                    tiltY = 0,
                    timestamp = 0,
                )
                glRenderer.frontBufferRenderer?.renderFrontBufferedLayer(strokePoint)
            }
        }

        // observe pen and stroke size
        coroutineScope.launch {
            snapshotFlow { state.pen }.drop(1).collect {
                logCanvasObserver.v("pen change: ${state.pen}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.penSettings.toMap() }.drop(1).collect {
                logCanvasObserver.v("pen settings change: ${state.penSettings}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.eraser }.drop(1).collect {
                logCanvasObserver.v("eraser change: ${state.eraser}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }

        // observe is drawing
        coroutineScope.launch {
            snapshotFlow { state.isDrawing }.drop(1).collect {
                logCanvasObserver.v("isDrawing change to $it")
                // We need to close all menus
                if (it) {
//                    logCallStack("Closing all menus")
                    state.closeAllMenus()
//                    EpdController.waitForUpdateFinished() // it does not work.
                    waitForEpdRefresh()
                }
                updateIsDrawing()
            }
        }

        // observe toolbar open
        coroutineScope.launch {
            snapshotFlow { state.isToolbarOpen }.drop(1).collect {
                logCanvasObserver.v("istoolbaropen change: ${state.isToolbarOpen}")
                updateActiveSurface()
                updatePenAndStroke()
                refreshUi(null)
            }
        }

        // observe mode
        coroutineScope.launch {
            snapshotFlow { getActualState().mode }.drop(1).collect {
                logCanvasObserver.v("mode change: ${getActualState().mode}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }

        coroutineScope.launch {
            //After 500ms add to history strokes
            commitHistorySignal.debounce(500).collect {
                logCanvasObserver.v("Commiting to history")
                commitToHistory()
            }
        }
        coroutineScope.launch {
            commitHistorySignalImmediately.collect {
                commitToHistory()
                commitCompletion.complete(Unit)
            }
        }

    }

    private suspend fun selectRectangle(rectToSelect: Rect) {
        val inPageCoordinates = toPageCoordinates(rectToSelect, page.zoomLevel.value, page.scroll)

        val imagesToSelect = PageDataManager.getImagesInRectangle(inPageCoordinates, page.id)
        val strokesToSelect = PageDataManager.getStrokesInRectangle(inPageCoordinates, page.id)
        if (imagesToSelect.isNotNull() && strokesToSelect.isNotNull()) {
            rectangleToSelect.value = null
            if (imagesToSelect.isNotEmpty() || strokesToSelect.isNotEmpty()) {
                selectImagesAndStrokes(coroutineScope, page, state, imagesToSelect, strokesToSelect)
            } else {
                SnackState.Companion.globalSnackFlow.emit(
                    SnackConf(
                        text = "There isn't anything.",
                        duration = 3000,
                    )
                )
            }
        } else SnackState.Companion.globalSnackFlow.emit(
            SnackConf(
                text = "Page isn't loaded!",
                duration = 3000,
            )
        )

    }

    private fun commitToHistory() {
        if (strokeHistoryBatch.isNotEmpty()) history.addOperationsToHistory(
            operations = listOf(
                Operation.DeleteStroke(strokeHistoryBatch.map { it })
            )
        )
        strokeHistoryBatch.clear()
        //testing if it will help with undo hiding strokes.
        drawCanvasToView(null)
    }

    private fun refreshUi(dirtyRect: Rect?) {
        log.d("refreshUi")

        // post what page drawn to visible surface
        drawCanvasToView(dirtyRect)
        if (drawingInProgress.isLocked) log.w("Drawing is still in progress there might be a bug.")

        // Use only if you have confidence that there are no strokes being drawn at the moment
        if (!state.isDrawing) {
            log.w("Not in drawing mode, skipping unfreezing")
            return
        }
        // reset screen freeze
        resetScreenFreeze(touchHelper)
    }

    private suspend fun refreshUiSuspend() {
        // Do not use, if refresh need to be preformed without delay.
        // This function waits for strokes to be fully rendered.
        if (!state.isDrawing) {
            waitForDrawing()
            drawCanvasToView(null)
            log.w("Not in drawing mode -- refreshUi ")
            return
        }
        if (Looper.getMainLooper().isCurrentThread) {
            log.w(
                "refreshUiSuspend() is called from the main thread."
            )
            logCallStack("refreshUiSuspend_main_thread")
        } else
            log.v(
                "refreshUiSuspend() is called from the non-main thread."
            )
        waitForDrawing()
        drawCanvasToView(null)
        resetScreenFreeze(touchHelper)
    }

    private fun handleImage(imageUri: Uri) {
        // Convert the image to a software-backed bitmap
        val imageBitmap = uriToBitmap(context, imageUri)?.asImageBitmap()
        if (imageBitmap == null)
            showHint("There was an error during image processing.", coroutineScope)
        val softwareBitmap =
            imageBitmap?.asAndroidBitmap()?.copy(Bitmap.Config.ARGB_8888, true)
        if (softwareBitmap != null) {
            addImageByUri.value = null

            // Get the image dimensions
            val imageWidth = softwareBitmap.width
            val imageHeight = softwareBitmap.height

            // Calculate the center position for the image relative to the page dimensions
            val centerX = (page.viewWidth - imageWidth) / 2 + page.scroll.x.toInt()
            val centerY = (page.viewHeight - imageHeight) / 2 + page.scroll.y.toInt()
            val imageToSave = Image(
                x = centerX,
                y = centerY,
                height = imageHeight,
                width = imageWidth,
                uri = imageUri.toString(),
                pageId = page.id
            )
            drawImage(
                context, page.windowedCanvas, imageToSave, -page.scroll
            )
            selectImage(coroutineScope, page, state, imageToSave)
            // image will be added to database when released, the same as with paste element.
            state.selectionState.placementMode = PlacementMode.Paste
            // make sure, that after regaining focus, we wont go back to drawing mode
        } else {
            // Handle cases where the bitmap could not be created
            Log.e("ImageProcessing", "Failed to create software bitmap from URI.")
        }
    }


    fun drawCanvasToView(dirtyRect: Rect?) {
        val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)

        // Lock the canvas only for the dirtyRect region
        val canvas = this.holder.lockCanvas(zoneToRedraw) ?: return

        canvas.drawBitmap(page.windowedBitmap, zoneToRedraw, zoneToRedraw, Paint())

        if (getActualState().mode == Mode.Select) {
            // render selection, but only within dirtyRect
            getActualState().selectionState.firstPageCut?.let { cutPoints ->
                log.i("render cut")
                val path = pointsToPath(cutPoints.map {
                    SimplePointF(
                        it.x - page.scroll.x, it.y - page.scroll.y
                    )
                })
                canvas.drawPath(path, selectPaint)
            }
        }
        // finish rendering
        this.holder.unlockCanvasAndPost(canvas)
    }

    private suspend fun updateIsDrawing() {
        log.i("Update is drawing: ${state.isDrawing}")
        if (state.isDrawing) {
            touchHelper.setRawDrawingEnabled(true)
        } else {
            // Check if drawing is completed
            waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvasToView(null)
            touchHelper.setRawDrawingEnabled(false)
        }
    }

    fun updatePenAndStroke() {
        // it takes around 11 ms to run on Note 4c.
        log.i("Update pen and stroke")
        when (state.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Line -> touchHelper.setStrokeStyle(penToStroke(state.pen))
                ?.setStrokeWidth(state.penSettings[state.pen.penName]!!.strokeSize * page.zoomLevel.value)
                ?.setStrokeColor(state.penSettings[state.pen.penName]!!.color)

            Mode.Erase -> {
                when (state.eraser) {
                    Eraser.PEN -> touchHelper.setStrokeStyle(penToStroke(Pen.MARKER))
                        ?.setStrokeWidth(30f)
                        ?.setStrokeColor(Color.GRAY)

                    Eraser.SELECT -> touchHelper.setStrokeStyle(penToStroke(Pen.BALLPEN))
                        ?.setStrokeWidth(3f)
                        ?.setStrokeColor(Color.GRAY)
                }
            }

            Mode.Select -> touchHelper.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)
        }
    }

    fun updateActiveSurface() {
        // Takes at least 50ms on Note 4c,
        // and I don't think that we need it immediately
        log.i("Update editable surface")
        coroutineScope.launch {
            onSurfaceInit(this@DrawCanvas)
            val toolbarHeight =
                if (state.isToolbarOpen) convertDpToPixel(40.dp, context).toInt() else 0
            setupSurface(this@DrawCanvas, touchHelper, toolbarHeight)
        }
    }

    fun restoreCanvas(dirtyRect: Rect) {
        post {
            val holder = this@DrawCanvas.holder
            var surfaceCanvas: Canvas? = null
            try {
                // 2. Then: draw the bitmap onto the visible surface
                surfaceCanvas = holder.lockCanvas(dirtyRect)
                surfaceCanvas.drawBitmap(page.windowedBitmap, dirtyRect, dirtyRect, null)
            } catch (e: Exception) {
                Log.e("DrawCanvas", "Canvas lock failed: ${e.message}")
            } finally {
                if (surfaceCanvas != null) {
                    holder.unlockCanvasAndPost(surfaceCanvas)
                }
                // 3. Trigger partial refresh
                refreshScreenRegion(this@DrawCanvas, dirtyRect)
            }
        }
    }

}