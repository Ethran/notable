package com.ethran.notable.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger that maintains recent sync log messages for display in UI.
 */
object SyncLogger {
    private const val MAX_LOGS = 50

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * Add an info log entry.
     */
    fun i(tag: String, message: String) {
        addLog(LogLevel.INFO, tag, message)
        io.shipbook.shipbooksdk.Log.i(tag, message)
    }

    /**
     * Add a warning log entry.
     */
    fun w(tag: String, message: String) {
        addLog(LogLevel.WARNING, tag, message)
        io.shipbook.shipbooksdk.Log.w(tag, message)
    }

    /**
     * Add an error log entry.
     */
    fun e(tag: String, message: String) {
        addLog(LogLevel.ERROR, tag, message)
        io.shipbook.shipbooksdk.Log.e(tag, message)
    }

    /**
     * Clear all log entries.
     */
    fun clear() {
        _logs.value = emptyList()
    }

    private fun addLog(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message
        )

        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(entry)

        // Keep only last MAX_LOGS entries
        if (currentLogs.size > MAX_LOGS) {
            currentLogs.removeAt(0)
        }

        _logs.value = currentLogs
    }

    data class LogEntry(
        val timestamp: String,
        val level: LogLevel,
        val tag: String,
        val message: String
    )

    enum class LogLevel {
        INFO, WARNING, ERROR
    }
}
