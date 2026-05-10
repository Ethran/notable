package com.ethran.notable.ui.views

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.sync.ConnectionTestResult
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.SyncStep
import com.ethran.notable.ui.components.SettingToggleRow
import com.ethran.notable.ui.components.SettingsDivider
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.viewmodels.SyncSettingsUiState
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError

data class SyncCredentialsCallbacks(
    val onServerUrlChange: (String) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onTogglePasswordVisibility: () -> Unit = {},
    val onSaveCredentials: () -> Unit = {},
)

data class SyncBehaviorCallbacks(
    val onToggleSyncEnabled: (Boolean) -> Unit = {},
    val onAutoSyncChanged: (Boolean) -> Unit = {},
    val onSyncIntervalChanged: (Int) -> Unit = {},
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

private val EInkFieldShape = RoundedCornerShape(4.dp)
private val EInkButtonShape = RoundedCornerShape(8.dp)
private val EInkFieldBorderWidth = 1.dp

@Composable
fun SyncSettings(
    state: SyncSettingsUiState,
    callbacks: SyncSettingsCallbacks,
) {
    val isConfigured by remember(state.isPasswordSaved, state.serverUrl) {
        derivedStateOf { state.isPasswordSaved && state.serverUrl.isNotEmpty() }
    }
    val serverSectionTitle by remember(isConfigured, state.serverUrl) {
        derivedStateOf {
            if (isConfigured) "Server: ${state.serverUrl.take(25)}..." else "Connection Setup"
        }
    }
    var showServerConfig by remember { mutableStateOf(!isConfigured) }

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

        ConnectionSection(
            state = state,
            callbacks = callbacks,
            sectionTitle = serverSectionTitle,
            isConfigured = isConfigured,
            showServerConfig = showServerConfig,
            onToggleSection = { showServerConfig = !showServerConfig }
        )

        if (isConfigured) {
            Spacer(modifier = Modifier.height(24.dp))

            SyncBehaviorSection(state = state, callbacks = callbacks)

            if (state.syncSettings.syncEnabled) {
                Spacer(modifier = Modifier.height(24.dp))

                SyncActionsSection(state = state, callbacks = callbacks)

                Spacer(modifier = Modifier.height(24.dp))

                var logsExpanded by remember { mutableStateOf(false) }
                SyncLogsSection(
                    state = state,
                    callbacks = callbacks,
                    isExpanded = logsExpanded,
                    onToggleExpanded = { logsExpanded = !logsExpanded }
                )
            }
        } else {
            Spacer(modifier = Modifier.height(24.dp))
            MissingConfigurationHint()
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ConnectionSection(
    state: SyncSettingsUiState,
    callbacks: SyncSettingsCallbacks,
    sectionTitle: String,
    isConfigured: Boolean,
    showServerConfig: Boolean,
    onToggleSection: () -> Unit,
) {
    EInkSection(
        title = sectionTitle,
        icon = Icons.Default.Cloud,
        isExpandable = isConfigured,
        isExpanded = showServerConfig,
        onHeaderClick = { if (isConfigured) onToggleSection() }
    ) {
        SyncCredentialFields(
            serverUrl = state.serverUrl,
            username = state.username,
            password = state.password,
            isPasswordSaved = state.isPasswordSaved,
            passwordVisible = state.passwordVisible,
            onServerUrlChange = callbacks.credentials.onServerUrlChange,
            onUsernameChange = callbacks.credentials.onUsernameChange,
            onPasswordChange = callbacks.credentials.onPasswordChange,
            onTogglePasswordVisibility = callbacks.credentials.onTogglePasswordVisibility
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EInkActionButton(
                text = "Save Credentials",
                onClick = callbacks.credentials.onSaveCredentials,
                enabled = state.credentialsChanged && state.username.isNotEmpty(),
                modifier = Modifier.weight(1f),
                isBold = true
            )
            EInkActionButton(
                text = if (state.testingConnection) "Testing..." else "Test Connection",
                onClick = callbacks.onTestConnection,
                enabled = !state.testingConnection && state.serverUrl.isNotEmpty(),
                modifier = Modifier.weight(1f),
                isSecondary = true
            )
        }

        state.connectionStatus?.let {
            Spacer(modifier = Modifier.height(8.dp))
            ConnectionStatusText(it)
        }
    }
}

@Composable
private fun SyncBehaviorSection(
    state: SyncSettingsUiState,
    callbacks: SyncSettingsCallbacks,
) {
    EInkSection(title = "Sync Behavior", icon = Icons.Default.Settings) {
        SyncEnableToggle(state.syncSettings, callbacks.behavior.onToggleSyncEnabled)

        if (state.syncSettings.syncEnabled) {
            SyncControlToggles(
                syncSettings = state.syncSettings,
                onAutoSyncChanged = callbacks.behavior.onAutoSyncChanged,
                onSyncIntervalChanged = callbacks.behavior.onSyncIntervalChanged,
                onSyncOnCloseChanged = callbacks.behavior.onSyncOnCloseChanged,
                onWifiOnlyChanged = callbacks.behavior.onWifiOnlyChanged
            )
        }
    }
}

@Composable
private fun SyncActionsSection(
    state: SyncSettingsUiState,
    callbacks: SyncSettingsCallbacks,
) {
    EInkSection(title = "Manual Actions", icon = Icons.Default.Sync) {
        ManualSyncButton(
            syncSettings = state.syncSettings,
            serverUrl = state.serverUrl,
            syncState = state.syncState,
            onManualSync = callbacks.onManualSync
        )

        LastSyncInfo(lastSyncTime = state.syncSettings.lastSyncTime)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "DANGER ZONE",
            style = MaterialTheme.typography.overline,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colors.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        ForceOperationsSection(
            syncSettings = state.syncSettings,
            onForceUploadRequested = callbacks.danger.onForceUploadRequested,
            onForceDownloadRequested = callbacks.danger.onForceDownloadRequested,
            onConfirmForceUpload = callbacks.danger.onConfirmForceUpload,
            onConfirmForceDownload = callbacks.danger.onConfirmForceDownload,
            showForceUploadConfirm = state.showForceUploadConfirm,
            showForceDownloadConfirm = state.showForceDownloadConfirm
        )
    }
}

@Composable
private fun LastSyncInfo(lastSyncTime: String?) {
    val label = lastSyncTime?.takeIf { it.isNotBlank() } ?: "Never"
    Text(
        text = "Last sync: $label",
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )
}

@Composable
private fun SyncLogsSection(
    state: SyncSettingsUiState,
    callbacks: SyncSettingsCallbacks,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    EInkSection(
        title = "Activity Log",
        icon = Icons.Default.History,
        isExpandable = true,
        isExpanded = isExpanded,
        onHeaderClick = onToggleExpanded
    ) {
        SyncLogViewer(syncLogs = state.syncLogs, onClearLog = callbacks.onClearSyncLogs)
    }
}

@Composable
private fun MissingConfigurationHint() {
    Text(
        "Complete the connection setup above to enable sync features.",
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}


@Composable
fun EInkSection(
    title: String,
    icon: ImageVector,
    isExpandable: Boolean = false,
    isExpanded: Boolean = true,
    onHeaderClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = isExpandable) { onHeaderClick() }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, 
                contentDescription = null, 
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title, 
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Bold, 
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colors.onSurface
            )
            if (isExpandable) {
                Icon(
                    if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onSurface
                )
            }
        }
        
        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                content()
            }
        }
        SettingsDivider()
    }
}

