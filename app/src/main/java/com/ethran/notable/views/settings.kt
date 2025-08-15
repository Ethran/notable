package com.ethran.notable.views

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults
import androidx.compose.material.TabRowDefaults.Divider
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.BuildConfig
import com.ethran.notable.classes.showHint
import com.ethran.notable.components.SelectMenu
import com.ethran.notable.db.KvProxy
import com.ethran.notable.modals.AppSettings
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.utils.isNext
import kotlin.concurrent.thread

@Composable
fun SettingsView(navController: NavController) {
    val context = LocalContext.current
    val kv = KvProxy(context)
    val settings = GlobalAppSettings.current

    // Tab titles
    val tabs = listOf("General", "Gestures", "Debug")
    var selectedTab by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            TitleBar(navController)
            // Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                backgroundColor = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.onSurface,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.2f)
                    )
                },
                divider = {
                    Divider(
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f), thickness = 1.dp
                    )
                }) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedTab == index) MaterialTheme.colors.onSurface
                                else MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                        },
                        selectedContentColor = MaterialTheme.colors.onSurface,
                        unselectedContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // The scrollable tab content area, takes up all available space
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (selectedTab) {
                        0 -> GeneralSettings(kv, settings)
                        1 -> EditGestures(kv, settings)
                        2 -> DebugSettings(kv, settings)
                    }
                }
            }

            // Additional actions, only on main settings tab
            if (selectedTab == 0) {
                Column(
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    GitHubSponsorButton(
                        Modifier
                            .padding(horizontal = 120.dp, vertical = 16.dp)
                            .height(48.dp)
                            .fillMaxWidth()
                    )
                    ShowUpdateButton(
                        context = context,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 30.dp, vertical = 8.dp)
                            .height(48.dp)
                    )
                }
            }
        }
    }
}


@Composable
fun GeneralSettings(kv: KvProxy, settings: AppSettings) {
    Column {
        SelectorRow(
            label = "Default Page Background Template", options = listOf(
                "blank" to "Blank page",
                "dotted" to "Dot grid",
                "lined" to "Lines",
                "squared" to "Small squares grid",
                "hexed" to "Hexagon grid",
            ), value = settings.defaultNativeTemplate, onValueChange = {
                kv.setAppSettings(settings.copy(defaultNativeTemplate = it))
            })
        SelectorRow(
            label = "Toolbar Position (Work in progress)", options = listOf(
                AppSettings.Position.Top to "Top", AppSettings.Position.Bottom to "Bottom"
            ), value = settings.toolbarPosition, onValueChange = { newPosition ->
                settings.let {
                    kv.setAppSettings(it.copy(toolbarPosition = newPosition))
                }
            })

        SettingToggleRow(
            label = "Use Onyx NeoTools (may cause crashes)",
            value = settings.neoTools,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(neoTools = isChecked))
            })

        SettingToggleRow(
            label = "Enable scribble-to-erase (scribble out your mistakes to erase them)",
            value = settings.scribbleToEraseEnabled,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(scribbleToEraseEnabled = isChecked))
            })

        SettingToggleRow(
            label = "Enable smooth scrolling",
            value = settings.smoothScroll,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(smoothScroll = isChecked))
            })

        SettingToggleRow(
            label = "Continuous Zoom (Work in progress)",
            value = settings.continuousZoom,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(continuousZoom = isChecked))
            })
        SettingToggleRow(
            label = "Monochrome mode (Work in progress)",
            value = settings.monochromeMode,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(monochromeMode = isChecked))
            })

        SettingToggleRow(
            label = "Paginate PDF", value = settings.paginatePdf, onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(paginatePdf = isChecked))
            })

        SettingToggleRow(
            label = "Visualize PDF Pagination",
            value = settings.visualizePdfPagination,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(visualizePdfPagination = isChecked))
            })
    }
}

@Composable
fun SettingToggleRow(
    label: String, value: Boolean, onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 4.dp, end = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f), // Take all available space
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface,
            maxLines = 2 // allow wrapping for long labels
        )
        Switch(
            checked = value, onCheckedChange = onToggle, colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colors.onSurface,
                uncheckedThumbColor = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                checkedTrackColor = MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                uncheckedTrackColor = MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
            ), modifier = Modifier.padding(start = 8.dp)
        )
    }
    SettingsDivider()
}

@Composable
fun TitleBar(navController: NavController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = { navController.popBackStack() }, modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colors.onBackground
            )
        }

        Text(
            text = "Settings",
            style = MaterialTheme.typography.h5,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )

        @Suppress("KotlinConstantConditions") Text(
            text = "v${BuildConfig.VERSION_NAME}${if (isNext) " [NEXT]" else ""}",
            style = MaterialTheme.typography.subtitle1,
        )
    }
}


