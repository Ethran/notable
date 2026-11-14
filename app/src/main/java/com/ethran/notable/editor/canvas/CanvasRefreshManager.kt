package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.pointsToPath
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook

class CanvasRefreshManager(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
) {
    private val log = ShipBook.getLogger("DrawCanvas")

    /**
     * Screen area of strokes drawn since the firmware layer was last dropped. Only the firmware
     * shows them, so [commitErase] pushes them with the erased area before dropping its layer.
     */
    private val firmwareInk = Rect()

    /** Notes [area] as drawn on the panel by the firmware only, see [firmwareInk]. */
    fun addFirmwareInk(area: Rect) {
        synchronized(firmwareInk) { firmwareInk.union(area) }
    }

    /**
     * Shows [dirtyRect] of the page (null: all of it) on the panel, from any thread. While the pen
     * draws, the firmware render is frozen for the post and comes back after a settle.
     */
    fun refreshUi(dirtyRect: Rect?) {
        log.d("refreshUi: scroll: ${page.scroll}, zoom: ${page.zoom}")
        if (CanvasEventBus.drawingInProgress.isLocked)
            log.w("Drawing is still in progress there might be a bug.")
        drawCanvas.post { postPage(dirtyRect) }
    }

    /** [refreshUi] of the whole page, after the stroke being drawn (if any) is in the bitmap. */
    suspend fun refreshUiSuspend() {
        CanvasEventBus.waitForDrawing()
        refreshUi(null)
    }

    /** Main thread. */
    private fun postPage(dirtyRect: Rect?) {
        val rawDrawing = drawCanvas.inputHandler.rawDrawing
        if (rawDrawing != null) rawDrawing.postPage(dirtyRect) else drawCanvasToViewSync(dirtyRect)
    }

    /**
     * Atomic erase commit for both the pen-button eraser and scribble-to-erase, on the main
     * thread: [RawDrawingController.commitErase] pushes the already-repainted page while the
     * screen is still frozen, then drops the firmware layer, so the eraser indicator and the
     * erased strokes disappear in one transition with no gap to draw into.
     * See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
     * Must be called after the page bitmap has already been repainted (after handleErase /
     * handleScribbleToErase). Strokes shown only by the firmware ([firmwareInk]) are pushed too.
     */
    fun commitErase(dirtyRect: Rect?, areaErase: Boolean = false) {
        val dirty = Rect(dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight))
        synchronized(firmwareInk) {
            dirty.union(firmwareInk)
            firmwareInk.setEmpty()
        }
        val rawDrawing = drawCanvas.inputHandler.rawDrawing
        if (rawDrawing != null) rawDrawing.commitErase(dirty, areaErase)
        else drawCanvasToViewSync(dirty)
    }

    /**
     * [drawCanvasToViewSync] forced through to the panel while raw drawing still owns the screen.
     * Main thread only.
     */
    fun forceCanvasToViewSync(dirtyRect: Rect?) {
        val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
        var canvas: Canvas? = null
        try {
            canvas = drawCanvas.holder.lockCanvas(zoneToRedraw)
            if (canvas == null) {
                log.e("forceCanvasToViewSync: failed to lock canvas (surface invalid/destroyed)")
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
        drawCanvas.post { drawCanvasToViewSync(dirtyRect, onPosted) }
    }

    /**
     * [drawCanvasToView] without the post: draws the page (and the page-cut line in Select mode)
     * to the surface now. Main thread only.
     */
    fun drawCanvasToViewSync(dirtyRect: Rect?, onPosted: (() -> Unit)? = null) {
        val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
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
                return
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