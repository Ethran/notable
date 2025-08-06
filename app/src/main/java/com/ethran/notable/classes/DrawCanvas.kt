package com.ethran.notable.classes

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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
import com.ethran.notable.db.Image
import com.ethran.notable.db.StrokePoint
import com.ethran.notable.db.handleSelect
import com.ethran.notable.db.selectImage
import com.ethran.notable.db.selectImagesAndStrokes
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.Eraser
import com.ethran.notable.utils.History
import com.ethran.notable.utils.Mode
import com.ethran.notable.utils.Operation
import com.ethran.notable.utils.Pen
import com.ethran.notable.utils.PlacementMode
import com.ethran.notable.utils.SimplePointF
import com.ethran.notable.utils.calculateBoundingBox
import com.ethran.notable.utils.convertDpToPixel
import com.ethran.notable.utils.copyInput
import com.ethran.notable.utils.copyInputToSimplePointF
import com.ethran.notable.utils.drawEraserStroke
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.handleDraw
import com.ethran.notable.utils.handleErase
import com.ethran.notable.utils.handleScribbleToErase
import com.ethran.notable.utils.penToStroke
import com.ethran.notable.utils.pointsToPath
import com.ethran.notable.utils.refreshScreenRegion
import com.ethran.notable.utils.selectPaint
import com.ethran.notable.utils.toPageCoordinates
import com.ethran.notable.utils.transformToLine
import com.ethran.notable.utils.uriToBitmap
import com.ethran.notable.utils.waitForEpdRefresh
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.extension.isNotNull
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


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
    private val log =  ShipBook.getLogger("DrawCanvas")
    var lastStrokeEndTime: Long = 0
    //private val commitHistorySignal = MutableSharedFlow<Unit>()

    companion object {
        var forceUpdate = MutableSharedFlow<Rect?>() // null for full redraw
        var refreshUi = MutableSharedFlow<Unit>()
        var isDrawing = MutableSharedFlow<Boolean>()
        var restartAfterConfChange = MutableSharedFlow<Unit>()

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
            withTimeoutOrNull(3000) {
                // Just to make sure wait 1ms before checking lock.
                delay(1)
                // Wait until drawingInProgress is unlocked before proceeding
                while (drawingInProgress.isLocked) {
                    delay(5)
                }
            } ?: Log.e("DrawCanvas.waitForDrawing", "Timeout while waiting for drawing lock. Potential deadlock.")
        }

        suspend fun waitForDrawingWithSnack() {
            if (drawingInProgress.isLocked) {
                val snack = SnackConf(text = "Waiting for drawing to finish…", duration = 60000)
                SnackState.globalSnackFlow.emit(snack)
                waitForDrawing()
                SnackState.cancelGlobalSnack.emit(snack.id)
            }
        }

        suspend fun waitForObservers(){
            delay(25)
            // TODO: Find proper solution
        }
    }

    fun getActualState(): EditorState {
        return this.state
    }

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

            if (getActualState().mode == Mode.Draw || getActualState().mode == Mode.Line) {
//                val newThread = System.currentTimeMillis()
//                log.d( "Got to new thread ${Thread.currentThread().name}, in ${newThread - startTime}}")
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    // After each stroke ends, we draw it on our canvas.
                    // This way, when screen unfreezes the strokes are shown.
                    // When in scribble mode, ui want be refreshed.
                    // If we UI will be refreshed and frozen before we manage to draw
                    // strokes want be visible, so we need to ensure that it will be done
                    // before anything else happens.
                    drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d(  "lock obtained in ${lock - startTime} ms")

//                        Thread.sleep(1000)
                        // transform points to page space
                        val scaledPoints =
                            if (getActualState().mode == Mode.Line)
                                copyInput(
                                    transformToLine(plist.points),
                                    page.scroll,
                                    page.zoomLevel.value
                                )
                            else
                                copyInput(plist.points, page.scroll, page.zoomLevel.value)

                        val erasedByScribble = handleScribbleToErase(
                            page,
                            scaledPoints,
                            history,
                            getActualState().pen,
                            currentLastStrokeEndTime
                        )
                        if (!erasedByScribble) {
                            // draw the stroke
                            handleDraw(
                            this@DrawCanvas.page,
                            strokeHistoryBatch,
                            getActualState().penSettings[getActualState().pen.penName]!!.strokeSize,
                            getActualState().penSettings[getActualState().pen.penName]!!.color,
                            getActualState().pen,
                                scaledPoints
                            )
                        }
                        if (getActualState().mode == Mode.Line || erasedByScribble)
                            refreshUi()
//                        val drawEndTime = System.currentTimeMillis()
//                        log.d(  "Drawing operation took ${drawEndTime - startTime} ms")

                    }
                    coroutineScope.launch {
                        commitHistorySignal.emit(Unit)
                    }

