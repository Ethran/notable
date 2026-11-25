package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.ethran.notable.editor.utils.resetScreenFreeze
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.launch

class CanvasRefreshManager(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val penDevice: PenDevice?
) {
    private val log = ShipBook.getLogger("DrawCanvas")
    private val surfaceWriter = SurfaceWriter(drawCanvas, page, viewModel)

    /**
     * Screen area of strokes drawn since the firmware layer was last dropped. Only the firmware
     * shows them, so [commitErase] pushes them with the erased area before dropping its layer.
     */
    private val firmwareInk = Rect()

    /** Notes [area] as drawn on the panel by the firmware only, see [firmwareInk]. */
    fun addFirmwareInk(area: Rect) {
        synchronized(firmwareInk) { firmwareInk.union(area) }
    }

    fun refreshUi(dirtyRect: Rect?) {
        log.d("refreshUi: scroll: ${page.scroll}, zoom: ${page.zoom}")

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
        resetScreenFreeze(penDevice)
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
        resetScreenFreeze(penDevice)
    }

    /**
     * Atomic erase commit for both the pen-button eraser and scribble-to-erase. Pushes the
     * already-repainted page bitmap to the panel while the screen is still frozen, then fully
     * toggles setRawDrawingEnabled(false→true) to drop the firmware layer atomically — eraser
     * indicator and erased strokes disappear in one transition with no gap to draw into.
     * ORDER IS CRITICAL: push first, then drop. See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
     * Must be called after the page bitmap has already been repainted (after handleErase /
     * handleScribbleToErase). Strokes shown only by the firmware ([firmwareInk]) are pushed too.
     */
    fun commitErase(dirtyRect: Rect?, areaErase: Boolean = false) {
        val dirty = Rect(dirtyRect ?: Rect(0, 0, page.viewWidth, page.viewHeight))
        synchronized(firmwareInk) {
            dirty.union(firmwareInk)
            firmwareInk.setEmpty()
        }
        // 1. Block input immediately so no stroke can start during the swap/settle.
        penDevice?.setInputEnabled(false)
        drawCanvas.coroutineScope.launch {
            // 2. Push the erased page bitmap to the EPD *while still frozen* (a forced write goes
            //    through with enablePost(0)+enablePost(1)).
            surfaceWriter.write(dirty, bitmap = null, force = true)
            // 3. Now drop the firmware raw layer (eraser indicator / scribble ink). The panel
            //    already shows the clean page, so this is a no-flash transition.
            penDevice?.setDrawingEnabled(false)
            // 4. Settle before re-arming (150ms stroke / 500ms area), mirroring the official app.
            DeviceCompat.delayBeforeResumingDrawing(isErasing = true, areaErase = areaErase)
            // 5. Re-arm raw drawing (also re-enables input). The heavy toggle resets the eraser
            //    channel and stroke style; enableRawDrawing re-asserts both (matches the
            //    official C(true) path).
            if (viewModel.toolbarState.value.isDrawing) {
                drawCanvas.inputHandler.enableRawDrawing()
            } else {
                log.w("commitErase: not in drawing mode, leaving raw drawing disabled")
            }
        }
    }

    fun drawCanvasToView(dirtyRect: Rect?) {
        drawCanvas.post { drawCanvasToViewSync(dirtyRect) }
    }

    /**
     * [drawCanvasToView] without the post: draws the page (and the page-cut line in Select mode)
     * to the surface now. Main thread only.
     */
    fun drawCanvasToViewSync(dirtyRect: Rect?) {
        surfaceWriter.write(dirtyRect, bitmap = null, force = false)
    }

    fun restoreCanvas(dirtyRect: Rect, bitmap: Bitmap = page.windowedBitmap) {
        drawCanvas.post {
            surfaceWriter.write(dirtyRect, bitmap, force = false)
            // Trigger partial refresh
            refreshScreenRegion(drawCanvas, dirtyRect)
        }
    }
}
