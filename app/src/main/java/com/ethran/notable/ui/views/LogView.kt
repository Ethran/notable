package com.ethran.notable.ui.views

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ethran.notable.navigation.NavigationDestination


object BugReportDestination : NavigationDestination {
    override val route = "bugReport"
}

// TODO: refactor, improve code quality, maybe add ViewModel
@Composable
fun BugReportScreen(navController: NavController) {
    val context = LocalContext.current
    var description by remember { mutableStateOf("") }
    var includeLogs by remember { mutableStateOf(true) }
    val logTags =
        listOf("PageDataManager", "PageViewCache", "GestureReceiver" /*, more if needed */)
    var selectedTags by remember { mutableStateOf(logTags.associateWith { true }) }
    var includeLibrariesLogs by remember { mutableStateOf(false) }
    val reportData = ReportData(context, selectedTags, includeLibrariesLogs)

    BugReportScreenContent(
        goBack = {navController.popBackStack()},
        description = "TODO",
        includeLogs = true,
        reportData = reportData,
        includeLibrariesLogs = includeLibrariesLogs,
        submitBugReport = {}
    )
}
@Composable
fun BugReportScreenContent(goBack: ()-> Unit,
                           description: String,
                           includeLogs: Boolean,
                           reportData: ReportData,
                           includeLibrariesLogs: Boolean,
                           submitBugReport: () -> Unit
                           ) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .fillMaxHeight()
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = goBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text("Report an Issue", style = MaterialTheme.typography.h6)
        }

        Spacer(Modifier.height(16.dp))

        // Description field
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Issue description") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )
        Spacer(Modifier.height(8.dp))
        // Include logs toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = includeLogs,
                onCheckedChange = { includeLogs = it }
            )
            Spacer(Modifier.width(8.dp))
            Text("Include diagnostic logs")
        }
        if (includeLogs) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Include logs from(leave default if unsure):",
                style = MaterialTheme.typography.caption
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                logTags.forEach { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedTags[tag] == true,
                            onCheckedChange = { checked ->
                                selectedTags =
                                    selectedTags.toMutableMap().apply { put(tag, checked) }
                            }
                        )
                        Text(tag, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeLibrariesLogs,
                        onCheckedChange = { checked ->
                            includeLibrariesLogs = checked
                        }
                    )
                    Text("Include libraries logs", modifier = Modifier.padding(end = 8.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Report Preview Card
        ReportPreviewCard(
            reportData,
            description.ifBlank { "_No description provided_" },
            includeLogs
        )

        Spacer(Modifier.height(16.dp))

        // Action buttons
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(
                onClick = { reportData.copyReportToClipboard(context, description, includeLogs) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy Report")
            }

            Spacer(Modifier.width(16.dp))

            Button(
                onClick = {
                    reportData.submitBugReport(context, description, includeLogs)
                },
                modifier = Modifier.weight(1f),
                enabled = description.isNotBlank()
            ) {
                Text("Submit via GitHub")
            }
        }
    }
}

@Composable
private fun ReportPreviewCard(
    reportData: ReportData,
    description: String,
    includeLogs: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f),
        elevation = 4.dp,
        backgroundColor = MaterialTheme.colors.surface
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            Text("📋 Report Preview", style = MaterialTheme.typography.subtitle1)
            Spacer(Modifier.height(8.dp))

            // Description
            Text(
                "📝 Description:",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary
            )
            Text(description, modifier = Modifier.padding(vertical = 4.dp))

            // Device Info
            Text(
                "📱 Device Info:",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary
            )
            Text(reportData.deviceInfo, modifier = Modifier.padding(vertical = 4.dp))

            // Logs - only this section should be scrollable
            if (includeLogs) {
                Text(
                    "📋 Logs:",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary
                )
                Box(
                    modifier = Modifier
                        .weight(1f) // Take remaining space
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        reportData.formatLogsForDisplay(),
                        modifier = Modifier.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.body2.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

