package com.ethran.notable.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.TabRowDefaults.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ethran.notable.editor.ui.SelectMenu
import kotlin.math.roundToInt

@Composable
fun <T> SelectorRow(
    label: String,
    options: List<Pair<T, String>>,
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelMaxLines: Int = 2
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface,
            maxLines = labelMaxLines
        )
        SelectMenu(
            options = options,
            value = value,
            onChange = onValueChange,
        )
    }
    SettingsDivider()
}


@Composable
fun SettingToggleRow(
    label: String, value: Boolean, onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 4.dp, end = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f), // Take all available space
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface,
            maxLines = 2 // allow wrapping for long labels
        )
        OnOffSwitch(
            checked = value,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 12.dp),
        )
    }
    SettingsDivider()
}

@Composable
fun SettingSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit
) {
    // Kept local while dragging; the value is committed only on release.
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 4.dp, end = 4.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onSurface,
                maxLines = 2
            )
            Text(
                text = "%.2fx".format(draft),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface
            )
        }
        StepSlider(
            value = draft,
            valueRange = valueRange,
            steps = steps,
            onValueChange = { draft = it },
            onValueChangeFinished = { onValueChangeFinished(draft) }
        )
    }

    SettingsDivider()
}

private val SliderHeight = 36.dp
private val SliderTrackHeight = 8.dp
private val SliderThumbWidth = 14.dp
private val SliderThumbHeight = 26.dp

/**
 * High-contrast stepped slider for e-ink: outlined track filled up to the thumb, a tick per
 * step, and a flat rectangular thumb. No animation. Tap jumps to the nearest step; dragging
 * moves step by step. [onValueChangeFinished] fires after a tap or at the end of a drag.
 *
 * @param steps number of values between the two ends, as in Material's Slider.
 */
@Composable
private fun StepSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val intervals = steps + 1
    val span = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / span * intervals)
        .roundToInt().coerceIn(0, intervals).toFloat() / intervals

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    val trackColor = MaterialTheme.colors.onSurface
    val backgroundColor = MaterialTheme.colors.surface

    // Maps a touch x to the nearest step; the thumb's centre travels between half a thumb from each edge.
    fun PointerInputScope.valueAt(x: Float): Float {
        val half = SliderThumbWidth.toPx() / 2
        val usable = (size.width - 2 * half).coerceAtLeast(1f)
        val index = ((x - half) / usable * intervals).roundToInt().coerceIn(0, intervals)
        return valueRange.start + index * span / intervals
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SliderHeight)
            .pointerInput(valueRange, steps) {
                detectTapGestures { offset ->
                    currentOnValueChange(valueAt(offset.x))
                    currentOnValueChangeFinished()
                }
            }
            .pointerInput(valueRange, steps) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> currentOnValueChange(valueAt(offset.x)) },
                    onDragEnd = { currentOnValueChangeFinished() },
                    onDragCancel = { currentOnValueChangeFinished() },
                ) { change, _ ->
                    change.consume()
                    currentOnValueChange(valueAt(change.position.x))
                }
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val thumbWidth = SliderThumbWidth.toPx()
            val half = thumbWidth / 2
            val usable = size.width - thumbWidth
            val centerY = size.height / 2
            val trackHeight = SliderTrackHeight.toPx()
            val trackTop = centerY - trackHeight / 2
            val line = 1.dp.toPx()
            val trackCorner = CornerRadius(trackHeight / 2)
            val thumbX = half + usable * fraction

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(half, trackTop),
                size = Size(thumbX - half, trackHeight),
                cornerRadius = trackCorner
            )
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(half, trackTop),
                size = Size(usable, trackHeight),
                cornerRadius = trackCorner,
                style = Stroke(line)
            )

            val tickTop = trackTop + trackHeight + 3.dp.toPx()
            val tickBottom = tickTop + 5.dp.toPx()
            for (i in 0..intervals) {
                val x = half + usable * i / intervals
                drawLine(trackColor, Offset(x, tickTop), Offset(x, tickBottom), strokeWidth = line)
            }

            val thumbHeight = SliderThumbHeight.toPx()
            val thumbTopLeft = Offset(thumbX - half, centerY - thumbHeight / 2)
            val thumbSize = Size(thumbWidth, thumbHeight)
            val thumbCorner = CornerRadius(3.dp.toPx())
            drawRoundRect(backgroundColor, thumbTopLeft, thumbSize, thumbCorner)
            drawRoundRect(trackColor, thumbTopLeft, thumbSize, thumbCorner, style = Stroke(2.dp.toPx()))
        }
    }
}



@Composable
fun SettingsDivider() {
    Divider(
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
        thickness = 1.dp,
        modifier = Modifier.padding(top = 0.dp, bottom = 4.dp)
    )
}
