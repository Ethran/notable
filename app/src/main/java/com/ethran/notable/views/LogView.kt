package com.ethran.notable.views

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
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
import androidx.compose.material.Switch
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
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.ethran.notable.BuildConfig
import com.ethran.notable.classes.PageDataManager
import com.ethran.notable.utils.getDbDir
import com.onyx.android.sdk.utils.ClipboardUtils.copyToClipboard
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow


class ReportData(
    context: Context,
    selectedTags: Map<String, Boolean>,
    includeLibrariesLogs: Boolean
) {
    val deviceInfo: String = buildDeviceInfo(context)
    private val logs: String = getRecentLogs(selectedTags, includeLibrariesLogs)

    companion object {
        private const val MAX_LOG_LINES = 100
        private const val LOG_LINE_REGEX =
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}.\d{3})\s+(\d+)\s+(\d+)\s([VDIWE])\s([^:]+):\s(.*)$"""
    }

    private fun rapportMarkdown(includeLogs: Boolean, description: String): String {
        val formattedLogs = formatLogsForDisplay()
        val baseReport = buildString {
            append("### Description\n")
            append(description).append("\n\n")
            append("### Device Info\n")
            append(deviceInfo.replace("•", "-")).append("\n")
        }

        if (!includeLogs) return baseReport

        val logHeader = "\n### Diagnostic Logs\n```\n"
        val logFooter = "\n```"
        val logBoxLength = URLEncoder.encode(logFooter + logHeader, "UTF-8").length
        // Calculate space available for logs
        val urlPrefixLength = ("https://github.com/ethran/notable/issues/new?" +
                "title=${URLEncoder.encode("Bug: ${getTitle(description)}", "UTF-8")}" +
                "&body=").length

        val encodedBaseLength = URLEncoder.encode(baseReport, "UTF-8").length

        val availableSpace = 8201 - (encodedBaseLength + logBoxLength + urlPrefixLength+50)

        val wholeLogsLength = URLEncoder.encode(formattedLogs, "UTF-8").length
        val trimmedLogs = if (wholeLogsLength > availableSpace) {
            // Binary search for optimal truncation point
            var low = 0
            var high = wholeLogsLength
            var bestLength = 0

            while (low <= high) {
                val mid = (low + high) / 2
                val testLogs = formattedLogs.take(mid)
                val testEncoded = URLEncoder.encode(testLogs, "UTF-8")

                if (testEncoded.length <= availableSpace) {
                    bestLength = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            formattedLogs.take(bestLength)
        } else {
            formattedLogs
        }

        return buildString {
            append(baseReport)
            append(logHeader)
            append(trimmedLogs)
            append(logFooter)
        }
    }

    private fun getTitle(description: String): String {
        return description.take(40)
    }

    private fun getRecentLogs(
        selectedTags: Map<String, Boolean>,
        includeLibrariesLogs: Boolean
    ): String {
        return try {
            // Get logs with threadtime format (most detailed format)
            val process = Runtime.getRuntime().exec("logcat -d -v threadtime")
            val reader = BufferedReader(InputStreamReader(process.inputStream))


            // Read all lines and keep only the newest matching entries
            val allLines = reader.useLines { lines ->
                lines.filter {
                    val match = Regex(LOG_LINE_REGEX).matchEntire(it)
                    excludeLibraryLogs(match, includeLibrariesLogs) && filterLogsByTags(
                        match,
                        selectedTags
                    )
                }
                    .toList()
            }

            // Take the most recent logs and reverse order (newest first)
            val recentLines = allLines.takeLast(MAX_LOG_LINES).reversed()

            if (recentLines.isEmpty()) "No recent logs found"
            else recentLines.joinToString("\n")
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    private fun excludeLibraryLogs(match: MatchResult?, includeLibrariesLogs: Boolean): Boolean {
        if (includeLibrariesLogs) return true
        val excludedTags = setOf(
            "OnBackInvokedCallback",      // System back gesture
            "OpenGLRenderer",             // Graphics system
            "GoogleInputMethodService",   // Keyboard
            "ProfileInstaller",           // Code optimization
            "Parcel",                     // IPC
            "InputMethodManager",         // Keyboard/input manager
            "InsetsController",           // System UI/IME transitions
            "Choreographer",              // Frame timing/animation
            "ViewRootImpl",               // UI framework internals
            "SurfaceView",                // Surface rendering
            "BLASTBufferQueue",           // Graphics buffer management
            "BufferQueueProducer",        //
            "TrafficStats",               // Network stats
            "StrictMode",                 // Policy violations
            "androidx.compose",           // Jetpack Compose framework
            "HwBinder",                   // Hardware binder subsystem
            "libc",                       // Native C/C++ library
            "lib_touch_reader",           // Touch input driver logs
            "RawInputReader\$a",          // Raw input reader internal threads
            "AdrenoGLES-0",                // GPU driver and Adreno graphics logs
            "CompatibilityChangeReporter", ".ethran.notable"
        )
        if (match != null) {
            val tag = match.groupValues[5].trim()
            return tag !in excludedTags
        } else {
            return false
        }
    }

    private fun filterLogsByTags(match: MatchResult?, selectedTags: Map<String, Boolean>): Boolean {
        return if (match != null) {
            val tag = match.groupValues[5].trim()
            if (selectedTags.containsKey(tag))
                selectedTags[tag] == true
            else
                true
        } else {
            false
        }

    }

    fun formatLogsForDisplay(): String {
        return logs.lines().joinToString("\n") { line ->
            val match = Regex(LOG_LINE_REGEX).find(line)
            if (match != null) {
                val (datetime, _, _, level, tag, message) = match.destructured
                val flag = when (level) {
                    "E" -> "🔴"
                    "W" -> "🟠"
                    "I" -> "🔵"
                    "D" -> "🟢"
                    "V" -> "⚪"
                    else -> "⚫"
                }
                "$flag $datetime $level $tag: $message"
            } else {
                line // Fallback for non-matching lines
            }
        }
    }

    private fun buildDeviceInfo(context: Context): String {
        val runtime = Runtime.getRuntime()

        // Memory
        val maxHeap = runtime.maxMemory().toHumanReadable()
        val appUsed = (runtime.totalMemory() - runtime.freeMemory()).toHumanReadable()
        val pageMemoryMB = PageDataManager.getUsedMemory()

        // Storage
        val dbDir = getDbDir()
        val dbUsed = getFolderSize(dbDir).toHumanReadable()
        val freeSpace = StatFs(dbDir.path).availableBytes.toHumanReadable()
        val totalStorage = getTotalDeviceStorage().toHumanReadable()
        val totalMemory = getTotalDeviceMemory().toHumanReadable()
        val batteryPct = getBatteryPercentage(context)
        val threadCount = Thread.activeCount()
        val buildType = getSignature(context)

        return """
        |• Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE},  SDK ${Build.VERSION.SDK_INT})
        |• System: $totalMemory RAM | $totalStorage storage | Battery: $batteryPct% | Threads: $threadCount
        |• Memory: ${pageMemoryMB}MB used by pages | $appUsed used by app | $maxHeap max
        |• Storage: $dbUsed used by app | $freeSpace free
        |• Version: ${BuildConfig.VERSION_NAME} ($buildType)
        |• Current time: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}
    """.trimMargin()
    }


    private fun Long.toHumanReadable(): String {
        if (this <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (log10(this.toDouble()) / log10(1024.0)).toInt()
        val unit = units[minOf(digitGroups, units.size - 1)]
        val value = this / 1024.0.pow(minOf(digitGroups, units.size - 1).toDouble())

        return "%.1f %s".format(Locale.US, value, unit)
    }

    private fun getTotalDeviceMemory(): Long {
        return try {
            val memInfoFile = File("/proc/meminfo")
            if (memInfoFile.exists()) {
                memInfoFile.readLines().firstOrNull { it.startsWith("MemTotal:") }
                    ?.split("\\s+".toRegex())?.getOrNull(1)?.toLongOrNull()?.times(1024) ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun getTotalDeviceStorage(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.totalBytes
    }

    private fun getFolderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { file ->
            if (file.isDirectory) getFolderSize(file) else file.length()
        } ?: 0L
    }


    // Example helper functions (stubs - implement as needed)
    private fun getBatteryPercentage(context: Context): Int {
        val batteryIntent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    private fun getSignature(context: Context): String {
        return try {
            val packageInfo =
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )

            val signatures =
                packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()

            if (signatures.isNotEmpty()) {
                val cert = signatures[0].toByteArray()
                val md = MessageDigest.getInstance("SHA-1")
                val digest = md.digest(cert)
                val currentFingerprint = digest.joinToString(":") { "%02X".format(it) }

                when (currentFingerprint) {
                    "2B:28:68:8C:78:69:D9:9F:12:F8:73:EE:C3:45:2C:D7:8B:49:FD:70" -> "next build"
                    "3E:7E:96:AA:01:E3:1E:90:43:50:B5:30:EB:55:FF:12:60:B1:FE:9D" -> "release build"
                    else -> "Dev build"
                }
            } else {
                "no signatures found"
            }
        } catch (e: Exception) {
            "error: ${e.message ?: "unknown error"}"
        }
    }

    fun copyReportToClipboard(context: Context, description: String, includeLogs: Boolean) {
        copyToClipboard(
            context,
            rapportMarkdown(includeLogs, description.ifBlank { "_No description provided_" })
        )
        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun submitBugReport(context: Context, description: String, includeLogs: Boolean) {
        try {
            val url = "https://github.com/ethran/notable/issues/new?" +
                    "title=${URLEncoder.encode("Bug: ${getTitle(description)}", "UTF-8")}" +
                    "&body=${URLEncoder.encode(rapportMarkdown(includeLogs, description), "UTF-8")}"

            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to submit report", Toast.LENGTH_LONG).show()
        }
    }
}


@Composable
fun BugReportScreen(navController: NavController) {
    val context = LocalContext.current
    var description by remember { mutableStateOf("") }
    var includeLogs by remember { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val logTags =
        listOf("PageDataManager", "PageViewCache", "GestureReceiver" /*, more if needed */)
    var selectedTags by remember { mutableStateOf(logTags.associateWith { true }) }
    var includeLibrariesLogs by remember { mutableStateOf(false) }
    val reportData = ReportData(context, selectedTags, includeLibrariesLogs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .fillMaxHeight()
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
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
            Switch(
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
        ReportPreviewCard(reportData, description.ifBlank { "_No description provided_" }, includeLogs)

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