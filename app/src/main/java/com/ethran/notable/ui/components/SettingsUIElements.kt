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
fun SettingsDivider() {
    Divider(
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
        thickness = 1.dp,
        modifier = Modifier.padding(top = 0.dp, bottom = 4.dp)
    )
}
