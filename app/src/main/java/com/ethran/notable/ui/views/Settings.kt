package com.ethran.notable.ui.views

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.BuildConfig
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.editor.ui.SelectMenu
import com.ethran.notable.sync.CredentialManager
import com.ethran.notable.sync.SyncEngine
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncResult
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.WebDAVClient
import com.ethran.notable.ui.components.OnOffSwitch
import com.ethran.notable.ui.showHint
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.utils.isNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

@Composable
fun SettingsView(navController: NavController) {
    val context = LocalContext.current
    val kv = KvProxy(context)
    val settings = GlobalAppSettings.current

    // Tab titles
    val tabs = listOf(
        context.getString(R.string.settings_tab_general_name),
        context.getString(R.string.settings_tab_gestures_name),
        "Sync",  // TODO: Add to strings.xml
        context.getString(R.string.settings_tab_debug_name)
    )
    var selectedTab by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            TitleBar(context, navController)
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
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab) {
                    0 -> GeneralSettings(kv, settings)
                    1 -> EditGestures(context, kv, settings)
                    2 -> SyncSettings(kv, settings, context)
                    3 -> DebugSettings(kv, settings, navController)
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
            label = stringResource(R.string.default_page_background_template), options = listOf(
                "blank" to stringResource(R.string.blank_page),
                "dotted" to stringResource(R.string.dot_grid),
                "lined" to stringResource(R.string.lines),
                "squared" to stringResource(R.string.small_squares_grid),
                "hexed" to stringResource(R.string.hexagon_grid),
            ), value = settings.defaultNativeTemplate, onValueChange = {
                kv.setAppSettings(settings.copy(defaultNativeTemplate = it))
            })
        SelectorRow(
            label = "Toolbar Position", options = listOf(
                AppSettings.Position.Top to stringResource(R.string.toolbar_position_top),
                AppSettings.Position.Bottom to stringResource(
                    R.string.toolbar_position_bottom
                )
            ), value = settings.toolbarPosition, onValueChange = { newPosition ->
                settings.let {
                    kv.setAppSettings(it.copy(toolbarPosition = newPosition))
                }
            })

        SettingToggleRow(
            label = stringResource(R.string.use_onyx_neotools_may_cause_crashes),
            value = settings.neoTools,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(neoTools = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.enable_scribble_to_erase),
            value = settings.scribbleToEraseEnabled,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(scribbleToEraseEnabled = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.enable_smooth_scrolling),
            value = settings.smoothScroll,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(smoothScroll = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.continuous_zoom),
            value = settings.continuousZoom,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(continuousZoom = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.continuous_stroke_slider),
            value = settings.continuousStrokeSlider,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(continuousStrokeSlider = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.monochrome_mode) + " " + stringResource(R.string.work_in_progress),
            value = settings.monochromeMode,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(monochromeMode = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.paginate_pdf),
            value = settings.paginatePdf,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(paginatePdf = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.preview_pdf_pagination),
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
        OnOffSwitch(
            checked = value,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 12.dp),
        )
    }
    SettingsDivider()
}


@Composable
fun TitleBar(context: Context, navController: NavController) {
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
            text = context.getString(R.string.settings_title),
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
                    text = stringResource(R.string.sponsor_button_text),
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
                text = stringResource(R.string.app_new_version),
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
                Text(text = stringResource(R.string.app_see_release))
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
                            context.getString(R.string.app_latest_version), duration = 1000
                        )
                    }
                }
            }, modifier = modifier // Adjust the modifier as needed
        ) {
            Icon(Icons.Default.Update, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.app_check_updates))
        }
    }
}


@Composable
fun EditGestures(context: Context, kv: KvProxy, settings: AppSettings?) {
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
    }
}


