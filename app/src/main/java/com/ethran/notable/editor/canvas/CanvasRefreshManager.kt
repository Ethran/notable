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
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val touchHelper: TouchHelper?
) {
    private val log = ShipBook.getLogger("DrawCanvas")

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
        val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
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
            canvas.drawBitmap(page.windowedBitmap, zoneToRedraw, zoneToRedraw, Paint())
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
            val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
            var canvas: Canvas? = null
            try {
                log.v("Canvas refresh started, dirtyRect: $zoneToRedraw, bitmap: ${page.windowedBitmap.hashCode()}, thread: ${Thread.currentThread().name}")
                // Lock the canvas only for the dirtyRect region
                canvas = drawCanvas.holder.lockCanvas(zoneToRedraw)
                if (canvas == null) {
                    log.e("FAILED to lock canvas! Surface is likely invalid, destroyed, or locked by another thread.")
                    return@post
                }
                canvas.drawBitmap(page.windowedBitmap, zoneToRedraw, zoneToRedraw, Paint())

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


    fun restoreCanvas(dirtyRect: Rect, bitmap: Bitmap = page.windowedBitmap) {
        drawCanvas.post {
            val holder = drawCanvas.holder
            var surfaceCanvas: Canvas? = null
            try {
                surfaceCanvas = holder.lockCanvas(dirtyRect)
                // Draw the preview bitmap scaled to fit the dirty rect
                surfaceCanvas.drawBitmap(bitmap, dirtyRect, dirtyRect, null)
            } catch (e: Exception) {
                Log.e("DrawCanvas", "Canvas lock failed: ${e.message}")
            } finally {
                if (surfaceCanvas != null) {
                    log.d("restoreCanvas: page=${page.currentPageId} bitmap=${bitmap.hashCode()}")
                    holder.unlockCanvasAndPost(surfaceCanvas)
                }
                // Trigger partial refresh
                refreshScreenRegion(drawCanvas, dirtyRect)
            }
        }
    }
}