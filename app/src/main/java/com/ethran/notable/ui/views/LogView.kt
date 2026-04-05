package com.ethran.notable.ui.views

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.viewmodels.BugReportUiState
import com.ethran.notable.ui.viewmodels.BugReportViewModel
import com.onyx.android.sdk.utils.ClipboardUtils.copyToClipboard
import java.net.URLEncoder



object BugReportDestination : NavigationDestination {
    override val route = "bugReport"
}

@Composable
fun BugReportScreen(
    goBack: () -> Unit, viewModel: BugReportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun submitBugReport() {
        try {
            val title = viewModel.getIssueTitle()
            val body = uiState.finalMarkdown
            val url = "https://github.com/ethran/notable/issues/new?" +
                    "title=${URLEncoder.encode("Bug: $title", "UTF-8")}" +
                    "&body=${URLEncoder.encode(body, "UTF-8")}"

            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to submit report", Toast.LENGTH_LONG).show()
        }
    }

    fun copyReportToClipboard() {
        copyToClipboard(context, uiState.finalMarkdown)
        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    BugReportScreenContent(
        uiState = uiState,
        onBack = goBack,
        onDescriptionChange = viewModel::onDescriptionChange,
        onIncludeLogsChange = viewModel::onIncludeLogsToggle,
        onTagChange = viewModel::onTagToggle,
        onIncludeLibrariesChange = viewModel::onIncludeLibrariesToggle,
        onCopyClick = ::copyReportToClipboard,
        onSubmitClick = ::submitBugReport
    )
}


// 2. STATELESS UI
@Composable
fun BugReportScreenContent(
    uiState: BugReportUiState,
    onBack: () -> Unit,
    onDescriptionChange: (String) -> Unit,
    onIncludeLogsChange: (Boolean) -> Unit,
    onTagChange: (String, Boolean) -> Unit,
    onIncludeLibrariesChange: (Boolean) -> Unit,
    onCopyClick: () -> Unit,
    onSubmitClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text("Report an Issue", style = MaterialTheme.typography.h6)
        }

        Spacer(Modifier.height(16.dp))

        // Description field
        OutlinedTextField(
            value = uiState.description, onValueChange = onDescriptionChange,
            label = { Text("Issue description") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )

        Spacer(Modifier.height(8.dp))

        // Include logs toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = uiState.includeLogs, onCheckedChange = onIncludeLogsChange
            )
            Spacer(Modifier.width(8.dp))
            Text("Include diagnostic logs")
        }

        // Tags Selection Row
        if (uiState.includeLogs) {
            Spacer(Modifier.height(8.dp))
            Text("Include logs from:", style = MaterialTheme.typography.caption)

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                uiState.availableTags.forEach { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = uiState.selectedTags[tag] == true,
                            onCheckedChange = { checked -> onTagChange(tag, checked) }
                        )
                        Text(tag, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uiState.includeLibrariesLogs,
                        onCheckedChange = onIncludeLibrariesChange
                    )
                    Text("Include libraries logs", modifier = Modifier.padding(end = 8.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Report Preview Card
        ReportPreviewCard(
            uiState = uiState, modifier = Modifier.weight(1f) // Grow to fill space above buttons
        )

        Spacer(Modifier.height(16.dp))

        // Action buttons
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(
                onClick = onCopyClick,
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy Report")
            }

            Spacer(Modifier.width(16.dp))

            Button(
                onClick = onSubmitClick,
                modifier = Modifier.weight(1f),
                enabled = uiState.description.isNotBlank() && !uiState.isGenerating
            ) {
                Text("Submit via GitHub")
            }
        }
    }
}

@Composable
private fun ReportPreviewCard(
    uiState: BugReportUiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = 4.dp,
        backgroundColor = MaterialTheme.colors.surface
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📋 Report Preview", style = MaterialTheme.typography.subtitle1)
                if (uiState.isGenerating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "📝 Description:",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary
            )
            Text(
                uiState.description.ifBlank { "_No description provided_" },
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                "📱 Device Info:",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary
            )
            Text(uiState.deviceInfo, modifier = Modifier.padding(vertical = 4.dp))

            if (uiState.includeLogs) {
                Text(
                    "📋 Logs:",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = uiState.formattedLogs,
                        modifier = Modifier.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }
}

// 3. PREVIEWS
@Preview(showBackground = true, name = "Report Screen - Logs Enabled")
@Composable
fun BugReportScreenPreview() {
    BugReportScreenContent(
        uiState = BugReportUiState(
            description = "App crashes when I click the favorite button.",
            includeLogs = true,
            isGenerating = false,
            deviceInfo = "• Device: Google Pixel 7\n• Memory: 150MB used",
            formattedLogs = "🟢 12-01 10:15 PageDataManager: Saved successfully\n🔴 12-01 10:16 GestureReceiver: Crash on tap"
        ),
        onBack = {},
        onDescriptionChange = {},
        onIncludeLogsChange = {},
        onTagChange = { _, _ -> },
        onIncludeLibrariesChange = {},
        onCopyClick = {},
        onSubmitClick = {})
}