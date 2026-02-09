package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.db.KvProxy


@Composable
fun DebugSettings(kv: KvProxy, settings: AppSettings, navController: NavController) {
    Column {
        SettingToggleRow(
            label = "Show welcome screen", value = settings.showWelcome, onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(showWelcome = isChecked))
            })
        SettingToggleRow(
            label = "Show System Information", value = false, onToggle = {
                navController.navigate("SystemInformationView")
            })
        SettingToggleRow(
            label = "Debug Mode (show changed area)",
            value = settings.debugMode,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(debugMode = isChecked))
            })
        SettingToggleRow(
            label = "Use simple rendering for scroll and zoom -- uses more resources.",
            value = settings.simpleRendering,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(simpleRendering = isChecked))
            })
        SettingToggleRow(
            label = "Use openGL rendering for eraser.",
            value = settings.openGLRendering,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(openGLRendering = isChecked))
            })
        SettingToggleRow(
            label = "Use MuPdf as a renderer for pdfs.",
            value = settings.muPdfRendering,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(muPdfRendering = isChecked))
            })
        SettingToggleRow(
            label = "Allow destructive migrations",
            value = settings.destructiveMigrations,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(destructiveMigrations = isChecked))
            })
    }
}