@Composable
fun <T> SelectorRow(
    label: String,
    options: List<Pair<T, String>>,
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelMaxLines: Int = 2
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface,
            maxLines = labelMaxLines
        )
        SelectMenu(
            options = options,
            value = value,
            onChange = onValueChange,
        )
    }
    SettingsDivider()
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
            AppSettings.GestureAction.Undo to "Undo",
            AppSettings.GestureAction.Redo to "Redo",
            AppSettings.GestureAction.PreviousPage to "Previous Page",
            AppSettings.GestureAction.NextPage to "Next Page",
            AppSettings.GestureAction.ChangeTool to "Toggle Pen / Eraser",
            AppSettings.GestureAction.ToggleZen to "Toggle Zen Mode",
        ), value = if (settings != null) override(settings) else default, onValueChange = {
            if (settings != null) {
                val updated = update(it)
                if (updated != null) kv.setAppSettings(updated)
            }
        })
}


@Composable
fun GitHubSponsorButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(
                    color = Color(0xFF24292E), shape = RoundedCornerShape(25.dp)
                )
                .clickable {
                    val urlIntent = Intent(
                        Intent.ACTION_VIEW, "https://github.com/sponsors/ethran".toUri()
                    )
                    context.startActivity(urlIntent)
                }, contentAlignment = Alignment.Center
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
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    ),
                )
            }
        }
    }
}


@Composable
fun ShowUpdateButton(context: Context, modifier: Modifier = Modifier) {
    var isLatestVersion by remember { mutableStateOf(true) }
    LaunchedEffect(key1 = Unit, block = { thread { isLatestVersion = isLatestVersion(context) } })

    if (!isLatestVersion) {
        Column(modifier = modifier) {
            Text(
                text = "It seems a new version of Notable is available on GitHub.",
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.h6,
            )

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    val urlIntent = Intent(
                        Intent.ACTION_VIEW, "https://github.com/ethran/notable/releases".toUri()
                    )
                    context.startActivity(urlIntent)
                }, modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upgrade, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "See release in browser")
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
                            "You are on the latest version.", duration = 1000
                        )
                    }
                }
            }, modifier = modifier // Adjust the modifier as needed
        ) {
            Icon(Icons.Default.Update, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Check for newer version")
        }
    }
}


@Composable
fun EditGestures(kv: KvProxy, settings: AppSettings?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        val gestures = listOf(
            Triple("Double Tap Action", AppSettings.defaultDoubleTapAction, AppSettings::doubleTapAction),
            Triple("Two Finger Tap Action", AppSettings.defaultTwoFingerTapAction, AppSettings::twoFingerTapAction),
            Triple("Swipe Left Action", AppSettings.defaultSwipeLeftAction, AppSettings::swipeLeftAction),
            Triple("Swipe Right Action", AppSettings.defaultSwipeRightAction, AppSettings::swipeRightAction),
            Triple("Two Finger Swipe Left Action", AppSettings.defaultTwoFingerSwipeLeftAction, AppSettings::twoFingerSwipeLeftAction),
            Triple("Two Finger Swipe Right Action", AppSettings.defaultTwoFingerSwipeRightAction, AppSettings::twoFingerSwipeRightAction),
        )

        gestures.forEachIndexed { index, (title, default, override) ->
            GestureSelectorRow(
                title = title, kv = kv, settings = settings, update = { action ->
                    when (title) {
                        "Double Tap Action" -> settings?.copy(doubleTapAction = action)
                        "Two Finger Tap Action" -> settings?.copy(twoFingerTapAction = action)
                        "Swipe Left Action" -> settings?.copy(swipeLeftAction = action)
                        "Swipe Right Action" -> settings?.copy(swipeRightAction = action)
                        "Two Finger Swipe Left Action" -> settings?.copy(twoFingerSwipeLeftAction = action)
                        "Two Finger Swipe Right Action" -> settings?.copy(twoFingerSwipeRightAction = action)
                        else -> settings
                    } ?: settings
                }, default = default, override = override
            )
        }
    }
}


@Composable
fun DebugSettings(kv: KvProxy, settings: AppSettings) {
    Column {
        SettingToggleRow(
            label = "Show welcome screen", value = settings.showWelcome, onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(showWelcome = isChecked))
            })
        SettingToggleRow(
            label = "Debug Mode (show changed area)",
            value = settings.debugMode,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(debugMode = isChecked))
            })
        SettingToggleRow(
            label = "Use simple rendering for scroll and zoom -- uses more resources.",
            value = settings.simpleRendering,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(simpleRendering = isChecked))
            })
    }
}

@Composable
fun SettingsDivider() {
    Divider(
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
        thickness = 1.dp,
        modifier = Modifier.padding(top = 0.dp, bottom = 4.dp)
    )
}