package com.ethran.notable.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
 * Simple discrete (quantized) horizontal page slider with a Return button.
 * Logic:
 * - Dragging or tapping immediately jumps to the nearest quantized page.
 * - No free continuous thumb movement; thumb only sits on quantized positions.
 * - Uses container width measurement and converts x position -> fraction -> index.
 *
 * Extra features added while keeping original logic style:
 * - Optional favorites (shown as small rings).
 * - Optional current page indicator text.
 * - Visual tick marks for each (quantized) page.
 *
 * Quantization:
 * - If quantization = 1 -> every page.
 * - If quantization = N -> thumb only lands on indices that are multiples of N.
 */
@Composable
fun PageHorizontalSliderWithReturn(
    modifier: Modifier = Modifier,
    pageCount: Int,
    currentIndex: Int,
    favIndexes: List<Int> = emptyList(),
    trackHeight: Dp = 26.dp,
    thumbWidth: Dp = 20.dp,
    quantization: Int = 1,
    showPageIndicator: Boolean = true,
    showTicks: Boolean = true,
    onDragStart: () -> Unit = {},
    onPreviewIndexChanged: (index: Int) -> Unit = {},
    onDragEnd: (index: Int) -> Unit = {},
    onReturnClick: () -> Unit = {},
) {
    val density = LocalDensity.current

    // Internal index state resets when currentIndex or pageCount changes.
    var dragIndex by remember(currentIndex, pageCount) {
        mutableIntStateOf(currentIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }

    // Measured width of the whole track box (including padding/border).
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val thumbWidthPx = with(density) { thumbWidth.toPx() }

    // Fraction of position on track (0..1)
    val fraction: Float =
        if (pageCount <= 1) 0f else (dragIndex.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)

    // Pixel offset for the thumb (left edge relative to start of track content).
    val thumbOffsetXPx: Float = (containerWidthPx - thumbWidthPx).coerceAtLeast(0f) * fraction

    // Press state for color feedback
    var isPressed by remember { mutableStateOf(false) }
    val thumbColor by animateColorAsState(
        targetValue = if (isPressed) Color.Black else Color.White,
        label = "thumbColor"
    )

    // Helper: quantize any raw page index
    val quantizeIndex: (Int) -> Int = { idx ->
        if (quantization <= 1) idx.coerceIn(0, pageCount - 1)
        else ((idx / quantization) * quantization).coerceIn(0, pageCount - 1)
    }

    // Gesture conversion: x position (inside track) -> quantized page index
    fun indexFromX(x: Float): Int {
        if (pageCount <= 1 || containerWidthPx <= 0) return 0
        val clampedX = x.coerceIn(0f, containerWidthPx.toFloat())
        val fracLocal = (clampedX / containerWidthPx.toFloat()).coerceIn(0f, 1f)
        val rawIndex = (fracLocal * (pageCount - 1)).roundToInt()
        return quantizeIndex(rawIndex)
    }

    Column(modifier = modifier) {
        if (showPageIndicator && pageCount > 0) {
            Text(
                text = "${dragIndex + 1} / $pageCount",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp),
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Track + thumb + ticks/favorites
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(1.dp, Color.Black, RoundedCornerShape(10.dp))
                    .onGloballyPositioned { coords -> containerWidthPx = coords.size.width }
                    .pointerInput(pageCount, currentIndex, quantization) {
                        if (pageCount <= 0) return@pointerInput

                        detectDragGestures(
                            onDragStart = { offset ->
                                isPressed = true
                                onDragStart()
                                val newIndex = indexFromX(offset.x)
                                if (newIndex != dragIndex) {
                                    dragIndex = newIndex
                                    onPreviewIndexChanged(newIndex)
                                }
                            },
                            onDragEnd = {
                                isPressed = false
                                onDragEnd(dragIndex)
                            },
                            onDragCancel = {
                                isPressed = false
                                onDragEnd(dragIndex)
                            }
                        ) { change, _ ->
                            change.consume()
                            val currentX =
                                change.position.x.coerceIn(0f, containerWidthPx.toFloat())
                            val newIndex = indexFromX(currentX)
                            if (newIndex != dragIndex) {
                                dragIndex = newIndex
                                onPreviewIndexChanged(newIndex)
                            }
                        }
                    }
                    .pointerInput(pageCount, currentIndex, quantization, favIndexes) {
                        if (pageCount <= 0) return@pointerInput
                        detectTapGestures { offset ->
                            val newIndexRaw = indexFromX(offset.x)

                            // --- proximity detection for favorites ---
                            // Convert X → index space, then check if we’re close to a favorite index
                            var newIndex = newIndexRaw
                            if (favIndexes.isNotEmpty() && containerWidthPx > 0) {
                                val usableWidth =
                                    (containerWidthPx - thumbWidthPx).coerceAtLeast(0f)
                                val xPerIndex = usableWidth / (pageCount - 1).coerceAtLeast(1)
                                val tapIndexFloat = offset.x / xPerIndex
                                val nearestFav =
                                    favIndexes.minByOrNull { kotlin.math.abs(it - tapIndexFloat) }


                                // Define how close in width units
                                val thresholdInIndex = containerWidthPx / 40
                                if (nearestFav != null && kotlin.math.abs(nearestFav * xPerIndex - offset.x) < thresholdInIndex) {
                                    newIndex = nearestFav
                                }
                            }
                            // --- end proximity check ---

                            if (newIndex != dragIndex) {
                                dragIndex = newIndex
                                onPreviewIndexChanged(newIndex)
                            }
                            onDragEnd(dragIndex)
                        }
                    }

                    .autoEInkAnimationOnScroll()
            ) {
                // Ticks: drawn beneath thumb (using simple Boxes for minimal overhead)
                if (showTicks && pageCount > 1 && containerWidthPx > 0) {
                    val usableWidth = (containerWidthPx - thumbWidthPx).coerceAtLeast(0f)
                    for (i in 0 until pageCount) {
                        val fracTick = i.toFloat() / (pageCount - 1)
                        val xPx = (usableWidth * fracTick) + (thumbWidthPx / 2f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset { IntOffset(xPx.roundToInt() - 1, -2) }
                                .width(1.dp)
                                .height(trackHeight / 3)
                                .background(Color.Black)
                        )
                    }
                }

                // Favorites: rings centered vertically
                if (favIndexes.isNotEmpty() && pageCount > 1 && containerWidthPx > 0) {
                    val usableWidth = (containerWidthPx - thumbWidthPx).coerceAtLeast(0f)
                    favIndexes.forEach { fav ->
                        if (fav in 0 until pageCount) {
                            val fracFav = fav.toFloat() / (pageCount - 1)
                            val xPx = (usableWidth * fracFav) + (thumbWidthPx / 2f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset { IntOffset(xPx.roundToInt() - 12, 0) }
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }

                // Thumb (snaps directly to quantized positions)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                        .offset { IntOffset(thumbOffsetXPx.toInt(), 0) }
                        .height(trackHeight - 4.dp)
                        .width(thumbWidth)
                        .clip(RoundedCornerShape(8.dp))
                        .background(thumbColor)
                        .border(1.dp, Color.Black, RoundedCornerShape(8.dp))
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            ToolbarButton(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                onSelect = { onReturnClick() }
            )
        }
    }
}