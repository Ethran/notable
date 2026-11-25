package com.ethran.notable.editor.canvas

import android.graphics.Bitmap
import android.graphics.Rect
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.utils.refreshScreenRegion
import com.ethran.notable.utils.checkMainThread
import io.shipbook.shipbooksdk.ShipBook

/**
 * The editor's requests for what the canvas surface shows, handed to the [RawDrawingController]
 * on the main thread. It decides what is written and when; nothing here writes the surface.
 */
class CanvasRefreshManager(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val rawDrawing: RawDrawingController<Bitmap>,
) {
    private val log = ShipBook.getLogger("DrawCanvas")

    /**
     * Shows [dirtyRect] of the page (screen coordinates; null: all of it) on the canvas surface,
     * from any thread. Only the page's surface: the Compose UI (toolbar, menus) is not touched.
     * Shown as soon as the page owns the surface ([RawDrawingController.showPage]).
     */
    fun refreshUi(dirtyRect: Rect?) {
        log.d("refreshUi: scroll: ${page.scroll}, zoom: ${page.zoom}")
        val area = dirtyRect?.toScreenArea()
        drawCanvas.post { rawDrawing.showPage(area) }
    }

    /** [refreshUi] of the whole page, after the stroke being drawn (if any) is in the bitmap. */
    suspend fun refreshUiSuspend() {
        CanvasEventBus.waitForDrawing()
        refreshUi(null)
    }

    /**
     * QuickNav [scrub] shows [preview] over the whole surface, from any thread, until it ends
     * ([endPreview]). Dropped if that scrub is over by the time it reaches the main thread.
     */
    fun showPreview(scrub: Int, preview: Bitmap) {
        drawCanvas.post {
            if (!CanvasEventBus.isCurrentScrub(scrub)) {
                log.d("QuickNav preview of scrub $scrub dropped: the scrub is over")
                return@post
            }
            if (rawDrawing.showPreview(scrub, preview)) refreshScreenRegion(drawCanvas, fullScreen())
        }
    }

    /**
     * QuickNav [scrub] ended: its preview (if it still owns the surface) gives way to the whole
     * page, refreshed in full on the panel. From any thread.
     */
    fun endPreview(scrub: Int) {
        drawCanvas.post {
            if (rawDrawing.endPreview(scrub)) refreshScreenRegion(drawCanvas, fullScreen())
        }
    }

    private fun fullScreen() = Rect(0, 0, page.viewWidth, page.viewHeight)

    /**
     * Atomic erase commit for both the pen-button eraser and scribble-to-erase, on the main
     * thread: [RawDrawingController.commitErase] pushes the already-repainted area (with every
     * stroke only the firmware shows) while the screen is still frozen, then drops the firmware
     * layer, so the eraser indicator and the erased strokes disappear in one transition.
     * Must be called after the page bitmap has already been repainted (after handleErase /
     * handleScribbleToErase).
     */
    fun commitErase(dirtyRect: Rect, areaErase: Boolean) {
        checkMainThread("CanvasRefreshManager.commitErase")
        rawDrawing.commitErase(dirtyRect.toScreenArea(), areaErase)
    }
}
