package com.ethran.notable.editor

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.copyImageToDatabase
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.getPageIndex
import com.ethran.notable.data.db.getParentFolder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


private val log = ShipBook.getLogger("EditorViewModel")


/**
 * Toolbar Actions (Intents) representing user interactions.
 */
sealed class ToolbarAction {
    object ToggleToolbar : ToolbarAction()
    data class ChangeMode(val mode: Mode) : ToolbarAction()
    data class ChangePen(val pen: Pen) : ToolbarAction()
    data class ChangePenSetting(val pen: Pen, val setting: PenSetting) : ToolbarAction()
    data class ChangeEraser(val eraser: Eraser) : ToolbarAction()
    object ToggleMenu : ToolbarAction()
    data class UpdateMenuOpenTo(val isOpen: Boolean) : ToolbarAction()
    data class ToggleBackgroundSelector(val isOpen: Boolean) : ToolbarAction()
    data class ToggleScribbleToErase(val enabled: Boolean) : ToolbarAction()

    // Actions that trigger side effects in ControlTower or other components
    object Undo : ToolbarAction()
    object Redo : ToolbarAction()
    object Paste : ToolbarAction()
    object ResetView : ToolbarAction()
    object ClearAllStrokes : ToolbarAction()

    // Complex Actions
    data class ImagePicked(val uri: Uri) : ToolbarAction()
    data class ExportPage(val format: ExportFormat) : ToolbarAction()
    data class ExportBook(val format: ExportFormat) : ToolbarAction()
    data class BackgroundChanged(val type: String, val path: String?) : ToolbarAction()

    // Navigation
    object NavigateToLibrary : ToolbarAction()
    object NavigateToBugReport : ToolbarAction()
    object NavigateToPages : ToolbarAction()
    object NavigateToHome : ToolbarAction()

    object CloseAllMenus : ToolbarAction()
}

/**
 * UI Events for one-time side effects (navigation, snackbars, etc.)
 */
sealed class EditorUiEvent {
    data class ShowSnackbar(val message: String) : EditorUiEvent()
    data class NavigateToLibrary(val folderId: String?) : EditorUiEvent()
    data class NavigateToPages(val bookId: String) : EditorUiEvent()
    object NavigateToBugReport : EditorUiEvent()
    
    // Side effects back to drawing logic/engine
    object Undo : EditorUiEvent()
    object Redo : EditorUiEvent()
    object Paste : EditorUiEvent()
    object ResetView : EditorUiEvent()
    object ClearAllStrokes : EditorUiEvent()
    object RefreshCanvas : EditorUiEvent()
    data class CopyImageToCanvas(val uri: Uri) : EditorUiEvent()

    // Sync state back to EditorState
    data class ModeChanged(val mode: Mode) : EditorUiEvent()
    data class PenChanged(val pen: Pen) : EditorUiEvent()
    data class PenSettingChanged(val pen: Pen, val setting: PenSetting) : EditorUiEvent()
    data class EraserChanged(val eraser: Eraser) : EditorUiEvent()
    data class ToolbarVisibilityChanged(val visible: Boolean) : EditorUiEvent()
}

