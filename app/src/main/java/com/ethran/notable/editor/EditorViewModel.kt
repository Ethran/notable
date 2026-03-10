package com.ethran.notable.editor

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.copyImageToDatabase
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.getPageIndex
import com.ethran.notable.data.db.getParentFolder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.EditorViewModel.Companion.DEFAULT_PEN_SETTINGS
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private val log = ShipBook.getLogger("EditorViewModel")

// --------------------------------------------------------
// 1. UI STATE
// --------------------------------------------------------

/**
 * Flat toolbar/editor UI state exposed to Compose.
 * Also used as `EditorUiState` via typealias for backward compatibility.
 */
data class ToolbarUiState(
    // Document info
    val notebookId: String? = null,
    val pageId: String? = null,
    val isBookActive: Boolean = false,
    val pageNumberInfo: String = "1/1",
    val currentPageNumber: Int = 0,

    // Background
    val backgroundType: String = "native",
    val backgroundPath: String = "blank",
    val backgroundPageNumber: Int = 0,

    // Toolbar visibility & menus
    val isToolbarOpen: Boolean = false,
    val isMenuOpen: Boolean = false,
    val isStrokeSelectionOpen: Boolean = false,
    val isBackgroundSelectorModalOpen: Boolean = false,
    val showResetView: Boolean = false,

    // Canvas / drawing
    val mode: Mode = Mode.Draw,
    val pen: Pen = Pen.BALLPEN,
    val eraser: Eraser = Eraser.PEN,
    // TODO: if it is an  emptyMap(), the DrawCanvas crashes, to be fixed.
    val penSettings: Map<String, PenSetting> = DEFAULT_PEN_SETTINGS,
    val isSelectionActive: Boolean = false,
    val hasClipboard: Boolean = false,
    val isDrawing: Boolean = true,
) {
    val isDrawingAllowed: Boolean
        get() = mode == Mode.Draw &&
                !isMenuOpen &&
                !isStrokeSelectionOpen &&
                !isBackgroundSelectorModalOpen &&
                !isSelectionActive
}


// --------------------------------------------------------
// 2. USER ACTIONS (Intents)
// --------------------------------------------------------

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

    object Undo : ToolbarAction()
    object Redo : ToolbarAction()
    object Paste : ToolbarAction()
    object ResetView : ToolbarAction()
    object ClearAllStrokes : ToolbarAction()

    data class ImagePicked(val uri: Uri) : ToolbarAction()
    data class ExportPage(val format: ExportFormat) : ToolbarAction()
    data class ExportBook(val format: ExportFormat) : ToolbarAction()
    data class BackgroundChanged(val type: String, val path: String?) : ToolbarAction()

    object NavigateToLibrary : ToolbarAction()
    object NavigateToBugReport : ToolbarAction()
    object NavigateToPages : ToolbarAction()
    object NavigateToHome : ToolbarAction()

    object CloseAllMenus : ToolbarAction()
}


// --------------------------------------------------------
// 3. CANVAS COMMANDS (Imperative drawing actions)
// --------------------------------------------------------

sealed class CanvasCommand {
    object Undo : CanvasCommand()
    object Redo : CanvasCommand()
    object Paste : CanvasCommand()
    object ResetView : CanvasCommand()
    object ClearAllStrokes : CanvasCommand()
    object RefreshCanvas : CanvasCommand()
    data class CopyImageToCanvas(val uri: Uri) : CanvasCommand()
}

// --------------------------------------------------------
// 4. UI EVENTS (Navigation, Snackbars)
// --------------------------------------------------------

sealed class EditorUiEvent {
    data class ShowSnackbar(val message: String) : EditorUiEvent()
    data class NavigateToLibrary(val folderId: String?) : EditorUiEvent()
    data class NavigateToPages(val bookId: String) : EditorUiEvent()
    object NavigateToBugReport : EditorUiEvent()
}

// --------------------------------------------------------
// 5. VIEW MODEL
// --------------------------------------------------------

