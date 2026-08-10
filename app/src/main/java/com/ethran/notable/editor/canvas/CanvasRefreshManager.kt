package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.pointsToPath
import com.ethran.notable.editor.utils.enableNativeEraser
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.ethran.notable.editor.utils.resetScreenFreeze
import com.ethran.notable.utils.logCallStack
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.pen.TouchHelper
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch

class CanvasRefreshManager(
    private val drawCanvas: DrawCanvas,
    private val viewModel: EditorViewModel,
    private val touchHelper: TouchHelper?
) {
    private val log = ShipBook.getLogger("DrawCanvas")

    // Follows focus. Passing a PageView in by value captured the first pane forever, so every
    // default dirty rect and every log line described the left pane whichever one was active.
    private val page: PageView get() = drawCanvas.activePane.page
    private val blitPaint = Paint()

    // Mid grey: on a monochrome panel a black divider competes with ink, and white is invisible.
    private val dividerPaint = Paint().apply { color = android.graphics.Color.GRAY }

    /** Thickness of the active-pane outline, in pixels. */
    private val outlineWidth = 3f

    private val outlinePaint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth
    }

    /**
     * The whole drawing surface, in surface coordinates.
     *
     * Deliberately not `page.viewWidth/viewHeight`: those are the *pane's* dimensions, which stop
     * matching the surface as soon as there is more than one pane. Falls back to the page while
     * the view has not been laid out yet.
     */
    private fun surfaceRect(): Rect =
        if (drawCanvas.width > 0 && drawCanvas.height > 0)
            Rect(0, 0, drawCanvas.width, drawCanvas.height)
        else
            Rect(0, 0, page.viewWidth, page.viewHeight)

    /**
     * Paints every pane that intersects [surfaceDirty] onto the locked surface canvas.
     *
     * Each pane holds a view-sized bitmap of its own page, so the blit maps pane-local source
     * pixels to the pane's slice of the surface. Panes are clipped to their own rect and never to
     * each other's, which is what stops one pane's redraw from painting over its neighbour.
     */
    private fun blitPanes(canvas: Canvas, surfaceDirty: Rect) {
        val panes = drawCanvas.panes
        for (pane in panes) {
            // A pane is laid out from surfaceChanged. If a draw somehow beats that, an empty rect
            // would clip everything away and the screen would come up blank with no error — so a
            // lone pane falls back to owning the whole surface, which is what it will be given.
            // Deliberately not applied when there are several: silently overlapping panes would
            // hide a real layout bug.
            val paneRect =
                if (pane.screenRect.isEmpty && panes.size == 1) surfaceRect() else pane.screenRect

            val dst = Rect(surfaceDirty)
            if (!dst.intersect(paneRect)) continue
            val src = Rect(dst).apply { offset(-paneRect.left, -paneRect.top) }
            canvas.drawBitmap(pane.page.windowedBitmap, src, dst, blitPaint)
        }
        drawDividers(canvas, surfaceDirty)
        drawActivePaneOutline(canvas, surfaceDirty)
    }

    /**
     * Outline the pane that input is directed at, so it is obvious which one the pen and toolbar
     * will act on.
     *
     * Drawn onto the surface rather than into a pane's bitmap: the bitmap holds page content, and
     * an outline baked into it would be saved with the note.
     */
    private fun drawActivePaneOutline(canvas: Canvas, surfaceDirty: Rect) {
        if (drawCanvas.panes.size < 2) return
        val rect = drawCanvas.activePane.screenRect
        if (rect.isEmpty) return
        if (!Rect(surfaceDirty).intersect(rect)) return
        // Inset by half the stroke so the whole outline lands inside the pane rather than
        // straddling the boundary and bleeding into the gutter.
        val inset = outlineWidth / 2f
        canvas.drawRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            outlinePaint,
        )
    }

    /**
     * Repaint just the outline bands of [panes], for when focus moves between them.
     *
     * Four thin strips per pane rather than the whole rect: a full-pane refresh at the moment the
     * pen touches down would flash the panel exactly when the user is trying to write.
     */
    fun refreshPaneOutlines(panes: List<com.ethran.notable.editor.Pane>) {
        val t = outlineWidth.toInt() + 1
        for (pane in panes) {
            val r = pane.screenRect
            if (r.isEmpty) continue
            listOf(
                Rect(r.left, r.top, r.right, r.top + t),
                Rect(r.left, r.bottom - t, r.right, r.bottom),
                Rect(r.left, r.top, r.left + t, r.bottom),
                Rect(r.right - t, r.top, r.right, r.bottom),
            ).forEach { drawCanvasToView(it) }
        }
    }

    /**
     * Fill the gaps between panes.
     *
     * Nothing else paints them: each pane clips to its own rect, so without this the gutter keeps
     * whatever was on the panel last — on e-ink that means stale ink sitting between the panes
     * indefinitely. Drawn from the gaps between pane rects rather than a stored divider position,
     * so it cannot drift out of step with the layout.
     */
    private fun drawDividers(canvas: Canvas, surfaceDirty: Rect) {
        val rects = drawCanvas.panes.map { it.screenRect }.filterNot { it.isEmpty }.sortedBy { it.left }
        if (rects.size < 2) return
        for (i in 0 until rects.size - 1) {
            val gap = Rect(rects[i].right, rects[i].top, rects[i + 1].left, rects[i].bottom)
            if (gap.isEmpty) continue
            if (!gap.intersect(surfaceDirty)) continue
            canvas.drawRect(gap, dividerPaint)
        }
    }

    fun refreshUi(dirtyRect: Rect?) {
        log.d("refreshUi: scroll: ${page.scroll}, zoom: ${page.zoomLevel.value}")

        // post what page drawn to visible surface
        drawCanvasToView(dirtyRect)
        if (CanvasEventBus.drawingInProgress.isLocked)
            log.w("Drawing is still in progress there might be a bug.")

        // Use only if you have confidence that there are no strokes being drawn at the moment
        if (!viewModel.toolbarState.value.isDrawing) {
            log.w("Not in drawing mode, skipping unfreezing")
            return
        }
        // reset screen freeze
        resetScreenFreeze(touchHelper)
        log.d("refreshUi: done")
    }

    suspend fun refreshUiSuspend() {
        // Do not use, if refresh need to be preformed without delay.
        // This function waits for strokes to be fully rendered.
        if (!viewModel.toolbarState.value.isDrawing) {
            CanvasEventBus.waitForDrawing()
            drawCanvasToView(null)
            log.w("Not in drawing mode -- refreshUi ")
            return
        }
        if (Looper.getMainLooper().isCurrentThread) {
            log.w(
                "refreshUiSuspend() is called from the main thread."
            )
            logCallStack("refreshUiSuspend_main_thread")
        } else log.v(
            "refreshUiSuspend() is called from the non-main thread."
        )
        CanvasEventBus.waitForDrawing()
        drawCanvasToView(null)
        resetScreenFreeze(touchHelper)
    }

    /**
     * Atomic erase commit for both the pen-button eraser and scribble-to-erase. Pushes the
     * already-repainted page bitmap to the panel while the screen is still frozen, then fully
     * toggles setRawDrawingEnabled(false→true) to drop the firmware layer atomically — eraser
     * indicator and erased strokes disappear in one transition with no gap to draw into.
     * ORDER IS CRITICAL: push first, then drop. See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
     * Must be called after the page bitmap has already been repainted (after handleErase /
     * handleScribbleToErase).
     */
    fun commitErase(dirtyRect: Rect?, areaErase: Boolean = false) {
        val dirty = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
        // 1. Block input immediately so no stroke can start during the swap/settle.
        touchHelper?.setRawInputReaderEnable(false)
        drawCanvas.coroutineScope.launch {
            // 2. Push the erased page bitmap to the EPD *while still frozen* (drawBitmapToSurfaceSync
            //    forces it through with enablePost(0)+enablePost(1), like kreader's renderToScreen).
            drawBitmapToSurfaceSync(dirty)
            // 3. Now drop the firmware raw layer (eraser indicator / scribble ink). The panel
            //    already shows the clean page, so this is a no-flash transition.
            touchHelper?.setRawDrawingEnabled(false)
            // 4. Settle before re-arming (150ms stroke / 500ms area), mirroring the official app.
            DeviceCompat.delayBeforeResumingDrawing(isErasing = true, areaErase = areaErase)
            // 5. Re-arm raw drawing. The heavy toggle resets the eraser channel and stroke
            //    style, so re-assert both (matches the official C(true) path).
            if (viewModel.toolbarState.value.isDrawing) {
                touchHelper?.setRawDrawingEnabled(true)
                enableNativeEraser(touchHelper, viewModel.toolbarState.value.eraser)
                drawCanvas.inputHandler.updatePenAndStroke()
                touchHelper?.setRawInputReaderEnable(true)
            } else {
                log.w("commitErase: not in drawing mode, leaving raw drawing disabled")
            }
        }
    }

    /** Synchronous variant of [drawCanvasToView] (no `post{}` hop). Locks, draws the page
     *  bitmap for [dirtyRect], and posts — on the calling thread. */
    private fun drawBitmapToSurfaceSync(dirtyRect: Rect?) {
        val zoneToRedraw = dirtyRect ?: surfaceRect()
        var canvas: Canvas? = null
        try {
            canvas = drawCanvas.holder.lockCanvas(zoneToRedraw)
            if (canvas == null) {
                log.e("commitErase: failed to lock canvas (surface invalid/destroyed)")
                return
            }
            // enablePost(0)+enablePost(1) forces the bitmap through to the EPD even while the
            // screen is frozen (a bare unlockCanvasAndPost is swallowed). Mirrors kreader's
            // RxBaseReaderRequest.unlockCanvas.
            EpdController.enablePost(0)
            EpdController.enablePost(1)
            blitPanes(canvas, zoneToRedraw)
        } catch (e: IllegalStateException) {
            log.w("Surface released during erase draw", e)
        } finally {
            try {
                if (canvas != null) drawCanvas.holder.unlockCanvasAndPost(canvas)
                // Let the EPD apply the pushed region (kreader's afterUnlockCanvas equivalent).
                EpdController.resetViewUpdateMode(drawCanvas)
            } catch (e: IllegalStateException) {
                log.w("Surface released during unlock", e)
            }
        }
    }

    fun drawCanvasToView(dirtyRect: Rect?, onPosted: (() -> Unit)? = null) {
        drawCanvas.post {
            val zoneToRedraw = dirtyRect ?: surfaceRect()
            var canvas: Canvas? = null
            try {
                log.v("Canvas refresh started, dirtyRect: $zoneToRedraw, bitmap: ${page.windowedBitmap.hashCode()}, thread: ${Thread.currentThread().name}")
                // Lock the canvas only for the dirtyRect region
                canvas = drawCanvas.holder.lockCanvas(zoneToRedraw)
                if (canvas == null) {
                    log.e(
                        "FAILED to lock canvas (surface invalid/destroyed or locked by another " +
                            "thread). page=${page.currentPageId}, dirtyRect=$zoneToRedraw, " +
                            "surfaceValid=${drawCanvas.holder.surface?.isValid}, " +
                            "thread=${Thread.currentThread().name}"
                    )
                    return@post
                }
                blitPanes(canvas, zoneToRedraw)

                if (viewModel.toolbarState.value.mode == Mode.Select) {
                    // render selection, but only within dirtyRect
                    viewModel.selectionState.firstPageCut?.let { cutPoints ->
                        log.i("render cut")
                        val path = pointsToPath(cutPoints.map {
                            SimplePointF(
                                it.x - page.scroll.x, it.y - page.scroll.y
                            )
                        })
                        canvas.drawPath(path, selectPaint)
                    }
                }
            } catch (e: IllegalStateException) {
                log.w("Surface released during draw", e)
                // ignore — surface is gone
            } finally {
                try {
                    if (canvas != null) {
                        log.d("drawCanvasToView: page=${page.currentPageId} bitmap=${page.windowedBitmap.hashCode()}")
                        drawCanvas.holder.unlockCanvasAndPost(canvas)
                    }
                    log.v("Canvas refreshed")
                } catch (e: IllegalStateException) {
                    log.w("Surface released during unlock", e)
                } finally {
                    onPosted?.invoke()
                }
            }
        }
    }


    /**
     * Repaints [dirtyRect] from [bitmap], or from the panes when [bitmap] is null.
     *
     * The explicit-bitmap form is quick-nav preview scrubbing, which shows a single page preview
     * over the whole region and is an active-pane concept; the null form restores what the panes
     * actually hold.
     */
    fun restoreCanvas(dirtyRect: Rect, bitmap: Bitmap? = null) {
        drawCanvas.post {
            val holder = drawCanvas.holder
            var surfaceCanvas: Canvas? = null
            try {
                surfaceCanvas = holder.lockCanvas(dirtyRect)
                if (bitmap != null) {
                    // Draw the preview bitmap scaled to fit the dirty rect
                    surfaceCanvas.drawBitmap(bitmap, dirtyRect, dirtyRect, null)
                } else {
                    blitPanes(surfaceCanvas, dirtyRect)
                }
            } catch (e: Exception) {
                Log.e("DrawCanvas", "Canvas lock failed: ${e.message}")
            } finally {
                if (surfaceCanvas != null) {
                    log.d("restoreCanvas: page=${page.currentPageId} bitmap=${bitmap?.hashCode()}")
                    holder.unlockCanvasAndPost(surfaceCanvas)
                }
                // Trigger partial refresh
                refreshScreenRegion(drawCanvas, dirtyRect)
            }
        }
    }
}