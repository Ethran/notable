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
import com.ethran.notable.sync.CredentialManager
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncResult
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.WebDAVClient
import com.ethran.notable.utils.isLatestVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val testingConnection: Boolean = false,
    val connectionStatus: SyncConnectionStatus? = null,
    val syncLogs: List<SyncLogger.LogEntry> = emptyList(),
    val syncState: SyncState = SyncState.Idle,
    val showForceUploadConfirm: Boolean = false,
    val showForceDownloadConfirm: Boolean = false,
    val syncSettings: SyncSettings = SyncSettings()
) {
    val credentialsChanged: Boolean
        get() = username != savedUsername || password != savedPassword
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

        // Load initial password
        viewModelScope.launch(Dispatchers.IO) {
            val password = credentialManager.getPassword()
            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(
                    password = password ?: "",
                    savedPassword = password ?: ""
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

    fun onSaveCredentials() {
        val username = syncUiState.username
        val password = syncUiState.password
        if (username.isBlank() || password.isBlank()) return

        credentialManager.saveCredentials(username, password)
        syncUiState = syncUiState.copy(
            savedUsername = username, savedPassword = password
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
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) return

        syncUiState = syncUiState.copy(testingConnection = true, connectionStatus = null)
        viewModelScope.launch(Dispatchers.IO) {
            val (connected, clockSkewMs) = WebDAVClient.testConnection(
                serverUrl, username, password
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
        val settings = credentialManager.settings.value
        if (isChecked && settings.autoSync) {
            SyncScheduler.enablePeriodicSync(
                appContext, settings.syncInterval.toLong(), settings.wifiOnly
            )
        } else {
            SyncScheduler.disablePeriodicSync(appContext)
        }
    }

    fun onAutoSyncChanged(isChecked: Boolean) {
        credentialManager.updateSettings { it.copy(autoSync = isChecked) }
        val settings = credentialManager.settings.value
        if (isChecked && settings.syncEnabled) {
            SyncScheduler.enablePeriodicSync(
                appContext, settings.syncInterval.toLong(), settings.wifiOnly
            )
        } else {
            SyncScheduler.disablePeriodicSync(appContext)
        }
    }

    fun onSyncOnNoteCloseChanged(isChecked: Boolean) {
        credentialManager.updateSettings { it.copy(syncOnNoteClose = isChecked) }
    }

    fun onWifiOnlyChanged(isChecked: Boolean) {
        credentialManager.updateSettings { it.copy(wifiOnly = isChecked) }
        val settings = credentialManager.settings.value
        if (settings.autoSync && settings.syncEnabled) {
            SyncScheduler.enablePeriodicSync(appContext, settings.syncInterval.toLong(), isChecked)
        }
    }

    fun onManualSync() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = syncOrchestrator.syncAllNotebooks()
            withContext(Dispatchers.Main) {
                if (result is SyncResult.Success) {
                    val timestamp =
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    credentialManager.updateSettings { it.copy(lastSyncTime = timestamp) }
                    _syncEffects.emit(SyncSettingsEffect.ShowHint("Sync completed successfully"))
                } else {
                    val error = (result as? SyncResult.Failure)?.error?.toString() ?: "Unknown"
                    _syncEffects.emit(SyncSettingsEffect.ShowHint("Sync failed: $error"))
                }
            }
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
        viewModelScope.launch(Dispatchers.IO) {
            val result = syncOrchestrator.forceUploadAll()
            val message =
                if (result is SyncResult.Success) "Force upload complete" else "Force upload failed"
            _syncEffects.emit(SyncSettingsEffect.ShowHint(message))
        }
    }

    fun onConfirmForceDownload() {
        syncUiState = syncUiState.copy(showForceDownloadConfirm = false)
        viewModelScope.launch(Dispatchers.IO) {
            val result = syncOrchestrator.forceDownloadAll()
            val message =
                if (result is SyncResult.Success) "Force download complete" else "Force download failed"
            _syncEffects.emit(SyncSettingsEffect.ShowHint(message))
        }
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