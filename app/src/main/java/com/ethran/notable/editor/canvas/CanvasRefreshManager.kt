package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.pointsToPath
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.ethran.notable.editor.utils.resetScreenFreeze
import com.ethran.notable.utils.logCallStack
import com.onyx.android.sdk.pen.TouchHelper
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook

class CanvasRefreshManager(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val state: EditorState,
    private val touchHelper: TouchHelper?
) {
    private val log = ShipBook.getLogger("DrawCanvas")

    fun refreshUi(dirtyRect: Rect?) {
        log.d("refreshUi: scroll: ${page.scroll}, zoom: ${page.zoomLevel.value}")

        // post what page drawn to visible surface
        drawCanvasToView(dirtyRect)
        if (CanvasEventBus.drawingInProgress.isLocked) log.w("Drawing is still in progress there might be a bug.")

        // Use only if you have confidence that there are no strokes being drawn at the moment
        if (!state.isDrawing) {
            log.w("Not in drawing mode, skipping unfreezing")
            return
        }
        // reset screen freeze
        resetScreenFreeze(touchHelper)
    }

    suspend fun refreshUiSuspend() {
        // Do not use, if refresh need to be preformed without delay.
        // This function waits for strokes to be fully rendered.
        if (!state.isDrawing) {
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


    fun drawCanvasToView(dirtyRect: Rect?) {
        val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
        var canvas: Canvas? = null
        try {
            // Lock the canvas only for the dirtyRect region
            canvas = drawCanvas.holder.lockCanvas(zoneToRedraw) ?: return

            canvas.drawBitmap(page.windowedBitmap, zoneToRedraw, zoneToRedraw, Paint())

            if (drawCanvas.getActualState().mode == Mode.Select) {
                // render selection, but only within dirtyRect
                drawCanvas.getActualState().selectionState.firstPageCut?.let { cutPoints ->
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
                    drawCanvas.holder.unlockCanvasAndPost(canvas)
                }
            } catch (e: IllegalStateException) {
                log.w("Surface released during unlock", e)
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
                    holder.unlockCanvasAndPost(surfaceCanvas)
                }
                // Trigger partial refresh
                refreshScreenRegion(drawCanvas, dirtyRect)
            }
        }
    }
}