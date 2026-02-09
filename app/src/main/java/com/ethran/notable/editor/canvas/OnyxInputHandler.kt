package com.ethran.notable.editor.canvas

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.calculateBoundingBox
import com.ethran.notable.editor.utils.copyInput
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.getModifiedStrokeEndpoints
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.handleSelect
import com.ethran.notable.editor.utils.onSurfaceInit
import com.ethran.notable.editor.utils.partialRefreshRegionOnce
import com.ethran.notable.editor.utils.penToStroke
import com.ethran.notable.editor.utils.prepareForPartialUpdate
import com.ethran.notable.editor.utils.restoreDefaults
import com.ethran.notable.editor.utils.setupSurface
import com.ethran.notable.editor.utils.transformToLine
import com.ethran.notable.ui.convertDpToPixel
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.extension.isNullOrEmpty
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class OnyxInputHandler(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val state: EditorState,
    private val history: History,
    private val coroutineScope: CoroutineScope,
) {
    var isErasing: Boolean = false
    var lastStrokeEndTime: Long = 0
    private val strokeHistoryBatch = mutableListOf<String>()
    private val log = ShipBook.Companion.getLogger("DrawCanvas")

    val touchHelper by lazy {
        val helper = if (DeviceCompat.isOnyxDevice) {
            try {
                referencedSurfaceView = this.hashCode().toString()
                TouchHelper.create(drawCanvas, inputCallback)
            } catch (t: Throwable) {
                Log.w("OnyxInputHandler", "TouchHelper.create failed: ${t.message}")
                null
            }
        } else null
        helper
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
            if (touchHelper == null) return
            val currentLastStrokeEndTime = lastStrokeEndTime
            lastStrokeEndTime = System.currentTimeMillis()
            val startTime = System.currentTimeMillis()
            // sometimes UI will get refreshed and frozen before we draw all the strokes.
            // I think, its because of doing it in separate thread. Commented it for now, to
            // observe app behavior, and determine if it fixed this bug,
            // as I do not know reliable way to reproduce it
            // Need testing if it will be better to do in main thread on, in separate.
            // thread(start = true, isDaemon = false, priority = Thread.MAX_PRIORITY) {

            when (drawCanvas.getActualState().mode) {
                Mode.Erase -> onRawErasingTouchPointListReceived(plist)
                Mode.Select -> {
                    thread {
                        val points =
                            copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)
                        handleSelect(
                            coroutineScope, drawCanvas.page, drawCanvas.getActualState(), points
                        )
                        val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }.toRect()
                        val padding = 10
                        val dirtyRect = Rect(
                            boundingBox.left - padding,
                            boundingBox.top - padding,
                            boundingBox.right + padding,
                            boundingBox.bottom + padding
                        )
                        drawCanvas.refreshManager.refreshUi(dirtyRect)
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
                        CanvasEventBus.drawingInProgress.withLock {
                            val lock = System.currentTimeMillis()
                            log.d("lock obtained in ${lock - startTime} ms")


                            val (startPoint, endPoint) = getModifiedStrokeEndpoints(
                                plist.points,
                                page.scroll,
                                page.zoomLevel.value
                            )
                            val linePoints = transformToLine(startPoint, endPoint)

                            handleDraw(
                                drawCanvas.page,
                                strokeHistoryBatch,
                                drawCanvas.getActualState().penSettings[drawCanvas.getActualState().pen.penName]!!.strokeSize,
                                drawCanvas.getActualState().penSettings[drawCanvas.getActualState().pen.penName]!!.color,
                                drawCanvas.getActualState().pen,
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
                                drawCanvas.refreshManager.refreshUi(dirtyRect)
                                CanvasEventBus.commitHistorySignal.emit(Unit)
                            }
                        }

                    }
                }

                Mode.Draw -> {
                    coroutineScope.launch(Dispatchers.Main.immediate) {
                        CanvasEventBus.drawingInProgress.withLock {
                            val lock = System.currentTimeMillis()
                            log.d("lock obtained in ${lock - startTime} ms")

                            // Thread.sleep(1000)
                            // transform points to page space
                            val scaledPoints =
                                copyInput(plist.points, page.scroll, page.zoomLevel.value)
                            val firstPointTime = plist.points.first().timestamp
                            val erasedByScribbleDirtyRect = handleScribbleToErase(
                                page,
                                scaledPoints,
                                history,
                                drawCanvas.getActualState().pen,
                                currentLastStrokeEndTime,
                                firstPointTime
                            )
                            if (erasedByScribbleDirtyRect.isNullOrEmpty()) {
                                log.d("Drawing...")
                                // draw the stroke
                                handleDraw(
                                    drawCanvas.page,
                                    strokeHistoryBatch,
                                    drawCanvas.getActualState().penSettings[drawCanvas.getActualState().pen.penName]!!.strokeSize,
                                    drawCanvas.getActualState().penSettings[drawCanvas.getActualState().pen.penName]!!.color,
                                    drawCanvas.getActualState().pen,
                                    scaledPoints
                                )
                            } else {
                                log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                                drawCanvas.drawCanvasToView(erasedByScribbleDirtyRect)
                                partialRefreshRegionOnce(
                                    drawCanvas,
                                    erasedByScribbleDirtyRect,
                                    touchHelper!!
                                )

                            }

                        }
                        coroutineScope.launch(Dispatchers.Default) {
                            CanvasEventBus.commitHistorySignal.emit(Unit)
                        }
                    }
                }
            }
        }

        // Handle button/eraser tip of the pen:
        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            if (touchHelper == null) return
            if (GlobalAppSettings.current.openGLRendering) {
                prepareForPartialUpdate(drawCanvas, touchHelper!!)
                log.d("Eraser Mode")
            }
            isErasing = true
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            if (GlobalAppSettings.current.openGLRendering) {
                restoreDefaults(drawCanvas)
                drawCanvas.glRenderer.clearPointBuffer()
            }
            drawCanvas.glRenderer.frontBufferRenderer?.cancel()
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
            drawCanvas.refreshManager.refreshUi(strokeArea)

            val zoneEffected = handleErase(
                drawCanvas.page,
                history,
                points,
                eraser = drawCanvas.getActualState().eraser
            )
            if (zoneEffected != null)
                drawCanvas.refreshManager.refreshUi(zoneEffected)
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

    fun updatePenAndStroke() {
        if(touchHelper == null) return
        // it takes around 11 ms to run on Note 4c.
        log.i("Update pen and stroke")
        when (state.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Line -> touchHelper!!.setStrokeStyle(penToStroke(state.pen))
                ?.setStrokeWidth(state.penSettings[state.pen.penName]!!.strokeSize * page.zoomLevel.value)
                ?.setStrokeColor(state.penSettings[state.pen.penName]!!.color)

            Mode.Erase -> {
                when (state.eraser) {
                    Eraser.PEN -> touchHelper!!.setStrokeStyle(penToStroke(Pen.MARKER))
                        ?.setStrokeWidth(30f)
                        ?.setStrokeColor(Color.GRAY)

                    Eraser.SELECT -> {
                        val dashStyleID = penToStroke(Pen.DASHED)
                        touchHelper!!.setStrokeStyle(dashStyleID)
                            ?.setStrokeWidth(3f)
                            ?.setStrokeColor(Color.BLACK)
                        val params: FloatArray = FloatArray(4)
                        params[0] = 5f // thickness
                        params[1] = 9f // no idea
                        params[2] = 9f // no idea
                        params[3] = 0f // no idea
                        Device.currentDevice().setStrokeParameters(dashStyleID, params)
                    }
                }
            }

            Mode.Select -> touchHelper?.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)
        }
    }

    suspend fun updateIsDrawing() {
        if(touchHelper == null) return
        log.i("Update is drawing: ${state.isDrawing}")
        if (state.isDrawing) {
            touchHelper!!.setRawDrawingEnabled(true)
        } else {
            // Check if drawing is completed
            CanvasEventBus.waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvas.drawCanvasToView(null)
            touchHelper!!.setRawDrawingEnabled(false)
        }
    }

    fun updateActiveSurface() {
        // Takes at least 50ms on Note 4c,
        // and I don't think that we need it immediately
        log.i("Update editable surface")
        coroutineScope.launch {
            onSurfaceInit(drawCanvas)
            val toolbarHeight =
                if (state.isToolbarOpen) convertDpToPixel(40.dp, drawCanvas.context).toInt() else 0
            setupSurface(
                drawCanvas,
                touchHelper,
                toolbarHeight
            )
        }
    }

}