//                    val endTime = System.currentTimeMillis()
//                    log.d( "onRawDrawingTouchPointListReceived completed in ${endTime - startTime} ms")

                }
            } else thread {
                val points =
                    copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)
                if (getActualState().mode == Mode.Erase) {
                    handleErase(
                        this@DrawCanvas.page,
                        history,
                        points,
                        eraser = getActualState().eraser
                    )
                    drawCanvasToView()
                    refreshUi()
                }

                if (getActualState().mode == Mode.Select) {
                    handleSelect(
                        coroutineScope,
                        this@DrawCanvas.page,
                        getActualState(),
                        points
                    )
                    drawCanvasToView()
                    refreshUi()
                }
            }
        }

        // Handle button/eraser tip of the pen:

        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            EpdController.setViewDefaultUpdateMode(
                this@DrawCanvas,
                UpdateMode.HAND_WRITING_REPAINT_MODE
            )
            EpdController.enablePost(this@DrawCanvas, 1)
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            EpdController.resetViewUpdateMode(this@DrawCanvas)
            queue.clear()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) {
            if (plist == null) return

            plist.points
            // First return screen to previous state
            val padding = 10
            val boundingBox = (calculateBoundingBox(plist.points) { Pair(it.x, it.y) }).toRect()
            val dirtyRect = Rect(
                boundingBox.left - padding,
                boundingBox.top - padding,
                boundingBox.right + padding,
                boundingBox.bottom + padding
            )
            restoreCanvas(dirtyRect)

            val points = copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)
            handleErase(
                this@DrawCanvas.page,
                history,
                points,
                eraser = getActualState().eraser
            )
            drawCanvasToView()
            refreshUi()
        }

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
            if (p0 == null) return
            drawEraserIndicator(p0)
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
        log.i(  "Initializing Canvas")

        val surfaceCallback: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                log.i(  "surface created $holder")
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
            }
        }

        this.holder.addCallback(surfaceCallback)

    }

    fun registerObservers() {

        coroutineScope.launch {
            onFocusChange.collect { hasFocus ->
               logCanvasObserver.v("App has focus: $hasFocus")
                if (hasFocus) {
                    state.checkForSelectionsAndMenus()
                    drawCanvasToView()
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

        // observe forceUpdate, takes rect in screen coordinates
        // given null it will redraw whole page
        coroutineScope.launch {
            forceUpdate.collect { zoneAffected ->
                // On loading, make sure that the loaded strokes are visible to it.
                logCanvasObserver.v("Force update, zone: $zoneAffected")
                val zoneToRedraw = zoneAffected ?: Rect(0, 0, page.viewWidth, page.viewHeight)
                page.drawAreaScreenCoordinates(zoneToRedraw)
                refreshUiSuspend()
            }
        }

        // observe refreshUi
        coroutineScope.launch {
            refreshUi.collect {
                logCanvasObserver.v("Refreshing UI!")
                refreshUiSuspend()
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
                drawCanvasToView()
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
                refreshUi()
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
                SnackState.globalSnackFlow.emit(
                    SnackConf(
                        text = "There isn't anything.",
                        duration = 3000,
                    )
                )
            }
        } else SnackState.globalSnackFlow.emit(
            SnackConf(
                text = "Page isn't loaded!",
                duration = 3000,
            )
        )

    }

    private fun commitToHistory() {
        if (strokeHistoryBatch.size > 0) history.addOperationsToHistory(
            operations = listOf(
                Operation.DeleteStroke(strokeHistoryBatch.map { it })
            )
        )
        strokeHistoryBatch.clear()
        //testing if it will help with undo hiding strokes.
        drawCanvasToView()
    }

    private fun refreshUi() {
        log.d(  "refreshUi")
        // Use only if you have confidence that there are no strokes being drawn at the moment
        if (!state.isDrawing) {
            log.w(  "Not in drawing mode, skipping refreshUI")
            return
        }
        if (drawingInProgress.isLocked)
            log.w(  "Drawing is still in progress there might be a bug.")

        drawCanvasToView()

        // reset screen freeze
        // if in scribble mode, the screen want refresh
        // So to update interface we need to disable, and re-enable
        touchHelper.setRawDrawingEnabled(false)
        touchHelper.setRawDrawingEnabled(true)
        // screen won't freeze until you actually stoke
    }

    private suspend fun refreshUiSuspend() {
        // Do not use, if refresh need to be preformed without delay.
        // This function waits for strokes to be fully rendered.
        if (!state.isDrawing) {
            waitForDrawing()
            drawCanvasToView()
            log.w(  "Not in drawing mode -- refreshUi ")
            return
        }
        if (Looper.getMainLooper().isCurrentThread) {
            log.i(  "refreshUiSuspend() is called from the main thread."
            )
        } else
            log.i(  "refreshUiSuspend() is called from the non-main thread."
            )
        waitForDrawing()
        drawCanvasToView()
        touchHelper.setRawDrawingEnabled(false)
        if (drawingInProgress.isLocked)
            log.w(  "Lock was acquired during refreshing UI. It might cause errors.")
        touchHelper.setRawDrawingEnabled(true)
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
            val centerX = (page.viewWidth - imageWidth) / 2
            val centerY = (page.viewHeight - imageHeight) / 2 + page.scroll
            val imageToSave = Image(
                x = centerX,
                y = centerY,
                height = imageHeight,
                width = imageWidth,
                uri = imageUri.toString(),
                pageId = page.id
            )
            drawImage(
                context, page.windowedCanvas, imageToSave, IntOffset(0, -page.scroll)
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


    fun drawCanvasToView() {
        val canvas = this.holder.lockCanvas() ?: return
        canvas.drawBitmap(page.windowedBitmap, 0f, 0f, Paint())
        if (getActualState().mode == Mode.Select) {
            // render selection
            if (getActualState().selectionState.firstPageCut != null) {
                log.i(  "render cut")
                val path = pointsToPath(getActualState().selectionState.firstPageCut!!.map {
                    SimplePointF(
                        it.x, it.y - page.scroll
                    )
                })
                canvas.drawPath(path, selectPaint)
            }
        }
        // finish rendering
        this.holder.unlockCanvasAndPost(canvas)
    }

    private suspend fun updateIsDrawing() {
        log.i(  "Update is drawing: ${state.isDrawing}")
        if (state.isDrawing) {
            touchHelper.setRawDrawingEnabled(true)
        } else {
            // Check if drawing is completed
            waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvasToView()
            touchHelper.setRawDrawingEnabled(false)
        }
    }

    fun updatePenAndStroke() {
        // it takes around 11 ms to run on Note 4c.
        log.i(  "Update pen and stroke")
        when (state.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw -> touchHelper.setStrokeStyle(penToStroke(state.pen))
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

            Mode.Line -> {
            }
        }
    }

    fun updateActiveSurface() {
        // Takes at least 50ms on Note 4c,
        // and I don't think that we need it immediately
        log.i("Update editable surface")
        coroutineScope.launch {
            val toolbarHeight =
                if (state.isToolbarOpen) convertDpToPixel(40.dp, context).toInt() else 0

            touchHelper.setRawDrawingEnabled(false)
            touchHelper.closeRawDrawing()

            // Store view dimensions locally before using in Rect
            val viewWidth = this@DrawCanvas.width
            val viewHeight = this@DrawCanvas.height

            // Determine the exclusion area based on toolbar position
            val excludeRect: Rect =
                if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top) {
                    Rect(0, 0, viewWidth, toolbarHeight)
                } else {
                    Rect(0, viewHeight - toolbarHeight, viewWidth, viewHeight)
                }

            val limitRect =
                if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top)
                    Rect(0, toolbarHeight, viewWidth, viewHeight)
                else
                    Rect(0, 0, viewWidth, viewHeight - toolbarHeight)

            touchHelper.setLimitRect(mutableListOf(limitRect)).setExcludeRect(listOf(excludeRect))
                .openRawDrawing()

            touchHelper.setRawDrawingEnabled(true)
            log.i("Update editable surface completed")
        }
    }


    private val queue = mutableListOf<StrokePoint>()
    private var timeOfLastRefresh = 0L
    private val isRendering = AtomicBoolean(false)

    fun drawEraserIndicator(p: TouchPoint) {
        val timer = Timing("Canvas Lock Timing")
        val strokeSize = 10f
        queue.add(
            StrokePoint(
                x = p.x,
                y = p.y,
                pressure = p.pressure,
                size = p.size,
                tiltX = p.tiltX,
                tiltY = p.tiltY,
                timestamp = p.timestamp,
            )
        )
        if (System.currentTimeMillis() - timeOfLastRefresh < 16)
            return
        if (!isAttachedToWindow || holder.surface?.isValid != true) {
            Log.w("DrawCanvas", "Surface not ready, skipping draw")
            return
        }
        if (queue.size < 3)
            return
        if (!isRendering.compareAndSet(false, true))
            return
        val pointsToDraw = queue.toList()
        timeOfLastRefresh = System.currentTimeMillis()
        queue.clear()
        queue.add(pointsToDraw.last())


        val boundingBox = (calculateBoundingBox(pointsToDraw){ Pair(it.x, it.y) } ).toRect()
        val padding = (strokeSize / 2).toInt().coerceAtLeast(1)
        val dirtyRect = Rect(
            boundingBox.left - padding,
            boundingBox.top - padding,
            boundingBox.right + padding,
            boundingBox.bottom + padding
        )
        timer.step("init")

        // firstly drawing on page Canvas doesn't produce any visual bugs on joints

//            val offscreenCanvas = page.windowedCanvas
//            offscreenCanvas.setBitmap(page.windowedBitmap)
//            drawEraserStroke(offscreenCanvas, pointsToDraw, strokeSize)

        timer.step("draw")
        post {
            val holder = this@DrawCanvas.holder
            var surfaceCanvas: Canvas? = null
            try {
                // 2. Then: draw the bitmap onto the visible surface
                surfaceCanvas = holder.lockCanvas(dirtyRect)
                timer.step("lock")
//                    surfaceCanvas.drawBitmap(
//                        page.windowedBitmap,
//                        dirtyRect,      // src
//                        dirtyRect,      // dst
//                        null            // paint
//                    )
                drawEraserStroke(surfaceCanvas, pointsToDraw, strokeSize)
                timer.step("drawn on surface")

            } catch (e: Exception) {
                Log.e("DrawCanvas", "Canvas lock failed: ${e.message}")
            } finally {
                if (surfaceCanvas != null) {
                    holder.unlockCanvasAndPost(surfaceCanvas)
                }
                timer.step("Post")
                // 3. Trigger partial refresh
                refreshScreenRegion(this@DrawCanvas, dirtyRect)
                isRendering.set(false)
                timer.step("onyx")
            }
        }
        timer.end("Done")
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
                isRendering.set(false)
            }
        }
    }

}