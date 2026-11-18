package com.ethran.notable.editor.canvas

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.geometry.Offset
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
import com.ethran.notable.editor.utils.cancelPendingScreenFreezeReset
import com.ethran.notable.editor.utils.copyInput
import com.ethran.notable.editor.utils.configureCalligraphyLiveAngle
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.ERASER_INDICATOR_COLOR
import com.ethran.notable.editor.utils.enableNativeEraser
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.handleSelect
import com.ethran.notable.editor.utils.onSurfaceInit
import com.ethran.notable.editor.utils.penToStroke
import com.ethran.notable.editor.utils.setupSurface
import com.ethran.notable.editor.utils.transformToLine
import com.ethran.notable.editor.canvas.rectify.BandPaintSpec
import com.ethran.notable.editor.canvas.rectify.toPoint2D
import com.ethran.notable.editor.canvas.rectify.PenStrokeRectifier
import com.ethran.notable.editor.canvas.rectify.Point2D
import com.ethran.notable.editor.canvas.rectify.RectBounds
import com.ethran.notable.editor.canvas.rectify.RectifyCommit
import com.ethran.notable.editor.canvas.rectify.RectifyRenderer
import com.ethran.notable.editor.canvas.rectify.toPageStrokePoint
import com.ethran.notable.editor.canvas.rectify.rectifyBandStroke
import com.ethran.notable.editor.canvas.rectify.toRect
import com.ethran.notable.editor.canvas.rectify.toRectBounds
import com.ethran.notable.editor.drawing.onyx.OnyxStrokeRenderer
import com.ethran.notable.editor.utils.rawInputMaxPressure
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OnyxInputHandler(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val history: History,
    private val coroutineScope: CoroutineScope,
) {
    var isErasing: Boolean = false
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
    /**
     * Holds the rectify gesture's whole rule — dwell, band bounds, push throttle, commit point.
     * Its clock is the wall clock, its panel the real EPD, and it reports the mode from the
     * toolbar, so it is wired here rather than being passed in.
     */
    private val rectifier = PenStrokeRectifier(
        now = { System.currentTimeMillis() },
        renderer = OnyxRectifyRenderer(),
        surfaceSize = { page.viewWidth to page.viewHeight },
    )

    /** True while the pen's gesture is being rectified into a straight line. */
    private val rectifyMode get() = rectifier.isActive

    /**
     * Pushes over the firmware layer, draws the band as described by the paint spec, and reports the
     * pushed area; the blit that wipes the previous band happens inside `drawOverFirmware`.
     */
    private inner class OnyxRectifyRenderer : RectifyRenderer {

        override fun clearFirmwareLayer(area: RectBounds) {
            drawCanvas.refreshManager.drawOverFirmware(area.toRect(), inset = 0) { /* blit only */ }
        }

        override fun drawBand(
            a: Point2D,
            b: Point2D,
            spec: BandPaintSpec,
            dirty: RectBounds,
        ): RectBounds? {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = spec.strokeWidthPx
                color = spec.color
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND

                // To have translucent highlighter during rectify
                if (spec.darken) blendMode = BlendMode.DARKEN
            }
            return drawCanvas.refreshManager
                .drawOverFirmware(dirty.toRect()) { canvas ->
                    if (spec.stamped) drawStampedBand(canvas, a, b, spec)
                    else canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                }
                ?.toRectBounds()
        }

        /** Stamps the band through the pen's own wrapper, via the dry renderer, so panel and page match. */
        private fun drawStampedBand(
            canvas: Canvas,
            a: Point2D,
            b: Point2D,
            spec: BandPaintSpec,
        ) {
            val viewport = page.viewport.value
            val stroke = rectifyBandStroke(
                spec = spec,
                a = a,
                b = b,
                scroll = viewport.scroll,
                zoom = viewport.zoom,
            )
            // The surface is screen space, so the page transform is applied by hand as PageView sets it up.
            canvas.save()
            canvas.scale(viewport.zoom, viewport.zoom)
            canvas.translate(-viewport.scroll.x, -viewport.scroll.y)
            OnyxStrokeRenderer.drawStroke(canvas, stroke, Offset.Zero)
            canvas.restore()
        }

        override fun wipeBand() {
            drawCanvas.refreshManager.clearOverFirmware()
        }

        // Render off, input on: setRawDrawingEnabled(false) would drop the rest of the gesture.
        override var rawDrawingRenderEnabled: Boolean
            get() = touchHelper?.isRawDrawingRenderEnabled ?: true
            set(value) {
                touchHelper?.isRawDrawingRenderEnabled = value
            }
    }

    /**
     * Commits a rectified gesture: the straight line from A to B goes into the page bitmap, and the
     * area the band covered is noted as firmware ink rather than pushed again.
     *
     * The band already sits on the panel and `handleDraw` has put the line in the page bitmap, so a
     * refresh here would only cost the raw-drawing toggle and its settle — the microfreeze and the
     * toolbar blip that `refreshUi` used to cause on this path. See commit 939a593f.
     */
    internal fun commitRectifiedGesture(
        commit: RectifyCommit?,
        refresh: CanvasRefreshManager = drawCanvas.refreshManager,
    ) {
        if (commit == null) return
        val gesturePageId = commit.pageId
        coroutineScope.launch(Dispatchers.Main.immediate) {
            CanvasEventBus.drawingInProgress.withLock {
                if (gesturePageId != page.currentPageId) {
                    log.i("Rectified line made on page $gesturePageId dropped, another page is shown")
                    return@withLock
                }
                val viewport = page.viewport.value
                // The engine holds screen positions; the page stroke needs page coordinates.
                val line = transformToLine(
                    commit.a.toPageStrokePoint(viewport.scroll, viewport.zoom),
                    commit.b.toPageStrokePoint(viewport.scroll, viewport.zoom),
                )
                // Flat pressure at the level the band was stamped at, instead of transformToLine's
                // endpoint ramp, which fades in where the pen came down. Raw scale divided to [0, 1].
                    .map {
                        it.copy(
                            pressure = (commit.pressure / rawInputMaxPressure).coerceIn(0f, 1f),
                        )
                    }
                handleDraw(
                    drawCanvas.page,
                    gesturePageId,
                    history,
                    toolbarState.activePenSetting.strokeSize,
                    toolbarState.activePenSetting.color,
                    toolbarState.pen,
                    line
                )
            }
            commit.dirty?.let { refresh.addFirmwareInk(it.toRect()) }
        }
    }

    @Suppress("RedundantOverride")
    private val inputCallback: RawInputCallback = object : RawInputCallback() {
        // Documentation: https://github.com/onyx-intl/OnyxAndroidDemo/blob/d3a1ffd3af231fe4de60a2a0da692c17cb35ce31/doc/Onyx-Pen-SDK.md#L40-L62
        // - pen : `onBeginRawDrawing()` -> `onRawDrawingTouchPointMoveReceived()` -> `onRawDrawingTouchPointListReceived()` -> `onEndRawDrawing()`
        // - erase :  `onBeginRawErasing()` -> `onRawErasingTouchPointMoveReceived()` -> `onRawErasingTouchPointListReceived()` -> `onEndRawErasing()`

        // Called on pen down event
        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
            penDownPageId = page.currentPageId
            log.d("PEN DOWN EVENT: A = (${p1?.x}, ${p1?.y}), bool $p0")
            // A is copied into the engine as a Point2D: the SDK recycles the TouchPoint it hands
            // over, and pen-up reads A later still.
            rectifier.onPenDown(
                a = p1?.toPoint2D(),
                pageId = page.currentPageId,
                pen = toolbarState.pen,
                strokeWidthPx = toolbarState.activePenSetting.strokeSize * page.zoom,
                // The nib, unscaled, as handleDraw stores it in Stroke.size.
                strokeWidth = toolbarState.activePenSetting.strokeSize,
                color = toolbarState.activePenSetting.color,
            )
        }

        // Called on pen up event
        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
            log.d("PEN UP EVENT: B = (${p1?.x}, ${p1?.y}), bool $p0")
            // The engine restores the firmware's live drawing and disarms the dwell detector, in a
            // gesture. Only the commit, which needs the page and the history, happens here.
            val commit = rectifier.onPenUp(p1?.toPoint2D())
            if (commit != null) {
                // The gesture is spent either way, so the page id is taken now.
                commitRectifiedGesture(commit.copy(pageId = takeGesturePageId()))
            } else {
                takeGesturePageId()
            }
        }

        // Called as long as pen is down
        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
