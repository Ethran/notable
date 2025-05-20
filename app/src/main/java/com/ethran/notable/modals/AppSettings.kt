package com.ethran.notable.modals

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.TabRowDefaults.Divider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.ethran.notable.BuildConfig
import com.ethran.notable.classes.showHint
import com.ethran.notable.components.SelectMenu
import com.ethran.notable.db.KvProxy
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.utils.isNext
import com.ethran.notable.utils.noRippleClickable
import kotlinx.serialization.Serializable
import kotlin.concurrent.thread


// Define the target page size (A4 in points: 595 x 842)
const val A4_WIDTH = 595
const val A4_HEIGHT = 842
const val BUTTON_SIZE = 37


object GlobalAppSettings {
    private val _current = mutableStateOf(AppSettings(version = 1))
    val current: AppSettings
        get() = _current.value

    fun update(settings: AppSettings) {
        _current.value = settings
    }
}


@Serializable
data class AppSettings(
    val version: Int,
    val defaultNativeTemplate: String = "blank",
    val quickNavPages: List<String> = listOf(),
    val debugMode: Boolean = false,
    val neoTools: Boolean = false,
    val toolbarPosition: Position = Position.Top,
    val smoothScroll: Boolean = false,

    val doubleTapAction: GestureAction? = defaultDoubleTapAction,
    val twoFingerTapAction: GestureAction? = defaultTwoFingerTapAction,
    val swipeLeftAction: GestureAction? = defaultSwipeLeftAction,
    val swipeRightAction: GestureAction? = defaultSwipeRightAction,
    val twoFingerSwipeLeftAction: GestureAction? = defaultTwoFingerSwipeLeftAction,
    val twoFingerSwipeRightAction: GestureAction? = defaultTwoFingerSwipeRightAction,
    val holdAction: GestureAction? = defaultHoldAction,

    ) {
    companion object {
        val defaultDoubleTapAction get() = GestureAction.Undo
        val defaultTwoFingerTapAction get() = GestureAction.ChangeTool
        val defaultSwipeLeftAction get() = GestureAction.NextPage
        val defaultSwipeRightAction get() = GestureAction.PreviousPage
        val defaultTwoFingerSwipeLeftAction get() = GestureAction.ToggleZen
        val defaultTwoFingerSwipeRightAction get() = GestureAction.ToggleZen
        val defaultHoldAction get() = GestureAction.Select
    }

    enum class GestureAction {
        Undo, Redo, PreviousPage, NextPage, ChangeTool, ToggleZen, Select
    }

    enum class Position {
        Top, Bottom, // Left,Right,
    }

}

@Composable
fun AppSettingsModal(onClose: () -> Unit) {
    val context = LocalContext.current
    val kv = KvProxy(context)


    val settings = GlobalAppSettings.current ?: return

    Dialog(
        onDismissRequest = { onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(40.dp)
                .background(Color.White)
                .fillMaxWidth()
                .border(2.dp, Color.Black, RectangleShape)
        ) {
            Column(Modifier.padding(20.dp, 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "App setting - v${BuildConfig.VERSION_NAME}${if (isNext) " [NEXT]" else ""}",
                        style = MaterialTheme.typography.h5,
                    )
                }
            }
            Box(
                Modifier
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(Color.Black)
            )

            Column(Modifier.padding(20.dp, 10.dp)) {
                GeneralSettings(kv, settings)
                EditGestures(kv, settings)
                GitHubSponsorButton()
                ShowUpdateButton(context)
            }
        }
    }
}

