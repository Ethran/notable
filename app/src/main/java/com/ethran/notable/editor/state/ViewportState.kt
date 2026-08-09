package com.ethran.notable.editor.state

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.utils.div
import com.ethran.notable.editor.utils.minus
import com.ethran.notable.editor.utils.plus
import com.ethran.notable.editor.utils.times
import com.ethran.notable.gestures.MAX_ZOOM
import com.ethran.notable.gestures.MIN_ZOOM
import com.ethran.notable.gestures.ZOOM_SENSITIVITY
import com.ethran.notable.gestures.ZOOM_SNAP_THRESHOLD
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * The editor's view onto a page: how far it is scrolled, how far it is zoomed, and the conversion
 * between screen and page coordinates.
 *
 * Extracted from [com.ethran.notable.editor.PageView], which had accumulated this alongside page
 * lifecycle, data proxying and rendering. Pairs with
 * [com.ethran.notable.editor.drawing.PageRenderer]: the renderer owns *where* pixels land, this
 * owns *which part of the page* they represent.
 *
 * ### Scroll is not stored here (yet)
 *
 * [scroll] deliberately still delegates to [PageDataManager], keyed by page id — the same
 * indirection `PageView` had. This class is currently an owner of that *delegation*, not of the
 * state itself, which keeps the extraction a pure move with no behaviour change.
 *
 * The consequence is that two views of the *same* page share a scroll offset and zoom, because
 * both key off one page id. Two views of *different* pages are already independent. Making the
 * viewport genuinely per-view is a follow-up, and is a design change rather than a refactor.
 */
class ViewportState(
    private val pageDataManager: PageDataManager,
    private val currentPageId: () -> String,
) {
    private val log = ShipBook.getLogger("ViewportState")

    /**
     * Top-left corner of the view, in page coordinates.
     *
     * **Owned here, not in [PageDataManager].** Each viewport scrolls independently, so two views
     * of the same page no longer share a position — which is the point: two views of one document
     * exist precisely to look at two places in it.
     *
     * Writes are mirrored into [PageDataManager] because it remains the *persistence* layer: it is
     * what `setScrollInDb` writes to the database, what the scroll indicator reads, and what
     * restores position when a page is reopened. It is simply no longer the live source of truth.
     *
     * Identical writes are skipped so that adopting a persisted value in [reloadFromPersistence]
     * does not materialise a map entry that `getPageScroll` deliberately avoids creating on read.
     */
    var scroll: Offset = pageDataManager.getPageScroll(currentPageId())
        set(value) {
            if (field == value) return
            field = value
            pageDataManager.setPageScroll(currentPageId(), value)
        }

    /**
     * Observed by the UI so stroke widths can track the zoom. Already per-instance, and written
     * back to [PageDataManager] by `CanvasObserverRegistry` for the same persistence reason.
     */
    val zoomLevel: MutableStateFlow<Float> =
        MutableStateFlow(pageDataManager.getPageZoom(currentPageId()))

    /**
     * Adopt the persisted viewport for whichever page this view now shows.
     *
     * Must be called after the page behind the view changes. Previously unnecessary for [scroll],
     * which read through to [PageDataManager] on every access and so followed the page implicitly;
     * now that the value is held here, a page switch has to reload it explicitly.
     */
    fun reloadFromPersistence() {
        val pageId = currentPageId()
        scroll = pageDataManager.getPageScroll(pageId)
        zoomLevel.value = pageDataManager.getPageZoom(pageId)
    }

    val isTransformationAllowed: Boolean
        get() = pageDataManager.isTransformationAllowedForCurrentPage()

    // ------------------------------------------------------------ coordinate transforms

    /**
     * Page coordinates to screen coordinates.
     *
     * Note the rounding is asymmetric between the two operations: translation rounds to nearest,
     * scaling truncates toward zero. Characterised in `GeometryExtensionsTest`.
     */
    fun toScreenCoordinates(rect: Rect): Rect = (rect - scroll) * zoomLevel.value

    /** Screen coordinates to page coordinates. Lossy at fractional zoom — see the same tests. */
    fun toPageCoordinates(rect: Rect): Rect = rect / zoomLevel.value + scroll

    /** Page coordinates to page coordinates relative to the current scroll origin. */
    fun removeScroll(rect: Rect): Rect = rect - scroll

    // ------------------------------------------------------------ zoom

    /**
     * The next zoom level for a pinch of [scaleDelta] from [currentZoom].
     *
     * **Derives its snap targets from the device, not from this viewport.** `SCREEN_WIDTH` and
     * `SCREEN_HEIGHT` are process-wide globals describing the display, so the targets describe the
     * panel rather than the region being zoomed. That is harmless while a page fills the screen and
     * wrong for anything narrower. Left as-is here so this extraction stays a pure move;
     * characterised in `PageViewZoomTest` so changing it later is deliberate and visible.
     */
    fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
    ): Float {
        // TODO: Better snapping logic
        val portraitRatio = SCREEN_WIDTH.toFloat() / SCREEN_HEIGHT

        return if (!GlobalAppSettings.current.continuousZoom) {
            // Discrete zoom mode - snap to either 1.0 or screen ratio.
            // scaleDelta is a growth ratio minus 1 (see PointerTracker.pinchRatio),
            // so it is negative when pinching in (zoom out) and positive when
            // spreading (zoom in); split on 0, not 1.
            if (scaleDelta <= 0f) {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) portraitRatio else 1.0f
            } else {
                if (SCREEN_HEIGHT > SCREEN_WIDTH) 1.0f else portraitRatio
            }
        } else {
            // Continuous zoom: scaleDelta is the per-frame growth ratio minus 1,
            // so the zoom scales multiplicatively. ZOOM_SENSITIVITY damps how
            // hard the pinch drives the zoom (< 1 zooms more gently than the
            // fingers spread).
            val newZoom =
                (currentZoom * (1f + scaleDelta * ZOOM_SENSITIVITY)).coerceIn(MIN_ZOOM, MAX_ZOOM)

            // Snap to either 1.0 or screen ratio depending on which is closer
            val snapTarget = if (abs(newZoom - 1.0f) < abs(newZoom - portraitRatio)) {
                1.0f
            } else {
                portraitRatio
            }

            if (abs(newZoom - snapTarget) < ZOOM_SNAP_THRESHOLD) {
                log.d("Zoom snap to $snapTarget")
                snapTarget
            } else {
                log.d("Left zoom as is. $newZoom")
                newZoom
            }
        }
    }
}
