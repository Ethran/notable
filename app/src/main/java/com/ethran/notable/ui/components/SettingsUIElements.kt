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
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.ethran.notable.data.datastore.AppSettings

@Composable
fun <T> SelectorRow(
    label: String,
    options: List<Pair<T, String>>,
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelMaxLines: Int = 2,
    enabled: Boolean = true,
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
            color = if (enabled) {
                MaterialTheme.colors.onSurface
            } else {
                MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
            },
            maxLines = labelMaxLines
        )

        SelectMenu(
            options = options,
            value = value,
            onChange = onValueChange,
            enabled = enabled,
        )
    }
    SettingsDivider()
}

@Composable
fun SettingToggleRow(
    label: String,
    value: Boolean,
    onToggle: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    var showInfoDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 2.dp,
                start = 4.dp,
                end = 4.dp,
                bottom = 0.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body1,
            color = if (enabled) {
                MaterialTheme.colors.onSurface
            } else {
                MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
            },
            maxLines = 2,
        )

        if (description != null) {
            IconButton(
                onClick = { showInfoDialog = true },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "More information",
                )
            }
        }

        OnOffSwitch(
            checked = value,
            onCheckedChange = onToggle,
            enabled = enabled,
            modifier = Modifier.padding(
                start = 4.dp,
                top = 10.dp,
                bottom = 12.dp,
            ),
        )
    }

    SettingsDivider()

    if (showInfoDialog && description != null) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = {
                Text(text = label)
            },
            text = {
                Text(text = description)
            },
            confirmButton = {
                TextButton(
                    onClick = { showInfoDialog = false }
                ) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
fun GestureSelectorRow(
    title: String,
    currentAction: AppSettings.GestureAction,
    onActionSelected: (AppSettings.GestureAction) -> Unit,
    availableGestures: List<Pair<AppSettings.GestureAction, Any>>,
    enabled: Boolean = true,
) {
    val options = availableGestures.map { (action, resource) ->
        val label = when (resource) {
            is Int -> stringResource(resource)
            is String -> resource
            else -> resource.toString()
        }
        action to label
    }

    SelectorRow(
        label = title,
        options = options,
        value = currentAction,
        onValueChange = onActionSelected,
        enabled = enabled,
    )
}

@Composable
fun SettingsDivider() {
    Divider(
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
        thickness = 1.dp,
        modifier = Modifier.padding(top = 0.dp, bottom = 4.dp)
    )
}
