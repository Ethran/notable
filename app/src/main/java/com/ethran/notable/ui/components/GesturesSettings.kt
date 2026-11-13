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
                label = stringResource(R.string.block_system_gestures),
                value = settings.blockSystemGestures,
                onToggle = { isChecked ->
                    onSettingsChange(settings.copy(blockSystemGestures = isChecked))
                },
                description = stringResource(R.string.block_system_gestures_description),
            )
        }

        val threeFingerGesturesAvailable =
            !DeviceCompat.isOnyxDevice || settings.blockSystemGestures

        listOfGestures.forEach { config ->

            GestureSelectorRow(
                title = stringResource(config.titleRes),
                currentAction = config.currentValue,
                onActionSelected = { action -> config.onUpdate(action) },
                availableGestures = availableGestures,
                enabled = (
                        if (config.blockedByOnyxGestures) threeFingerGesturesAvailable else true
                        ),
            )
        }

        SettingToggleRow(
            label = stringResource(R.string.enable_quick_nav),
            value = settings.enableQuickNav && threeFingerGesturesAvailable,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(enableQuickNav = isChecked))
            },
            enabled = threeFingerGesturesAvailable
        )

    }
}