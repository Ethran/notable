package com.ethran.notable.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.ui.views.BugReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BugReportUiState(
    val description: String = "",
    val includeLogs: Boolean = true,
    val includeLibrariesLogs: Boolean = false,
    val availableTags: List<String> = listOf("PageDataManager", "PageViewCache", "GestureReceiver"),
    val selectedTags: Map<String, Boolean> = emptyMap(),

    // Generated Data
    val isGenerating: Boolean = true,
    val deviceInfo: String = "Loading device info...",
    val formattedLogs: String = "Loading logs...",
    val finalMarkdown: String = ""
)

// Using AndroidViewModel because we need the Context to read Battery, Storage, etc.
class BugReportViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        BugReportUiState(
            selectedTags = listOf("PageDataManager", "PageViewCache", "GestureReceiver").associateWith { true }
        )
    )
    val uiState: StateFlow<BugReportUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    init {
        regenerateReport()
    }

    fun onDescriptionChange(newDesc: String) {
        _uiState.update { it.copy(description = newDesc) }
        regenerateReport()
    }

    fun onIncludeLogsToggle(include: Boolean) {
        _uiState.update { it.copy(includeLogs = include) }
        regenerateReport()
    }

    fun onIncludeLibrariesToggle(include: Boolean) {
        _uiState.update { it.copy(includeLibrariesLogs = include) }
        regenerateReport()
    }

    fun onTagToggle(tag: String, isChecked: Boolean) {
        _uiState.update { state ->
            val newTags = state.selectedTags.toMutableMap().apply { put(tag, isChecked) }
            state.copy(selectedTags = newTags)
        }
        regenerateReport()
    }

    private fun regenerateReport() {
        generationJob?.cancel() // Cancel previous job if user is toggling fast

        _uiState.update { it.copy(isGenerating = true) }

        generationJob = viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val context = getApplication<Application>()

            // Generate the raw data (Logs, Device Info)
            val generator = BugReportGenerator(context, state.selectedTags, state.includeLibrariesLogs)

            // Format the final markdown string needed for Clipboard/GitHub
            val markdown = generator.rapportMarkdown(
                includeLogs = state.includeLogs,
                description = state.description.ifBlank { "_No description provided_" }
            )

            _uiState.update {
                it.copy(
                    isGenerating = false,
                    deviceInfo = generator.deviceInfo,
                    formattedLogs = generator.formatLogsForDisplay(),
                    finalMarkdown = markdown
                )
            }
        }
    }

    // Helper for generating the GitHub issue title
    fun getIssueTitle(): String {
        val desc = _uiState.value.description
        return "Bug: " + (if (desc.length > 40) desc.take(40) + "..." else desc.ifBlank { "Unknown issue" })
    }
}