//            log.d("PEN DOWN MOVE: = (${p0?.x}, ${p0?.y}), ${toolbarState.mode}")

            if (p0 == null) return;
            // Dwell detection, band bounds and the push throttle all live in the engine.
            rectifier.onMove(p0.toPoint2D(), canRectify = toolbarState.mode == Mode.Draw)
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
            isErasing = true
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
        if (touchHelper == null) return
        // it takes around 11 ms to run on Note 4c.
        log.i("Update pen and stroke")
        when (toolbarState.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Shape -> {
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

    suspend fun updateIsDrawing() {
        if (touchHelper == null) return
        log.i("Update is drawing: $toolbarState.isDrawing")
        if (toolbarState.isDrawing) {
            enableRawDrawing()
        } else {
            // A pending resetScreenFreeze resume would re-freeze the screen after we disable
            // raw drawing (e.g. lasso select: the select-stroke refreshUi armed it) — kill it.
            cancelPendingScreenFreezeReset()
            // Check if drawing is completed
            CanvasEventBus.waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvas.refreshManager.drawCanvasToView(null)
            touchHelper!!.setRawDrawingEnabled(false)
        }
    }

    /**
     * The only way raw drawing is (re)enabled. Every setRawDrawingEnabled call resets the
     * firmware's eraser channel (TouchHelper.resetPenDefaultRawDrawing), and SDK callbacks are
     * posted to the UI thread after the firmware has already started a stroke, so the eraser track
     * and the pen style must be configured here, synchronously, before any stroke can begin.
     * See docs/onyx-sdk/onyx-native-eraser-indicator.md.
     */
    fun enableRawDrawing() {
        val helper = touchHelper ?: return
        helper.setRawDrawingEnabled(true)
        enableNativeEraser(helper, toolbarState.eraser)
        updatePenAndStroke()
    }

    /**
     * Re-styles the native eraser track for the current eraser type. Must run when the type
     * changes: the re-assert in onBeginRawErasing lands after the firmware has latched the style,
     * so without this the first button-erase after switching would still use the old track.
     * While raw drawing is off there is nothing to update; [enableRawDrawing] applies it on resume.
     */
    fun updateEraserTrack() {
        if (!toolbarState.isDrawing) return
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
            val toolbarHeight =
                if (toolbarState.isToolbarOpen) convertDpToPixel(
                    40.dp,
                    drawCanvas.context
                ).toInt() else 0
            setupSurface(drawCanvas, touchHelper, toolbarHeight)
            // Armed inside the same coroutine, after the session is recreated: a caller that sets
            // the pen style right after updateActiveSurface() would otherwise race this launch.
            // Only when drawing is on: if updateIsDrawing() disabled it while the surface did not
            // exist yet (QuickNav open across a page navigation), nothing would disable it again.
            if (toolbarState.isDrawing) enableRawDrawing()
        }
    }

    private fun onRawDrawingList(plist: TouchPointList) {
        if (touchHelper == null) return
        if (rectifyMode) {
            // onEndRawDrawing runs first, so this is unreachable in theory. Commit rather than let
            // the raw wobble through.
            log.w("Rectify gesture reached the drawing list, committing the band instead")
            val commit = rectifier.onPenUp(plist.points.lastOrNull()?.toPoint2D())
            if (commit != null) {
                commitRectifiedGesture(commit.copy(pageId = takeGesturePageId()))
            }
            return
        }
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

            Mode.Draw, Mode.Shape -> {
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
                            val width =
                                (toolbarState.activePenSetting.strokeSize * viewport.zoom).toInt()
                            drawCanvas.refreshManager.addFirmwareInk(
                                trackArea(plist).apply { inset(-width, -width) }
                            )
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
        isErasing = false

        if (plist == null) return
        val strokeArea = trackArea(plist)
        if (pageId != page.currentPageId) {
            // Made on a page no longer shown: only the eraser track is cleared.
            log.i("Erasing made on page $pageId dropped, another page is shown")
            drawCanvas.refreshManager.commitErase(
                strokeArea,
                areaErase = toolbarState.eraser == Eraser.SELECT
            )
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
        drawCanvas.refreshManager.commitErase(
            dirty,
            areaErase = toolbarState.eraser == Eraser.SELECT
        )
    }

}