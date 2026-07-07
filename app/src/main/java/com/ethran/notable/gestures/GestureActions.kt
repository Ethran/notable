package com.ethran.notable.gestures

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset

/**
 * The complete surface the gesture pipeline needs from the editor. The
 * gestures package depends only on this interface; [com.ethran.notable.editor.EditorControlTower]
 * implements it. Keeping the boundary here means gesture code can be tested
 * against a fake and never reaches into editor internals directly.
 */
interface GestureActions {
    fun requestScroll(delta: Offset)
    fun onPinchToZoom(delta: Float, center: Offset?)
    fun goToNextPage()
    fun goToPreviousPage()
    fun toggleTool()
    fun toggleZen()
    fun undo()
    fun redo()
    fun setIsDrawing(value: Boolean)
    fun showHint(text: String)

    /** Select the page content inside [rect] (screen coordinates). */
    fun selectRectangle(rect: Rect)

    /** Full canvas redraw, used after a zoom/drag gesture settles. */
    fun redrawCanvas()
}
