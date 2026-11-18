package com.ethran.notable.editor.canvas.rectify

import com.ethran.notable.editor.utils.rawInputMaxPressure

/**
 * What a rectified gesture produced, as a value the adapter commits to the page.
 *
 * [a] and [b] are screen pixels — the pen-down point and where the pen was when it lifted — which
 * the adapter maps through the page viewport before turning them into a stroke.
 *
 * @param dirty the screen area the band was pushed over, which the panel still shows and which is
 *   therefore already clean: the commit notes it as firmware ink instead of pushing it again. Null
 *   when the band never reached the panel.
 * @param pressure the same level the band was stamped at, on the digitizer's raw scale.
 */
data class RectifyCommit(
    val a: Point2D,
    val b: Point2D,
    val pageId: String,
    val dirty: RectBounds?,
    val pressure: Float = rawInputMaxPressure,
)
