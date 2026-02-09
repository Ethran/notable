package com.ethran.notable.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy

@Composable
fun GesturesSettings(context: Context, kv: KvProxy, settings: AppSettings?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        val gestures = listOf(
            Triple(
                stringResource(R.string.gestures_double_tap_action),
                AppSettings.defaultDoubleTapAction,
                AppSettings::doubleTapAction
            ),
            Triple(
                stringResource(R.string.gestures_two_finger_tap_action),
                AppSettings.defaultTwoFingerTapAction,
                AppSettings::twoFingerTapAction
            ),
            Triple(
                stringResource(R.string.gestures_swipe_left_action),
                AppSettings.defaultSwipeLeftAction,
                AppSettings::swipeLeftAction
            ),
            Triple(
                stringResource(R.string.gestures_swipe_right_action),
                AppSettings.defaultSwipeRightAction,
                AppSettings::swipeRightAction
            ),
            Triple(
                stringResource(R.string.gestures_two_finger_swipe_left_action),
                AppSettings.defaultTwoFingerSwipeLeftAction,
                AppSettings::twoFingerSwipeLeftAction
            ),
            Triple(
                stringResource(R.string.gestures_two_finger_swipe_right_action),
                AppSettings.defaultTwoFingerSwipeRightAction,
                AppSettings::twoFingerSwipeRightAction
            ),
        )

        gestures.forEachIndexed { index, (title, default, override) ->
            GestureSelectorRow(
                title = title, kv = kv, settings = settings, update = { action ->
                    when (title) {
                        context.getString(R.string.gestures_double_tap_action) -> settings?.copy(
                            doubleTapAction = action
                        )

                        context.getString(R.string.gestures_two_finger_tap_action) -> settings?.copy(
                            twoFingerTapAction = action
                        )

                        context.getString(R.string.gestures_swipe_left_action) -> settings?.copy(
                            swipeLeftAction = action
                        )

                        context.getString(R.string.gestures_swipe_right_action) -> settings?.copy(
                            swipeRightAction = action
                        )

                        context.getString(R.string.gestures_two_finger_swipe_left_action) -> settings?.copy(
                            twoFingerSwipeLeftAction = action
                        )

                        context.getString(R.string.gestures_two_finger_swipe_right_action) -> settings?.copy(
                            twoFingerSwipeRightAction = action
                        )

                        else -> settings
                    } ?: settings
                }, default = default, override = override
            )
        }
        SettingToggleRow(
            label = stringResource(R.string.enable_quick_nav),
            value = GlobalAppSettings.current.enableQuickNav,
            onToggle = { isChecked ->
                kv.setAppSettings( GlobalAppSettings.current.copy(enableQuickNav = isChecked))
            })
    }
}


@Composable
fun GestureSelectorRow(
    title: String,
    kv: KvProxy,
    settings: AppSettings?,
    update: (AppSettings.GestureAction?) -> AppSettings?,
    default: AppSettings.GestureAction,
    override: (AppSettings) -> AppSettings.GestureAction?
) {
    SelectorRow(
        label = title, options = listOf(
            null to "None",
            AppSettings.GestureAction.Undo to stringResource(R.string.gesture_action_undo),
            AppSettings.GestureAction.Redo to stringResource(R.string.gesture_action_redo),
            AppSettings.GestureAction.PreviousPage to stringResource(R.string.gesture_action_previous_page),
            AppSettings.GestureAction.NextPage to stringResource(R.string.gesture_action_next_page),
            AppSettings.GestureAction.ChangeTool to stringResource(R.string.gesture_action_toggle_pen_eraser),
            AppSettings.GestureAction.ToggleZen to stringResource(R.string.gesture_action_toggle_zen_mode),
        ), value = if (settings != null) override(settings) else default, onValueChange = {
            if (settings != null) {
                val updated = update(it)
                if (updated != null) kv.setAppSettings(updated)
            }
        })
}
