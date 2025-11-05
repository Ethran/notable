package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import kotlin.math.roundToInt

/**
 * Horizontal page scrubber with a draggable thumb and a "Return" button.
 * Pure UI for now; provide callbacks to hook up preview/final refresh behavior.
 */
@Composable
fun PageHorizontalSliderWithReturn(
    modifier: Modifier = Modifier,
    pageCount: Int = 100,
    currentIndex: Int = 0,
    trackHeight: Dp = 36.dp,
    thumbWidth: Dp = 56.dp,
    showPreviewBadge: Boolean = true,
    quantization: Int = 1,
    onDragStart: () -> Unit = {},
    onPreviewIndexChanged: (index: Int) -> Unit = {},
    onDragEnd: (index: Int) -> Unit = {},
    onReturnClick: () -> Unit = {},
) {
    val density = LocalDensity.current

    var dragIndex by remember(currentIndex, pageCount) {
        mutableIntStateOf(currentIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val thumbWidthPx = with(density) { thumbWidth.toPx() }
    val fraction: Float =
        if (pageCount <= 1) 0f else (dragIndex.toFloat() / (pageCount - 1).toFloat()).coerceIn(
            0f,
            1f
        )
    val thumbOffsetXPx: Float = (containerWidthPx - thumbWidthPx).coerceAtLeast(0f) * fraction

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Slider track + thumb
        Box(
            modifier = Modifier
                .weight(1f)
                .height(trackHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x11000000))
                .border(1.dp, Color.Black, RoundedCornerShape(10.dp))
                .onGloballyPositioned { coords -> containerWidthPx = coords.size.width }
                .pointerInput(pageCount) {
                    detectHorizontalDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd(dragIndex) },
                        onDragCancel = { onDragEnd(dragIndex) }
                    ) { change, _ ->
                        change.consume()
                        if (containerWidthPx == 0 || pageCount <= 0) return@detectHorizontalDragGestures
                        val localX = change.position.x.coerceIn(0f, containerWidthPx.toFloat())
                        val frac = (localX / containerWidthPx.toFloat()).coerceIn(0f, 1f)
                        val rawIndex =
                            (frac * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
                        val quantized = (rawIndex / quantization) * quantization
                        if (quantized != dragIndex) {
                            dragIndex = quantized
                            onPreviewIndexChanged(dragIndex)
                        }
                    }
                }
                .autoEInkAnimationOnScroll()
        ) {
            // Thumb
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .offset { IntOffset(thumbOffsetXPx.toInt(), 0) }
                    .height(trackHeight - 4.dp)
                    .width(thumbWidth)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF111111))
                    .border(1.dp, Color.Black, RoundedCornerShape(8.dp))
            )

            // Floating preview badge above the thumb (UI only)
            if (showPreviewBadge && pageCount > 0 && containerWidthPx > 0) {
                val badgeYOffset = with(density) { (-trackHeight.value * 1.2f).dp.toPx().toInt() }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(thumbOffsetXPx.toInt(), badgeYOffset) }
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000))
                        .border(1.dp, Color.Black, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${dragIndex + 1} / $pageCount",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Return button (icon-only for compactness)
        ToolbarButton(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            onSelect = { onReturnClick() }
        )
    }
}