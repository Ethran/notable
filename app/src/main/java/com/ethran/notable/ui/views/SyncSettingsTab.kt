package com.ethran.notable.ui.views

import android.content.Context
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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.datastore.SyncSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.sync.CredentialManager
import com.ethran.notable.sync.SyncEngine
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncResult
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.WebDAVClient
import com.ethran.notable.ui.showHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncSettings(kv: KvProxy, settings: AppSettings, context: Context) {
    val syncSettings = settings.syncSettings
    val credentialManager = remember { CredentialManager(context) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(syncSettings.serverUrl) }
    var username by remember { mutableStateOf(syncSettings.username) }
    var password by remember { mutableStateOf("") }
    var savedUsername by remember { mutableStateOf(syncSettings.username) }
    var savedPassword by remember { mutableStateOf("") }
    var testingConnection by remember { mutableStateOf(false) }
    var syncInProgress by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }

    // Observe sync logs
    val syncLogs by SyncLogger.logs.collectAsState()

    // Load password from CredentialManager on first composition
    LaunchedEffect(Unit) {
        credentialManager.getCredentials()?.let { (user, pass) ->
            username = user
            password = pass
            savedUsername = user
            savedPassword = pass
            SyncLogger.i("Settings", "Loaded credentials for user: $user")
        } ?: SyncLogger.w("Settings", "No credentials found in storage")
    }

    // Check if credentials have changed
    val credentialsChanged = username != savedUsername || password != savedPassword

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.sync_title),
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Enable/Disable Sync Toggle
        SyncEnableToggle(
            syncSettings = syncSettings,
            settings = settings,
            kv = kv,
            context = context
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Credential Fields
        SyncCredentialFields(
            serverUrl = serverUrl,
            username = username,
            password = password,
            onServerUrlChange = {
                serverUrl = it
                kv.setAppSettings(settings.copy(syncSettings = syncSettings.copy(serverUrl = it)))
            },
            onUsernameChange = { username = it },
            onPasswordChange = { password = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Save Credentials Button
        Button(
            onClick = {
                credentialManager.saveCredentials(username, password)
                savedUsername = username
                savedPassword = password
                kv.setAppSettings(settings.copy(syncSettings = syncSettings.copy(username = username)))
                SyncLogger.i("Settings", "Credentials saved for user: $username")
                showHint(context.getString(R.string.sync_credentials_saved), scope)
            },
            enabled = credentialsChanged && username.isNotEmpty() && password.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0, 120, 200),
                contentColor = Color.White,
                disabledBackgroundColor = Color(200, 200, 200),
                disabledContentColor = Color.Gray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(48.dp)
        ) {
            Text(stringResource(R.string.sync_save_credentials), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Test Connection Button and Status
        SyncConnectionTest(
            serverUrl = serverUrl,
            username = username,
            password = password,
            testingConnection = testingConnection,
            connectionStatus = connectionStatus,
            onTestConnection = {
                testingConnection = true
                connectionStatus = null
                scope.launch(Dispatchers.IO) {
                    val result = WebDAVClient.testConnection(serverUrl, username, password)
                    withContext(Dispatchers.Main) {
                        testingConnection = false
                        connectionStatus = if (result)
                            context.getString(R.string.sync_connected_successfully)
                        else
                            context.getString(R.string.sync_connection_failed)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Sync Controls (auto-sync and sync on close)
        SyncControlToggles(
            syncSettings = syncSettings,
            settings = settings,
            kv = kv,
            context = context
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Manual Sync Button
        ManualSyncButton(
            syncInProgress = syncInProgress,
            syncSettings = syncSettings,
            serverUrl = serverUrl,
            context = context,
            kv = kv,
            scope = scope,
            onSyncStateChange = { syncInProgress = it }
        )

        Spacer(modifier = Modifier.height(32.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Danger Zone: Force Operations
        ForceOperationsSection(
            syncSettings = syncSettings,
            serverUrl = serverUrl,
            context = context,
            scope = scope,
            onSyncStateChange = { syncInProgress = it }
        )

        Spacer(modifier = Modifier.height(32.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Sync Log Viewer
        SyncLogViewer(syncLogs = syncLogs)
    }
}

@Composable
fun SyncEnableToggle(
    syncSettings: SyncSettings,
    settings: AppSettings,
    kv: KvProxy,
    context: Context
) {
    SettingToggleRow(
        label = stringResource(R.string.sync_enable_label),
        value = syncSettings.syncEnabled,
        onToggle = { isChecked ->
            kv.setAppSettings(
                settings.copy(syncSettings = syncSettings.copy(syncEnabled = isChecked))
            )
            // Enable/disable WorkManager sync
            if (isChecked && syncSettings.autoSync) {
                SyncScheduler.enablePeriodicSync(context, syncSettings.syncInterval.toLong())
            } else {
                SyncScheduler.disablePeriodicSync(context)
            }
        }
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
    // Server URL Field
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
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
                .background(Color(230, 230, 230, 255))
                .padding(12.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (serverUrl.isEmpty()) {
                        Text(
                            stringResource(R.string.sync_server_url_placeholder),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                        )
                    }
                    innerTextField()
                }
            }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Username Field
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
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
                .background(Color(230, 230, 230, 255))
                .padding(12.dp)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Password Field
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
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
                .background(Color(230, 230, 230, 255))
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
    connectionStatus: String?,
    onTestConnection: () -> Unit
) {
    Button(
        onClick = onTestConnection,
        enabled = !testingConnection && serverUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(80, 80, 80),
            contentColor = Color.White,
            disabledBackgroundColor = Color(200, 200, 200),
            disabledContentColor = Color.Gray
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(48.dp)
    ) {
        if (testingConnection) {
            Text(stringResource(R.string.sync_testing_connection))
        } else {
            Text(stringResource(R.string.sync_test_connection), fontWeight = FontWeight.Bold)
        }
    }

    connectionStatus?.let { status ->
        Text(
            text = status,
            style = MaterialTheme.typography.body2,
            color = if (status.startsWith("✓")) Color(0, 150, 0) else Color(200, 0, 0),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun SyncControlToggles(
    syncSettings: SyncSettings,
    settings: AppSettings,
    kv: KvProxy,
    context: Context
) {
    SettingToggleRow(
        label = stringResource(R.string.sync_auto_sync_label, syncSettings.syncInterval),
        value = syncSettings.autoSync,
        onToggle = { isChecked ->
            kv.setAppSettings(
                settings.copy(syncSettings = syncSettings.copy(autoSync = isChecked))
            )
            if (isChecked && syncSettings.syncEnabled) {
                SyncScheduler.enablePeriodicSync(context, syncSettings.syncInterval.toLong())
            } else {
                SyncScheduler.disablePeriodicSync(context)
            }
        }
    )

    SettingToggleRow(
        label = stringResource(R.string.sync_on_note_close_label),
        value = syncSettings.syncOnNoteClose,
        onToggle = { isChecked ->
            kv.setAppSettings(
                settings.copy(syncSettings = syncSettings.copy(syncOnNoteClose = isChecked))
            )
        }
    )
}

@Composable
fun ManualSyncButton(
    syncInProgress: Boolean,
    syncSettings: SyncSettings,
    serverUrl: String,
    context: Context,
    kv: KvProxy,
    scope: kotlinx.coroutines.CoroutineScope,
    onSyncStateChange: (Boolean) -> Unit
) {
    // Observe sync state from SyncEngine
    val syncState by SyncEngine.syncState.collectAsState()

    Column {
        Button(
            onClick = {
                onSyncStateChange(true)
                scope.launch(Dispatchers.IO) {
                    val result = SyncEngine(context).syncAllNotebooks()
                    withContext(Dispatchers.Main) {
                        onSyncStateChange(false)
                        if (result is SyncResult.Success) {
                            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                            val latestSettings = GlobalAppSettings.current
                            kv.setAppSettings(
                                latestSettings.copy(
                                    syncSettings = latestSettings.syncSettings.copy(lastSyncTime = timestamp)
                                )
                            )
                            showHint(context.getString(R.string.sync_completed_successfully), scope)
                        } else {
                            showHint(context.getString(R.string.sync_failed_message, (result as? SyncResult.Failure)?.error.toString()), scope)
                        }
                    }
                }
            },
            enabled = syncState is SyncState.Idle && syncSettings.syncEnabled && serverUrl.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = when (syncState) {
                    is SyncState.Success -> Color(0, 150, 0)
                    is SyncState.Error -> Color(200, 0, 0)
                    else -> Color(0, 120, 200)
                },
                contentColor = Color.White,
                disabledBackgroundColor = Color(200, 200, 200),
                disabledContentColor = Color.Gray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(56.dp)
        ) {
            when (val state = syncState) {
                is SyncState.Idle -> Text(stringResource(R.string.sync_now), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                is SyncState.Syncing -> Text(
                    stringResource(R.string.sync_progress_details, state.details, (state.progress * 100).toInt()),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                is SyncState.Success -> Text(stringResource(R.string.sync_synced), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                is SyncState.Error -> Text(stringResource(R.string.sync_failed), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // Progress indicator
        if (syncState is SyncState.Syncing) {
            androidx.compose.material.LinearProgressIndicator(
                progress = (syncState as SyncState.Syncing).progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                color = Color(0, 120, 200)
            )
        }

        // Success summary
        if (syncState is SyncState.Success) {
            val summary = (syncState as SyncState.Success).summary
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
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }

        // Error details
        if (syncState is SyncState.Error) {
            val error = syncState as SyncState.Error
            Text(
                text = stringResource(
                    R.string.sync_error_at_step,
                    error.step.toString(),
                    error.error.toString(),
                    if (error.canRetry) stringResource(R.string.sync_can_retry) else ""
                ),
                style = MaterialTheme.typography.caption,
                color = Color(200, 0, 0),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }

        // Last sync time
        syncSettings.lastSyncTime?.let { timestamp ->
            Text(
                text = stringResource(R.string.sync_last_synced, timestamp),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun ForceOperationsSection(
    syncSettings: SyncSettings,
    serverUrl: String,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onSyncStateChange: (Boolean) -> Unit
) {
    Text(
        text = stringResource(R.string.sync_force_operations_title),
        style = MaterialTheme.typography.h6,
        fontWeight = FontWeight.Bold,
        color = Color(200, 0, 0),
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Text(
        text = stringResource(R.string.sync_force_operations_warning),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(bottom = 16.dp, start = 4.dp, end = 4.dp)
    )

    var showForceUploadConfirm by remember { mutableStateOf(false) }
    Button(
        onClick = { showForceUploadConfirm = true },
        enabled = syncSettings.syncEnabled && serverUrl.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(200, 100, 0),
            contentColor = Color.White,
            disabledBackgroundColor = Color(200, 200, 200),
            disabledContentColor = Color.Gray
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(48.dp)
    ) {
        Text(stringResource(R.string.sync_force_upload_button), fontWeight = FontWeight.Bold)
    }

    if (showForceUploadConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.sync_confirm_force_upload_title),
            message = stringResource(R.string.sync_confirm_force_upload_message),
            onConfirm = {
                showForceUploadConfirm = false
                onSyncStateChange(true)
                scope.launch(Dispatchers.IO) {
                    val result = SyncEngine(context).forceUploadAll()
                    withContext(Dispatchers.Main) {
                        onSyncStateChange(false)
                        showHint(
                            if (result is SyncResult.Success)
                                context.getString(R.string.sync_force_upload_success)
                            else
                                context.getString(R.string.sync_force_upload_failed),
                            scope
                        )
                    }
                }
            },
            onDismiss = { showForceUploadConfirm = false }
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    var showForceDownloadConfirm by remember { mutableStateOf(false) }
    Button(
        onClick = { showForceDownloadConfirm = true },
        enabled = syncSettings.syncEnabled && serverUrl.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Color(200, 0, 0),
            contentColor = Color.White,
            disabledBackgroundColor = Color(200, 200, 200),
            disabledContentColor = Color.Gray
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(48.dp)
    ) {
        Text(stringResource(R.string.sync_force_download_button), fontWeight = FontWeight.Bold)
    }

    if (showForceDownloadConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.sync_confirm_force_download_title),
            message = stringResource(R.string.sync_confirm_force_download_message),
            onConfirm = {
                showForceDownloadConfirm = false
                onSyncStateChange(true)
                scope.launch(Dispatchers.IO) {
                    val result = SyncEngine(context).forceDownloadAll()
                    withContext(Dispatchers.Main) {
                        onSyncStateChange(false)
                        showHint(
                            if (result is SyncResult.Success)
                                context.getString(R.string.sync_force_download_success)
                            else
                                context.getString(R.string.sync_force_download_failed),
                            scope
                        )
                    }
                }
            },
            onDismiss = { showForceDownloadConfirm = false }
        )
    }
}

@Composable
fun SyncLogViewer(syncLogs: List<SyncLogger.LogEntry>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.sync_log_title),
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold
        )
        Button(
            onClick = { SyncLogger.clear() },
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color.Gray,
                contentColor = Color.White
            ),
            modifier = Modifier.height(32.dp)
        ) {
            Text(stringResource(R.string.sync_clear_log), fontSize = 12.sp)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(Color(250, 250, 250))
            .border(1.dp, Color.Gray)
    ) {
        val scrollState = rememberScrollState()

        LaunchedEffect(syncLogs.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        if (syncLogs.isEmpty()) {
            Text(
                text = stringResource(R.string.sync_log_empty),
                style = MaterialTheme.typography.body2,
                color = Color.Gray,
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
                        SyncLogger.LogLevel.INFO -> Color(0, 100, 0)
                        SyncLogger.LogLevel.WARNING -> Color(200, 100, 0)
                        SyncLogger.LogLevel.ERROR -> Color(200, 0, 0)
                    }
                    Text(
                        text = "[${log.timestamp}] ${log.message}",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = logColor
                        ),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(2.dp, Color.Black, RectangleShape)
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.body1,
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
                        backgroundColor = Color.Gray,
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.sync_dialog_cancel))
                }

                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(200, 0, 0),
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.sync_dialog_confirm), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