/**
 * UI State for the Toolbar rendering.
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
    val isSelectionActive: Boolean = false,
    
    // Context needed for visibility rules in UI
    val notebookId: String? = null,
    val pageId: String? = null,
    val isBookActive: Boolean = false,

    // TODO: check correctness
    // Internal data for BackgroundSelector rendering if it remains stateless
    val backgroundType: String = "native",
    val backgroundPath: String = "blank",
    val backgroundPageNumber: Int = 0,
    val currentPageNumber: Int = 0
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val exportEngine: ExportEngine
) : ViewModel() {

    private val _toolbarState = MutableStateFlow(ToolbarUiState())
    val toolbarState: StateFlow<ToolbarUiState> = _toolbarState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<EditorUiEvent>()
    val uiEvents: SharedFlow<EditorUiEvent> = _uiEvents.asSharedFlow()

    // Internal context
    private var currentBookId: String? = null
    private var currentPageId: String = ""

    fun onToolbarAction(action: ToolbarAction) {
        when (action) {
            is ToolbarAction.ToggleToolbar -> {
                val newVisible = !_toolbarState.value.isToolbarOpen
                _toolbarState.update { it.copy(isToolbarOpen = newVisible) }
                sendUiEvent(EditorUiEvent.ToolbarVisibilityChanged(newVisible))
                updateDrawingState()
            }
            is ToolbarAction.ChangeMode -> {
                _toolbarState.update { it.copy(mode = action.mode) }
                sendUiEvent(EditorUiEvent.ModeChanged(action.mode))
                updateDrawingState()
            }
            is ToolbarAction.ChangePen -> handlePenChange(action.pen)
            is ToolbarAction.ChangePenSetting -> handlePenSettingChange(action.pen, action.setting)
            is ToolbarAction.ChangeEraser -> handleEraserChange(action.eraser)
            is ToolbarAction.ToggleMenu -> {
                _toolbarState.update { it.copy(isMenuOpen = !it.isMenuOpen) }
                updateDrawingState()
            }
            is ToolbarAction.UpdateMenuOpenTo -> {
                _toolbarState.update { it.copy(isStrokeSelectionOpen = action.isOpen) }
                updateDrawingState()
            }
            is ToolbarAction.ToggleBackgroundSelector -> {
                _toolbarState.update { it.copy(isBackgroundSelectorModalOpen = action.isOpen) }
                updateDrawingState()
            }

            is ToolbarAction.ToggleScribbleToErase -> updateScribbleToErase(action.enabled)
            is ToolbarAction.ImagePicked -> handleImagePicked(action.uri)
            is ToolbarAction.ExportPage -> handleExport(ExportTarget.Page(currentPageId), action.format)
            is ToolbarAction.ExportBook -> {
                currentBookId?.let { handleExport(ExportTarget.Book(it), action.format) }
            }
            is ToolbarAction.BackgroundChanged -> handleBackgroundChange(action.type, action.path)

            ToolbarAction.Undo -> sendUiEvent(EditorUiEvent.Undo)
            ToolbarAction.Redo -> sendUiEvent(EditorUiEvent.Redo)
            ToolbarAction.Paste -> sendUiEvent(EditorUiEvent.Paste)
            ToolbarAction.ResetView -> sendUiEvent(EditorUiEvent.ResetView)
            ToolbarAction.ClearAllStrokes -> sendUiEvent(EditorUiEvent.ClearAllStrokes)

            ToolbarAction.NavigateToLibrary -> handleNavigateToLibrary()
            ToolbarAction.NavigateToBugReport -> sendUiEvent(EditorUiEvent.NavigateToBugReport)
            ToolbarAction.NavigateToPages -> handleNavigateToPages()
            ToolbarAction.NavigateToHome -> sendUiEvent(EditorUiEvent.NavigateToLibrary(null))

            ToolbarAction.CloseAllMenus -> handleCloseAllMenus()
        }
    }

    private fun sendUiEvent(event: EditorUiEvent) {
        viewModelScope.launch { _uiEvents.emit(event) }
    }

    private fun handlePenChange(pen: Pen) {
        _toolbarState.update { state ->
            if (state.mode == Mode.Draw && state.pen == pen) {
                state.copy(isStrokeSelectionOpen = true)
            } else {
                sendUiEvent(EditorUiEvent.PenChanged(pen))
                if (state.mode != Mode.Draw) sendUiEvent(EditorUiEvent.ModeChanged(Mode.Draw))
                state.copy(mode = Mode.Draw, pen = pen)
            }
        }
        updateDrawingState()
    }

    private fun handleEraserChange(eraser: Eraser) {
        _toolbarState.update { it.copy(eraser = eraser) }
        sendUiEvent(EditorUiEvent.EraserChanged(eraser))
        updateDrawingState()
    }


    private fun handleCloseAllMenus() {
        log.d("Closing all menus in EditorViewModel")
        _toolbarState.update {
            it.copy(
                isMenuOpen = false,
                isStrokeSelectionOpen = false,
                isBackgroundSelectorModalOpen = false
            )
        }
        updateDrawingState()
    }

    /**
     * Re-evaluates whether drawing should be enabled based on menu and selection states.
     * Also handles switching back to drawing mode when menus are closed.
     */
    fun updateDrawingState() {
        val state = _toolbarState.value
        val anyMenuOpen =
            state.isMenuOpen || state.isStrokeSelectionOpen || state.isBackgroundSelectorModalOpen
        val shouldBeDrawing = !anyMenuOpen && !_toolbarState.value.isSelectionActive
        log.d("Drawing state: $shouldBeDrawing")
        viewModelScope.launch {
            CanvasEventBus.isDrawing.emit(shouldBeDrawing)
        }
    }

    fun onFocusChanged(isFocused: Boolean) {
        if (isFocused) {
            updateDrawingState()
        }
    }

    private fun handlePenSettingChange(pen: Pen, setting: PenSetting) {
        _toolbarState.update { state ->
            val newSettings = state.penSettings.toMutableMap()
            newSettings[pen.penName] = setting
            sendUiEvent(EditorUiEvent.PenSettingChanged(pen, setting))
            state.copy(penSettings = newSettings)
        }
    }

    private fun updateScribbleToErase(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.kvProxy.setAppSettings(
                GlobalAppSettings.current.copy(scribbleToEraseEnabled = enabled)
            )
        }
    }

    private fun handleImagePicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val copiedFile = copyImageToDatabase(context, uri)
                _uiEvents.emit(EditorUiEvent.CopyImageToCanvas(copiedFile.toUri()))
            } catch (e: Exception) {
                _uiEvents.emit(EditorUiEvent.ShowSnackbar("Image import failed: ${e.message}"))
            }
        }
    }

    private fun handleExport(target: ExportTarget, format: ExportFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = exportEngine.export(target, format)
                _uiEvents.emit(EditorUiEvent.ShowSnackbar(result))
            } catch (e: Exception) {
                _uiEvents.emit(EditorUiEvent.ShowSnackbar("Export failed: ${e.message}"))
            }
        }
    }

    private fun handleBackgroundChange(type: String, path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val page = appRepository.pageRepository.getById(currentPageId) ?: return@launch
            val updatedPage = if (path == null) {
                page.copy(backgroundType = type)
            } else {
                page.copy(background = path, backgroundType = type)
            }
            appRepository.pageRepository.update(updatedPage)

            // Calculate background page number
            val bgPageNum = when (val bgTypeObj = BackgroundType.fromKey(type)) {
                is BackgroundType.Pdf -> bgTypeObj.page
                is BackgroundType.AutoPdf -> {
                    currentBookId?.let { appRepository.getPageNumber(it, currentPageId) } ?: 0
                }

                else -> 0
            }

            _toolbarState.update { 
                it.copy(
                    backgroundType = updatedPage.backgroundType,
                    backgroundPath = updatedPage.background,
                    backgroundPageNumber = bgPageNum
                )
            }
            _uiEvents.emit(EditorUiEvent.RefreshCanvas)
        }
    }

    private fun handleNavigateToLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val page = appRepository.pageRepository.getById(currentPageId)
            val parentFolder = page?.getParentFolder(appRepository.bookRepository)
            _uiEvents.emit(EditorUiEvent.NavigateToLibrary(parentFolder))
        }
    }

    private fun handleNavigateToPages() {
        currentBookId?.let { bookId ->
            viewModelScope.launch {
                _uiEvents.emit(EditorUiEvent.NavigateToPages(bookId))
            }
        }
    }

    /**
     * Loads context data for the toolbar (page number, background info, etc.)
     */
    fun loadBookData(bookId: String?, pageId: String) {
        currentBookId = bookId
        currentPageId = pageId

        viewModelScope.launch(Dispatchers.IO) {
            val page = appRepository.pageRepository.getById(pageId)
            val book = bookId?.let { appRepository.bookRepository.getById(it) }
            
            val pageIndex = book?.getPageIndex(pageId) ?: 0
            val totalPages = book?.pageIds?.size ?: 1

            val backgroundTypeObj = BackgroundType.fromKey(page?.backgroundType ?: "native")
            val bgPageNumber = when (backgroundTypeObj) {
                is BackgroundType.Pdf -> backgroundTypeObj.page
                is BackgroundType.AutoPdf -> {
                    bookId?.let { appRepository.getPageNumber(it, pageId) } ?: 0
                }

                else -> 0
            }
            
            _toolbarState.update {
                it.copy(
                    notebookId = bookId,
                    pageId = pageId,
                    isBookActive = bookId != null,
                    pageNumberInfo = if (bookId != null) "${pageIndex + 1}/$totalPages" else "1/1",
                    currentPageNumber = pageIndex,
                    backgroundType = page?.backgroundType ?: "native",
                    backgroundPath = page?.background ?: "blank",
                    backgroundPageNumber = bgPageNumber
                )
            }
        }
    }

    fun setHasClipboard(hasClipboard: Boolean) {
        _toolbarState.update { it.copy(hasClipboard = hasClipboard) }
    }

    fun setShowResetView(showResetView: Boolean) {
        _toolbarState.update { it.copy(showResetView = showResetView) }
    }

    fun setSelectionActive(active: Boolean) {
        if (_toolbarState.value.isSelectionActive != active) {
            _toolbarState.update { it.copy(isSelectionActive = active) }
            updateDrawingState()
        }
    }

    fun updateToolbarSettings(state: ToolbarUiState) {
        _toolbarState.update { 
            it.copy(
                isToolbarOpen = state.isToolbarOpen,
                mode = state.mode,
                pen = state.pen,
                eraser = state.eraser,
                penSettings = state.penSettings
            )
        }
    }
}
