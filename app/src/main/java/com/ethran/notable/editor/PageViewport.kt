package com.ethran.notable.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.round

/**
 * How a page is viewed: [scroll] is the page point at the top left corner of the view, in page
 * coordinates, and [zoom] is the scale from page to screen.
 */
data class PageViewport(val scroll: Offset = Offset.Zero, val zoom: Float = 1f) {
    /**
     * Whether a view drawn at this viewport also serves for [other]: the same zoom, and the same
     * scroll to the nearest page pixel, as the vertical scroll is saved rounded.
     */
    fun matches(other: PageViewport): Boolean =
        zoom == other.zoom && scroll.round() == other.scroll.round()
}
