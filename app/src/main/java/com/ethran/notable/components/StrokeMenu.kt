package com.ethran.notable.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.PenSetting
import com.ethran.notable.utils.convertDpToPixel
import kotlin.math.max
import kotlin.math.roundToInt


@Composable
fun StrokeMenu(
    value: PenSetting,
    onChange: (setting: PenSetting) -> Unit,
    onClose: () -> Unit,
    sizeOptions: List<Pair<String, Float>>,
    colorOptions: List<Color>,
) {
    val context = LocalContext.current

    Popup(
        offset = IntOffset(0, convertDpToPixel(43.dp, context).toInt()),
        onDismissRequest = { onClose() },
        properties = PopupProperties(focusable = true),
        alignment = Alignment.TopCenter
    ) {

        Column(
            modifier = Modifier
                .width(IntrinsicSize.Min) // match the widest child (ColorPicker Row)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .background(Color.White)
                .border(1.dp, Color.Black)
        ) {
            val sizes = sizeOptions.map { it.second }
            val minSize = sizes.minOrNull() ?: 1f
            val maxSize = sizes.maxOrNull() ?: 20f

//           ThicknessPicker(value, onChange, sizeOptions,  modifier = Modifier.align(Alignment.CenterHorizontally))

            val listOfColors =
                if (GlobalAppSettings.current.monochromeMode)
                    listOf(Color.Black, Color.DarkGray, Color.Gray, Color.LightGray)
                else
                    colorOptions


            ColorPicker(
                value = value,
                onChange = onChange,
                colorOptions = listOfColors,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(6.dp))

            val widthOfPicker = (35 * listOfColors.size.coerceAtLeast(5)).dp
            val heightOfPicker = 40.dp
            DiscreteThicknessSlider(
                value = value.strokeSize,
                onValueChange = { newSize ->
                    onChange(
                        PenSetting(
                            strokeSize = newSize, color = value.color
                        )
                    )
                },
                min = minSize,
                max = maxSize,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(widthOfPicker)   // shorter than full width
                    .height(heightOfPicker)   // compact height
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )

        }


    }
}

@Composable
private fun ColorPicker(
    value: PenSetting,
    onChange: (setting: PenSetting) -> Unit,
    colorOptions: List<Color>,
    modifier: Modifier = Modifier,
    embedded: Boolean = false
) {
    val rowModifier = if (embedded) {
        modifier.height(IntrinsicSize.Max)
    } else {
        modifier
            .background(Color.White)
            .border(1.dp, Color.Black)
            .height(IntrinsicSize.Max)
    }

    Row(rowModifier) {
        colorOptions.map { color ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color)
                    .border(
                        3.dp,
                        if (color == Color(value.color)) Color.Black else Color.Transparent
                    )
                    .clickable {
                        onChange(
                            PenSetting(
                                strokeSize = value.strokeSize,
                                color = android.graphics.Color.argb(
                                    (color.alpha * 255).toInt(),
                                    (color.red * 255).toInt(),
                                    (color.green * 255).toInt(),
                                    (color.blue * 255).toInt()
                                )
                            )
                        )
                    }
                    .padding(8.dp))
        }
    }

    if (!embedded) {
        Spacer(Modifier.height(4.dp))
    }

}

// Old Picker
@Suppress("unused")
@Composable
private fun ThicknessPicker(
    value: PenSetting,
    onChange: (setting: PenSetting) -> Unit,
    sizeOptions: List<Pair<String, Float>>,
    modifier: Modifier = Modifier
) {

    Row(
        modifier = modifier
            .background(Color.White)
            .border(1.dp, Color.Black),
        horizontalArrangement = Arrangement.Center
    ) {
        sizeOptions.forEach {
            ToolbarButton(
                text = it.first, isSelected = value.strokeSize == it.second, onSelect = {
                    onChange(
                        PenSetting(
                            strokeSize = it.second, color = value.color
                        )
                    )
                }, modifier = Modifier
            )
        }
    }
}

/**
 * Discrete slider:
 * - Track is a wedge: thin on the left, gradually thicker to the right.
 * - ~10 evenly spaced discrete stops (count configurable), with tick marks following the track's top edge.
 * - Tap or drag to change; snapping to nearest discrete value.
 * - Single down arrow/rect thumb for clarity (high contrast).
 */
