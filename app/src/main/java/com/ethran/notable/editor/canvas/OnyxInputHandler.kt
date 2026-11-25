package com.ethran.notable.editor.canvas

import android.graphics.Color
import android.graphics.Rect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
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
import com.ethran.notable.editor.utils.getModifiedStrokeEndpoints
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.handleSelect
import com.ethran.notable.editor.utils.onSurfaceInit
import com.ethran.notable.editor.utils.penToStroke
import com.ethran.notable.editor.utils.rawDrawingArea
import com.ethran.notable.editor.utils.transformToLine
import com.ethran.notable.ui.convertDpToPixel
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.extension.isNullOrEmpty
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

class OnyxInputHandler(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val history: History,
    private val coroutineScope: CoroutineScope,
) {
    private var lastStrokeEndTime: Long = 0

    // The page the pen went down on, for the gesture under way. Pen callbacks arrive on the main
    // thread, as page swaps do, so a page changed in the middle of a gesture is seen when the
    // gesture's points arrive, and a stroke, erase or selection is dropped.
    private var penDownPageId: String? = null

    // Taken once per gesture, when its points arrive; without a recorded pen-down, the page shown.
    private fun takeGesturePageId(): String =
        (penDownPageId ?: page.currentPageId).also { penDownPageId = null }
    private val log = ShipBook.getLogger("DrawCanvas")
    private val toolbarState get() = viewModel.toolbarState.value

    /** The owner of raw drawing on this surface ([DrawCanvas] builds it from [penDevice]). */
    private val rawDrawing get() = drawCanvas.rawDrawing

    private val inputCallback: RawInputCallback = object : RawInputCallback() {
        // Documentation: https://github.com/onyx-intl/OnyxAndroidDemo/blob/d3a1ffd3af231fe4de60a2a0da692c17cb35ce31/doc/Onyx-Pen-SDK.md#L40-L62
        // - pen : `onBeginRawDrawing()` -> `onRawDrawingTouchPointMoveReceived()` -> `onRawDrawingTouchPointListReceived()` -> `onEndRawDrawing()`
        // - erase :  `onBeginRawErasing()` -> `onRawErasingTouchPointMoveReceived()` -> `onRawErasingTouchPointListReceived()` -> `onEndRawErasing()`

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
            penDownPageId = page.currentPageId
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) =
            onRawDrawingList(plist)


        // Handle button/eraser tip of the pen:
        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            penDownPageId = page.currentPageId
            val device = penDevice ?: return
            // Belt and braces: armPen has set the eraser track when the pen came on, and the
            // firmware has latched its style for this erase by now. Kept as found working on
            // devices; it costs about 1 ms.
            device.enableEraserTrack(toolbarState.eraser)
            // Width comes from the style's params, so this touches colour only, not thickness.
            device.setEraserTrackColor()
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            updatePenAndStroke()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) =
            onRawErasingList(plist, takeGesturePageId())

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
        }
    }

    /** This surface's Onyx pen; null without one. Declared after [inputCallback], which it uses. */
    val penDevice: TouchHelperPenDevice? = createTouchHelper()?.let { helper ->
        TouchHelperPenDevice(helper, drawCanvas, inputCallback) {
            val toolbarHeight = if (toolbarState.isToolbarOpen)
                convertDpToPixel(40.dp, drawCanvas.context).toInt() else 0
            rawDrawingArea(drawCanvas, toolbarHeight)
        }
    }

    private fun createTouchHelper(): TouchHelper? {
        if (!DeviceCompat.isOnyxDevice) return null
        return try {
            val start = System.nanoTime()
            TouchHelper.create(drawCanvas, inputCallback).also {
                log.d("TouchHelper.create: %.1f ms".format((System.nanoTime() - start) / 1e6))
            }
        } catch (t: Throwable) {
            log.w("TouchHelper.create failed: ${t.message}")
            null
        }
    }

    fun updatePenAndStroke() {
        val device = penDevice ?: return
        // it takes around 11 ms to run on Note 4c.
        log.i("Update pen and stroke")
        when (toolbarState.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Line -> {
                val scaledWidth = toolbarState.activePenSetting.strokeSize * page.zoom
                device.setStroke(
                    penToStroke(toolbarState.pen), scaledWidth, toolbarState.activePenSetting.color
                )
                // Match the live square-pen nib angle to the dry render (+45°) so the calligraphy
                // stroke doesn't rotate on pen-up.
                if (toolbarState.pen == Pen.CALLIGRAPHY) {
                    device.setCalligraphyAngle(angleDegrees = 45f, strokeWidth = scaledWidth)
                }
            }

            // The hand eraser's feedback: a grey marker for the pen eraser, a dashed line for the
            // lasso. (The side-button eraser draws the firmware's own track, see armPen.)
            Mode.Erase -> when (toolbarState.eraser) {
                Eraser.PEN -> device.setStroke(penToStroke(Pen.MARKER), 30f, Color.GRAY)
                Eraser.SELECT -> device.setDashedStroke(width = 3f, color = Color.BLACK)
            }

            Mode.Select -> device.setStroke(penToStroke(Pen.BALLPEN), 3f, Color.GRAY)
        }
    }

    /**
     * Configures the side-button eraser's track and the pen style for the current tool. Run by the
     * controller after every [PenDevice.resetPenDefaults], which clears them, and before the pen's
     * input comes on: SDK callbacks reach the UI thread after the firmware has started a stroke,
     * so this cannot wait for one. Also run when the tool changes.
     */
    fun armPen() {
        val device = penDevice ?: return
        device.enableEraserTrack(toolbarState.eraser)
        updatePenAndStroke()
    }

    private var surfaceSetupJob: Job? = null

    fun updateActiveSurface() {
        // Takes at least 50ms on Note 4c, so bursts (toolbar change + surfaceCreated when the
        // editor opens) are coalesced: a newer request cancels one that has not started yet.
        log.i("Update editable surface")
        surfaceSetupJob?.cancel()
        surfaceSetupJob = coroutineScope.launch {
            // Before surfaceCreated there is nothing to set up (no surface, possibly zero size);
            // surfaceCreated calls this again once the surface exists.
            if (!drawCanvas.holder.surface.isValid) {
                log.d("Surface not ready, skipping surface setup")
                return@launch
            }
            onSurfaceInit(drawCanvas)
            // Recreates the session over the area outside the toolbar and turns the pen back on
            // if it should draw; the pen style is armed in the same step.
            rawDrawing.rebuildSession()
        }
    }
    private fun onRawDrawingList(plist: TouchPointList) {
        val currentLastStrokeEndTime = lastStrokeEndTime
        lastStrokeEndTime = System.currentTimeMillis()
        val startTime = System.currentTimeMillis()
        val pageId = takeGesturePageId()

        when (toolbarState.mode) {
            Mode.Erase -> onRawErasingList(plist, pageId)
            Mode.Select -> {
                // On the main thread, as drawing is: selecting draws into the page's screen bitmap.
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        if (pageId != page.currentPageId) {
                            log.i("Selection made on page $pageId dropped, another page is shown")
                            return@withLock
                        }
                        val viewport = page.viewport.value
                        val points =
                            copyInputToSimplePointF(plist.points, viewport.scroll, viewport.zoom)
                        handleSelect(
                            scope = coroutineScope,
                            page = drawCanvas.page,
                            viewModel = viewModel,
                            points = points
                        )
                    }
                    drawCanvas.refreshManager.refreshUi(trackArea(plist))
                }
            }

            Mode.Line -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")


                        val viewport = page.viewport.value
                        val (startPoint, endPoint) = getModifiedStrokeEndpoints(
                            plist.points,
                            viewport.scroll,
                            viewport.zoom
                        )
                        val linePoints = transformToLine(startPoint, endPoint)

                        handleDraw(
                            drawCanvas.page,
                            pageId,
                            history,
                            toolbarState.activePenSetting.strokeSize,
                            toolbarState.activePenSetting.color,
                            toolbarState.pen,
                            linePoints
                        )

                        // On screen: the firmware's freehand track and the straight line drawn.
                        val dirtyRect = trackArea(plist).apply {
                            union(
                                viewport.toScreen(
                                    Rect(
                                        min(startPoint.x, endPoint.x).toInt(),
                                        min(startPoint.y, endPoint.y).toInt(),
                                        max(startPoint.x, endPoint.x).toInt(),
                                        max(startPoint.y, endPoint.y).toInt()
                                    )
                                )
                            )
                        }
                        drawCanvas.refreshManager.refreshUi(dirtyRect)
                    }

                }
            }

            Mode.Draw -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    var dropped = false
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")

                        if (pageId != page.currentPageId) {
                            dropped = true
                            return@withLock
                        }
                        val viewport = page.viewport.value
                        val scaledPoints =
                            copyInput(plist.points, viewport.scroll, viewport.zoom)
                        val firstPointTime = plist.points.first().timestamp
                        val erasedByScribbleDirtyRect = handleScribbleToErase(
                            page,
                            scaledPoints,
                            history,
                            toolbarState.pen,
                            toolbarState.activePenSetting.strokeSize,
                            toolbarState.activePenSetting.color,
                            currentLastStrokeEndTime,
                            firstPointTime
                        )
                        if (erasedByScribbleDirtyRect.isNullOrEmpty()) {
                            log.d("Drawing...")
                            // draw the stroke
                            handleDraw(
                                drawCanvas.page,
                                pageId,
                                history,
                                toolbarState.activePenSetting.strokeSize,
                                toolbarState.activePenSetting.color,
                                toolbarState.pen,
                                scaledPoints
                            )
                            val width = (toolbarState.activePenSetting.strokeSize * viewport.zoom).toInt()
                            val strokeArea = trackArea(plist).apply { inset(-width, -width) }
                            rawDrawing.strokeDrawn(strokeArea.toScreenArea())
                        } else {
                            log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                            // Union the scribble track (firmware screen coords) with the erased
                            // strokes' bounds so commitErase overwrites both in one pass while
                            // still frozen. Scribble is not drawn into the page bitmap — we only
                            // need the region to cover the firmware's live track.
                            val dirty = trackArea(plist).apply { union(erasedByScribbleDirtyRect) }
                            // Use areaErase=true for the longer 500ms settle (scribble is a large gesture).
                            drawCanvas.refreshManager.commitErase(dirty, areaErase = true)
                        }

                    }
                    if (dropped) {
                        // Made on a page no longer shown; only the firmware's ink is left to clear.
                        log.i("Stroke made on page $pageId dropped, another page is shown")
                        drawCanvas.refreshManager.refreshUi(trackArea(plist))
                    }
                }
            }
        }
    }

    // The screen area the firmware drew [plist]'s track over.
    private fun trackArea(plist: TouchPointList): Rect {
        val padding = 10
        val boundingBox = (calculateBoundingBox(plist.points) { Pair(it.x, it.y) }).toRect()
        return Rect(
            boundingBox.left - padding,
            boundingBox.top - padding,
            boundingBox.right + padding,
            boundingBox.bottom + padding
        )
    }

    private fun onRawErasingList(plist: TouchPointList?, pageId: String) {
        if (plist == null) return
        val strokeArea = trackArea(plist)
        if (pageId != page.currentPageId) {
            // Made on a page no longer shown: only the eraser track is cleared.
            log.i("Erasing made on page $pageId dropped, another page is shown")
            drawCanvas.refreshManager.commitErase(strokeArea, areaErase = toolbarState.eraser == Eraser.SELECT)
            return
        }
        val viewport = page.viewport.value
        val points = copyInputToSimplePointF(plist.points, viewport.scroll, viewport.zoom)

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
        val dirty = Rect(strokeArea)
        if (zoneEffected != null) dirty.union(zoneEffected)
        // Area (lasso/select) erase needs the longer 500ms settle; the
        // pen/marker erase uses the 150ms stroke settle.
        drawCanvas.refreshManager.commitErase(dirty, areaErase = toolbarState.eraser == Eraser.SELECT)
    }

}