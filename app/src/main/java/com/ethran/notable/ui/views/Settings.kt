package com.ethran.notable.ui.views

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.BuildConfig
import com.ethran.notable.R
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.DebugSettings
import com.ethran.notable.ui.components.GesturesSettings
import com.ethran.notable.ui.components.GeneralSettings
import com.ethran.notable.ui.showHint
import com.ethran.notable.utils.isLatestVersion
import com.ethran.notable.utils.isNext
import kotlin.concurrent.thread

@Composable
fun SettingsView(navController: NavController) {
    val context = LocalContext.current
    val kv = KvProxy(context)
    val settings = GlobalAppSettings.current

    // Tab titles
    val tabs = listOf(
        stringResource(R.string.settings_tab_general_name),
        stringResource(R.string.settings_tab_gestures_name),
        stringResource(R.string.settings_tab_debug_name)
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
                    1 -> GesturesSettings(context, kv, settings)
                    2 -> DebugSettings(kv, settings, navController)
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
                    openInBrowser(
                        context, "https://github.com/sponsors/ethran"
                    )
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
                    openInBrowser(context, "https://github.com/ethran/notable/releases")
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





fun openInBrowser(context: Context, uriString: String) {
    val urlIntent = Intent(
        Intent.ACTION_VIEW, uriString.toUri()
    )
    try {
        context.startActivity(urlIntent)
    } catch (_: ActivityNotFoundException) {
        // log and show error
        SnackState.logAndShowError(
            "openInBrowser",
            "No application can handle this request. Please install a web browser.",
            Log::w
        )
    }
}