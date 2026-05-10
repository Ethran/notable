package com.ethran.notable.ui.views

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
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
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ethran.notable.BuildConfig
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.DebugSettings
import com.ethran.notable.ui.components.GeneralSettings
import com.ethran.notable.ui.components.GesturesSettings
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.viewmodels.GestureRowModel
import com.ethran.notable.ui.viewmodels.SettingsViewModel
import com.ethran.notable.ui.viewmodels.SyncSettingsUiState
import com.ethran.notable.utils.isNext
import kotlinx.coroutines.launch


object SettingsDestination : NavigationDestination {
    override val route = "settings"
}

@Composable
fun SettingsView(
    onBack: () -> Unit,
    goToWelcome: () -> Unit,
    goToSystemInfo: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings = viewModel.settings

    LaunchedEffect(Unit) {
        viewModel.checkUpdate(context, force = false)
    }

    @Suppress("KotlinConstantConditions") val versionString = remember {
        "v${BuildConfig.VERSION_NAME}${if (isNext) " [NEXT]" else ""}"
    }

    SettingsContent(
        versionString = versionString,
        settings = settings,
        isLatestVersion = viewModel.isLatestVersion,
        onBack = onBack,
        goToWelcome = goToWelcome,
        goToSystemInfo = goToSystemInfo,
        onCheckUpdate = { force ->
            viewModel.checkUpdate(context, force)
        },
        onUpdateSettings = { viewModel.updateSettings(it) },
        listOfGestures = viewModel.getGestureRows(),
        availableGestures = viewModel.availableGestures,
        syncUiState = viewModel.syncUiState,
        syncCallbacks = SyncSettingsCallbacks(
            onUpdateSyncSettings = viewModel::updateSyncSettings,
            onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
            onSaveCredentials = viewModel::onSaveCredentials,
            onTestConnection = viewModel::onTestConnection,
            onManualSync = viewModel::onManualSync,
            onClearSyncLogs = viewModel::onClearSyncLogs,
            danger = SyncDangerCallbacks(
                onForceUploadRequested = viewModel::onForceUploadRequested,
                onForceDownloadRequested = viewModel::onForceDownloadRequested,
                onConfirmForceUpload = viewModel::onConfirmForceUpload,
                onConfirmForceDownload = viewModel::onConfirmForceDownload,
            ),
        )
    )
}

@Composable
fun SettingsContent(
    versionString: String,
    settings: AppSettings,
    isLatestVersion: Boolean,
    onBack: () -> Unit,
    goToWelcome: () -> Unit,
    goToSystemInfo: () -> Unit,
    onCheckUpdate: (Boolean) -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    selectedTabInitial: Int = 0,
    listOfGestures: List<GestureRowModel> = emptyList(),
    availableGestures: List<Pair<AppSettings.GestureAction?, Any>> = emptyList(),
    syncUiState: SyncSettingsUiState = SyncSettingsUiState(),
    syncCallbacks: SyncSettingsCallbacks = SyncSettingsCallbacks(),
) {
    var selectedTab by remember { mutableIntStateOf(selectedTabInitial) }
    val tabs = listOf(
        stringResource(R.string.settings_tab_general_name),
        stringResource(R.string.settings_tab_gestures_name),
        stringResource(R.string.settings_tab_sync_name),
        stringResource(R.string.settings_tab_debug_name)
    )

    Surface(
        modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            SettingsTitleBar(versionString, onBack)

            SettingsTabRow(tabs, selectedTab) { selectedTab = it }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab) {
                    0 -> GeneralSettings(settings, onUpdateSettings)
                    1 -> GesturesSettings(
                        settings, onUpdateSettings, listOfGestures, availableGestures
                    )

                    2 -> SyncSettings(
                        state = syncUiState,
                        callbacks = syncCallbacks,
                    )

                    3 -> DebugSettings(settings, onUpdateSettings, goToWelcome, goToSystemInfo)
                }
            }

            if (selectedTab == 0) {
                Column(
                    modifier = Modifier.padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GitHubSponsorButton(
                        Modifier
                            .padding(horizontal = 120.dp, vertical = 16.dp)
                            .height(48.dp)
                            .fillMaxWidth()
                    )
                    UpdateActions(
                        isLatestVersion = isLatestVersion,
                        onCheckUpdate = onCheckUpdate,
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
private fun SettingsTitleBar(versionString: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colors.onBackground
            )
        }

        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.h5,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        Text(text = versionString, style = MaterialTheme.typography.subtitle1)
    }
}

