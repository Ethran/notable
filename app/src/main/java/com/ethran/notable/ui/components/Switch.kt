package com.ethran.notable.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


/**
 * A custom switch composable that displays "ON" or "OFF" text inside the track.
 *
 * This switch animates its colors and thumb position based on the checked state, providing
 * clear visual feedback. It is designed to mimic a physical toggle switch.
 *
 * @param modifier The [Modifier] to be applied to the switch.
 * @param checked Whether the switch is in the 'on' or 'off' state.
 * @param onCheckedChange A callback that is invoked when the user toggles the switch.
 *                        The new checked state is passed as a parameter.
 * @param enabled Controls the enabled state of the switch. When `false`, this switch will not
 *                be interactive.
 * @param animateTransition If `true`, the switch will animate color and position changes.
 */
@Composable
fun OnOffSwitch(
    modifier: Modifier = Modifier,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    animateTransition: Boolean = false,
) {
    // Dimensions
    val trackWidth = 48.dp
    val trackHeight = 20.dp
    val thumbSize = 18.dp
    val startOffset = 2.dp


    val endOffset = trackWidth - thumbSize - startOffset
    val safePad = thumbSize + 4.dp
    val padStart = if (!checked) safePad else 6.dp
    val padEnd = if (checked) safePad else 6.dp

    // Color
    val targetTrackColor =
        if (checked) MaterialTheme.colors.onSurface else MaterialTheme.colors.surface
    val targetThumbColor =
        if (checked) MaterialTheme.colors.surface else MaterialTheme.colors.onSurface
    val targetLabelColor =
        if (checked) MaterialTheme.colors.surface else MaterialTheme.colors.onSurface
    val targetThumbOffset = if (checked) endOffset else startOffset


    val interactionSource = remember { MutableInteractionSource() }


    // Animate or Set Values
    val trackColor: Color by if (animateTransition) {
        animateColorAsState(targetValue = targetTrackColor, label = "trackColor")
    } else {
        remember(targetTrackColor) { mutableStateOf(targetTrackColor) }
    }

    val thumbColor: Color by if (animateTransition) {
        animateColorAsState(targetValue = targetThumbColor, label = "thumbColor")
    } else {
        remember(targetThumbColor) { mutableStateOf(targetThumbColor) }
    }

    val labelColor: Color by if (animateTransition) {
        animateColorAsState(targetValue = targetLabelColor, label = "labelColor")
    } else {
        remember(targetLabelColor) { mutableStateOf(targetLabelColor) }
    }

    val thumbOffset: Dp by if (animateTransition) {
        animateDpAsState(targetValue = targetThumbOffset, label = "thumbOffset")
    } else {
        remember(targetThumbOffset) { mutableStateOf(targetThumbOffset) }
    }


    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .background(trackColor)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
                interactionSource = interactionSource,
                indication = null
            )
            .border(BorderStroke(1.dp, MaterialTheme.colors.onSurface)),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(start = padStart, end = padEnd),
            contentAlignment = if (checked) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            Text(
                text = if (checked) "ON" else "OFF",
                color = labelColor,
                fontSize = 10.sp,
                style = MaterialTheme.typography.caption,
                maxLines = 1
            )
        }

        Surface(
            color = thumbColor,
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .padding(2.dp)
        ) {}
    }
}