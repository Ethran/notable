package com.ethran.notable.ui.views

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.ethran.notable.PACKAGE_NAME
import com.ethran.notable.R
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.editor.utils.getCurRefreshModeString
import com.ethran.notable.editor.utils.isRecommendedRefreshMode
import com.ethran.notable.editor.utils.setRecommendedMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow


@Composable
fun WelcomeView(navController: NavController) {
    val context = LocalContext.current
    val filePermissionGranted = remember { mutableStateOf(hasFilePermission(context)) }
    val recommendedRefreshMode = remember { mutableStateOf(isRecommendedRefreshMode()) }
    val refreshModeString = remember { mutableStateOf(getCurRefreshModeString()) }

    // For automatic permission state updates
    LaunchedEffect(Unit) {
        flow {
            while (true) {
                emit(Unit)
                delay(500) // Check every 500ms
            }
        }.collect {
            filePermissionGranted.value = hasFilePermission(context)
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
                .fillMaxSize()
                .padding(horizontal = 16.dp), // Only horizontal padding for full height
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
            ) {
                Text(
                    stringResource(R.string.welcome_view_title),
                    style = MaterialTheme.typography.h4,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    stringResource(R.string.welcome_view_subtitle),
                    style = MaterialTheme.typography.body1,
                    textAlign = TextAlign.Center
                )
            }

            PermissionsRow(
                context,
                filePermissionGranted,
                recommendedRefreshMode,
                refreshModeString
            )

            // SCROLLABLE instructions area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
                    OnyxUnfreezeInstruction()
                    ShowInstructions()
                }
            }

            // Spacer to push button up from the bottom
            Spacer(modifier = Modifier.height(12.dp))

            // Continue Button pinned near bottom
            ContinueButton(context, navController, filePermissionGranted.value)

            // Extra spacer to ensure button is not flush with bottom edge
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
fun PermissionsRow(
    context: Context,
    filePermissionGranted: MutableState<Boolean>,
    recommendedRefreshMode: MutableState<Boolean>,
    refreshModeString: MutableState<String>
) {
    // Two-column layout for permissions and instructions
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
                title = stringResource(R.string.welcome_view_file_access),
                description = stringResource(R.string.welcome_view_file_access_explanation),
                isGranted = filePermissionGranted.value,
                buttonText = if (filePermissionGranted.value) stringResource(R.string.welcome_view_permissions_granted) else stringResource(
                    R.string.welcome_view_permissions_button
                ),
                onClick = {
                    if (!filePermissionGranted.value) {
                        requestPermissions(context)
                    }
                },
                enabled = !filePermissionGranted.value
            )
        }

        // Refresh Mode Column
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PermissionItem(
                title = stringResource(R.string.welcome_view_refresh_mode),
                description = stringResource(R.string.welcome_view_refresh_mode_details),
                isGranted = recommendedRefreshMode.value,
                buttonText = if (recommendedRefreshMode.value) stringResource(
                    R.string.welcome_view_refresh_mode_applied, refreshModeString.value
                )
                else stringResource(
                    R.string.welcome_view_refresh_mode_set_hd_mode, refreshModeString.value
                ),
                onClick = {
                    setRecommendedMode()
                },
                enabled = !recommendedRefreshMode.value
            )
        }
    }
}

@Composable
fun ContinueButton(context: Context, navController: NavController, filePermissionGranted: Boolean) {
    Button(
        onClick = {
            KvProxy(context).setAppSettings(
                GlobalAppSettings.current.copy(showWelcome = false)
            )
            navController.navigate("library")
        },
        enabled = filePermissionGranted,
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .padding(top = 24.dp)
    ) {
        Text(
            if (filePermissionGranted) stringResource(R.string.welcome_view_continue) else stringResource(
                R.string.welcome_view_complete_setup_first
            )
        )
    }
}

@Composable
fun OnyxUnfreezeInstruction() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        elevation = 0.dp,
        backgroundColor = MaterialTheme.colors.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 5.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.welcome_view_prevent_frozen),
                style = MaterialTheme.typography.h6,
                color = MaterialTheme.colors.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.welcome_view_prevent_frozen_details),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.welcome_view_prevent_frozen_instructions_title),
                style = MaterialTheme.typography.subtitle2,
                color = MaterialTheme.colors.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.welcome_view_prevent_frozen_instructions),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.welcome_view_prevent_frozen_recommended),
                style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colors.onSurface
            )
        }
    }
}

@Composable
fun ShowInstructions() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        elevation = 0.dp,
        backgroundColor = MaterialTheme.colors.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(start = 18.dp, top = 5.dp, end = 18.dp, bottom = 5.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = stringResource(R.string.welcome_view_quick_start_title),
                style = MaterialTheme.typography.h6,
                color = MaterialTheme.colors.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            // Split into two columns for compactness
            Row(Modifier.fillMaxWidth()) {
                // First column: navigation, editing, selection
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.welcome_view_quick_start_navigation),
                        style = MaterialTheme.typography.subtitle2,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onSurface
                    )
                    Text(
                        text = stringResource(R.string.welcome_view_quick_start_navigation_details),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.welcome_view_quick_start_selection_editing_title),
                        style = MaterialTheme.typography.subtitle2,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onSurface
                    )
                    Text(
                        text = stringResource(R.string.welcome_view_quick_start_selection_editing_details),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.width(20.dp))
                // Second column: extra features
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.welcome_view_quick_start_tips_title),
                        style = MaterialTheme.typography.subtitle2,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onSurface
                    )
                    Text(
                        text = stringResource(R.string.welcome_view_quick_start_tips_details),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface,
                        lineHeight = 18.sp
                    )
                }
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

/**
 * Returns true if the app has "full file access" for your current storage model:
 * - < Android 11: WRITE_EXTERNAL_STORAGE is granted
 * - >= Android 11: MANAGE_EXTERNAL_STORAGE ("All files access") is granted
 */
fun hasFilePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
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