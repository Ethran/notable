package com.ethran.notable.ui.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.ConnectionTestResult
import com.ethran.notable.sync.CredentialManager
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.sync.SyncProgressReporter
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.WebDAVClient
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.isLatestVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class GestureRowModel(
    val titleRes: Int,
    val currentValue: AppSettings.GestureAction?,
    val defaultValue: AppSettings.GestureAction,
    val onUpdate: (AppSettings.GestureAction?) -> Unit
)

data class SyncSettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val savedUsername: String = "",
    val savedPassword: String = "",
    val isPasswordSaved: Boolean = false,
    val passwordVisible: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionStatus: AppResult<ConnectionTestResult, DomainError>? = null,
    val syncLogs: List<SyncLogger.LogEntry> = emptyList(),
    val syncState: SyncState = SyncState.Idle,
    val showForceUploadConfirm: Boolean = false,
    val showForceDownloadConfirm: Boolean = false,
    val syncSettings: SyncSettings = SyncSettings()
) {
    val credentialsChanged: Boolean
        get() = username != savedUsername || (password.isNotEmpty() && password != savedPassword)
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val kvProxy: KvProxy,
    private val credentialManager: CredentialManager,
    private val syncOrchestrator: SyncOrchestrator,
    private val syncProgressReporter: SyncProgressReporter,
    private val snackDispatcher: SnackDispatcher,
    private val appEventBus: AppEventBus,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    // We use the GlobalAppSettings object directly.
    val settings: AppSettings
        get() = GlobalAppSettings.current

    var isLatestVersion: Boolean by mutableStateOf(true)
        private set

    var syncUiState by mutableStateOf(SyncSettingsUiState())
        private set

    init {
        // Observe logs
        viewModelScope.launch {
            SyncLogger.logs.collect { logs ->
                syncUiState = syncUiState.copy(syncLogs = logs)
            }
        }

        // Observe sync engine state
        viewModelScope.launch {
            syncProgressReporter.state.collect { state ->
                syncUiState = syncUiState.copy(syncState = state)
            }
        }

        // Observe credential manager settings (single source of truth)
        viewModelScope.launch {
            credentialManager.settings.collect { settings ->
                syncUiState = syncUiState.copy(
                    syncSettings = settings,
                    serverUrl = settings.serverUrl,
                    username = settings.username,
                    savedUsername = settings.username
                )
            }
        }

        // Load initial password state (don't load actual password into memory)
        viewModelScope.launch(Dispatchers.IO) {
            val hasPassword = credentialManager.getPassword() != null
            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(
                    isPasswordSaved = hasPassword, password = "", savedPassword = ""
                )
            }
        }
    }

    /**
     * Checks if the app is the latest version.
     */
    fun checkUpdate(context: Context, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = isLatestVersion(context, appEventBus, force)
            withContext(Dispatchers.Main) {
                isLatestVersion = result
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        GlobalAppSettings.update(newSettings)
        viewModelScope.launch(Dispatchers.IO) {
            kvProxy.setKv(APP_SETTINGS_KEY, newSettings, AppSettings.serializer())
        }
    }

    // ----------------- //
    // Sync Settings
    // ----------------- //

    fun onServerUrlChanged(serverUrl: String) {
        viewModelScope.launch {
            credentialManager.updateSettings { it.copy(serverUrl = serverUrl) }
        }
    }

    fun onUsernameChanged(username: String) {
        syncUiState = syncUiState.copy(username = username)
    }

    fun onPasswordChanged(password: String) {
        syncUiState = syncUiState.copy(password = password)
    }

    fun onTogglePasswordVisibility() {
        syncUiState = syncUiState.copy(passwordVisible = !syncUiState.passwordVisible)
    }

    fun onSaveCredentials() {
        val username = syncUiState.username
        val password = syncUiState.password

        // If password is empty but saved, we only update username if it changed
        if (password.isEmpty() && syncUiState.isPasswordSaved) {
            if (username != syncUiState.savedUsername) {
                viewModelScope.launch(Dispatchers.IO) {
                    val currentPassword = credentialManager.getPassword() ?: ""
                    credentialManager.saveCredentials(username, currentPassword)
                    withContext(Dispatchers.Main) {
                        syncUiState = syncUiState.copy(savedUsername = username)
                        SyncLogger.i("Settings", "Username updated to: $username")
                        snackDispatcher.showOrUpdateSnack(
                            SnackConf(text = "Credentials updated", duration = 3000)
                        )
                    }
                }
            }
            return
        }

        if (username.isBlank() || password.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            credentialManager.saveCredentials(username, password)
            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(
                    savedUsername = username,
                    savedPassword = password,
                    isPasswordSaved = true,
                    password = "" // Clear after saving for security
                )

                SyncLogger.i("Settings", "Credentials saved for user: $username")
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Credentials saved", duration = 3000
                    )
                )
            }
        }
    }

    fun onTestConnection() {
        val serverUrl = syncUiState.serverUrl
        val username = syncUiState.username
        val password = syncUiState.password
        if (serverUrl.isBlank() || username.isBlank()) return

        syncUiState = syncUiState.copy(testingConnection = true, connectionStatus = null)
        viewModelScope.launch(Dispatchers.IO) {
            // If password field is empty, use the saved one
            val passwordToUse = password.ifEmpty {
                credentialManager.getPassword() ?: ""
            }

            val client = WebDAVClient(serverUrl, username, passwordToUse)
            val result = client.testConnection()

            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(
                    testingConnection = false, connectionStatus = result
                )
            }
        }
    }

    fun onSyncEnabledChanged(isChecked: Boolean) {
        viewModelScope.launch {
            credentialManager.updateSettings { it.copy(syncEnabled = isChecked) }
            updatePeriodicSyncSchedule()
        }
    }

    fun onAutoSyncChanged(isChecked: Boolean) {
        viewModelScope.launch {
            credentialManager.updateSettings { it.copy(autoSync = isChecked) }
            updatePeriodicSyncSchedule()
        }
    }

    fun onSyncIntervalChanged(minutes: Int) {
        viewModelScope.launch {
            credentialManager.updateSettings {
                it.copy(syncInterval = minutes.coerceAtLeast(15))
            }
            updatePeriodicSyncSchedule()
        }
    }

    fun onSyncOnNoteCloseChanged(isChecked: Boolean) {
        viewModelScope.launch {
            credentialManager.updateSettings { it.copy(syncOnNoteClose = isChecked) }
        }
    }

    fun onWifiOnlyChanged(isChecked: Boolean) {
        viewModelScope.launch {
            credentialManager.updateSettings { it.copy(wifiOnly = isChecked) }
            updatePeriodicSyncSchedule()
        }
    }

    fun onManualSync() {
        runSyncWithSnack(
            textDuring = "Sync initialized...", successMessage = "Sync completed successfully"
        ) {
            val result = syncOrchestrator.syncAllNotebooks()
            if (result is AppResult.Success) {
                val timestamp =
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                credentialManager.updateSettings { it.copy(lastSyncTime = timestamp) }
            }
            result
        }
    }

    fun onForceUploadRequested(show: Boolean) {
        syncUiState = syncUiState.copy(showForceUploadConfirm = show)
    }

    fun onForceDownloadRequested(show: Boolean) {
        syncUiState = syncUiState.copy(showForceDownloadConfirm = show)
    }

    fun onConfirmForceUpload() {
        syncUiState = syncUiState.copy(showForceUploadConfirm = false)
        runSyncWithSnack(
            textDuring = "Force upload started...", successMessage = "Force upload complete"
        ) { syncOrchestrator.forceUploadAll() }
    }

    fun onConfirmForceDownload() {
        syncUiState = syncUiState.copy(showForceDownloadConfirm = false)
        runSyncWithSnack(
            textDuring = "Force download started...", successMessage = "Force download complete"
        ) { syncOrchestrator.forceDownloadAll() }
    }

    private fun runSyncWithSnack(
        textDuring: String,
        successMessage: String,
        action: suspend () -> AppResult<Unit, DomainError>
    ) {
        appScope.launch {
            val snackId = java.util.UUID.randomUUID().toString()
            snackDispatcher.showOrUpdateSnack(
                SnackConf(id = snackId, text = textDuring, duration = null)
            )
            val message = try {
                when (val result = action()) {
                    is AppResult.Success -> successMessage
                    is AppResult.Error -> "Sync failed: ${result.error.userMessage}"
                }
            } catch (e: Exception) {
                "Sync failed: ${e.message ?: "Unknown"}"
            }
            snackDispatcher.showOrUpdateSnack(
                SnackConf(id = snackId, text = message, duration = 3000)
            )
        }
    }

    private fun updatePeriodicSyncSchedule() {
        val settings = syncUiState.syncSettings
        SyncScheduler.reconcilePeriodicSync(appContext, settings)
    }

    fun onClearSyncLogs() {
        SyncLogger.clear()
    }

    // ----------------- //
    // Gesture Settings
    // ----------------- //

    fun getGestureRows(): List<GestureRowModel> = listOf(
        GestureRowModel(
            R.string.gestures_double_tap_action,
            settings.doubleTapAction,
            AppSettings.defaultDoubleTapAction
        ) { a -> updateSettings(settings.copy(doubleTapAction = a)) },
        GestureRowModel(
            (R.string.gestures_two_finger_tap_action),
            settings.twoFingerTapAction,
            AppSettings.defaultTwoFingerTapAction,
        ) { a -> updateSettings(settings.copy(twoFingerTapAction = a)) },
        GestureRowModel(
            (R.string.gestures_swipe_left_action),
            settings.swipeLeftAction,
            AppSettings.defaultSwipeLeftAction
        ) { a -> updateSettings(settings.copy(swipeLeftAction = a)) },
        GestureRowModel(
            (R.string.gestures_swipe_right_action),
            settings.swipeRightAction,
            AppSettings.defaultSwipeRightAction
        ) { a -> updateSettings(settings.copy(swipeRightAction = a)) },
        GestureRowModel(
            (R.string.gestures_two_finger_swipe_left_action),
            settings.twoFingerSwipeLeftAction,
            AppSettings.defaultTwoFingerSwipeLeftAction
        ) { a -> updateSettings(settings.copy(twoFingerSwipeLeftAction = a)) },
        GestureRowModel(
            R.string.gestures_two_finger_swipe_right_action,
            settings.twoFingerSwipeRightAction,
            AppSettings.defaultTwoFingerSwipeRightAction
        ) { a -> updateSettings(settings.copy(twoFingerSwipeRightAction = a)) },
    )


    val availableGestures = listOf(
        null to "None", // null represents no action
        AppSettings.GestureAction.Undo to R.string.gesture_action_undo,
        AppSettings.GestureAction.Redo to R.string.gesture_action_redo,
        AppSettings.GestureAction.PreviousPage to R.string.gesture_action_previous_page,
        AppSettings.GestureAction.NextPage to R.string.gesture_action_next_page,
        AppSettings.GestureAction.ChangeTool to R.string.gesture_action_toggle_pen_eraser,
        AppSettings.GestureAction.ToggleZen to R.string.gesture_action_toggle_zen_mode,
        AppSettings.GestureAction.Select to R.string.gesture_action_select,
    )


}
