package com.ethran.notable.editor.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import com.ethran.notable.editor.utils.toIntOffset
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the editor's *window buffer*: the screen-sized bitmap a page is drawn into, the canvas
 * wrapping it, and the spare buffer reused when scrolling.
 *
 * This is deliberately about the **buffer**, not the **content**. Deciding what to draw needs the
 * page's strokes, images and background, and stays on
 * [com.ethran.notable.editor.PageView]; this class only manages the surface those things land on.
 * Keeping that line sharp is what lets the buffer be per-pane later without dragging page data
 * along with it.
 *
 * Note the bitmap is *view-sized*, not page-sized — it is a window onto the page, which is why
 * scrolling shifts pixels and redraws only the newly exposed strip rather than re-rendering
 * everything.
 *
 * ### Resizing is a normal operation
 *
 * [resize] is idempotent and safe to call repeatedly. That matters because the editor is moving to
 * a draggable split divider, where re-layout is user-driven rather than a rare event. **Callers
 * decide when to call it**; this class deliberately holds no debounce policy, because a bitmap
 * reallocation per drag frame would thrash both the heap and the e-ink panel.
 */
class PageRenderer(width: Int, height: Int) {

    var width: Int = width
        private set

    var height: Int = height
        private set

    @Volatile
    var bitmap: Bitmap = createBitmap(width, height)
        private set

    @Volatile
    var canvas: Canvas = Canvas(bitmap)
        private set

    /**
     * Spare screen-sized buffer reused by [shift] so scrolling does not allocate a new bitmap on
     * every event. Ping-ponged with [bitmap]. Always dropped when the geometry changes — a buffer
     * sized to the old viewport would corrupt the next scroll.
     */
    private var backBuffer: Bitmap? = null

    /**
     * Resize the window buffer. Returns true if the dimensions actually changed.
     *
     * Idempotent: calling it with the current dimensions is a no-op, mirroring the guard that
     * already existed in `PageView.updateDimensions` and in the surface-changed callback.
     */
    fun resize(newWidth: Int, newHeight: Int): Boolean {
        if (newWidth == width && newHeight == height) return false
        width = newWidth
        height = newHeight
        recreate()
        return true
    }

    /** Allocate a fresh buffer at the current size, discarding the old one and the spare. */
    fun recreate() {
        bitmap = createBitmap(width, height)
        canvas = Canvas(bitmap)
        backBuffer = null
    }

    /**
     * Adopt an externally supplied bitmap (e.g. one recovered from the page cache), wrapping it in
     * a **new** Canvas.
     *
     * The caller must ensure the bitmap is mutable; `Canvas()` throws on an immutable one.
     * `PageDataManager.getCachedBitmap` already filters on `isMutable`.
     */
    fun adopt(newBitmap: Bitmap) {
        bitmap = newBitmap
        canvas = Canvas(bitmap)
        width = newBitmap.width
        height = newBitmap.height
        backBuffer = null
    }

    /**
     * Swap in an externally created bitmap, **reusing the existing Canvas** via `setBitmap`.
     *
     * Distinct from [adopt] on purpose: the zoom paths rebind the same Canvas instance rather than
     * constructing a new one. `setBitmap` resets the canvas matrix, so callers that rely on a
     * scale must reapply it afterwards — which is why [scaleCanvas] exists.
     */
    fun swapBitmap(newBitmap: Bitmap) {
        bitmap = newBitmap
        canvas.setBitmap(bitmap)
        width = newBitmap.width
        height = newBitmap.height
    }

    /** Reapply a uniform scale to the canvas. Needed after any `setBitmap`, which clears it. */
    fun scaleCanvas(zoom: Float) {
        canvas.scale(zoom, zoom)
    }

    /**
     * Scroll the window by [movement] screen pixels, reusing the spare buffer, and return the
     * region of the new buffer that already holds valid content. The caller is responsible for
     * redrawing everything outside that rect.
     *
     * [movement] is a float Offset by design. The pixel blit uses the sub-pixel value while the
     * returned rect uses the rounded one, exactly as the original code did — narrowing this
     * parameter to an IntOffset would quietly change scrolling precision.
     */
    fun shift(movement: Offset, zoom: Float): Rect {
        val w = bitmap.width
        val h = bitmap.height

        // Reuse the spare only if it still matches the live buffer's geometry and config.
        val shifted = backBuffer?.takeIf {
            it.width == w && it.height == h && it.config == bitmap.config
        } ?: createBitmap(w, h, bitmap.config!!)

        val shiftedCanvas = Canvas(shifted)
        // Preserved from the original implementation, which labelled it "for debugging". Any
        // region not covered by the blit below shows red until the caller redraws the exposed
        // strip. Changing it would be a behaviour change, not a tidy-up.
        shiftedCanvas.drawColor(Color.RED)
        shiftedCanvas.drawBitmap(bitmap, -movement.x, -movement.y, null)

        // The old live buffer becomes the next spare.
        backBuffer = bitmap
        bitmap = shifted
        canvas.setBitmap(bitmap)
        canvas.scale(zoom, zoom)

        return alreadyDrawnRectAfterShift(movement.toIntOffset(), w, h)
    }

    /**
     * The region of the shifted buffer that still holds valid content.
     *
     * Overshoot is not clamped: a movement larger than the buffer yields `bottom < top`, an
     * inverted rect rather than an empty one. Callers are protected only because [Rect.isEmpty] is
     * true for inverted rects. Characterised in `PageViewViewportTest`.
     */
    fun alreadyDrawnRectAfterShift(movement: IntOffset, screenW: Int, screenH: Int): Rect {
        val dx = -movement.x
        val dy = -movement.y
        return Rect(
            max(0, dx),
            max(0, dy),
            min(screenW, dx + screenW),
            min(screenH, dy + screenH),
        )
    }
}
