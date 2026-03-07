package com.ethran.notable.ui.views

import android.Manifest
import android.app.Activity
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ethran.notable.PACKAGE_NAME
import com.ethran.notable.R
import com.ethran.notable.editor.utils.getCurRefreshModeString
import com.ethran.notable.editor.utils.isRecommendedRefreshMode
import com.ethran.notable.editor.utils.setRecommendedMode
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.viewmodels.WelcomeViewModel
import com.ethran.notable.utils.hasFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


object WelcomeDestination : NavigationDestination {
    override val route = "welcome"
}

@Composable
fun WelcomeView(
    goToLibrary: () -> Unit = {},
    viewModel: WelcomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var filePermissionGranted by remember { mutableStateOf(hasFilePermission(context)) }
    var recommendedRefreshMode by remember { mutableStateOf(isRecommendedRefreshMode()) }
    var refreshModeString by remember { mutableStateOf(getCurRefreshModeString()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                filePermissionGranted = hasFilePermission(context)
                recommendedRefreshMode = isRecommendedRefreshMode()
                refreshModeString = getCurRefreshModeString()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    @RequiresApi(Build.VERSION_CODES.R)
    fun requestManageAllFilesPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.fromParts("package", PACKAGE_NAME, null)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun requestPermissions() {
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
            requestManageAllFilesPermission()
        }
    }


    // Call the stateless content view
    WelcomeContent(
        filePermissionGranted = filePermissionGranted,
        recommendedRefreshMode = recommendedRefreshMode,
        refreshModeString = refreshModeString,
        onFilePermissionRequest = { requestPermissions() },
        onRefreshModeRequest = { setRecommendedMode() },
        onContinue = {
            scope.launch(Dispatchers.IO) {
                viewModel.removeWelcome()
            }
            goToLibrary()
        }
    )
}

@Composable
fun WelcomeContent(
    filePermissionGranted: Boolean,
    recommendedRefreshMode: Boolean,
    refreshModeString: String,
    onFilePermissionRequest: () -> Unit,
    onRefreshModeRequest: () -> Unit,
    onContinue: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            WelcomeHeader()

            // Pass values and callbacks down
            PermissionsRow(
                isFilePermissionGranted = filePermissionGranted,
                isRecommendedRefreshMode = recommendedRefreshMode,
                refreshModeString = refreshModeString,
                onFileClick = onFilePermissionRequest,
                onRefreshClick = onRefreshModeRequest
            )

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

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onContinue,
                enabled = filePermissionGranted,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .padding(top = 24.dp)
            ) {
                Text(
                    if (filePermissionGranted)
                        stringResource(R.string.welcome_view_continue)
                    else
                        stringResource(R.string.welcome_view_complete_setup_first)
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun WelcomeHeader() {
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
}

@Composable
private fun PermissionsRow(
    isFilePermissionGranted: Boolean,
    isRecommendedRefreshMode: Boolean,
    refreshModeString: String,
    onFileClick: () -> Unit,
    onRefreshClick: () -> Unit
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
                isGranted = isFilePermissionGranted,
                buttonText = if (isFilePermissionGranted) stringResource(R.string.welcome_view_permissions_granted) else stringResource(
                    R.string.welcome_view_permissions_button
                ),
                onClick = onFileClick,
                enabled = !isFilePermissionGranted
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
                isGranted = isRecommendedRefreshMode,
                buttonText = if (isRecommendedRefreshMode) stringResource(
                    R.string.welcome_view_refresh_mode_applied, refreshModeString
                )
                else stringResource(
                    R.string.welcome_view_refresh_mode_set_hd_mode, refreshModeString
                ),
                onClick = onRefreshClick,
                enabled = !isRecommendedRefreshMode
            )
        }
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



// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //


@Preview(
    showBackground = true,
    name = "Setup Required"
)
@Composable
fun WelcomePreviewSetup() {
    MaterialTheme {
        WelcomeContent(
            filePermissionGranted = false,
            recommendedRefreshMode = false,
            refreshModeString = "Normal",
            onFilePermissionRequest = {},
            onRefreshModeRequest = {},
            onContinue = {}
        )
    }
}

@Preview(
    showBackground = true,
    name = "Ready to Go"
)
@Composable
fun WelcomePreviewReady() {
    MaterialTheme {
        WelcomeContent(
            filePermissionGranted = true,
            recommendedRefreshMode = true,
            refreshModeString = "Extreme",
            onFilePermissionRequest = {},
            onRefreshModeRequest = {},
            onContinue = {}
        )
    }
}