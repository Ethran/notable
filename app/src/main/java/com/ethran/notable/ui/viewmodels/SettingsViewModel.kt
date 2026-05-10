package com.ethran.notable.ui.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.ConnectionTestResult
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class GestureRowModel(
    val titleRes: Int,
    val currentValue: AppSettings.GestureAction?,
    val defaultValue: AppSettings.GestureAction,
    val onUpdate: (AppSettings.GestureAction?) -> Unit
)

data class SyncSettingsUiState(
    val syncSettings: SyncSettings = SyncSettings(),
    val lastSavedSettings: SyncSettings = SyncSettings(),
    val isPasswordSaved: Boolean = false,
    val passwordVisible: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionStatus: AppResult<ConnectionTestResult, DomainError>? = null,
    val syncLogs: List<SyncLogger.LogEntry> = emptyList(),
    val syncState: SyncState = SyncState.Idle,
    val showForceUploadConfirm: Boolean = false,
    val showForceDownloadConfirm: Boolean = false
) {
    val credentialsDirty: Boolean
        get() = syncSettings.serverUrl != lastSavedSettings.serverUrl ||
                syncSettings.username != lastSavedSettings.username ||
                syncSettings.password.isNotEmpty()
}


@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val kvProxy: KvProxy,
    private val syncOrchestrator: SyncOrchestrator,
    private val syncProgressReporter: SyncProgressReporter,
    private val syncScheduler: SyncScheduler,
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

        // Load persisted sync settings from KvProxy.
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = kvProxy.getSyncSettings()
            val hasPassword = persisted.password.isNotEmpty()
            val uiSettings = persisted.copy(password = "")
            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(
                    syncSettings = uiSettings,
                    lastSavedSettings = uiSettings,
                    isPasswordSaved = hasPassword
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
            kvProxy.setAppSettings(newSettings)
        }
    }

    // ----------------- //
    // Sync Settings
    // ----------------- //

    /**
     * Universal update function for SyncSettings.
     * @param newSettings The updated settings object.
     * @param saveToDb If true, persists to KvProxy immediately. Set to false for text fields
     * (like username/password) if you want to wait for an explicit "Save" click.
     */
    fun updateSyncSettings(newSettings: SyncSettings, saveToDb: Boolean = true) {
        val oldSettings = syncUiState.syncSettings
        syncUiState = syncUiState.copy(syncSettings = newSettings)

        if (saveToDb) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    kvProxy.setSyncSettings(newSettings)

                    // Reconcile schedule only if relevant parameters changed
                    val scheduleChanged = oldSettings.syncEnabled != newSettings.syncEnabled ||
                            oldSettings.autoSync != newSettings.autoSync ||
                            oldSettings.syncInterval != newSettings.syncInterval ||
                            oldSettings.wifiOnly != newSettings.wifiOnly

                    if (scheduleChanged) {
                        syncScheduler.reconcilePeriodicSync( newSettings)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Failed to save: ${e.message}"))
                    }
                }
            }
        }
    }

    fun onSaveCredentials() {
        val currentSettings = syncUiState.syncSettings
        updateSyncSettings(currentSettings, saveToDb = true)

        syncUiState = syncUiState.copy(
            lastSavedSettings = currentSettings.copy(password = ""),
            isPasswordSaved = currentSettings.password.isNotEmpty() || syncUiState.isPasswordSaved
        )

        appScope.launch {
            snackDispatcher.showOrUpdateSnack(SnackConf(text = "Credentials saved", duration = 3000))
        }
    }

    fun onTogglePasswordVisibility() {
        syncUiState = syncUiState.copy(passwordVisible = !syncUiState.passwordVisible)
    }

    fun onTestConnection() {
        val settings = syncUiState.syncSettings
        if (settings.serverUrl.isBlank() || settings.username.isBlank()) return

        syncUiState = syncUiState.copy(testingConnection = true, connectionStatus = null)
        viewModelScope.launch(Dispatchers.IO) {
            val client = WebDAVClient(settings.serverUrl, settings.username, settings.password)
            val result = client.testConnection()

            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(testingConnection = false, connectionStatus = result)
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


    fun onClearSyncLogs() {
        SyncLogger.clear()
    }

    fun onManualSync() {
        runSyncWithSnack(
            textDuring = "Sync initialized...", successMessage = "Sync completed successfully"
        ) {
            val result = syncOrchestrator.syncAllNotebooks()
            if (result is AppResult.Success) {
                // Save unix timestamp (ms since epoch). UI layer will format it for display.
                updateSyncSettings(syncUiState.syncSettings.copy(lastSyncTime = System.currentTimeMillis()), saveToDb = true)
            }
            result
        }
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
