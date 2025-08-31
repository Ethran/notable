package com.ethran.notable.editor.ui.toolbar

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.ui.convertDpToPixel
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

    val columnModifier =
        if (GlobalAppSettings.current.continuousStrokeSlider) Modifier
            .background(Color.White)
            .border(1.dp, Color.Black)
        else Modifier
    Popup(
        offset = IntOffset(0, convertDpToPixel(43.dp, context).toInt()),
        onDismissRequest = { onClose() },
        properties = PopupProperties(focusable = true),
        alignment = Alignment.TopCenter
    ) {

        Column(
            modifier = columnModifier
                .width(IntrinsicSize.Min) // match the widest child (ColorPicker Row)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {

            val listOfColors = if (GlobalAppSettings.current.monochromeMode) listOf(
                Color.Black,
                Color.DarkGray,
                Color.Gray,
                Color.LightGray
            )
            else colorOptions


            ColorPicker(
                value = value,
                onChange = onChange,
                colorOptions = listOfColors,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(6.dp))

            if (!GlobalAppSettings.current.continuousStrokeSlider) {
                ThicknessPicker(
                    value,
                    onChange,
                    sizeOptions,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            } else {
                val sizes = sizeOptions.map { it.second }

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
                    values = sizes,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(widthOfPicker)   // shorter than full width
                        .height(heightOfPicker)   // compact height
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
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
                        3.dp, if (color == Color(value.color)) Color.Black else Color.Transparent
                    )
                    .clickable {
                        onChange(
                            PenSetting(
                                strokeSize = value.strokeSize, color = android.graphics.Color.argb(
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
 * - Tap or drag to change; snapping to nearest integer value between min and max.
 * - Single down arrow/rect thumb for clarity (high contrast).
 * - Takes a List<Float> of suggested values; indicators are shown above these.
 * - All integer values between min and max are selectable by dragging/tapping.
 */
@Composable
private fun DiscreteThicknessSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    values: List<Float>,
    modifier: Modifier = Modifier,
) {
    if (values.size < 2) return // Require at least two values to define a range

    // Use only sorted, distinct values
    val displayValues = values.distinct().sorted()
    val minVal = displayValues.first().toInt()
    val maxVal = displayValues.last().toInt()


    // Map each integer to a normalized position [0,1] along the slider
    fun fractionForInt(v: Int): Float = (v - minVal).toFloat() / (maxVal - minVal).toFloat()

    // For the indicators only: map the suggested values to fractions
    val indicatorFractions = displayValues.map { v ->
        val intV = v.toInt().coerceIn(minVal, maxVal)
        fractionForInt(intV)
    }

    // Clamp and snap to nearest integer on user interaction
    fun snapToNearestInt(xFraction: Float): Float {
        val clamped = xFraction.coerceIn(0f, 1f)
        val rawValue = minVal + ((maxVal - minVal) * clamped)
        return rawValue.roundToInt().coerceIn(minVal, maxVal).toFloat()
    }

    // Thumb's proportional position
    val thumbInt = value.roundToInt().coerceIn(minVal, maxVal)
    val thumbFraction = fractionForInt(thumbInt)

    Box(modifier = modifier
        .pointerInput(displayValues) {
            detectTapGestures { offset ->
                val w = size.width.coerceAtLeast(1)
                onValueChange(snapToNearestInt(offset.x / w))
            }
        }
        .pointerInput(displayValues) {
            detectDragGestures(onDrag = { change, _ ->
                val w = size.width.coerceAtLeast(1)
                onValueChange(snapToNearestInt(change.position.x / w))
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
                style = Stroke(
                    width = 6f,
                    cap = StrokeCap.Butt,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = trackPath,
                color = Color.Black,
                style = Fill
            )
            drawPath(
                path = trackPath,
                color = Color.Black,
                style = Stroke(
                    width = 2f,
                    cap = StrokeCap.Butt,
                    join = StrokeJoin.Round
                )
            )

            // Rounded end hints
            val leftR = minTrack / 4f
            val rightR = maxTrack / 4f
            drawCircle(Color.Black, radius = leftR, center = Offset(2f, centerY))
            drawCircle(Color.Black, radius = rightR, center = Offset(w - 3f, centerY - rightR))

            // Ticks (indicators) along the top for the suggested values only
            val tickGap = 6f
            val tickHeight = 12f
            for (frac in indicatorFractions) {
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
                    cap = StrokeCap.Round
                )
            }

            // Thumb position and prominence
            val thumbX = thumbFraction * w
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
                size = Size(
                    thumbW + thumbOuterPadding * 2f, thumbH + thumbOuterPadding * 2f
                ),
                cornerRadius = CornerRadius(thumbRadius, thumbRadius)
            )
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(thumbX - thumbW / 2f, centerY - thumbH),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(thumbRadius, thumbRadius)
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