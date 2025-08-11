package com.ethran.notable.views

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoDisturb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.ethran.notable.PACKAGE_NAME
import com.ethran.notable.db.KvProxy
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.getCurRefreshModeString
import com.ethran.notable.utils.isRecommendedRefreshMode
import com.ethran.notable.utils.setRecommendedMode

@ExperimentalFoundationApi
@Composable
fun WelcomeView(navController: NavController) {
    val context = LocalContext.current
    val filePermissionGranted = remember { mutableStateOf(hasFilePermission(context)) }
    val batteryOptimizationDisabled =
        remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val recommendedRefreshMode =
        remember { mutableStateOf(isRecommendedRefreshMode()) }
    val refreshModeString = remember { mutableStateOf(getCurRefreshModeString()) }


    // For automatic permission state updates
    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.flow {
            while (true) {
                emit(Unit)
                kotlinx.coroutines.delay(500) // Check every 500ms
            }
        }.collect {
            filePermissionGranted.value = hasFilePermission(context)
            batteryOptimizationDisabled.value = isIgnoringBatteryOptimizations(context)
            recommendedRefreshMode.value = isRecommendedRefreshMode()
            refreshModeString.value = getCurRefreshModeString()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    "Welcome to Notable",
                    style = MaterialTheme.typography.h4,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "For optimal experience, please complete these setup steps:",
                    style = MaterialTheme.typography.body1,
                    textAlign = TextAlign.Center
                )
            }

            // Two-column layout for permissions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // File Permission Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PermissionItem(
                        title = "File Access",
                        description = "Required for saving and loading your notes",
                        isGranted = filePermissionGranted.value,
                        buttonText = if (filePermissionGranted.value) "Granted ✓" else "Grant Permission",
                        onClick = {
                            if (!filePermissionGranted.value) {
                                requestPermissions(context)
                            }
                        },
                        enabled = !filePermissionGranted.value
                    )
                }

                // Battery Optimization Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PermissionItem(
                        title = "Battery Optimization (optional)",
                        description = "Might prevent app from being killed in background (probably does not work)",
                        isGranted = batteryOptimizationDisabled.value,
                        buttonText = if (batteryOptimizationDisabled.value) "Optimized ✓" else "Disable Optimization",
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        },
                        enabled = !batteryOptimizationDisabled.value
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Battery Optimization Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PermissionItem(
                        title = "Set Recommended Refresh Mode",
                        description = "Its recommended to use HD mode or Regal mode",
                        isGranted = recommendedRefreshMode.value,
                        buttonText = if (recommendedRefreshMode.value)
                            "Applied (${refreshModeString.value})"
                        else
                            "Set HD Mode (currently in ${refreshModeString.value})",
                        onClick = {
                            setRecommendedMode()
                        },
                        enabled = !recommendedRefreshMode.value
                    )
                }
            }

            // Gestures Instructions
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                elevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Quick Gestures Guide", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("1 Finger:", style = MaterialTheme.typography.subtitle1)
                    Text("• Swipe up/down: Scroll\n• Swipe left/right: Change pages\n• Double tap: Undo\n• Hold and drag: Select")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("2 Fingers:", style = MaterialTheme.typography.subtitle1)
                    Text("• Swipe left/right: Toggle toolbar\n• Single tap: Switch modes\n• Pinch: Zoom")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Selection:", style = MaterialTheme.typography.subtitle1)
                    Text("• Drag: Move selection\n• Double tap: Copy")
                }
            }

            // Continue Button
            Button(
                onClick = {
                    KvProxy(context).setAppSettings(
                        GlobalAppSettings.current.copy(showWelcome = false)
                    )
                    navController.navigate("library")
                },
                enabled = filePermissionGranted.value,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .padding(top = 24.dp)
            ) {
                Text(if (filePermissionGranted.value) "Continue" else "Complete Setup First")
            }
        }
    }
}


@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(8.dp)
    ) {
        // Status indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp)
        ) {
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.DoDisturb,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.subtitle1,
            textAlign = TextAlign.Center
        )

        Text(
            text = description,
            style = MaterialTheme.typography.caption,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = if (isGranted) MaterialTheme.colors.primary.copy(alpha = 0.12f)
                else MaterialTheme.colors.primary
            )
        ) {
            Text(buttonText)
        }
    }
}

// Helper functions


fun hasFilePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}


private fun requestPermissions(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                context as Activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1001
            )
        }
    } else if (!Environment.isExternalStorageManager()) {
        requestManageAllFilesPermission(context)
    }
}


@RequiresApi(Build.VERSION_CODES.R)
private fun requestManageAllFilesPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
    intent.data = Uri.fromParts("package", PACKAGE_NAME, null)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}