@Composable
fun ConnectionStatusText(result: AppResult<ConnectionTestResult, DomainError>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = when (result) {
            is AppResult.Success -> Icons.Default.CheckCircle
            is AppResult.Error -> Icons.Default.Warning
        }
        Icon(
            icon, 
            contentDescription = null, 
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colors.onSurface
        )
        Spacer(modifier = Modifier.width(4.dp))
        
        val text = when (result) {
            is AppResult.Success -> {
                val skewMs = result.data.clockSkewMs
                if (skewMs != null && kotlin.math.abs(skewMs) > 1000) {
                    "Connected (skew: ${skewMs / 1000}s)"
                } else {
                    "Connected successfully"
                }
            }
            is AppResult.Error -> result.error.userMessage
        }
        Text(
            text, 
            style = MaterialTheme.typography.caption, 
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface
        )
    }
}

@Composable
fun SyncCredentialFields(
    serverUrl: String,
    username: String,
    password: String,
    isPasswordSaved: Boolean,
    passwordVisible: Boolean,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit
) {
    EInkTextField(
        label = "Server URL",
        value = serverUrl,
        onValueChange = onServerUrlChange,
        placeholder = "https://example.com/dav/"
    )
    
    if (serverUrl.isNotEmpty()) {
        Text(
            "Path: ${serverUrl.trimEnd('/')}/notable/",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 2.dp, start = 4.dp)
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    EInkTextField(
        label = "Username",
        value = username,
        onValueChange = onUsernameChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        "Password", 
        style = MaterialTheme.typography.caption, 
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colors.onSurface
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface, EInkFieldShape)
            .border(EInkFieldBorderWidth, MaterialTheme.colors.onSurface, EInkFieldShape)
            .padding(start = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                if (password.isEmpty() && isPasswordSaved) {
                    Text(
                        "(unchanged)", 
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f), 
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    )
                }
                BasicTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace, 
                        fontSize = 14.sp,
                        color = MaterialTheme.colors.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                )
            }
            IconButton(onClick = onTogglePasswordVisibility) {
                Icon(
                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun EInkTextField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String = "") {
    Column {
        Text(
            label, 
            style = MaterialTheme.typography.caption, 
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.surface, EInkFieldShape)
                .border(EInkFieldBorderWidth, MaterialTheme.colors.onSurface, EInkFieldShape)
                .padding(12.dp)
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    placeholder, 
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f), 
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace, 
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun eInkButtonColors(isSecondary: Boolean = false) = ButtonDefaults.buttonColors(
    backgroundColor = if (isSecondary) MaterialTheme.colors.onSurface.copy(alpha = 0.1f) else MaterialTheme.colors.onSurface,
    contentColor = if (isSecondary) MaterialTheme.colors.onSurface else MaterialTheme.colors.surface,
    disabledBackgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
    disabledContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
)

@Composable
fun SyncEnableToggle(
    syncSettings: SyncSettings, onToggleSyncEnabled: (Boolean) -> Unit
) {
    SettingToggleRow(
        label = "Enable WebDAV Sync",
        value = syncSettings.syncEnabled,
        onToggle = onToggleSyncEnabled
    )
}

@Composable
fun SyncControlToggles(
    syncSettings: SyncSettings,
    onAutoSyncChanged: (Boolean) -> Unit,
    onSyncIntervalChanged: (Int) -> Unit,
    onSyncOnCloseChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit
) {
    SettingToggleRow(
        label = "Auto-sync (every ${syncSettings.syncInterval}m)",
        value = syncSettings.autoSync,
        onToggle = onAutoSyncChanged
    )
    SyncIntervalSelector(
        intervalMinutes = syncSettings.syncInterval,
        onIntervalChanged = onSyncIntervalChanged
    )
    SettingToggleRow(
        label = "Sync when closing notes",
        value = syncSettings.syncOnNoteClose,
        onToggle = onSyncOnCloseChanged
    )
    SettingToggleRow(
        label = "Use WiFi only",
        value = syncSettings.wifiOnly,
        onToggle = onWifiOnlyChanged
    )
}

@Composable
private fun SyncIntervalSelector(
    intervalMinutes: Int,
    onIntervalChanged: (Int) -> Unit,
) {
    val minInterval = 15
    val maxInterval = 240
    val stepMinutes = 5

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Sync interval",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.weight(1f)
        )

        EInkActionButton(
            text = "-",
            onClick = { onIntervalChanged((intervalMinutes - stepMinutes).coerceAtLeast(minInterval)) },
            enabled = intervalMinutes > minInterval,
            isSecondary = true
        )

        Text(
            text = "${intervalMinutes}m",
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface
        )

        EInkActionButton(
            text = "+",
            onClick = { onIntervalChanged((intervalMinutes + stepMinutes).coerceAtMost(maxInterval)) },
            enabled = intervalMinutes < maxInterval,
            isSecondary = true
        )
    }
}

