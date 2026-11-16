package com.ethran.notable.editor.canvas

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
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
import com.ethran.notable.editor.utils.configureCalligraphyLiveAngle
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.ERASER_INDICATOR_COLOR
import com.ethran.notable.editor.utils.enableNativeEraser
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
import com.onyx.android.sdk.device.Device
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
    var lastStrokeEndTime: Long = 0

    // The page the pen went down on, for the gesture under way. Pen callbacks arrive on the main
    // thread, as page swaps do, so a page changed in the middle of a gesture is seen when the
    // gesture's points arrive, and a stroke, erase or selection is dropped.
    private var penDownPageId: String? = null

    // Taken once per gesture, when its points arrive; without a recorded pen-down, the page shown.
    private fun takeGesturePageId(): String =
        (penDownPageId ?: page.currentPageId).also { penDownPageId = null }
    private val log = ShipBook.getLogger("DrawCanvas")
    private val toolbarState get() = viewModel.toolbarState.value

    private val touchHelper by lazy {
        val helper = if (DeviceCompat.isOnyxDevice) {
            try {
                TouchHelper.create(drawCanvas, inputCallback)
            } catch (t: Throwable) {
                Log.w("OnyxInputHandler", "TouchHelper.create failed: ${t.message}")
                null
            }
        } else null
        helper
    }

    /** The only owner of raw drawing on this surface; null without an Onyx pen. */
    val rawDrawing: RawDrawingController? by lazy {
        val helper = touchHelper ?: return@lazy null
        RawDrawingController(
            device = TouchHelperPenDevice(helper) {
                val toolbarHeight = if (toolbarState.isToolbarOpen)
                    convertDpToPixel(40.dp, drawCanvas.context).toInt() else 0
                rawDrawingArea(drawCanvas, toolbarHeight)
            },
            wanted = { toolbarState.isDrawing },
            armPen = ::armPen,
            drawPage = { drawCanvas.refreshManager.drawCanvasToViewSync(it) },
            forcePage = { drawCanvas.refreshManager.forceCanvasToViewSync(it) },
            timer = ScopeMainTimer(coroutineScope),
            renderResumeMillis = DeviceCompat::postSettleMillis,
            eraseSettleMillis = DeviceCompat::eraseSettleMillis,
            checkThread = ::checkRawDrawingMainThread,
        )
    }

    @Suppress("RedundantOverride")
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
            if (touchHelper == null) return
            // Re-assert the native eraser indicator because setRawDrawingEnabled(true) (called
            // on every resume) resets it to disabled internally. The track style follows the active
            // eraser type: the wide marker (style 8) for the pen/drag eraser, a dotted outline
            // (DASH style 5) for the lasso/select eraser. See docs/onyx-sdk/onyx-native-eraser-indicator.md.
            enableNativeEraser(touchHelper, toolbarState.eraser)
            // The eraser channel carries no colour of its own — the firmware paints the track with
            // the global setStrokeColor. Set it here (width comes from the style's params, so this
            // touches colour only, not thickness). This is the one thing we still set on begin.
            touchHelper?.setStrokeColor(ERASER_INDICATOR_COLOR)
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            updatePenAndStroke()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) =
            onRawErasingList(plist, takeGesturePageId())

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
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
            Mode.Draw, Mode.Line -> {
                val scaledWidth = toolbarState.activePenSetting.strokeSize * page.zoom
                touchHelper!!.setStrokeStyle(penToStroke(toolbarState.pen))
                    ?.setStrokeWidth(scaledWidth)
                    ?.setStrokeColor(toolbarState.activePenSetting.color)
                // Match the live square-pen nib angle to the dry render (+45°) so the calligraphy
                // stroke doesn't rotate on pen-up. See docs/onyx-sdk/onyx-pen-styles-catalog.md.
                if (toolbarState.pen == Pen.CALLIGRAPHY) {
                    configureCalligraphyLiveAngle(angleDegrees = 45f, strokeWidth = scaledWidth)
                }
            }

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

    /** Brings raw drawing in line with [ToolbarUiState.isDrawing]. Main thread. */
    fun reconcileRawDrawing() {
        val controller = rawDrawing ?: return
        val state = toolbarState
        log.d(
            "Update is drawing: ${state.isDrawing} (resumed=${state.isResumed}, " +
                "focus=${state.hasWindowFocus}, menu=${state.isMenuOpen}, " +
                "eraserMenu=${state.isEraserMenuOpen}, penMenu=${state.penMenuPresetId}, " +
                "background=${state.isBackgroundSelectorModalOpen}, " +
                "quickNav=${state.isQuickNavOpen}, selection=${state.isSelectionActive}, " +
                "selectionGesture=${state.isSelectionGestureActive})"
        )
        controller.reconcile()
    }

    /**
     * Run by [rawDrawing] after every enable. Every setRawDrawingEnabled call resets the
     * firmware's eraser channel (TouchHelper.resetPenDefaultRawDrawing), and SDK callbacks are
     * posted to the UI thread after the firmware has already started a stroke, so the eraser track
     * and the pen style must be configured right after the enable, before any stroke can begin.
     * See docs/onyx-sdk/onyx-native-eraser-indicator.md.
     */
    private fun armPen() {
        val helper = touchHelper ?: return
        enableNativeEraser(helper, toolbarState.eraser)
        updatePenAndStroke()
    }

    /**
     * Re-styles the native eraser track for the current eraser type. Must run when the type
     * changes: the re-assert in onBeginRawErasing lands after the firmware has latched the style,
     * so without this the first button-erase after switching would still use the old track.
     * While raw drawing is off there is nothing to update; [armPen] applies it on the next enable.
     */
    fun updateEraserTrack() {
        if (rawDrawing?.enabled != true) return
        enableNativeEraser(touchHelper, toolbarState.eraser)
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
            rawDrawing?.rebuildSession()
        }
    }
    private fun onRawDrawingList(plist: TouchPointList) {
        if (touchHelper == null) return
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
                            val controller = rawDrawing
                            if (controller != null && !controller.enabled) {
                                // The pen went off before this stroke arrived, and its page was
                                // pushed without it: the firmware no longer shows it, so post it.
                                drawCanvas.refreshManager.refreshUi(strokeArea)
                            } else {
                                drawCanvas.refreshManager.addFirmwareInk(strokeArea)
                            }
                        } else {
                            log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                            // Union the scribble track (firmware screen coords) with the erased
                            // strokes' bounds so commitErase overwrites both in one pass while
                            // still frozen. Scribble is not drawn into the page bitmap — we only
                            // need the region to cover the firmware's live track.
                            // See docs/onyx-sdk/onyx-scribble-to-erase.md.
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
        // See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
        val dirty = Rect(strokeArea)
        if (zoneEffected != null) dirty.union(zoneEffected)
        // Area (lasso/select) erase needs the longer 500ms settle the official app uses; the
        // pen/marker erase uses the 150ms stroke settle.
        drawCanvas.refreshManager.commitErase(dirty, areaErase = toolbarState.eraser == Eraser.SELECT)
    }

}