@Composable
private fun DiscreteThicknessSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    modifier: Modifier = Modifier,
    count: Int = 10,
) {
    val points = max(2, count)
    val stepSize = (max - min) / (points - 1)

    fun snapToNearest(xFraction: Float): Float {
        val clamped = xFraction.coerceIn(0f, 1f)
        val idx = (clamped * (points - 1)).roundToInt()
        return min + idx * stepSize
    }

    Box(
        modifier = modifier
            .pointerInput(min, max, points) {
                detectTapGestures { offset ->
                    val w = size.width.coerceAtLeast(1)
                    onValueChange(snapToNearest(offset.x / w))
                }
            }
            .pointerInput(min, max, points) {
                detectDragGestures(onDrag = { change, _ ->
                    val w = size.width.coerceAtLeast(1)
                    onValueChange(snapToNearest(change.position.x / w))
                })
            }) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val centerY = h / 2f

            // Wedge parameters (thin -> thick)
            val minTrack = 6f
            val maxTrack = (h * 0.75f).coerceAtLeast(minTrack + 4f)

            fun thicknessAtX(x: Float): Float {
                val frac = if (w > 0) (x / w).coerceIn(0f, 1f) else 0f
                return minTrack + (maxTrack - minTrack) * frac
            }

            // Wedge track path with left thin, right thick (bottom edge kept centered)
            val trackPath = Path().apply {
                moveTo(0f, centerY - minTrack / 2f) // top-left
                lineTo(w, centerY - maxTrack / 2f)  // top-right
                lineTo(w, centerY)                  // bottom-right
                lineTo(0f, centerY)                 // bottom-left
                close()
            }

            // Outline first (white halo), then fill, then crisp black outline
            drawPath(
                path = trackPath,
                color = Color.White,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 6f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Butt,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )
            drawPath(
                path = trackPath,
                color = Color.Black,
                style = androidx.compose.ui.graphics.drawscope.Fill
            )
            drawPath(
                path = trackPath,
                color = Color.Black,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Butt,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )

            // Rounded end hints
            val leftR = minTrack / 4f
            val rightR = maxTrack / 4f
            drawCircle(Color.Black, radius = leftR, center = Offset(2f, centerY))
            drawCircle(Color.Black, radius = rightR, center = Offset(w - 3f, centerY - rightR))

            // Ticks along the top of the wedge with rounded caps (discrete stops)
            val tickGap = 6f
            val tickHeight = 12f
            for (i in 0 until points) {
                val frac = if (points == 1) 0f else i.toFloat() / (points - 1)
                val x = frac * w
                val t = thicknessAtX(x)
                val topY = (centerY - t / 2f)
                val startY = (topY - tickGap - tickHeight).coerceAtLeast(0f)
                val endY = (topY - tickGap).coerceAtLeast(0f)
                drawLine(
                    color = Color.Black,
                    start = Offset(x, startY),
                    end = Offset(x, endY),
                    strokeWidth = 3f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            // Thumb position and prominence
            val fraction = if (max > min) ((value - min) / (max - min)).coerceIn(0f, 1f) else 0f
            val thumbX = fraction * w
            val currentThickness = thicknessAtX(thumbX)

            // Highly visible vertical rounded thumb (white border + black body)
            val thumbW = 10f
            val thumbH = (currentThickness + 14f).coerceAtMost(h * 0.9f) / 2
            val thumbRadius = 6f
            val thumbOuterPadding = 3f

            drawRoundRect(
                color = Color.White,
                topLeft = Offset(
                    thumbX - thumbW / 2f - thumbOuterPadding, centerY - thumbH - thumbOuterPadding
                ),
                size = androidx.compose.ui.geometry.Size(
                    thumbW + thumbOuterPadding * 2f, thumbH + thumbOuterPadding * 2f
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbRadius, thumbRadius)
            )
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(thumbX - thumbW / 2f, centerY - thumbH),
                size = androidx.compose.ui.geometry.Size(thumbW, thumbH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbRadius, thumbRadius)
            )

            // Down arrow pointing to current position for visibility
            val arrowWidth = 30f
            val arrowHeight = 30f
            val downArrow = Path().apply {
                val tipY = (centerY + 6f).coerceAtMost(h)
                moveTo(thumbX, tipY) // tip (upwards)
                lineTo(thumbX - arrowWidth / 2f, (tipY + arrowHeight).coerceAtMost(h))
                lineTo(thumbX + arrowWidth / 2f, (tipY + arrowHeight).coerceAtMost(h))
                close()
            }
            drawPath(downArrow, color = Color.Black)
        }
    }
}