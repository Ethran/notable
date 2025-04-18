package com.ethran.notable.classes

import android.content.Context
import android.graphics.Bitmap
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
import com.ethran.notable.TAG
import com.ethran.notable.db.Image
import com.ethran.notable.db.ImageRepository
import com.ethran.notable.db.StrokeRepository
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
import com.ethran.notable.utils.convertDpToPixel
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.handleDraw
import com.ethran.notable.utils.handleErase
import com.ethran.notable.utils.handleLine
import com.ethran.notable.utils.penToStroke
import com.ethran.notable.utils.pointsToPath
import com.ethran.notable.utils.selectPaint
import com.ethran.notable.utils.uriToBitmap
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.Log
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread


val pressure = EpdController.getMaxTouchPressure()

// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""

class DrawCanvas(
    context: Context,
    val coroutineScope: CoroutineScope,
    val state: EditorState,
    val page: PageView,
    val history: History
) : SurfaceView(context) {
    private val strokeHistoryBatch = mutableListOf<String>()
//    private val commitHistorySignal = MutableSharedFlow<Unit>()


    companion object {
        var forceUpdate = MutableSharedFlow<Rect?>()
        var refreshUi = MutableSharedFlow<Unit>()
        var isDrawing = MutableSharedFlow<Boolean>()
        var restartAfterConfChange = MutableSharedFlow<Unit>()

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
    }

    fun getActualState(): EditorState {
        return this.state
    }

    private val inputCallback: RawInputCallback = object : RawInputCallback() {

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) {
            val startTime = System.currentTimeMillis()
            // sometimes UI will get refreshed and frozen before we draw all the strokes.
            // I think, its because of doing it in separate thread. Commented it for now, to
            // observe app behavior, and determine if it fixed this bug,
            // as I do not know reliable way to reproduce it
            // Need testing if it will be better to do in main thread on, in separate.
            // thread(start = true, isDaemon = false, priority = Thread.MAX_PRIORITY) {

            if (getActualState().mode == Mode.Draw) {
//                val newThread = System.currentTimeMillis()
//                Log.d(TAG,"Got to new thread ${Thread.currentThread().name}, in ${newThread - startTime}}")
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    // After each stroke ends, we draw it on our canvas.
                    // This way, when screen unfreezes the strokes are shown.
                    // When in scribble mode, ui want be refreshed.
                    // If we UI will be refreshed and frozen before we manage to draw
                    // strokes want be visible, so we need to ensure that it will be done
                    // before anything else happens.
                    drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        Log.d(TAG, "lock obtained in ${lock - startTime} ms")

//                        Thread.sleep(1000)
                        handleDraw(
                            this@DrawCanvas.page,
                            strokeHistoryBatch,
                            getActualState().penSettings[getActualState().pen.penName]!!.strokeSize,
                            getActualState().penSettings[getActualState().pen.penName]!!.color,
                            getActualState().pen,
                            plist.points
                        )
//                        val drawEndTime = System.currentTimeMillis()
//                        Log.d(TAG, "Drawing operation took ${drawEndTime - startTime} ms")

                    }
                    coroutineScope.launch {
                        commitHistorySignal.emit(Unit)
                    }

//                    val endTime = System.currentTimeMillis()
//                    Log.d(TAG,"onRawDrawingTouchPointListReceived completed in ${endTime - startTime} ms")

                }
            } else thread {
                if (getActualState().mode == Mode.Erase) {
                    handleErase(
                        this@DrawCanvas.page,
                        history,
                        plist.points.map { SimplePointF(it.x, it.y + page.scroll) },
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
                        plist.points.map { SimplePointF(it.x, it.y + page.scroll) })
                    drawCanvasToView()
                    refreshUi()
                }

                if (getActualState().mode == Mode.Line) {
                    // draw line
                    handleLine(
                        page = this@DrawCanvas.page,
                        historyBucket = strokeHistoryBatch,
                        strokeSize = getActualState().penSettings[getActualState().pen.penName]!!.strokeSize,
                        color = getActualState().penSettings[getActualState().pen.penName]!!.color,
                        pen = getActualState().pen,
                        touchPoints = plist.points
                    )
                    //make it visible
                    drawCanvasToView()
                    refreshUi()
                }

            }
        }


        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) {
            if (plist == null) return
            handleErase(
                this@DrawCanvas.page,
                history,
                plist.points.map { SimplePointF(it.x, it.y + page.scroll) },
                eraser = getActualState().eraser
            )
            drawCanvasToView()
            refreshUi()
        }

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
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
        Log.i(TAG, "Initializing Canvas")

        val surfaceView = this

        val surfaceCallback: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surface created $holder")
                // set up the drawing surface
                updateActiveSurface()
                // This is supposed to let the ui update while the old surface is being unmounted
                coroutineScope.launch {
                    forceUpdate.emit(null)
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
                Log.i(TAG, "surface changed $holder")
                drawCanvasToView()
                updatePenAndStroke()
                refreshUi()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(
                    TAG,
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

        // observe forceUpdate
        coroutineScope.launch {
            forceUpdate.collect { zoneAffected ->
                Log.v(TAG + "Observer", "Force update zone $zoneAffected")

                if (zoneAffected != null) page.drawArea(
                    area = Rect(
                        zoneAffected.left,
                        zoneAffected.top - page.scroll,
                        zoneAffected.right,
                        zoneAffected.bottom - page.scroll
                    ),
                )
                refreshUiSuspend()
            }
        }

        // observe refreshUi
        coroutineScope.launch {
            refreshUi.collect {
                Log.v(TAG + "Observer", "Refreshing UI!")
                refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            isDrawing.collect {
                Log.v(TAG + "Observer", "drawing state changed!")
                state.isDrawing = it
            }
        }


        coroutineScope.launch {
            addImageByUri.drop(1).collect { imageUri ->
                Log.v(TAG + "Observer", "Received image!")

                if (imageUri != null) {
                    handleImage(imageUri)
                } //else
//                    Log.i(TAG, "Image uri is empty")
            }
        }
        coroutineScope.launch {
            rectangleToSelect.drop(1).collect {
                selectRectangle(it)
            }
        }


        // observe restartcount
        coroutineScope.launch {
            restartAfterConfChange.collect {
                Log.v(TAG + "Observer", "Configuration changed!")
                init()
                drawCanvasToView()
            }
        }

        // observe pen and stroke size
        coroutineScope.launch {
            snapshotFlow { state.pen }.drop(1).collect {
                Log.v(TAG + "Observer", "pen change: ${state.pen}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.penSettings.toMap() }.drop(1).collect {
                Log.v(TAG + "Observer", "pen settings change: ${state.penSettings}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.eraser }.drop(1).collect {
                Log.v(TAG + "Observer", "eraser change: ${state.eraser}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }

        // observe is drawing
        coroutineScope.launch {
            snapshotFlow { state.isDrawing }.drop(1).collect {
                Log.v(TAG + "Observer", "isDrawing change: ${state.isDrawing}")
                updateIsDrawing()
            }
        }

        // observe toolbar open
        coroutineScope.launch {
            snapshotFlow { state.isToolbarOpen }.drop(1).collect {
                Log.v(TAG + "Observer", "istoolbaropen change: ${state.isToolbarOpen}")
                updateActiveSurface()
            }
        }

        // observe mode
        coroutineScope.launch {
            snapshotFlow { getActualState().mode }.drop(1).collect {
                Log.v(TAG + "Observer", "mode change: ${getActualState().mode}")
                updatePenAndStroke()
                refreshUiSuspend()
            }
        }

        coroutineScope.launch {
            //After 500ms add to history strokes
            commitHistorySignal.debounce(500).collect {
                Log.v(TAG + "Observer", "Commiting to history")
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

    private suspend fun selectRectangle(rectToSelect: Rect?) {
        if (rectToSelect != null) {
            Log.d(TAG + "Observer", "position of image $rectToSelect")
            rectToSelect.top += page.scroll
            rectToSelect.bottom += page.scroll
            // Query the database to find an image that coincides with the point
            val imagesToSelect = withContext(Dispatchers.IO) {
                ImageRepository(context).getImagesInRectangle(rectToSelect, page.id)
            }
            val strokesToSelect = withContext(Dispatchers.IO) {
                StrokeRepository(context).getStrokesInRectangle(rectToSelect, page.id)
            }
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
        }
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
        // Use only if you have confidence that there are no strokes being drawn at the moment
        if (!state.isDrawing) {
            Log.w(TAG, "Not in drawing mode, skipping refreshUI")
            return
        }
        if (drawingInProgress.isLocked)
            Log.w(TAG, "Drawing is still in progress there might be a bug.")

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
            Log.w(TAG, "Not in drawing mode -- refreshUi ")
            return
        }
        if (Looper.getMainLooper().isCurrentThread) {
            Log.i(
                TAG,
                "refreshUiSuspend() is called from the main thread, it might not be a good idea."
            )
        }
        waitForDrawing()
        drawCanvasToView()
        touchHelper.setRawDrawingEnabled(false)
        if (drawingInProgress.isLocked)
            Log.w(TAG, "Lock was acquired during refreshing UI. It might cause errors.")
        touchHelper.setRawDrawingEnabled(true)
    }

    private suspend fun waitForDrawing() {
        withTimeoutOrNull(3000) {
            // Just to make sure wait 1ms before checking lock.
            delay(1)
            // Wait until drawingInProgress is unlocked before proceeding
            while (drawingInProgress.isLocked) {
                delay(5)
            }
        } ?: Log.e(TAG, "Timeout while waiting for drawing lock. Potential deadlock.")
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
                Log.i(TAG, "render cut")
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
        Log.i(TAG, "Update is drawing: ${state.isDrawing}")
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
        Log.i(TAG, "Update pen and stroke")
        when (state.mode) {
            Mode.Draw -> touchHelper.setStrokeStyle(penToStroke(state.pen))
                ?.setStrokeWidth(state.penSettings[state.pen.penName]!!.strokeSize)
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
        Log.i(TAG, "Update editable surface")

        val toolbarHeight =
            if (state.isToolbarOpen) convertDpToPixel(40.dp, context).toInt() else 0

        touchHelper.setRawDrawingEnabled(false)
        touchHelper.closeRawDrawing()

        // Determine the exclusion area based on toolbar position
        val excludeRect: Rect =
            if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top) {
                Rect(0, 0, this.width, toolbarHeight)
            } else {
                Rect(0, this.height - toolbarHeight, this.width, this.height)
            }

        val limitRect = if (GlobalAppSettings.current.toolbarPosition == AppSettings.Position.Top)
            Rect(0, toolbarHeight, this.width, this.height)
        else
            Rect(0, 0, this.width, this.height - toolbarHeight)

        touchHelper.setLimitRect(mutableListOf(limitRect)).setExcludeRect(listOf(excludeRect))
            .openRawDrawing()

        touchHelper.setRawDrawingEnabled(true)
        updatePenAndStroke()

        refreshUi()
    }

}