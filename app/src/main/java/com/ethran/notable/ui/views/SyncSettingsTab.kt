package com.ethran.notable.ui.views

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncState
import com.ethran.notable.ui.components.SettingToggleRow
import com.ethran.notable.ui.components.SettingsDivider
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.viewmodels.SyncConnectionStatus
import com.ethran.notable.ui.viewmodels.SyncSettingsUiState

data class SyncCredentialsCallbacks(
    val onServerUrlChange: (String) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onSaveCredentials: () -> Unit = {},
)

data class SyncBehaviorCallbacks(
    val onToggleSyncEnabled: (Boolean) -> Unit = {},
    val onAutoSyncChanged: (Boolean) -> Unit = {},
    val onSyncOnCloseChanged: (Boolean) -> Unit = {},
    val onWifiOnlyChanged: (Boolean) -> Unit = {},
)

data class SyncDangerCallbacks(
    val onForceUploadRequested: (Boolean) -> Unit = {},
    val onForceDownloadRequested: (Boolean) -> Unit = {},
    val onConfirmForceUpload: () -> Unit = {},
    val onConfirmForceDownload: () -> Unit = {},
)

data class SyncSettingsCallbacks(
    val credentials: SyncCredentialsCallbacks = SyncCredentialsCallbacks(),
    val behavior: SyncBehaviorCallbacks = SyncBehaviorCallbacks(),
    val onTestConnection: () -> Unit = {},
    val onManualSync: () -> Unit = {},
    val onClearSyncLogs: () -> Unit = {},
    val danger: SyncDangerCallbacks = SyncDangerCallbacks(),
)

