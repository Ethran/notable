package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.ethran.notable.BuildConfig
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.getDbDir
import com.onyx.android.sdk.device.Device
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

class BugReportGenerator(
    context: Context,
    selectedTags: Map<String, Boolean>,
    includeLibrariesLogs: Boolean,
    private val pageDataManager: PageDataManager
) {
    val deviceInfo: String = buildDeviceInfo(context)
    private val logs: String = getRecentLogs(selectedTags, includeLibrariesLogs)

    companion object {
        private const val MAX_LOG_LINES = 100
        private const val LOG_LINE_REGEX =
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}.\d{3})\s+(\d+)\s+(\d+)\s([VDIWE])\s([^:]+):\s(.*)$"""
    }

    fun rapportMarkdown(includeLogs: Boolean, description: String): String {
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

        val availableSpace = 8201 - (encodedBaseLength + logBoxLength + urlPrefixLength + 50)

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
                }.toList()
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
            "CompatibilityChangeReporter",
            ".ethran.notable",
            // OpenGl rendering:
            "GLThread", "GLFrontBufferedRenderer", "Gralloc4"
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
        val pageMemoryMB = pageDataManager.getUsedMemory()

        // Storage
        val dbDir = getDbDir()
        val dbUsed = getFolderSize(dbDir).toHumanReadable()
        val freeSpace = StatFs(dbDir.path).availableBytes.toHumanReadable()
        val totalStorage = getTotalDeviceStorage().toHumanReadable()
        val totalMemory = getTotalDeviceMemory().toHumanReadable()
        val batteryPct = getBatteryPercentage(context)
        val threadCount = Thread.activeCount()
        val buildType = getSignature(context)
        val deviceName = Device.currentDevice().javaClass.name

        return """
        |• Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE},  SDK ${Build.VERSION.SDK_INT})
        |• Device Name: $deviceName
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
}