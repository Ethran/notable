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
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.ui.viewmodels.GestureRowModel

@Composable
fun GesturesSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    listOfGestures: List<GestureRowModel>,
    availableGestures: List<Pair<AppSettings.GestureAction, Any>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        if (DeviceCompat.isOnyxDevice) {
            SettingToggleRow(
                label = stringResource(R.string.block_onyx_system_gestures),
                value = settings.blockOnyxSystemGestures,
                onToggle = { isChecked ->
                    onSettingsChange(settings.copy(blockOnyxSystemGestures = isChecked))
                },
                description = stringResource(R.string.block_onyx_system_gestures_description),
            )
        }

        listOfGestures.forEach { config ->

            GestureSelectorRow(
                title = stringResource(config.titleRes),
                currentAction = config.currentValue,
                onActionSelected = { action -> config.onUpdate(action) },
                availableGestures = availableGestures,
                enabled = (
                        if (config.blockedByOnyxGestures) settings.blockOnyxSystemGestures else true
                        ),
            )
        }

        val quickNavAvailable = !DeviceCompat.isOnyxDevice || settings.blockOnyxSystemGestures
        SettingToggleRow(
            label = stringResource(R.string.enable_quick_nav),
            value = settings.enableQuickNav && quickNavAvailable,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(enableQuickNav = isChecked))
            },
            enabled = quickNavAvailable
        )

    }
}