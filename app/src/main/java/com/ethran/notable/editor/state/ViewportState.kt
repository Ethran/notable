package com.ethran.notable.editor.state

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
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
 * ### The viewport is per-view, not per-page
 *
 * [scroll], [zoomLevel] and the viewport dimensions are held here, so two views of the *same* page
 * scroll and zoom independently — which is the point of being able to open a document twice.
 *
 * [PageDataManager] remains the *persistence* layer: writes are mirrored into it so `setScrollInDb`
 * can reach the database, the scroll indicator can read the current position, and reopening a page
 * restores where you were. It is no longer the live source of truth.
 */
class ViewportState(
    private val pageDataManager: PageDataManager,
    width: Int,
    height: Int,
    private val currentPageId: () -> String,
) {
    private val log = ShipBook.getLogger("ViewportState")

    /**
     * Size of the region this viewport covers, in screen pixels.
     *
     * Owned here rather than read from the `SCREEN_WIDTH` / `SCREEN_HEIGHT` globals, because a
     * viewport is not necessarily the whole display — and will not be once the editor supports a
     * split view.
     */
    var width: Int = width
        private set

    var height: Int = height
        private set

    /** Adopt a new viewport size. Idempotent; returns true if the dimensions actually changed. */
    fun resize(newWidth: Int, newHeight: Int): Boolean {
        if (newWidth == width && newHeight == height) return false
        width = newWidth
        height = newHeight
        return true
    }

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
     * Snapping toggles between 1.0 and the **viewport's** aspect ratio. This previously used
     * `SCREEN_WIDTH` / `SCREEN_HEIGHT` — process-wide globals describing the *display* — so the
     * targets described the panel rather than the region being zoomed. Harmless while a viewport
     * fills the screen, meaningless for anything narrower, which a split view would be.
     *
     * For a full-screen viewport the two are equivalent apart from chrome (the editor area
     * excludes the toolbar), so single-view behaviour is materially unchanged.
     */
    fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
    ): Float {
        // TODO: Better snapping logic
        val viewportRatio = width.toFloat() / height

        return if (!GlobalAppSettings.current.continuousZoom) {
            // Discrete zoom mode - snap to either 1.0 or the viewport ratio.
            // scaleDelta is a growth ratio minus 1 (see PointerTracker.pinchRatio),
            // so it is negative when pinching in (zoom out) and positive when
            // spreading (zoom in); split on 0, not 1.
            if (scaleDelta <= 0f) {
                if (height > width) viewportRatio else 1.0f
            } else {
                if (height > width) 1.0f else viewportRatio
            }
        } else {
            // Continuous zoom: scaleDelta is the per-frame growth ratio minus 1,
            // so the zoom scales multiplicatively. ZOOM_SENSITIVITY damps how
            // hard the pinch drives the zoom (< 1 zooms more gently than the
            // fingers spread).
            val newZoom =
                (currentZoom * (1f + scaleDelta * ZOOM_SENSITIVITY)).coerceIn(MIN_ZOOM, MAX_ZOOM)

            // Snap to either 1.0 or screen ratio depending on which is closer
            val snapTarget = if (abs(newZoom - 1.0f) < abs(newZoom - viewportRatio)) {
                1.0f
            } else {
                viewportRatio
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