@Composable
private fun SettingsTabRow(tabs: List<String>, selectedTab: Int, onTabSelected: (Int) -> Unit) {
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
            TabRowDefaults.Divider(
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f), thickness = 1.dp
            )
        }) {
        tabs.forEachIndexed { index, title ->
            Tab(selected = selectedTab == index, onClick = { onTabSelected(index) }, text = {
                Text(
                    text = title, color = if (selectedTab == index) MaterialTheme.colors.onSurface
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            })
        }
    }
}

@Composable
fun GitHubSponsorButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snackState = LocalSnackContext.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .background(color = Color(0xFF24292E), shape = RoundedCornerShape(25.dp))
            .clickable {
                openInBrowser(context, "https://github.com/sponsors/ethran") {
                    scope.launch {
                        snackState.displaySnack(SnackConf(text = it, duration = 3000))
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.FavoriteBorder,
                contentDescription = null,
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

@Composable
fun UpdateActions(
    isLatestVersion: Boolean, onCheckUpdate: (Boolean) -> Unit, modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val snackState = LocalSnackContext.current
    val scope = rememberCoroutineScope()
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
                    openInBrowser(context, "https://github.com/ethran/notable/releases") {
                        scope.launch {
                            snackState.displaySnack(SnackConf(text = it, duration = 3000))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upgrade, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.app_see_release))
            }
        }
    } else {
        Button(onClick = { onCheckUpdate(true) }, modifier = modifier) {
            Icon(Icons.Default.Update, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.app_check_updates))
        }
    }
}


fun openInBrowser(context: Context, uriString: String, onError: (String) -> Unit) {
    val urlIntent = Intent(Intent.ACTION_VIEW, uriString.toUri())
    try {
        context.startActivity(urlIntent)
    } catch (_: ActivityNotFoundException) {
        val message = "No application can handle this request. Please install a web browser."
        Log.w("openInBrowser", message)
        onError(message)
    }
}

// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //


@Preview(showBackground = true)
@Composable
fun SettingsPreviewGeneral() {
    InkaTheme {
        SettingsContent(
            versionString = "v1.0.0",
            settings = AppSettings(version = 1),
            isLatestVersion = true,
            onBack = {},
            goToWelcome = {},
            goToSystemInfo = {},
            onCheckUpdate = {},
            onUpdateSettings = {},
            selectedTabInitial = 0
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsPreviewGestures() {
    val dummyRows = listOf(
        GestureRowModel(
            R.string.gestures_double_tap_action, null, AppSettings.defaultDoubleTapAction
        ) { }, GestureRowModel(
            R.string.gestures_two_finger_tap_action,
            AppSettings.GestureAction.Undo,
            AppSettings.defaultTwoFingerTapAction
        ) { })
    InkaTheme {
        SettingsContent(
            versionString = "v1.0.0",
            settings = AppSettings(version = 1),
            isLatestVersion = true,
            onBack = {},
            goToWelcome = {},
            goToSystemInfo = {},
            onCheckUpdate = {},
            onUpdateSettings = {},
            selectedTabInitial = 1,
            listOfGestures = dummyRows
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsPreviewDebug() {
    InkaTheme {
        SettingsContent(
            versionString = "v1.0.0",
            settings = AppSettings(version = 1),
            isLatestVersion = true,
            onBack = {},
            goToWelcome = {},
            goToSystemInfo = {},
            onCheckUpdate = {},
            onUpdateSettings = {},
            selectedTabInitial = 3
        )
    }
}
@Preview(
    name = "Light",
    showBackground = true,
    device = "spec:width=360dp,height=600dp"
)
@Preview(
    name = "Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
    device = "spec:width=360dp,height=600dp"
)
@Composable
fun SettingsPreviewSync() {
    InkaTheme {
        SettingsContent(
            versionString = "v1.0.0",
            settings = AppSettings(version = 1),
            isLatestVersion = true,
            onBack = {},
            goToWelcome = {},
            goToSystemInfo = {},
            onCheckUpdate = {},
            onUpdateSettings = {},
            selectedTabInitial = 2,
        )
    }
}