@Composable
fun ManualSyncButton(
    syncSettings: SyncSettings, serverUrl: String, syncState: SyncState, onManualSync: () -> Unit
) {
    val label by remember(syncState) {
        derivedStateOf {
            when (syncState) {
                is SyncState.Syncing -> "Syncing\u2026"
                is SyncState.Success -> "Successfully Synced"
                is SyncState.Error -> "Sync Failed"
                else -> "Sync Now"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (syncState is SyncState.Syncing) {
            SyncProgressPanel(syncState)
        }
        Button(
            onClick = onManualSync,
            enabled = syncState is SyncState.Idle && syncSettings.syncEnabled && serverUrl.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = eInkButtonColors(),
            shape = EInkButtonShape
        ) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SyncProgressPanel(syncing: SyncState.Syncing) {
    val overall = overallProgressOf(syncing).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colors.onSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = syncing.currentStep.displayName(),
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface
            )
            Text(
                text = "${(overall * 100).toInt()}%",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .border(1.dp, MaterialTheme.colors.onSurface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(overall)
                    .height(6.dp)
                    .background(MaterialTheme.colors.onSurface)
            )
        }
        syncing.item?.let { item ->
            Text(
                text = "Notebook ${item.index} of ${item.total} \u00b7 ${item.name}",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface
            )
        }
    }
}

private fun SyncStep.displayName(): String = when (this) {
    SyncStep.INITIALIZING -> "Preparing"
    SyncStep.SYNCING_FOLDERS -> "Syncing folders"
    SyncStep.APPLYING_DELETIONS -> "Applying deletions"
    SyncStep.SYNCING_NOTEBOOKS -> "Syncing notebooks"
    SyncStep.DOWNLOADING_NEW -> "Downloading new notebooks"
    SyncStep.UPLOADING_DELETIONS -> "Uploading deletions"
    SyncStep.FINALIZING -> "Finalizing"
}

private fun overallProgressOf(s: SyncState.Syncing): Float {
    val start = s.stepProgress
    val end = stepBandEnd(s.currentStep)
    val frac = s.item?.let { it.index.toFloat() / it.total.coerceAtLeast(1) } ?: 0f
    return start + (end - start) * frac
}

private fun stepBandEnd(step: SyncStep): Float = when (step) {
    SyncStep.INITIALIZING -> 0.1f
    SyncStep.SYNCING_FOLDERS -> 0.2f
    SyncStep.APPLYING_DELETIONS -> 0.3f
    SyncStep.SYNCING_NOTEBOOKS -> 0.6f
    SyncStep.DOWNLOADING_NEW -> 0.8f
    SyncStep.UPLOADING_DELETIONS -> 0.9f
    SyncStep.FINALIZING -> 1.0f
}

@Composable
fun ForceOperationsSection(
    syncSettings: SyncSettings,
    showForceUploadConfirm: Boolean,
    showForceDownloadConfirm: Boolean,
    onForceUploadRequested: (Boolean) -> Unit,
    onForceDownloadRequested: (Boolean) -> Unit,
    onConfirmForceUpload: () -> Unit,
    onConfirmForceDownload: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EInkActionButton(
            text = "Upload All",
            onClick = { onForceUploadRequested(true) },
            enabled = syncSettings.syncEnabled,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp
        )
        EInkActionButton(
            text = "Download All",
            onClick = { onForceDownloadRequested(true) },
            enabled = syncSettings.syncEnabled,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp
        )
    }

    if (showForceUploadConfirm) {
        ConfirmationDialog(
            title = "Replace Server Data?",
            message = "This will DELETE all notebooks on the server and replace them with your local data. This cannot be undone.",
            onConfirm = onConfirmForceUpload,
            onDismiss = { onForceUploadRequested(false) }
        )
    }
    if (showForceDownloadConfirm) {
        ConfirmationDialog(
            title = "Replace Local Data?",
            message = "This will DELETE all local notebooks and replace them with data from the server. This cannot be undone.",
            onConfirm = onConfirmForceDownload,
            onDismiss = { onForceDownloadRequested(false) }
        )
    }
}

@Composable
fun SyncLogViewer(syncLogs: List<SyncLogger.LogEntry>, onClearLog: () -> Unit) {
    val recentLogs = remember(syncLogs) { syncLogs.takeLast(30) }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            ) {
                if (recentLogs.isEmpty()) {
                    Text(
                        "No recent activity.", 
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                    )
                } else {
                    recentLogs.forEach { log ->
                        Text(
                            text = "[${log.timestamp}] ${log.message}",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace, 
                                fontSize = 10.sp,
                                color = MaterialTheme.colors.onSurface
                            ),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        EInkActionButton(
            text = "Clear Log",
            onClick = onClearLog,
            modifier = Modifier.align(Alignment.End),
            isSecondary = true,
            fontSize = 10.sp
        )
    }
}

@Composable
fun ConfirmationDialog(
    title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colors.surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    title, 
                    fontWeight = FontWeight.Bold, 
                    style = MaterialTheme.typography.h6,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    message, 
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss, 
                        modifier = Modifier.weight(1f), 
                        shape = EInkButtonShape,
                        colors = eInkButtonColors(isSecondary = true)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onConfirm, 
                        modifier = Modifier.weight(1f), 
                        shape = EInkButtonShape,
                        colors = eInkButtonColors()
                    ) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

@Composable
private fun EInkActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSecondary: Boolean = false,
    isBold: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = EInkButtonShape,
        colors = eInkButtonColors(isSecondary = isSecondary)
    ) {
        Text(
            text = text,
            fontWeight = if (isBold) FontWeight.Bold else null,
            fontSize = fontSize
        )
    }
}


// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //


@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "Dark Mode",
//    heightDp = 500
)
@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    name = "Light Mode",
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
                            syncEnabled = true,
                            serverUrl = "https://webdav.example.com"
                        )
                    ), callbacks = SyncSettingsCallbacks()
                )
            }
        }
    }
}


@Preview(name = "Configured - Collapsed", showBackground = true)
@Composable
fun SyncSettingsConfiguredPreview() {
    InkaTheme {
        Surface(color = MaterialTheme.colors.background) {
            SyncSettings(
                state = SyncSettingsUiState(
                    serverUrl = "https://webdav.example.com/dav/",
                    username = "demo_user",
                    isPasswordSaved = true,
                    syncSettings = SyncSettings(
                        syncEnabled = true,
                        lastSyncTime = "2024-03-20 14:30:05"
                    )
                ),
                callbacks = SyncSettingsCallbacks()
            )
        }
    }
}

@Preview(name = "Configured - Syncing", showBackground = true)
@Composable
fun SyncSettingsSyncingPreview() {
    InkaTheme {
        Surface(color = MaterialTheme.colors.background) {
            SyncSettings(
                state = SyncSettingsUiState(
                    serverUrl = "https://webdav.example.com/dav/",
                    username = "demo_user",
                    isPasswordSaved = true,
                    syncState = SyncState.Syncing(
                        SyncStep.SYNCING_NOTEBOOKS,
                        0.45f,
                        "Syncing notebooks..."
                    ),
                    syncSettings = SyncSettings(
                        syncEnabled = true,
                        lastSyncTime = "2024-03-20 14:30:05"
                    )
                ),
                callbacks = SyncSettingsCallbacks()
            )
        }
    }
}
