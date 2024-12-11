package com.olup.notable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olup.notable.components.SelectMenu
import com.olup.notable.db.KvProxy
import kotlinx.serialization.Serializable
import kotlin.concurrent.thread

@Serializable
data class AppSettings(
    val version: Int,
    val defaultNativeTemplate: String = "blank",
    val quickNavPages: List<String> = listOf(),

    val doubleTapAction: GestureAction? = defaultDoubleTapAction,
    val twoFingerTapAction: GestureAction? = defaultTwoFingerTapAction,
    val swipeLeftAction: GestureAction? = defaultSwipeLeftAction,
    val swipeRightAction: GestureAction? = defaultSwipeRightAction,
    val twoFingerSwipeLeftAction: GestureAction? = defaultTwoFingerSwipeLeftAction,
    val twoFingerSwipeRightAction: GestureAction? = defaultTwoFingerSwipeRightAction,
) {
    companion object {
        val defaultDoubleTapAction get() = GestureAction.ToggleZen
        val defaultTwoFingerTapAction get() = GestureAction.ChangeTool
        val defaultSwipeLeftAction get() = GestureAction.NextPage
        val defaultSwipeRightAction get() = GestureAction.PreviousPage
        val defaultTwoFingerSwipeLeftAction get() = GestureAction.Redo
        val defaultTwoFingerSwipeRightAction get() = GestureAction.Undo
    }

    enum class GestureAction {
        Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen,
    }
}

@Composable
fun AppSettingsModal(onClose: () -> Unit) {
    val context = LocalContext.current
    val kv = KvProxy(context)

    val settings by
    kv.observeKv("APP_SETTINGS", AppSettings.serializer(), AppSettings(version = 1))
        .observeAsState()

    if (settings == null) return

    Dialog(
        onDismissRequest = { onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier =
            Modifier
                .padding(40.dp)
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Column(Modifier.padding(20.dp, 10.dp)) {
                Text(text = "App setting - v${BuildConfig.VERSION_NAME}")
            }
            Box(
                Modifier
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black))

            Column(Modifier.padding(20.dp, 10.dp)) {
                Row {
                    Text(text = "Default Page Background Template")
                    Spacer(Modifier.width(10.dp))
                    SelectMenu(
                        options =
                        listOf(
                            "blank" to "Blank page",
                            "dotted" to "Dot grid",
                            "lined" to "Lines",
                            "squared" to "Small squares grid"
                        ),
                        onChange = {
                            kv.setKv(
                                "APP_SETTINGS",
                                settings!!.copy(defaultNativeTemplate = it),
                                AppSettings.serializer()
                            )
                        },
                        value = settings?.defaultNativeTemplate ?: "blank"
                    )
                }
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Double Tap Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(doubleTapAction = it) },
                    default = AppSettings.defaultDoubleTapAction,
                    override = { doubleTapAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Two Finger Tap Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(twoFingerTapAction = it) },
                    default = AppSettings.defaultTwoFingerTapAction,
                    override = { twoFingerTapAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Swipe Left Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(swipeLeftAction = it) },
                    default = AppSettings.defaultSwipeLeftAction,
                    override = { swipeLeftAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Swipe Right Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(swipeRightAction = it) },
                    default = AppSettings.defaultSwipeRightAction,
                    override = { swipeRightAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Two Finger Swipe Left Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(twoFingerSwipeLeftAction = it) },
                    default = AppSettings.defaultTwoFingerSwipeLeftAction,
                    override = { twoFingerSwipeLeftAction }
                )
                Spacer(Modifier.height(10.dp))

                GestureSelectorRow(
                    title = "Two Finger Swipe Right Action",
                    kv = kv,
                    settings = settings,
                    update = { copy(twoFingerSwipeRightAction = it) },
                    default = AppSettings.defaultTwoFingerSwipeRightAction,
                    override = { twoFingerSwipeRightAction }
                )
            }
        }
    }
}

@Composable
fun GestureSelectorRow(
    title: String,
    kv: KvProxy,
    settings: AppSettings?,
    update: AppSettings.(AppSettings.GestureAction?) -> AppSettings,
    default: AppSettings.GestureAction,
    override: AppSettings.() -> AppSettings.GestureAction?,
) {
    Row {
        Text(text = title)
        Spacer(Modifier.width(10.dp))
        SelectMenu(
            options = listOf(
                null to "None",
                AppSettings.GestureAction.Undo to "Undo",
                AppSettings.GestureAction.Redo to "Redo",
                AppSettings.GestureAction.PreviousPage to "Previous Page",
                AppSettings.GestureAction.NextPage to "Next Page",
                AppSettings.GestureAction.ChangeTool to "Toggle Pen / Eraser",
                AppSettings.GestureAction.ToggleZen to "Toggle Zen Mode",
            ),
            value = if (settings != null) settings.override() else default,
            onChange = {
                if (settings != null) {
                    kv.setKv(
                        "APP_SETTINGS",
                        settings.update(it),
                        AppSettings.serializer(),
                    )
                }
            },
        )
    }
}
