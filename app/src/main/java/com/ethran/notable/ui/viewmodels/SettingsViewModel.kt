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
import com.ethran.notable.utils.isLatestVersion
import dagger.hilt.android.lifecycle.HiltViewModel
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


@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val kvProxy: KvProxy,
) : ViewModel() {
    companion object {}

    // We use the GlobalAppSettings object directly.
    val settings: AppSettings
        get() = GlobalAppSettings.current

    var isLatestVersion: Boolean by mutableStateOf(true)
        private set

    /**
     * Checks if the app is the latest version.
     * Uses Dispatchers.IO for the network/disk call.
     */
    fun checkUpdate(context: Context, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = isLatestVersion(context, force)
            withContext(Dispatchers.Main) {
                isLatestVersion = result
            }
        }
    }

    /**
     * The ViewModel handles the side effects:
     * 1. Updating the global state for immediate UI feedback.
     * 2. Persisting to the database in a background scope.
     */
    fun updateSettings(newSettings: AppSettings) {
        // 1. Update the Global state (immediate recomposition)
        GlobalAppSettings.update(newSettings)

        // 2. Persist to DB in the background
        viewModelScope.launch(Dispatchers.IO) {
            kvProxy.setKv(APP_SETTINGS_KEY, newSettings, AppSettings.serializer())
        }
    }


    fun getGestureRows(): List<GestureRowModel> = listOf(
        GestureRowModel(
            R.string.gestures_double_tap_action,
            settings.doubleTapAction,
            AppSettings.defaultDoubleTapAction
        ) { a -> settings.copy(doubleTapAction = a) },
        GestureRowModel(
            (R.string.gestures_two_finger_tap_action),
            settings.twoFingerTapAction,
            AppSettings.defaultTwoFingerTapAction,

            ) { a -> settings.copy(twoFingerTapAction = a) },
        GestureRowModel(
            (R.string.gestures_swipe_left_action),
            settings.swipeLeftAction,
            AppSettings.defaultSwipeLeftAction
        ) { a -> settings.copy(swipeLeftAction = a) },
        GestureRowModel(
            (R.string.gestures_swipe_right_action),
            settings.swipeRightAction,
            AppSettings.defaultSwipeRightAction
        ) { a -> settings.copy(swipeRightAction = a) },
        GestureRowModel(
            (R.string.gestures_two_finger_swipe_left_action),
            settings.twoFingerSwipeLeftAction,
            AppSettings.defaultTwoFingerSwipeLeftAction
        ) { a -> settings.copy(twoFingerSwipeLeftAction = a) },
        GestureRowModel(
            R.string.gestures_two_finger_swipe_right_action,
            settings.twoFingerSwipeRightAction,
            AppSettings.defaultTwoFingerSwipeRightAction
        ) { a -> settings.copy(twoFingerSwipeRightAction = a) },
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