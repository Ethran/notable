package com.ethran.notable.editor.canvas

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.calculateBoundingBox
import com.ethran.notable.editor.utils.copyInput
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.enableNativeEraser
import com.ethran.notable.editor.utils.getModifiedStrokeEndpoints
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.handleSelect
import com.ethran.notable.editor.utils.onSurfaceInit
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
    private val viewModel: EditorViewModel,
    private val history: History,
    private val coroutineScope: CoroutineScope,
    private val strokeHistoryBatch: MutableList<String>,
) {
    var isErasing: Boolean = false
    var lastStrokeEndTime: Long = 0
    private val log = ShipBook.getLogger("DrawCanvas")
    private val toolbarState get() = viewModel.toolbarState.value

    // TODO: As OnyxInput is not done by lazy, which forces evaluation of the touchHelper
    //       lazy during DrawCanvas construction.
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

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) =
            onRawDrawingList(plist)


        // Handle button/eraser tip of the pen:
        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            if (touchHelper == null) return
            // NATIVE ERASER INDICATOR:
            // The eraser stroke is rendered natively by the firmware, enabled via
            // touchHelper.setEraserRawDrawingEnabled(true, ...). This is RE-ASSERTED here
            // because setRawDrawingEnabled(true) (called by updateIsDrawing() on every
            // resume) internally calls resetPenDefaultRawDrawing() ->
            // setEraserRawDrawingEnabled(false, 5), which would otherwise leave the native
            // eraser channel disabled and the indicator blank (verified by decompiling
            // onyxsdk-pen TouchHelper).
            // The native eraser channel draws using the helper's current stroke
            // color/width/style, so we configure a visible indicator that MATCHES the
            // active eraser type (marker for the pen eraser, dashed line for the lasso /
            // select eraser). It is restored in onEndRawErasing. The indicator itself is
            // transient: after pen-up, onRawErasingList() repaints from the bitmap (which
            // has no indicator), so it disappears once the erase is committed.
            // See docs/onyx-native-eraser-indicator.md.
            enableNativeEraser(touchHelper)
            applyEraserIndicatorStyle()

            // The OpenGL front-buffer workaround below is disabled, but kept (commented)
            // as a reference for non-native erase rendering.
            // if (GlobalAppSettings.current.openGLRendering) {
            //     prepareForPartialUpdate(drawCanvas, touchHelper!!)
            //     log.d("Eraser Mode")
            // }
            isErasing = true
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            // NATIVE ERASER INDICATOR: restore the pen's stroke settings after erasing.
            updatePenAndStroke()
            // OpenGL workaround disabled (see onBeginRawErasing).
            // if (GlobalAppSettings.current.openGLRendering) {
            //     restoreDefaults(drawCanvas)
            //     drawCanvas.glRenderer.clearPointBuffer()
            // }
            // drawCanvas.glRenderer.frontBufferRenderer?.cancel()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) =
            onRawErasingList(plist)

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
        when (toolbarState.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Line -> touchHelper!!.setStrokeStyle(penToStroke(toolbarState.pen))
                ?.setStrokeWidth(toolbarState.penSettings[toolbarState.pen.penName]!!.strokeSize * page.zoomLevel.value)
                ?.setStrokeColor(toolbarState.penSettings[toolbarState.pen.penName]!!.color)

            Mode.Erase -> applyEraserIndicatorStyle(penEraserColor = Color.GRAY)

            Mode.Select -> touchHelper?.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)
        }
    }

    /**
     * Configures the helper's stroke so the eraser feedback matches the active eraser type:
     * a marker for the pen eraser, and a dashed line for the lasso / select eraser. Shared
     * by the hand eraser (Mode.Erase in [updatePenAndStroke]) and the pen side-button
     * eraser ([onBeginRawErasing], native indicator).
     *
     * @param penEraserColor colour for the [Eraser.PEN] marker. Hand-erase uses grey; the
     * native button-erase indicator uses black (matches the user's preference and is more
     * visible against ink).
     */
    private fun applyEraserIndicatorStyle(penEraserColor: Int = Color.BLACK) {
        if (touchHelper == null) return
        when (toolbarState.eraser) {
            Eraser.PEN -> touchHelper!!.setStrokeStyle(penToStroke(Pen.MARKER))
                ?.setStrokeWidth(30f)
                ?.setStrokeColor(penEraserColor)

            Eraser.SELECT -> {
                val dashStyleID = penToStroke(Pen.DASHED)
                touchHelper!!.setStrokeStyle(dashStyleID)
                    ?.setStrokeWidth(3f)
                    ?.setStrokeColor(Color.BLACK)
                val params = FloatArray(4)
                params[0] = 5f // thickness
                params[1] = 9f // no idea
                params[2] = 9f // no idea
                params[3] = 0f // no idea
                Device.currentDevice().setStrokeParameters(dashStyleID, params)
            }
        }
    }

    suspend fun updateIsDrawing() {
        if(touchHelper == null) return
        log.i("Update is drawing: $toolbarState.isDrawing")
        if (toolbarState.isDrawing) {
//            DeviceCompat.delayBeforeResumingDrawing()
            touchHelper!!.setRawDrawingEnabled(true)
        } else {
            // Check if drawing is completed
            CanvasEventBus.waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvas.refreshManager.drawCanvasToView(null)
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
                if (toolbarState.isToolbarOpen) convertDpToPixel(40.dp, drawCanvas.context).toInt() else 0
            setupSurface(
                drawCanvas,
                touchHelper,
                toolbarHeight
            )
        }
    }
    private fun onRawDrawingList(plist: TouchPointList) {
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

        when (toolbarState.mode) {
            Mode.Erase -> onRawErasingList(plist)
            Mode.Select -> {
                thread {
                    val points =
                        copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)
                    handleSelect(
                        scope = coroutineScope,
                        page = drawCanvas.page,
                        viewModel = viewModel,
                        points = points
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
                            toolbarState.penSettings[toolbarState.pen.penName]!!.strokeSize,
                            toolbarState.penSettings[toolbarState.pen.penName]!!.color,
                            toolbarState.pen,
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
                            toolbarState.pen,
                            currentLastStrokeEndTime,
                            firstPointTime
                        )
                        if (erasedByScribbleDirtyRect.isNullOrEmpty()) {
                            log.d("Drawing...")
                            // draw the stroke
                            handleDraw(
                                drawCanvas.page,
                                strokeHistoryBatch,
                                toolbarState.penSettings[toolbarState.pen.penName]!!.strokeSize,
                                toolbarState.penSettings[toolbarState.pen.penName]!!.color,
                                toolbarState.pen,
                                scaledPoints
                            )
                        } else {
                            log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                            // Union the scribble TRACK (what the firmware drew live, in screen
                            // coords from the raw points) with the erased strokes' bounds, exactly
                            // like the eraser path (onRawErasingList). commitErase pushes this
                            // union to the panel WHILE STILL FROZEN, so the post overwrites BOTH
                            // the firmware scribble ink and the erased writing in one step; only
                            // then does it drop the firmware layer — which now reveals an already
                            // clean page, so scribble and writing vanish together with no gap.
                            // (Previously only the erased-strokes bounds were pushed, leaving the
                            // rest of the scribble ink on screen until the firmware layer dropped
                            // a moment later — the visible gap.) The scribble is NOT drawn into
                            // the page bitmap (unlike kreader, which keeps it as a Shape); we only
                            // need the pushed region to cover the firmware's track. See
                            // docs/onyx-scribble-to-erase.md.
                            val padding = 10
                            val trackBox =
                                calculateBoundingBox(plist.points) { Pair(it.x, it.y) }.toRect()
                            val dirty = Rect(
                                trackBox.left - padding,
                                trackBox.top - padding,
                                trackBox.right + padding,
                                trackBox.bottom + padding
                            )
                            erasedByScribbleDirtyRect.let { dirty.union(it) }
                            // Longer (area) settle: a scribble is a large-area gesture, far heavier
                            // on the EPD than a thin stroke erase — 150ms was too short and let the
                            // next stroke composite against not-yet-settled content.
                            drawCanvas.refreshManager.commitErase(dirty, areaErase = true)
                        }

                    }
                    coroutineScope.launch(Dispatchers.Default) {
                        CanvasEventBus.commitHistorySignal.emit(Unit)
                    }
                }
            }
        }
    }

    private fun onRawErasingList(plist: TouchPointList?) {
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
        val zoneEffected = handleErase(
            drawCanvas.page,
            history,
            points,
            eraser = toolbarState.eraser
        )

        // Single atomic commit of the whole touched region: the native eraser indicator
        // track spans strokeArea, the erased strokes' bounds are zoneEffected, so repainting
        // their union both wipes the indicator and shows the erased result in one pass.
        // commitErase blocks input, draws synchronously, then drops the firmware overlay so
        // indicator + strokes disappear together (no double refresh, no gap to draw into).
        // See docs/onyx-pen-up-refresh-and-screen-freeze.md.
        val dirty = Rect(strokeArea)
        if (zoneEffected != null) dirty.union(zoneEffected)
        // Area (lasso/select) erase needs the longer 500ms settle the official app uses; the
        // pen/marker erase uses the 150ms stroke settle.
        drawCanvas.refreshManager.commitErase(dirty, areaErase = toolbarState.eraser == Eraser.SELECT)
    }

}