@Composable
fun DebugSettings(kv: KvProxy, settings: AppSettings, navController: NavController) {
    Column {
        SettingToggleRow(
            label = "Show welcome screen", value = settings.showWelcome, onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(showWelcome = isChecked))
            })
        SettingToggleRow(
            label = "Show System Information", value = false, onToggle = {
                navController.navigate("SystemInformationView")
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
        SettingToggleRow(
            label = "Use openGL rendering for eraser.",
            value = settings.openGLRendering,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(openGLRendering = isChecked))
            })
        SettingToggleRow(
            label = "Use MuPdf as a renderer for pdfs.",
            value = settings.muPdfRendering,
            onToggle = { isChecked ->
                kv.setAppSettings(settings.copy(muPdfRendering = isChecked))
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

@Composable
fun SyncSettings(kv: KvProxy, settings: AppSettings, context: Context) {
    val syncSettings = settings.syncSettings
    val credentialManager = remember { CredentialManager(context) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(syncSettings.serverUrl) }
    var username by remember { mutableStateOf(syncSettings.username) }
    var password by remember { mutableStateOf("") }
    var testingConnection by remember { mutableStateOf(false) }
    var syncInProgress by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }

    // Observe sync logs
    val syncLogs by SyncLogger.logs.collectAsState()

    // Load password from CredentialManager on first composition
    LaunchedEffect(Unit) {
        credentialManager.getCredentials()?.let { (user, pass) ->
            username = user
            password = pass
        }
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = "WebDAV Synchronization",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Enable/Disable Sync Toggle
        SettingToggleRow(
            label = "Enable WebDAV Sync",
            value = syncSettings.syncEnabled,
            onToggle = { isChecked ->
                kv.setAppSettings(
                    settings.copy(
                        syncSettings = syncSettings.copy(syncEnabled = isChecked)
                    )
                )
                // Enable/disable WorkManager sync
                if (isChecked && syncSettings.autoSync) {
                    SyncScheduler.enablePeriodicSync(context, syncSettings.syncInterval.toLong())
                } else {
                    SyncScheduler.disablePeriodicSync(context)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Server URL Field
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = "Server URL",
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            BasicTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    kv.setAppSettings(
                        settings.copy(
                            syncSettings = syncSettings.copy(serverUrl = it)
                        )
                    )
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(230, 230, 230, 255))
                    .padding(12.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (serverUrl.isEmpty()) {
                            Text(
                                "https://nextcloud.example.com/remote.php/dav/files/username/",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Username Field
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = "Username",
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            BasicTextField(
                value = username,
                onValueChange = {
                    username = it
                    credentialManager.saveCredentials(it, password)
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(230, 230, 230, 255))
                    .padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Password Field
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = "Password",
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            BasicTextField(
                value = password,
                onValueChange = {
                    password = it
                    credentialManager.saveCredentials(username, it)
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface
                ),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(230, 230, 230, 255))
                    .padding(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Test Connection Button
        Button(
            onClick = {
                testingConnection = true
                connectionStatus = null
                scope.launch(Dispatchers.IO) {  // Use IO dispatcher for network calls
                    io.shipbook.shipbooksdk.Log.i("SyncSettings", "Testing connection with URL: $serverUrl, User: $username")
                    val result = WebDAVClient.testConnection(serverUrl, username, password)
                    withContext(Dispatchers.Main) {  // Switch back to main for UI updates
                        testingConnection = false
                        connectionStatus = if (result) "✓ Connected successfully" else "✗ Connection failed"
                        io.shipbook.shipbooksdk.Log.i("SyncSettings", "Test result: $result")
                    }
                }
            },
            enabled = !testingConnection && serverUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(80, 80, 80),
                contentColor = Color.White,
                disabledBackgroundColor = Color(200, 200, 200),
                disabledContentColor = Color.Gray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(48.dp)
        ) {
            if (testingConnection) {
                Text("Testing connection...")
            } else {
                Text("Test Connection", fontWeight = FontWeight.Bold)
            }
        }

        // Connection Status
        connectionStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.body2,
                color = if (status.startsWith("✓")) Color(0, 150, 0) else Color(200, 0, 0),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Auto-sync Toggle
        SettingToggleRow(
            label = "Automatic sync every ${syncSettings.syncInterval} minutes",
            value = syncSettings.autoSync,
            onToggle = { isChecked ->
                kv.setAppSettings(
                    settings.copy(
                        syncSettings = syncSettings.copy(autoSync = isChecked)
                    )
                )
                // Enable/disable periodic sync
                if (isChecked && syncSettings.syncEnabled) {
                    SyncScheduler.enablePeriodicSync(context, syncSettings.syncInterval.toLong())
                } else {
                    SyncScheduler.disablePeriodicSync(context)
                }
            }
        )

        // Sync on Note Close Toggle
        SettingToggleRow(
            label = "Sync when closing notes",
            value = syncSettings.syncOnNoteClose,
            onToggle = { isChecked ->
                kv.setAppSettings(
                    settings.copy(
                        syncSettings = syncSettings.copy(syncOnNoteClose = isChecked)
                    )
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Manual Sync Button
        Button(
            onClick = {
                syncInProgress = true
                scope.launch(Dispatchers.IO) {
                    val result = SyncEngine(context).syncAllNotebooks()

                    withContext(Dispatchers.Main) {
                        syncInProgress = false

                        if (result is SyncResult.Success) {
                            // Update last sync time
                            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                            kv.setAppSettings(
                                settings.copy(
                                    syncSettings = syncSettings.copy(lastSyncTime = timestamp)
                                )
                            )
                            showHint("Sync completed successfully", scope)
                        } else {
                            showHint("Sync failed: ${(result as? SyncResult.Failure)?.error}", scope)
                        }
                    }
                }
            },
            enabled = !syncInProgress && syncSettings.syncEnabled && serverUrl.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(0, 120, 200),
                contentColor = Color.White,
                disabledBackgroundColor = Color(200, 200, 200),
                disabledContentColor = Color.Gray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(56.dp)
        ) {
            if (syncInProgress) {
                Text("Syncing...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            } else {
                Text("Sync Now", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // Last Sync Time
        syncSettings.lastSyncTime?.let { timestamp ->
            Text(
                text = "Last synced: $timestamp",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // CAUTION: Replacement Operations
        Text(
            text = "CAUTION: Replacement Operations",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            color = Color(200, 0, 0),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Use these only when setting up a new device or resetting sync. These operations will delete data!",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp, end = 4.dp)
        )

        // Force Upload Button
        var showForceUploadConfirm by remember { mutableStateOf(false) }
        Button(
            onClick = { showForceUploadConfirm = true },
            enabled = syncSettings.syncEnabled && serverUrl.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(200, 100, 0),
                contentColor = Color.White,
                disabledBackgroundColor = Color(200, 200, 200),
                disabledContentColor = Color.Gray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(48.dp)
        ) {
            Text("⚠ Replace Server with Local Data", fontWeight = FontWeight.Bold)
        }

        if (showForceUploadConfirm) {
            ConfirmationDialog(
                title = "Replace Server Data?",
                message = "This will DELETE all data on the server and replace it with local data from this device. This cannot be undone!\n\nAre you sure?",
                onConfirm = {
                    showForceUploadConfirm = false
                    syncInProgress = true
                    scope.launch(Dispatchers.IO) {
                        // TODO: Implement force upload
                        val result = SyncEngine(context).forceUploadAll()
                        withContext(Dispatchers.Main) {
                            syncInProgress = false
                            showHint(if (result is SyncResult.Success) "Server replaced with local data" else "Force upload failed", scope)
                        }
                    }
                },
                onDismiss = { showForceUploadConfirm = false }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Force Download Button
        var showForceDownloadConfirm by remember { mutableStateOf(false) }
        Button(
            onClick = { showForceDownloadConfirm = true },
            enabled = syncSettings.syncEnabled && serverUrl.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color(200, 0, 0),
                contentColor = Color.White,
                disabledBackgroundColor = Color(200, 200, 200),
                disabledContentColor = Color.Gray
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .height(48.dp)
        ) {
            Text("⚠ Replace Local with Server Data", fontWeight = FontWeight.Bold)
        }

        if (showForceDownloadConfirm) {
            ConfirmationDialog(
                title = "Replace Local Data?",
                message = "This will DELETE all local notebooks and replace them with data from the server. This cannot be undone!\n\nAre you sure?",
                onConfirm = {
                    showForceDownloadConfirm = false
                    syncInProgress = true
                    scope.launch(Dispatchers.IO) {
                        // TODO: Implement force download
                        val result = SyncEngine(context).forceDownloadAll()
                        withContext(Dispatchers.Main) {
                            syncInProgress = false
                            showHint(if (result is SyncResult.Success) "Local data replaced with server data" else "Force download failed", scope)
                        }
                    }
                },
                onDismiss = { showForceDownloadConfirm = false }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        SettingsDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Sync Log Viewer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sync Log",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = { SyncLogger.clear() },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color.Gray,
                    contentColor = Color.White
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Clear", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color(250, 250, 250))
                .border(1.dp, Color.Gray)
        ) {
            val scrollState = rememberScrollState()

            // Auto-scroll to bottom when new logs arrive
            LaunchedEffect(syncLogs.size) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }

            if (syncLogs.isEmpty()) {
                Text(
                    text = "No sync activity yet",
                    style = MaterialTheme.typography.body2,
                    color = Color.Gray,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(8.dp)
                ) {
                    // Show last 20 logs
                    syncLogs.takeLast(20).forEach { log ->
                        val logColor = when (log.level) {
                            SyncLogger.LogLevel.INFO -> Color(0, 100, 0)
                            SyncLogger.LogLevel.WARNING -> Color(200, 100, 0)
                            SyncLogger.LogLevel.ERROR -> Color(200, 0, 0)
                        }

                        Text(
                            text = "[${log.timestamp}] ${log.message}",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = logColor
                            ),
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(2.dp, Color.Black, RectangleShape)
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.body1,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color.Gray,
                        contentColor = Color.White
                    )
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(200, 0, 0),
                        contentColor = Color.White
                    )
                ) {
                    Text("Confirm", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}