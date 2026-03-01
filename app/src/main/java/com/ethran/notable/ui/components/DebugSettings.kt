package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.ethran.notable.data.datastore.AppSettings

@Composable
fun DebugSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    goToWelcome: () -> Unit
) {
    Column {
        SettingToggleRow(
            label = "Show welcome screen",
            value = settings.showWelcome,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(showWelcome = isChecked))
            }
        )
        SettingToggleRow(
            label = "Show System Information",
            value = false,
            onToggle = {
                goToWelcome()
            }
        )
        SettingToggleRow(
            label = "Debug Mode (show changed area)",
            value = settings.debugMode,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(debugMode = isChecked))
            }
        )
        SettingToggleRow(
            label = "Use simple rendering for scroll and zoom -- uses more resources.",
            value = settings.simpleRendering,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(simpleRendering = isChecked))
            }
        )
        SettingToggleRow(
            label = "Use openGL rendering for eraser.",
            value = settings.openGLRendering,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(openGLRendering = isChecked))
            }
        )
        SettingToggleRow(
            label = "Use MuPdf as a renderer for pdfs.",
            value = settings.muPdfRendering,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(muPdfRendering = isChecked))
            }
        )
        SettingToggleRow(
            label = "Allow destructive migrations",
            value = settings.destructiveMigrations,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(destructiveMigrations = isChecked))
            }
        )
    }
}
