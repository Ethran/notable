package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.ui.convertDpToPixel
import kotlin.math.max

/**
 * Vertical scroll indicator (right side).
 * Uses page.scroll.y (IntOffset) for the vertical position.
 */
@Composable
fun ScrollIndicator(state: EditorState) {
    BoxWithConstraints(
        modifier = Modifier
            .width(5.dp)
            .fillMaxHeight()
    ) {
        val viewportHeightPx = convertDpToPixel(this.maxHeight, LocalContext.current).toInt()
        val page = state.pageView

        // Total scrollable height approximation:
        // page.height is the total content height (page coordinates)
        // page.scroll.y + viewportHeightPx ensures indicator still shows while near bottom
        val virtualHeight = max(page.height, page.scroll.y.toInt() + viewportHeightPx)
        if (virtualHeight <= viewportHeightPx) return@BoxWithConstraints

        val indicatorSizeDp = (viewportHeightPx / virtualHeight.toFloat()) * this.maxHeight.value
        val indicatorPositionDp = (page.scroll.y / virtualHeight.toFloat()) * this.maxHeight.value

        if (!state.isToolbarOpen) return@BoxWithConstraints

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset(
                    y = indicatorPositionDp.dp
                )
                .background(Color.Black)
                .height(indicatorSizeDp.dp)
        )
    }
}

/**
 * Horizontal scroll indicator (bottom).
 * Uses page.scroll.x (IntOffset) for the horizontal position.
 */
@Composable
fun HorizontalScrollIndicator(state: EditorState) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        BoxWithConstraints(
            modifier = Modifier
                .height(5.dp)
                .fillMaxWidth()
        ) {
            val viewportWidthPx = convertDpToPixel(this.maxWidth, LocalContext.current).toInt()
            val page = state.pageView

            // Total scrollable width approximation:
            // page.width is the total content width (page coordinates)
            // page.scroll.x + viewportWidthPx ensures indicator still shows while near right edge
            val virtualWidth = max(page.width, page.scroll.x.toInt() + viewportWidthPx)
            if (virtualWidth <= viewportWidthPx) return@BoxWithConstraints

            val indicatorSizeDp = (viewportWidthPx / virtualWidth.toFloat()) * this.maxWidth.value
            val indicatorPositionDp = (page.scroll.x / virtualWidth.toFloat()) * this.maxWidth.value

            if (!state.isToolbarOpen) return@BoxWithConstraints

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .offset(
                        x = indicatorPositionDp.dp
                    )
                    .background(Color.Black)
                    .width(indicatorSizeDp.dp)
            )
        }
    }
}