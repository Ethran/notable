package com.ethran.notable.editor

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.copyImageToDatabase
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.getPageIndex
import com.ethran.notable.data.db.getParentFolder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.di.ApplicationScope
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
import com.ethran.notable.io.exportToLinkedFile
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.SnackState.Companion.logAndShowError
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date
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
    val isQuickNavOpen: Boolean = false,
) {
    val isDrawingAllowed: Boolean
        get() = !isSelectionActive &&
                !(isMenuOpen || isStrokeSelectionOpen || isBackgroundSelectorModalOpen)
                && !isQuickNavOpen
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
    data class UpdateQuickNavOpen(val isOpen: Boolean) : ToolbarAction()
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
    private val editorSettingCacheManager: EditorSettingCacheManager,
    private val exportEngine: ExportEngine,
    val pageDataManager: PageDataManager,
    private val syncOrchestrator: SyncOrchestrator,
    @param:ApplicationScope private val appScope: CoroutineScope
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
    fun initFromPersistedSettings() {
        if (!didInitSettings.compareAndSet(false, true)) return
        val settings = editorSettingCacheManager.getEditorSettings()
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

    /**
     * Called when the EditorView is being disposed.
     * Performs cleanup, exports linked files, and triggers auto-sync.
     */
    fun onDispose(page: PageView) {
        // 1. Finish selection operation
        selectionState.applySelectionDisplace(page)
        bookId?.let { bookId ->
            exportEngine.exportToLinkedFileAsync(bookId)
        }

        // 3. Cleanup page resources
        page.disposeOldPage()
    }

    // --------------------------------------------------------
    // Toolbar Action Dispatch
    // --------------------------------------------------------

    fun onToolbarAction(action: ToolbarAction) {
        log.v("onToolbarAction: $action")
        when (action) {
            is ToolbarAction.ToggleToolbar -> {
                _toolbarState.update { it.copy(isToolbarOpen = !it.isToolbarOpen) }
                updateDrawingState()
                saveToolbarState()
            }

            is ToolbarAction.ChangeMode -> {
                _toolbarState.update { it.copy(mode = action.mode) }
                updateDrawingState()
                saveToolbarState()
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
            is ToolbarAction.UpdateQuickNavOpen -> {
                _toolbarState.update { it.copy(isQuickNavOpen = action.isOpen) }
                updateDrawingState()
            }
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
            saveToolbarState()
        }
        updateDrawingState()
    }

    private fun handleEraserChange(eraser: Eraser) {
        _toolbarState.update { it.copy(eraser = eraser) }
        updateDrawingState()
        saveToolbarState()
    }

    private fun handlePenSettingChange(pen: Pen, setting: PenSetting) {
        val newSettings = _toolbarState.value.penSettings.toMutableMap()
        newSettings[pen.penName] = setting
        _toolbarState.update { it.copy(penSettings = newSettings) }
        saveToolbarState()
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
                logAndShowError("EditorViewModel", "Image import failed: ${e.message}")
            }
        }
    }

    private fun handleExport(target: ExportTarget, format: ExportFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = exportEngine.export(target, format)
                val snack = SnackConf(text = result, duration = 4000)
                SnackState.globalSnackFlow.emit(snack)
            } catch (e: Exception) {
                logAndShowError("EditorViewModel", "Export failed: ${e.message}")
            }
        }
    }

    private fun handleBackgroundChange(type: String, path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val page = appRepository.pageRepository.getById(currentPageId) ?: return@launch
            val updatedPage = if (path == null) {
                page.copy(
                    backgroundType = type,
                    updatedAt = Date()
                )
            } else {
                page.copy(
                    background = path,
                    backgroundType = type,
                    updatedAt = Date()
                )
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
        log.v("updateDrawingState")
        val shouldBeDrawing = _toolbarState.value.isDrawingAllowed
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
    suspend fun loadToolbarState(bookId: String?, pageId: String) {
        log.v("loadBookData: bookId=$bookId, pageId=$pageId")
        this.bookId = bookId

        val page = appRepository.pageRepository.getById(pageId)
        if (page == null) {
            logAndShowError(
                reason = "EditorViewModel",
                message = "Could not find page",
            )
            fixNotebook(bookId, pageId)
            return
        }
        val book = bookId?.let { appRepository.bookRepository.getById(it) }

        val pageIndex = book?.getPageIndex(pageId) ?: 0
        val totalPages = book?.pageIds?.size ?: 1

        val backgroundTypeObj = BackgroundType.fromKey(page.backgroundType)
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
                backgroundType = page.backgroundType,
                backgroundPath = page.background,
                backgroundPageNumber = bgPageNumber
            )
        }
    }

    private fun saveToolbarState() {
        val currentState = _toolbarState.value
        editorSettingCacheManager.setEditorSettings(
            EditorSettingCacheManager.EditorSettings(
                isToolbarOpen = currentState.isToolbarOpen,
                mode = currentState.mode,
                pen = currentState.pen,
                eraser = currentState.eraser,
                penSettings = currentState.penSettings
            )
        )
    }

    /**
     * Attempts to repair potential inconsistencies in the notebook's data structure.
     */
    suspend fun fixNotebook(bookId: String?, pageId: String) {
        log.i("Could not find page, cleaning book and returning to library")
        if (bookId != null) {
            appRepository.bookRepository.removePage(bookId, pageId)
        }
        SnackState.globalSnackFlow.tryEmit(
            SnackConf(
                text = "Could not find page, returning to library", duration = 4000
            )
        )
        sendUiEvent(EditorUiEvent.NavigateToLibrary(null))
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
        log.v("goToNextPage")
        viewModelScope.launch(Dispatchers.IO) {
            getNextPageId()?.let { changePage(it) }
        }
    }

    fun goToPreviousPage() {
        log.v("goToPreviousPage")
        viewModelScope.launch(Dispatchers.IO) {
            getPreviousPageId()?.let { changePage(it) }
        }
    }

    /**
     * Updates the persistence layer and UI state to reflect a change in the currently opened page.
     *
     * This method saves the [newPageId] as the last opened page for the current notebook in the
     * repository. If the page ID has changed, it updates the toolbar state; otherwise, it
     * triggers a UI event to notify the user that the target page is already active.
     *
     * @param newPageId The unique identifier of the page to be set as open.
     */
    private suspend fun updateOpenedPage(newPageId: String) {
        log.v("updateOpenedPage: $newPageId")
        Log.d("EditorView", "Update open page to $newPageId")
        if (bookId != null) {
            appRepository.bookRepository.setOpenPageId(bookId!!, newPageId)
        }
        if (newPageId != currentPageId) {
            // The View's LaunchedEffect will handle the full load once navigation syncs.
            Log.d("EditorView", "Page changed")
//            loadBookData(bookId, newPageId)
            _toolbarState.update { it.copy(pageId = newPageId) }
        } else {
            Log.d("EditorView", "Tried to change to same page!")
            val snack = SnackConf(text = "Tried to change to same page!", duration = 4000)
            SnackState.globalSnackFlow.emit(snack)
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
            // 1. Notify the PageView about the change

            // 2. Update the persistent layer

            // 3. Update the UI state
            updateOpenedPage(id)


            // 4. Clean the selection state
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
        log.v("setSelectionActive: $active")
        if (_toolbarState.value.isSelectionActive != active) {
            if (active) //selection is active, we can directly update it, and skip other checks
                viewModelScope.launch {
                    CanvasEventBus.isDrawing.emit(false)
                }
            _toolbarState.update { it.copy(isSelectionActive = active) }
            if (!active)
                updateDrawingState()
        }
    }

    // --------------------------------------------------------
    // Event / Command Helpers
    // --------------------------------------------------------

    private fun sendUiEvent(event: EditorUiEvent) {
        log.v("sendUiEvent: $event")
        viewModelScope.launch { uiEventChannel.send(event) }
    }

    private fun sendCanvasCommand(command: CanvasCommand) {
        log.v("sendCanvasCommand: $command")
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
