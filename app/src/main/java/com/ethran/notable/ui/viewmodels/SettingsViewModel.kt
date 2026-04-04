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
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.CredentialManager
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.sync.SyncResult
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.WebDAVClient
import com.ethran.notable.ui.SnackState
import com.ethran.notable.utils.isLatestVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

sealed class SyncConnectionStatus {
    data object Success : SyncConnectionStatus()
    data object Failed : SyncConnectionStatus()
    data class ClockSkew(val seconds: Long) : SyncConnectionStatus()
}

data class SyncSettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val savedUsername: String = "",
    val savedPassword: String = "",
    val isPasswordSaved: Boolean = false,
    val passwordVisible: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionStatus: SyncConnectionStatus? = null,
    val syncLogs: List<SyncLogger.LogEntry> = emptyList(),
    val syncState: SyncState = SyncState.Idle,
    val showForceUploadConfirm: Boolean = false,
    val showForceDownloadConfirm: Boolean = false,
    val syncSettings: SyncSettings = SyncSettings()
) {
    val credentialsChanged: Boolean
        get() = username != savedUsername || (password.isNotEmpty() && password != savedPassword)
}

sealed class SyncSettingsEffect {
    data class ShowHint(val message: String) : SyncSettingsEffect()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val kvProxy: KvProxy,
    private val credentialManager: CredentialManager,
    private val syncOrchestrator: SyncOrchestrator,
    private val snackState: SnackState,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    // We use the GlobalAppSettings object directly.
    val settings: AppSettings
        get() = GlobalAppSettings.current

    var isLatestVersion: Boolean by mutableStateOf(true)
        private set

    var syncUiState by mutableStateOf(SyncSettingsUiState())
        private set

    private val _syncEffects = MutableSharedFlow<SyncSettingsEffect>()
    val syncEffects = _syncEffects.asSharedFlow()

    init {
        // Observe logs
        viewModelScope.launch {
            SyncLogger.logs.collect { logs ->
                syncUiState = syncUiState.copy(syncLogs = logs)
            }
        }

        // Observe sync engine state
        viewModelScope.launch {
            SyncOrchestrator.syncState.collect { state ->
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
                    isPasswordSaved = hasPassword,
                    password = "",
                    savedPassword = ""
                )
            }
        }
    }

    /**
     * Checks if the app is the latest version.
     */
    fun checkUpdate(context: Context, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = isLatestVersion(context, force)
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
        credentialManager.updateSettings { it.copy(serverUrl = serverUrl) }
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
                         _syncEffects.emit(SyncSettingsEffect.ShowHint("Credentials updated"))
                     }
                 }
             }
             return
        }

        if (username.isBlank() || password.isBlank()) return

        credentialManager.saveCredentials(username, password)
        syncUiState = syncUiState.copy(
            savedUsername = username, 
            savedPassword = password,
            isPasswordSaved = true,
            password = "" // Clear after saving for security
        )

        SyncLogger.i("Settings", "Credentials saved for user: $username")
        viewModelScope.launch {
            _syncEffects.emit(SyncSettingsEffect.ShowHint("Credentials saved"))
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
            val passwordToUse = if (password.isEmpty()) {
                credentialManager.getPassword() ?: ""
            } else {
                password
            }
            
            val (connected, clockSkewMs) = WebDAVClient.testConnection(
                serverUrl, username, passwordToUse
            )
            withContext(Dispatchers.Main) {
                val status = when {
                    !connected -> SyncConnectionStatus.Failed
                    clockSkewMs != null && kotlin.math.abs(clockSkewMs) > 30_000L -> SyncConnectionStatus.ClockSkew(
                        clockSkewMs / 1000
                    )

                    else -> SyncConnectionStatus.Success
                }
                syncUiState = syncUiState.copy(
                    testingConnection = false, connectionStatus = status
                )
            }
        }
    }

    fun onSyncEnabledChanged(isChecked: Boolean) {
        credentialManager.updateSettings { it.copy(syncEnabled = isChecked) }
        updatePeriodicSyncSchedule()
    }

    fun onAutoSyncChanged(isChecked: Boolean) {
        credentialManager.updateSettings { it.copy(autoSync = isChecked) }
        updatePeriodicSyncSchedule()
    }

    fun onSyncIntervalChanged(minutes: Int) {
        credentialManager.updateSettings {
            it.copy(syncInterval = minutes.coerceAtLeast(15))
        }
        updatePeriodicSyncSchedule()
    }

    fun onSyncOnNoteCloseChanged(isChecked: Boolean) {
        credentialManager.updateSettings { it.copy(syncOnNoteClose = isChecked) }
    }

    fun onWifiOnlyChanged(isChecked: Boolean) {
        credentialManager.updateSettings { it.copy(wifiOnly = isChecked) }
        updatePeriodicSyncSchedule()
    }

    fun onManualSync() {
        runSyncWithSnack(
            textDuring = "Sync initialized...",
            successMessage = "Sync completed successfully"
        ) {
            val result = syncOrchestrator.syncAllNotebooks()
            if (result is SyncResult.Success) {
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
            textDuring = "Force upload started...",
            successMessage = "Force upload complete"
        ) { syncOrchestrator.forceUploadAll() }
    }

    fun onConfirmForceDownload() {
        syncUiState = syncUiState.copy(showForceDownloadConfirm = false)
        runSyncWithSnack(
            textDuring = "Force download started...",
            successMessage = "Force download complete"
        ) { syncOrchestrator.forceDownloadAll() }
    }

    private fun runSyncWithSnack(
        textDuring: String,
        successMessage: String,
        action: suspend () -> SyncResult
    ) {
        appScope.launch {
            snackState.runWithSnack(textDuring = textDuring, resultDurationMs = 3000) {
                val result = action()
                val message =
                    if (result is SyncResult.Success) {
                        successMessage
                    } else {
                        val error = (result as? SyncResult.Failure)?.error?.toString() ?: "Unknown"
                        "Sync failed: $error"
                    }
                _syncEffects.emit(SyncSettingsEffect.ShowHint(message))
                message
            }
        }
    }

    private fun updatePeriodicSyncSchedule() {
        val settings = credentialManager.settings.value
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
