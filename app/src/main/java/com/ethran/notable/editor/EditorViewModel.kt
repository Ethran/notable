package com.ethran.notable.editor;

import com.ethran.notable.io.ExportEngine;
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.APP_SETTINGS_KEY
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.utils.isLatestVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


/**
 * UI State for the Toolbar
 */
data class ToolbarUiState(
    val isToolbarOpen: Boolean = true,
    val mode: Mode = Mode.Draw,
    val pen: Pen = Pen.BALLPEN,
    val penSettings: Map<String, PenSetting> = emptyMap(),
    val eraser: Eraser = Eraser.PEN,
    val isMenuOpen: Boolean = false,
    val isStrokeSelectionOpen: Boolean = false,
    val isBackgroundSelectorModalOpen: Boolean = false,
    val pageNumberInfo: String = "1/1",
    val hasClipboard: Boolean = false,
    val showResetView: Boolean = false,
    // Background Selector specific data
    val backgroundType: String = "native",
    val backgroundPath: String = "blank",
    val backgroundPageNumber: Int = 0,
    val notebookId: String? = null,
    val pageId: String? = null,
    val currentPageNumber: Int = 0
)

@HiltViewModel
class EditorViewModel @Inject
constructor(
    private val exportEngine:ExportEngine,
    // other dependencies...
) : ViewModel() {

    fun exportCurrentPage(format: ExportFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            // UI State: Show loading spinner
            _uiState.update { it.copy(isExporting = true) }

            val result = exportEngine.export(ExportTarget.Page(currentPageId), format)

            // Tell UI to show Snackbar based on result
            _uiEvents.emit(UiEvent.ShowSnackbar("Export successful!"))
            _uiState.update { it.copy(isExporting = false) }
        }
    }
}