@Composable
fun SyncSettings(
    state: SyncSettingsUiState,
    callbacks: SyncSettingsCallbacks,
) {
    val syncSettings = state.syncSettings

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.sync_title),
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Enable/Disable Sync Toggle
        SyncEnableToggle(
            syncSettings = syncSettings,
            onToggleSyncEnabled = callbacks.behavior.onToggleSyncEnabled
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Credential Fields
        SyncCredentialFields(
            serverUrl = state.serverUrl,
            username = state.username,
            password = state.password,
            onServerUrlChange = callbacks.credentials.onServerUrlChange,
            onUsernameChange = callbacks.credentials.onUsernameChange,
            onPasswordChange = callbacks.credentials.onPasswordChange
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Save Credentials Button
        Button(
            onClick = callbacks.credentials.onSaveCredentials,
            enabled = state.credentialsChanged && state.username.isNotEmpty() && state.password.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.primary,
                contentColor = MaterialTheme.colors.onPrimary,
                disabledBackgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                disabledContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(stringResource(R.string.sync_save_credentials), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Test Connection Button and Status
        SyncConnectionTest(
            serverUrl = state.serverUrl,
            username = state.username,
            password = state.password,
            testingConnection = state.testingConnection,
            connectionStatus = state.connectionStatus,
            onTestConnection = callbacks.onTestConnection
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Sync Controls (auto-sync and sync on close)
        SyncControlToggles(
            syncSettings = syncSettings,
            onAutoSyncChanged = callbacks.behavior.onAutoSyncChanged,
            onSyncOnCloseChanged = callbacks.behavior.onSyncOnCloseChanged,
            onWifiOnlyChanged = callbacks.behavior.onWifiOnlyChanged
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Manual Sync Button
        ManualSyncButton(
            syncSettings = syncSettings,
            serverUrl = state.serverUrl,
            syncState = state.syncState,
            onManualSync = callbacks.onManualSync
        )

        Spacer(modifier = Modifier.height(32.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Danger Zone: Force Operations
        ForceOperationsSection(
            syncSettings = syncSettings,
            serverUrl = state.serverUrl,
            showForceUploadConfirm = state.showForceUploadConfirm,
            showForceDownloadConfirm = state.showForceDownloadConfirm,
            onForceUploadRequested = callbacks.danger.onForceUploadRequested,
            onForceDownloadRequested = callbacks.danger.onForceDownloadRequested,
            onConfirmForceUpload = callbacks.danger.onConfirmForceUpload,
            onConfirmForceDownload = callbacks.danger.onConfirmForceDownload
        )

        Spacer(modifier = Modifier.height(32.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Sync Log Viewer
        SyncLogViewer(syncLogs = state.syncLogs, onClearLog = callbacks.onClearSyncLogs)

        Spacer(modifier = Modifier.height(32.dp))
    }
}


@Composable
fun SyncEnableToggle(
    syncSettings: SyncSettings, onToggleSyncEnabled: (Boolean) -> Unit
) {
    SettingToggleRow(
        label = stringResource(R.string.sync_enable_label),
        value = syncSettings.syncEnabled,
        onToggle = onToggleSyncEnabled
    )
}

@Composable
fun SyncCredentialFields(
    serverUrl: String,
    username: String,
    password: String,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit
) {
    val textFieldBackground = MaterialTheme.colors.onSurface.copy(alpha = 0.08f)

    // Server URL Field
    Column {
        Text(
            text = stringResource(R.string.sync_server_url_note),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.sync_server_url_label),
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        BasicTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colors.onSurface
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .background(textFieldBackground)
                .padding(12.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (serverUrl.isEmpty()) {
                        Text(
                            stringResource(R.string.sync_server_url_placeholder), style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                            )
                        )
                    }
                    innerTextField()
                }
            })
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Username Field
    Column {
        Text(
            text = stringResource(R.string.sync_username_label),
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        BasicTextField(
            value = username,
            onValueChange = onUsernameChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colors.onSurface
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .background(textFieldBackground)
                .padding(12.dp)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Password Field
    Column {
        Text(
            text = stringResource(R.string.sync_password_label),
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        BasicTextField(
            value = password,
            onValueChange = onPasswordChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = MaterialTheme.colors.onSurface
            ),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .background(textFieldBackground)
                .padding(12.dp)
        )
    }
}

@Composable
fun SyncConnectionTest(
    serverUrl: String,
    username: String,
    password: String,
    testingConnection: Boolean,
    connectionStatus: SyncConnectionStatus?,
    onTestConnection: () -> Unit
) {
    Button(
        onClick = onTestConnection,
        enabled = !testingConnection && serverUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colors.onSurface,
            disabledBackgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
            disabledContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        if (testingConnection) {
            Text(stringResource(R.string.sync_testing_connection))
        } else {
            Text(stringResource(R.string.sync_test_connection), fontWeight = FontWeight.Bold)
        }
    }

    connectionStatus?.let { status ->
        val statusColor = when (status) {
            is SyncConnectionStatus.Success -> Color(0, 150, 0)
            is SyncConnectionStatus.Failed -> MaterialTheme.colors.error
            else -> Color(200, 100, 0)
        }
        val statusText = when (status) {
            SyncConnectionStatus.Failed -> stringResource(R.string.sync_connection_failed)
            SyncConnectionStatus.Success -> stringResource(R.string.sync_connected_successfully)
            is SyncConnectionStatus.ClockSkew -> stringResource(
                R.string.sync_clock_skew_warning, status.seconds
            )
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.body2,
            color = statusColor,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
fun SyncControlToggles(
    syncSettings: SyncSettings,
    onAutoSyncChanged: (Boolean) -> Unit,
    onSyncOnCloseChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit
) {
    SettingToggleRow(
        label = stringResource(R.string.sync_auto_sync_label, syncSettings.syncInterval),
        value = syncSettings.autoSync,
        onToggle = onAutoSyncChanged
    )

    SettingToggleRow(
        label = stringResource(R.string.sync_on_note_close_label),
        value = syncSettings.syncOnNoteClose,
        onToggle = onSyncOnCloseChanged
    )

    SettingToggleRow(
        label = stringResource(R.string.sync_wifi_only_label),
        value = syncSettings.wifiOnly,
        onToggle = onWifiOnlyChanged
    )
}

@Composable
fun ManualSyncButton(
    syncSettings: SyncSettings, serverUrl: String, syncState: SyncState, onManualSync: () -> Unit
) {
    Column {
        Button(
            onClick = onManualSync,
            enabled = syncState is SyncState.Idle && syncSettings.syncEnabled && serverUrl.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = when (syncState) {
                    is SyncState.Success -> Color(0, 150, 0)
                    is SyncState.Error -> MaterialTheme.colors.error
                    else -> MaterialTheme.colors.primary
                },
                contentColor = Color.White,
                disabledBackgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                disabledContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            when (syncState) {
                is SyncState.Idle -> Text(
                    stringResource(R.string.sync_now),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                is SyncState.Syncing -> Text(
                    stringResource(
                        R.string.sync_progress_details,
                        syncState.details,
                        (syncState.progress * 100).toInt()
                    ), fontWeight = FontWeight.Bold, fontSize = 14.sp
                )

                is SyncState.Success -> Text(
                    stringResource(R.string.sync_synced),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                is SyncState.Error -> Text(
                    stringResource(R.string.sync_failed),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        // Progress indicator
        if (syncState is SyncState.Syncing) {
            androidx.compose.material.LinearProgressIndicator(
                progress = syncState.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = MaterialTheme.colors.primary
            )
        }

        // Success summary
        if (syncState is SyncState.Success) {
            val summary = syncState.summary
            Text(
                text = stringResource(
                    R.string.sync_summary,
                    summary.notebooksSynced,
                    summary.notebooksDownloaded,
                    summary.notebooksDeleted,
                    summary.duration
                ),
                style = MaterialTheme.typography.caption,
                color = Color(0, 150, 0),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // Error details
        if (syncState is SyncState.Error) {
            val errorText =
                if (syncState.error == com.ethran.notable.sync.SyncError.WIFI_REQUIRED) {
                    stringResource(R.string.sync_wifi_required_message)
                } else {
                    stringResource(
                        R.string.sync_error_at_step,
                        syncState.step.toString(),
                        syncState.error.toString(),
                        if (syncState.canRetry) stringResource(R.string.sync_can_retry) else ""
                    )
                }
            Text(
                text = errorText,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.error,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // Last sync time
        syncSettings.lastSyncTime?.let { timestamp ->
            Text(
                text = stringResource(R.string.sync_last_synced, timestamp),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
fun ForceOperationsSection(
    syncSettings: SyncSettings,
    serverUrl: String,
    showForceUploadConfirm: Boolean,
    showForceDownloadConfirm: Boolean,
    onForceUploadRequested: (Boolean) -> Unit,
    onForceDownloadRequested: (Boolean) -> Unit,
    onConfirmForceUpload: () -> Unit,
    onConfirmForceDownload: () -> Unit
) {
    Text(
        text = stringResource(R.string.sync_force_operations_title),
        style = MaterialTheme.typography.h6,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.error,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Text(
        text = stringResource(R.string.sync_force_operations_warning),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(bottom = 16.dp)
    )

    Button(
        onClick = { onForceUploadRequested(true) },
        enabled = syncSettings.syncEnabled && serverUrl.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(200, 100, 0),
            contentColor = Color.White,
            disabledBackgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Text(stringResource(R.string.sync_force_upload_button), fontWeight = FontWeight.Bold)
    }

    if (showForceUploadConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.sync_confirm_force_upload_title),
            message = stringResource(R.string.sync_confirm_force_upload_message),
            onConfirm = {
                onConfirmForceUpload()
            },
            onDismiss = { onForceUploadRequested(false) })
    }

    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = { onForceDownloadRequested(true) },
        enabled = syncSettings.syncEnabled && serverUrl.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = MaterialTheme.colors.error,
            contentColor = Color.White,
            disabledBackgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.38f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Text(stringResource(R.string.sync_force_download_button), fontWeight = FontWeight.Bold)
    }

    if (showForceDownloadConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.sync_confirm_force_download_title),
            message = stringResource(R.string.sync_confirm_force_download_message),
            onConfirm = {
                onConfirmForceDownload()
            },
            onDismiss = { onForceDownloadRequested(false) })
    }
}

@Composable
fun SyncLogViewer(syncLogs: List<SyncLogger.LogEntry>, onClearLog: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.sync_log_title),
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface
        )
        Button(
            onClick = onClearLog, colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colors.onSurface
            ), modifier = Modifier.height(32.dp)
        ) {
            Text(stringResource(R.string.sync_clear_log), fontSize = 12.sp)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f))
            .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.2f))
    ) {
        val scrollState = rememberScrollState()

        LaunchedEffect(syncLogs.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        if (syncLogs.isEmpty()) {
            Text(
                text = stringResource(R.string.sync_log_empty),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(12.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            ) {
                syncLogs.takeLast(20).forEach { log ->
                    val logColor = when (log.level) {
                        SyncLogger.LogLevel.INFO -> if (MaterialTheme.colors.isLight) Color(
                            0, 100, 0
                        ) else Color(150, 255, 150)

                        SyncLogger.LogLevel.WARNING -> Color(200, 100, 0)
                        SyncLogger.LogLevel.ERROR -> MaterialTheme.colors.error
                    }
                    Text(
                        text = "[${log.timestamp}] ${log.message}", style = TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = logColor
                        ), modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmationDialog(
    title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colors.surface,
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = message,
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colors.onSurface
                        )
                    ) {
                        Text(stringResource(R.string.sync_dialog_cancel))
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.error, contentColor = Color.White
                        )
                    ) {
                        Text(
                            stringResource(R.string.sync_dialog_confirm),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}


// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //


@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "Dark Mode",
    heightDp = 1200
)
@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    name = "Light Mode",
    heightDp = 1200
)
@Composable
fun SyncSettingsContentPreview() {
    InkaTheme {
        Surface(color = MaterialTheme.colors.background) {
            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SyncSettings(
                    state = SyncSettingsUiState(
                        serverUrl = "https://webdav.example.com",
                        username = "demo",
                        password = "secret",
                        savedUsername = "demo",
                        savedPassword = "secret",
                        syncSettings = SyncSettings(
                            syncEnabled = true, serverUrl = "https://webdav.example.com"
                        )
                    ), callbacks = SyncSettingsCallbacks()
                )
            }
        }
    }
}
