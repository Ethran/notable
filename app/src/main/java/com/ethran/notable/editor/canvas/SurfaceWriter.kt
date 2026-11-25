package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.selectPaint
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.pointsToPath
import com.onyx.android.sdk.api.device.epd.EpdController
import io.shipbook.shipbooksdk.ShipBook

/** The only code that locks and posts the canvas surface. */
class SurfaceWriter(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
) {
    private val log = ShipBook.getLogger("DrawCanvas")
    private val bitmapPaint = Paint()
    private val debugPostPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    /**
     * Locks the surface over [dirtyRect] (screen coordinates; null: all of it), draws [bitmap]
     * (null: the page, with the page-cut line in Select mode) there, and posts it. With [force],
     * the post is forced through to the panel while raw drawing still owns the screen. Returns
     * whether the requested content was drawn and posted (false when the lock, the draw or the
     * post failed).
     */
    fun write(dirtyRect: Rect?, bitmap: Bitmap?, force: Boolean): Boolean {
        val zoneToRedraw = dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight)
        val source = bitmap ?: page.windowedBitmap
        val start = SystemClock.uptimeMillis()
        var canvas: Canvas? = null
        // The requested content was drawn; it counts as shown only once the post succeeds too.
        var drawn = false
        var submitted = false
        try {
            log.v("Canvas refresh started, dirtyRect: $zoneToRedraw, bitmap: ${source.hashCode()}, force: $force")
            canvas = drawCanvas.holder.lockCanvas(zoneToRedraw)
            if (canvas == null) {
                log.e(
                    "FAILED to lock canvas (surface invalid/destroyed or locked by another " +
                        "thread). page=${page.currentPageId}, dirtyRect=$zoneToRedraw, " +
                        "surfaceValid=${drawCanvas.holder.surface?.isValid}"
                )
                return false
            }
            if (force) {
                // enablePost(0)+enablePost(1) forces the bitmap through to the EPD even while the
                // screen is frozen (a bare unlockCanvasAndPost is swallowed).
                EpdController.enablePost(0)
                EpdController.enablePost(1)
            }
            canvas.drawBitmap(source, zoneToRedraw, zoneToRedraw, bitmapPaint)
            if (bitmap == null) drawPageCut(canvas)
            // Debug mode: outline what this post covers, on the surface only (the page bitmap
            // stays clean), so the outline lasts until that area is posted again.
            if (GlobalAppSettings.current.debugMode) canvas.drawRect(zoneToRedraw, debugPostPaint)
            drawn = true
        } catch (e: RuntimeException) {
            log.w("Surface released or invalid during draw", e)
        } finally {
            try {
                // A locked canvas is always released, drawn or not.
                if (canvas != null) {
                    drawCanvas.holder.unlockCanvasAndPost(canvas)
                    submitted = drawn
                }
                // Let the EPD apply the pushed region.
                if (force) EpdController.resetViewUpdateMode(drawCanvas)
                if (canvas != null) log.d(
                    "write: page=${page.currentPageId}, rect=$zoneToRedraw, " +
                        "bitmap=${source.hashCode()}, force=$force, submitted=$submitted, " +
                        "took ${SystemClock.uptimeMillis() - start} ms"
                )
            } catch (e: IllegalStateException) {
                log.w("Surface released during unlock", e)
            }
        }
        return submitted
    }

    /** In Select mode, the selection's page-cut line, in screen coordinates. */
    private fun drawPageCut(canvas: Canvas) {
        if (viewModel.toolbarState.value.mode != Mode.Select) return
        val cutPoints = viewModel.selectionState.firstPageCut ?: return
        log.i("render cut")
        val path = pointsToPath(cutPoints.map { SimplePointF(it.x - page.scroll.x, it.y - page.scroll.y) })
        canvas.drawPath(path, selectPaint)
    }
}
