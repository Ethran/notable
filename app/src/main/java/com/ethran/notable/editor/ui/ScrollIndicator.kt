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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.PageView
import com.ethran.notable.ui.convertDpToPixel
import kotlin.math.max

/**
 * Vertical scroll indicator (right side).
 * Uses the vertical scroll of [PageView.viewport] and [PageView.contentHeight].
 */
@Composable
fun ScrollIndicator(viewModel: EditorViewModel, page: PageView) {
    val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()
    val viewport by page.viewport.collectAsStateWithLifecycle()
    val contentHeight by page.contentHeight.collectAsStateWithLifecycle()
    BoxWithConstraints(
        modifier = Modifier
            .width(5.dp)
            .fillMaxHeight()
    ) {
        val viewportHeightPx = convertDpToPixel(this.maxHeight, LocalContext.current).toInt()
        val scrollY = viewport.scroll.y

        // Total scrollable height approximation:
        // contentHeight is the total content height (page coordinates)
        // scrollY + viewportHeightPx ensures indicator still shows while near bottom
        val virtualHeight = max(contentHeight, scrollY.toInt() + viewportHeightPx)
        if (virtualHeight <= viewportHeightPx) return@BoxWithConstraints

        val indicatorSizeDp = (viewportHeightPx / virtualHeight.toFloat()) * this.maxHeight.value
        val indicatorPositionDp = (scrollY / virtualHeight.toFloat()) * this.maxHeight.value

        if (!toolbarState.isToolbarOpen) return@BoxWithConstraints

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
 * Uses the horizontal scroll of [PageView.viewport].
 */
@Composable
fun HorizontalScrollIndicator(viewModel: EditorViewModel, page: PageView) {
    val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()
    val viewport by page.viewport.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        BoxWithConstraints(
            modifier = Modifier
                .height(5.dp)
                .fillMaxWidth()
        ) {
            val viewportWidthPx = convertDpToPixel(this.maxWidth, LocalContext.current).toInt()
            val scrollX = viewport.scroll.x

            // Total scrollable width approximation:
            // page.viewWidth is the total content width (page coordinates)
            // scrollX + viewportWidthPx ensures indicator still shows while near right edge
            val virtualWidth = max(page.viewWidth, scrollX.toInt() + viewportWidthPx)
            if (virtualWidth <= viewportWidthPx) return@BoxWithConstraints

            val indicatorSizeDp = (viewportWidthPx / virtualWidth.toFloat()) * this.maxWidth.value
            val indicatorPositionDp = (scrollX / virtualWidth.toFloat()) * this.maxWidth.value

            if (!toolbarState.isToolbarOpen) return@BoxWithConstraints

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
