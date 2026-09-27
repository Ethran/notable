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
import com.ethran.notable.sync.CapabilityProbeService
import com.ethran.notable.sync.ConnectionTestResult
import com.ethran.notable.sync.ServerCapabilities
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncProgressReporter
import com.ethran.notable.sync.SyncRequest
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.WebDavClientFactoryPort
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
    val currentValue: AppSettings.GestureAction,
    val blockedByOnyxGestures: Boolean = false,
    val onUpdate: (AppSettings.GestureAction) -> Unit
)

data class SyncSettingsUiState(
    val syncSettings: SyncSettings = SyncSettings(),
    val lastSavedSettings: SyncSettings = SyncSettings(),
    val isPasswordSaved: Boolean = false,
    val passwordVisible: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionStatus: AppResult<ConnectionTestResult, DomainError>? = null,
    /** Capabilities measured by the last successful Test Connection; null until one runs. */
    val capabilities: ServerCapabilities? = null,
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
    private val syncProgressReporter: SyncProgressReporter,
    private val syncScheduler: SyncScheduler,
    private val webDavClientFactory: WebDavClientFactoryPort,
    private val capabilityProbeService: CapabilityProbeService,
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
                    // A blank password field means "(unchanged)". lastSyncTime belongs to the
                    // orchestrator; the copy held here dates from when the screen was opened.
                    kvProxy.updateSyncSettings(newPassword = newSettings.password.ifBlank { null }) {
                        newSettings.copy(lastSyncTime = it.lastSyncTime)
                    }

                    // Reconcile schedule only if relevant parameters changed
                    val scheduleChanged =
                        oldSettings.syncEnabled != newSettings.syncEnabled ||
                                oldSettings.autoSync != newSettings.autoSync ||
                                oldSettings.syncInterval != newSettings.syncInterval ||
                                oldSettings.wifiOnly != newSettings.wifiOnly

                    if (scheduleChanged) {
                        syncScheduler.reconcilePeriodicSync(newSettings)
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
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = "Credentials saved",
                    duration = 3000
                )
            )
        }
    }

    fun onTogglePasswordVisibility() {
        syncUiState = syncUiState.copy(passwordVisible = !syncUiState.passwordVisible)
    }

    fun onTestConnection() {
        val settings = syncUiState.syncSettings
        if (settings.serverUrl.isBlank() || settings.username.isBlank()) return

        syncUiState =
            syncUiState.copy(testingConnection = true, connectionStatus = null, capabilities = null)
        viewModelScope.launch(Dispatchers.IO) {
            val password =
                settings.password.ifBlank {
                    kvProxy.getSyncSettings().password
                }

            val client = webDavClientFactory.create(settings.serverUrl, settings.username, password)
            val result = client.testConnection()

            // Only probe once the server answered: the probe writes and reads a scratch tree, which
            // is pointless (and noisy) against a server we couldn't even reach or authenticate to.
            val capabilities = if (result is AppResult.Success) {
                capabilityProbeService
                    .probe(client, settings.serverUrl, settings.username, System.currentTimeMillis())
                    .also { kvProxy.setServerCapabilities(it) }
            } else {
                null
            }

            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(
                    testingConnection = false,
                    connectionStatus = result,
                    capabilities = capabilities
                )
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
        syncScheduler.triggerImmediateSync(SyncRequest.ForceUpload)
    }

    fun onConfirmForceDownload() {
        syncUiState = syncUiState.copy(showForceDownloadConfirm = false)
        syncScheduler.triggerImmediateSync(SyncRequest.ForceDownload)
    }

    /**
     * Cancel any running sync. All triggers now go through WorkManager (9c), so cancelling the sync
     * work covers manual, force, and background runs; the reporter is reset for immediate feedback,
     * and the terminal "Sync cancelled" snack comes from [SyncWorkUiBridge] like every other outcome.
     */
    fun onCancelSync() {
        syncScheduler.cancelRunningSync()
        syncProgressReporter.reset()
    }

    fun onClearSyncLogs() {
        SyncLogger.clear()
    }

    /**
     * Manual "Sync Now" goes through the same WorkManager funnel as every other trigger (9c), so it
     * shares constraints/retry and surfaces exactly one terminal snack via [SyncWorkUiBridge]. Live
     * progress still shows in the settings panel via [SyncProgressReporter]; `lastSyncTime` is
     * persisted by the orchestrator on success (no longer written here — P27).
     */
    fun onManualSync() {
        syncScheduler.triggerImmediateSync(SyncRequest.SyncAll)
    }

    // ----------------- //
    // Gesture Settings
    // ----------------- //

    fun getGestureRows(): List<GestureRowModel> = listOf(
        GestureRowModel(
            R.string.gestures_double_tap_action,
            settings.doubleTapAction,
        ) { a -> updateSettings(settings.copy(doubleTapAction = a)) },
        GestureRowModel(
            (R.string.gestures_two_finger_tap_action),
            settings.twoFingerTapAction,
        ) { a -> updateSettings(settings.copy(twoFingerTapAction = a)) },
        GestureRowModel(
            (R.string.gestures_three_finger_tap_action),
            settings.threeFingerTapAction,
            true,
        ) { a -> updateSettings(settings.copy(threeFingerTapAction = a)) },
        GestureRowModel(
            (R.string.gestures_swipe_left_action),
            settings.swipeLeftAction,
        ) { a -> updateSettings(settings.copy(swipeLeftAction = a)) },
        GestureRowModel(
            (R.string.gestures_swipe_right_action),
            settings.swipeRightAction,
        ) { a -> updateSettings(settings.copy(swipeRightAction = a)) },
        GestureRowModel(
            (R.string.gestures_three_finger_swipe_left_action),
            settings.threeFingerSwipeLeftAction,
            true,
        ) { a -> updateSettings(settings.copy(threeFingerSwipeLeftAction = a)) },
        GestureRowModel(
            R.string.gestures_three_finger_swipe_right_action,
            settings.threeFingerSwipeRightAction,
            true,
        ) { a -> updateSettings(settings.copy(threeFingerSwipeRightAction = a)) },
    )


    val availableGestures = listOf(
        AppSettings.GestureAction.None to "None",
        AppSettings.GestureAction.Undo to R.string.gesture_action_undo,
        AppSettings.GestureAction.Redo to R.string.gesture_action_redo,
        AppSettings.GestureAction.PreviousPage to R.string.gesture_action_previous_page,
        AppSettings.GestureAction.NextPage to R.string.gesture_action_next_page,
        AppSettings.GestureAction.ChangeTool to R.string.gesture_action_toggle_pen_eraser,
        AppSettings.GestureAction.ToggleZen to R.string.gesture_action_toggle_zen_mode,
        AppSettings.GestureAction.Select to R.string.gesture_action_select,
    )


}
