package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.ui.viewmodels.GestureRowModel

@Composable
fun GesturesSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    listOfGestures: List<GestureRowModel>,
    availableGestures: List<Pair<AppSettings.GestureAction?, Any>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        listOfGestures.forEach { config ->
            GestureSelectorRow(
                title = stringResource(config.titleRes),
                currentAction = config.currentValue,
                defaultAction = config.defaultValue,
                onActionSelected = { action -> config.onUpdate(action) },
                availableGestures = availableGestures
            )
        }

        SettingToggleRow(
            label = stringResource(R.string.enable_quick_nav), value = settings.enableQuickNav,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(enableQuickNav = isChecked))
            })
    }
}

@Composable
fun GestureSelectorRow(
    title: String,
    currentAction: AppSettings.GestureAction?,
    defaultAction: AppSettings.GestureAction,
    onActionSelected: (AppSettings.GestureAction?) -> Unit,
    availableGestures: List<Pair<AppSettings.GestureAction?, Any>>
) {
    // Map the Pair list to the format expected by SelectorRow
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
        value = currentAction ?: defaultAction,
        onValueChange = onActionSelected
    )
}