@HiltViewModel
class EditorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val exportEngine: ExportEngine
) : ViewModel() {

    // ---- Toolbar / UI State (single flat flow) ----
    private val _toolbarState = MutableStateFlow(ToolbarUiState())
    val toolbarState: StateFlow<ToolbarUiState> = _toolbarState.asStateFlow()

    // ---- One-Time Events (Channels) ----
    private val uiEventChannel = Channel<EditorUiEvent>(Channel.BUFFERED)
    val uiEvents = uiEventChannel.receiveAsFlow()

    private val canvasCommandChannel = Channel<CanvasCommand>(Channel.BUFFERED)
    val canvasCommands = canvasCommandChannel.receiveAsFlow()

    // ---- Internal document context ----
    private var bookId: String? = null
    private val currentPageId: String get() = _toolbarState.value.pageId.orEmpty()

    // ---- Init guard ----
    private val didInitSettings = AtomicBoolean(false)

    // ---- Selection state (kept for drawing logic compatibility) ----
    val selectionState = SelectionState()

    // --------------------------------------------------------
    // Initialization from persisted settings
    // --------------------------------------------------------

    /**
     * Restores editor settings from the persisted cache.
     * Idempotent: only applies settings on first call; subsequent calls are no-ops.
     */
    fun initFromPersistedSettings(settings: EditorSettingCacheManager.EditorSettings?) {
        if (!didInitSettings.compareAndSet(false, true)) return

        _toolbarState.update {
            it.copy(
                mode = settings?.mode ?: Mode.Draw,
                pen = settings?.pen ?: Pen.BALLPEN,
                eraser = settings?.eraser ?: Eraser.PEN,
                isToolbarOpen = settings?.isToolbarOpen ?: false,
                penSettings = settings?.penSettings ?: DEFAULT_PEN_SETTINGS
            )
        }
    }

    // --------------------------------------------------------
    // Toolbar Action Dispatch
    // --------------------------------------------------------

    fun onToolbarAction(action: ToolbarAction) {
        when (action) {
            is ToolbarAction.ToggleToolbar -> {
                _toolbarState.update { it.copy(isToolbarOpen = !it.isToolbarOpen) }
                updateDrawingState()
            }

            is ToolbarAction.ChangeMode -> {
                _toolbarState.update { it.copy(mode = action.mode) }
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
            is ToolbarAction.ExportPage -> handleExport(
                ExportTarget.Page(currentPageId),
                action.format
            )

            is ToolbarAction.ExportBook -> {
                bookId?.let { handleExport(ExportTarget.Book(it), action.format) }
            }

            is ToolbarAction.BackgroundChanged -> handleBackgroundChange(action.type, action.path)

            ToolbarAction.Undo -> sendCanvasCommand(CanvasCommand.Undo)
            ToolbarAction.Redo -> sendCanvasCommand(CanvasCommand.Redo)
            ToolbarAction.Paste -> sendCanvasCommand(CanvasCommand.Paste)
            ToolbarAction.ResetView -> sendCanvasCommand(CanvasCommand.ResetView)
            ToolbarAction.ClearAllStrokes -> sendCanvasCommand(CanvasCommand.ClearAllStrokes)

            ToolbarAction.NavigateToLibrary -> handleNavigateToLibrary()
            ToolbarAction.NavigateToBugReport -> sendUiEvent(EditorUiEvent.NavigateToBugReport)
            ToolbarAction.NavigateToPages -> handleNavigateToPages()
            ToolbarAction.NavigateToHome -> sendUiEvent(EditorUiEvent.NavigateToLibrary(null))

            ToolbarAction.CloseAllMenus -> handleCloseAllMenus()
        }
    }

    // --------------------------------------------------------
    // Toolbar Action Handlers (private)
    // --------------------------------------------------------

    private fun handlePenChange(pen: Pen) {
        val state = _toolbarState.value
        if (state.mode == Mode.Draw && state.pen == pen) {
            _toolbarState.update { it.copy(isStrokeSelectionOpen = true) }
        } else {
            _toolbarState.update {
                it.copy(pen = pen, mode = Mode.Draw)
            }
        }
        updateDrawingState()
    }

    private fun handleEraserChange(eraser: Eraser) {
        _toolbarState.update { it.copy(eraser = eraser) }
        updateDrawingState()
    }

    private fun handlePenSettingChange(pen: Pen, setting: PenSetting) {
        val newSettings = _toolbarState.value.penSettings.toMutableMap()
        newSettings[pen.penName] = setting
        _toolbarState.update { it.copy(penSettings = newSettings) }
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
                sendCanvasCommand(CanvasCommand.CopyImageToCanvas(copiedFile.toUri()))
            } catch (e: Exception) {
                sendUiEvent(EditorUiEvent.ShowSnackbar("Image import failed: ${e.message}"))
            }
        }
    }

    private fun handleExport(target: ExportTarget, format: ExportFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = exportEngine.export(target, format)
                sendUiEvent(EditorUiEvent.ShowSnackbar(result))
            } catch (e: Exception) {
                sendUiEvent(EditorUiEvent.ShowSnackbar("Export failed: ${e.message}"))
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
                    bookId?.let { appRepository.getPageNumber(it, currentPageId) } ?: 0
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
            sendCanvasCommand(CanvasCommand.RefreshCanvas)
        }
    }

    private fun handleNavigateToLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val page = appRepository.pageRepository.getById(currentPageId)
            val parentFolder = page?.getParentFolder(appRepository.bookRepository)
            sendUiEvent(EditorUiEvent.NavigateToLibrary(parentFolder))
        }
    }

    private fun handleNavigateToPages() {
        bookId?.let { id ->
            sendUiEvent(EditorUiEvent.NavigateToPages(id))
        }
    }

    // --------------------------------------------------------
    // Drawing State
    // --------------------------------------------------------

    /**
     * Re-evaluates whether drawing should be enabled based on menu and selection states.
     */
    fun updateDrawingState() {
        val state = _toolbarState.value
        val anyMenuOpen =
            state.isMenuOpen || state.isStrokeSelectionOpen || state.isBackgroundSelectorModalOpen
        val shouldBeDrawing = !anyMenuOpen && !state.isSelectionActive
        _toolbarState.update { it.copy(isDrawing = shouldBeDrawing) }
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

    // --------------------------------------------------------
    // Book / Page Data
    // --------------------------------------------------------

    /**
     * Loads context data for the toolbar (page number, background info, etc.)
     */
    fun loadBookData(bookId: String?, pageId: String) {
        this.bookId = bookId

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

    // --------------------------------------------------------
    // Page Navigation (from EditorState)
    // --------------------------------------------------------

    private suspend fun getNextPageId(): String? {
        return if (bookId != null) {
            appRepository.getNextPageIdFromBookAndPageOrCreate(
                pageId = currentPageId, notebookId = bookId!!
            )
        } else null
    }

    private suspend fun getPreviousPageId(): String? {
        return if (bookId != null) {
            appRepository.getPreviousPageIdFromBookAndPage(
                pageId = currentPageId, notebookId = bookId!!
            )
        } else null
    }

    fun goToNextPage() {
        viewModelScope.launch(Dispatchers.IO) {
            getNextPageId()?.let { changePage(it) }
        }
    }

    fun goToPreviousPage() {
        viewModelScope.launch(Dispatchers.IO) {
            getPreviousPageId()?.let { changePage(it) }
        }
    }

    private suspend fun updateOpenedPage(newPageId: String) {
        Log.d("EditorView", "Update open page to $newPageId")
        if (bookId != null) {
            appRepository.bookRepository.setOpenPageId(bookId!!, newPageId)
        }
        if (newPageId != currentPageId) {
            Log.d("EditorView", "Page changed")
            loadBookData(bookId, newPageId)
        } else {
            Log.d("EditorView", "Tried to change to same page!")
            sendUiEvent(EditorUiEvent.ShowSnackbar("Tried to change to same page!"))
        }
    }

    /**
     * Changes the current page to the one with the specified [id].
     *
     * @param id The unique identifier of the page to switch to.
     */
    fun changePage(id: String) {
        log.d("Changing page to $id, from $currentPageId")
        viewModelScope.launch(Dispatchers.IO) {
            updateOpenedPage(id)
            selectionState.reset()
        }
    }

    // --------------------------------------------------------
    // Toolbar State Sync Helpers
    // --------------------------------------------------------

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

    // --------------------------------------------------------
    // Event / Command Helpers
    // --------------------------------------------------------

    private fun sendUiEvent(event: EditorUiEvent) {
        viewModelScope.launch { uiEventChannel.send(event) }
    }

    private fun sendCanvasCommand(command: CanvasCommand) {
        viewModelScope.launch { canvasCommandChannel.send(command) }
    }

    companion object {
        val DEFAULT_PEN_SETTINGS = mapOf(
            Pen.BALLPEN.penName to PenSetting(5f, Color.BLACK),
            Pen.REDBALLPEN.penName to PenSetting(5f, Color.RED),
            Pen.BLUEBALLPEN.penName to PenSetting(5f, Color.BLUE),
            Pen.GREENBALLPEN.penName to PenSetting(5f, Color.GREEN),
            Pen.PENCIL.penName to PenSetting(5f, Color.BLACK),
            Pen.BRUSH.penName to PenSetting(5f, Color.BLACK),
            Pen.MARKER.penName to PenSetting(40f, Color.LTGRAY),
            Pen.FOUNTAIN.penName to PenSetting(5f, Color.BLACK)
        )
    }
}