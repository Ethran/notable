package com.ethran.notable.editor

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.round
import com.ethran.notable.editor.utils.div
import com.ethran.notable.editor.utils.minus
import com.ethran.notable.editor.utils.plus
import com.ethran.notable.editor.utils.times

/**
 * How a page is viewed: [scroll] is the page point at the top left corner of the view, in page
 * coordinates, and [zoom] is the scale from page to screen.
 */
data class PageViewport(val scroll: Offset = Offset.Zero, val zoom: Float = 1f) {
    /**
     * Whether a view drawn at this viewport can be reused for [other]: the same zoom, and the same
     * scroll after rounding to whole page pixels, since the scroll is saved as whole pixels.
     */
    fun matches(other: PageViewport): Boolean =
        zoom == other.zoom && scroll.round() == other.scroll.round()

    /** Where [rect], in page coordinates, is on screen. */
    fun toScreen(rect: Rect): Rect = (rect - scroll) * zoom

    /** Where [rect], on screen, is on the page. */
    fun toPage(rect: Rect): Rect = rect / zoom + scroll
}
