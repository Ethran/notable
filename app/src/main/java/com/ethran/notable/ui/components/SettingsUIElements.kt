package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.TabRowDefaults.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ethran.notable.editor.ui.SelectMenu
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Slider

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
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 4.dp, end = 4.dp)
    ) {
        val stepSize = (valueRange.endInclusive - valueRange.start) / (steps + 1)
        Text(
            text = "$label: ${"%.2f".format(value)}x",
            modifier = Modifier
                .padding(bottom = 8.dp),
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface,
            maxLines = 2
        )

        Slider(
            value = value,
            onValueChange = { newValue ->
                val snappedValue =
                    valueRange.start +
                            kotlin.math.round(
                                (newValue - valueRange.start) / stepSize
                            ) * stepSize

                onValueChange(snappedValue)
            },
            valueRange = valueRange,
            steps = steps
        )
    }

    SettingsDivider()
}

@Composable
fun SettingsDivider() {
    Divider(
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
        thickness = 1.dp,
        modifier = Modifier.padding(top = 0.dp, bottom = 4.dp)
    )
}