@Composable
fun GeneralSettings(kv: KvProxy, settings: AppSettings) {
    Row {
        Text(text = "Default Page Background Template")
        Spacer(Modifier.width(10.dp))
        SelectMenu(
            options = listOf(
                "blank" to "Blank page",
                "dotted" to "Dot grid",
                "lined" to "Lines",
                "squared" to "Small squares grid",
                "hexed" to "Hexagon grid",
            ),
            onChange = {
                kv.setAppSettings(settings.copy(defaultNativeTemplate = it))
            },
            value = settings.defaultNativeTemplate
        )
    }
    Spacer(Modifier.height(10.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Debug Mode (show changed area)")
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = settings.debugMode,
            onCheckedChange = { isChecked ->
                kv.setAppSettings(settings.copy(debugMode = isChecked))
            }
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Use Onyx NeoTools (may cause crashes)")
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = settings.neoTools,
            onCheckedChange = { isChecked ->
                kv.setAppSettings(settings.copy(neoTools = isChecked))
            }
        )
    }
    Spacer(Modifier.height(10.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Enable smooth scrolling")
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = settings.smoothScroll,
            onCheckedChange = { isChecked ->
                kv.setAppSettings(settings.copy(smoothScroll = isChecked))
            }
        )
    }
    Spacer(Modifier.height(10.dp))

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Toolbar Position (Work in progress)")
            Spacer(modifier = Modifier.width(10.dp))

            SelectMenu(
                options = listOf(
                    AppSettings.Position.Top to "Top",
                    AppSettings.Position.Bottom to "Bottom"
                ),
                value = settings.toolbarPosition,
                onChange = { newPosition ->
                    settings.let {
                        kv.setAppSettings(it.copy(toolbarPosition = newPosition))
                    }
                }
            )
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
                    kv.setAppSettings(settings.update(it))
                }
            },
        )
    }
}


@Composable
fun GitHubSponsorButton() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .padding(horizontal = 120.dp, vertical = 16.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(
                    color = Color(0xFF24292E),
                    shape = RoundedCornerShape(25.dp)
                )
                .clickable {
                    val urlIntent = Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/sponsors/ethran".toUri()
                    )
                    context.startActivity(urlIntent)
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = "Heart Icon",
                    tint = Color(0xFFEA4AAA),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sponsor",
                    color = Color.White,
                    style = MaterialTheme.typography.button.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                )
            }
        }
    }
}


@Composable
fun ShowUpdateButton(context: Context) {
    var isLatestVersion by remember { mutableStateOf(true) }
    LaunchedEffect(key1 = Unit, block = { thread { isLatestVersion = isLatestVersion(context) } })

    if (!isLatestVersion) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "It seems a new version of Notable is available on GitHub.",
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.h6,
            )

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    val urlIntent = Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/ethran/notable/releases".toUri()
                    )
                    context.startActivity(urlIntent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "See release in browser",
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    } else {
        Button(
            onClick = {
                thread {
                    isLatestVersion = isLatestVersion(context, true)
                    if (isLatestVersion) {
                        showHint(
                            "You are on the latest version.",
                            duration = 1000
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth() // Adjust the modifier as needed
        ) {
            Text(text = "Check for newer version")
        }
    }
}


@Composable
fun EditGestures(kv: KvProxy, settings: AppSettings?) {
    var gestureExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable { gestureExpanded = !gestureExpanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Gesture Settings",
                style = MaterialTheme.typography.h6,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (gestureExpanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (gestureExpanded) "Collapse" else "Expand"
            )
        }

        if (gestureExpanded) {
            Divider(
                color = Color.LightGray,
                thickness = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            GestureSelectorRow(
                title = "Double Tap Action",
                kv = kv,
                settings = settings,
                update = { copy(doubleTapAction = it) },
                default = AppSettings.defaultDoubleTapAction,
                override = { doubleTapAction }
            )

            GestureSelectorRow(
                title = "Two Finger Tap Action",
                kv = kv,
                settings = settings,
                update = { copy(twoFingerTapAction = it) },
                default = AppSettings.defaultTwoFingerTapAction,
                override = { twoFingerTapAction }
            )

            GestureSelectorRow(
                title = "Swipe Left Action",
                kv = kv,
                settings = settings,
                update = { copy(swipeLeftAction = it) },
                default = AppSettings.defaultSwipeLeftAction,
                override = { swipeLeftAction }
            )

            GestureSelectorRow(
                title = "Swipe Right Action",
                kv = kv,
                settings = settings,
                update = { copy(swipeRightAction = it) },
                default = AppSettings.defaultSwipeRightAction,
                override = { swipeRightAction }
            )

            GestureSelectorRow(
                title = "Two Finger Swipe Left Action",
                kv = kv,
                settings = settings,
                update = { copy(twoFingerSwipeLeftAction = it) },
                default = AppSettings.defaultTwoFingerSwipeLeftAction,
                override = { twoFingerSwipeLeftAction }
            )

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

