package com.ethran.notable.editor.ui

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
fun SelectionVisualCues(crossPosition: IntOffset?, rectangleBounds: Rect?) {
    // Box for showing visual clous for selection
    // separated from pointerInput box, as it has to be on top of everything.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .zIndex(1f)
    )
    {
        val density = LocalDensity.current
        // Draw cross where finger is touching
        DrawCross(crossPosition, density)
        // Draw the rectangle while dragging
        DrawRectangle(rectangleBounds, density)
    }
}


@Composable
private fun DrawRectangle(rectangleBounds: Rect?, density: Density) {
    rectangleBounds?.let { bounds ->
        // Draw the rectangle
        Box(
            Modifier
                .offset { IntOffset(bounds.left, bounds.top) }
                .size(
                    width = with(density) { (bounds.right - bounds.left).toDp() },
                    height = with(density) { (bounds.bottom - bounds.top).toDp() }
                )
                // Is there rendering speed difference between colors?
                .background(Color(0x55000000))
                .border(1.dp, Color.Black)
        )
    }

}

@Composable
private fun DrawCross(crossPosition: IntOffset?, density: Density) {

    // Draw cross where finger is touching
    crossPosition?.let { pos ->
        val crossSizePx = with(density) { 100.dp.toPx() }
        Box(
            Modifier
                .offset {
                    IntOffset(
                        pos.x - (crossSizePx / 2).toInt(),
                        pos.y
                    )
                } // Horizontal bar centered
                .size(width = 100.dp, height = 2.dp)
                .background(Color.Black)
        )
        Box(
            Modifier
                .offset {
                    IntOffset(
                        pos.x,
                        pos.y - (crossSizePx / 2).toInt()
                    )
                } // Vertical bar centered
                .size(width = 2.dp, height = 100.dp)
                .background(Color.Black)
        )
    }
}
