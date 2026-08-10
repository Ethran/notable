package com.ethran.notable.editor

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.copyImageToDatabase
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.getParentFolder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.editor.EditorViewModel.Companion.DEFAULT_PEN_SETTINGS
import com.ethran.notable.editor.PaneRegistry
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.PageLocation
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.ExportFormat
import com.ethran.notable.io.ExportTarget
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.utils.AppResult
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
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
import kotlinx.coroutines.withContext
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
    /**
     * Where the active pane's page sits — which page, which notebook, and its position in it.
     *
     * One value rather than the five correlated fields it replaces (`notebookId`, `pageId`,
     * `isBookActive`, `pageNumberInfo`, `currentPageNumber`), which were written by different code
     * on different triggers and could disagree. See [PageLocation] and STATE-PLAN §2.
     *
     * Null until the first page resolves.
     */
    val location: PageLocation? = null,

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
    /** Base type of the active pen preset — what strokes persist and renderers key on. */
    val pen: Pen = Pen.BALLPEN,
    /** The active [ToolbarPen] preset; identifies the pen button (two ballpen presets
     * differ only here). */
    val penPresetId: String = ToolbarPen.DEFAULT_PENS.first().id,
    val eraser: Eraser = Eraser.PEN,
    /** Color/size per pen preset, keyed by preset id — a projection of
     * AppSettings.toolbarPens kept in sync by the ViewModel (the preset is the source
     * of truth). */
    val penSettings: Map<String, PenSetting> = DEFAULT_PEN_SETTINGS,
    val isSelectionActive: Boolean = false,
    val hasClipboard: Boolean = false,
    val isDrawing: Boolean = true,
    val isQuickNavOpen: Boolean = false,
    /** The in-editor, pane-aware page picker opened from the page counter. */
    val isPagePickerOpen: Boolean = false,
    /**
     * The picker was opened to fill a pane that does not exist yet, so a selection creates the
     * split rather than changing an existing pane's page.
     */
    val isPagePickerForNewPane: Boolean = false,
    /** Whether a second pane is on screen. */
    val isSplit: Boolean = false,
) {
    /** The active preset's setting — what the drawing pipeline draws with. The fallback
     * only triggers if the active preset was deleted mid-session. */
    val activePenSetting: PenSetting
        get() = penSettings[penPresetId] ?: PenSetting(5f, android.graphics.Color.BLACK)

    val isDrawingAllowed: Boolean
        get() = !isSelectionActive &&
                !(isMenuOpen || isStrokeSelectionOpen || isBackgroundSelectorModalOpen)
                && !isQuickNavOpen && !isPagePickerOpen
}


// --------------------------------------------------------
// 2. USER ACTIONS (Intents)
// --------------------------------------------------------

sealed class ToolbarAction {
    object ToggleToolbar : ToolbarAction()
    data class ChangeMode(val mode: Mode) : ToolbarAction()
    data class ChangePen(val presetId: String) : ToolbarAction()
    data class ChangePenSetting(val presetId: String, val setting: PenSetting) : ToolbarAction()
    data class ChangeEraser(val eraser: Eraser) : ToolbarAction()
    object ToggleMenu : ToolbarAction()
    data class ToggleEraserManu(val isOpen: Boolean) : ToolbarAction()
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

    /**
     * Open or close the in-editor page picker. Distinct from [NavigateToPages], which leaves the
     * editor entirely for the full-screen grid and takes both panes with it.
     *
     * [forNewPane] opens it to fill a pane that does not exist yet — the split entry point.
     */
    data class SetPagePickerOpen(
        val isOpen: Boolean,
        val forNewPane: Boolean = false,
    ) : ToolbarAction()

    /**
     * Split into two panes, or return to one. Splitting opens the picker rather than guessing a
     * page: which note goes beside this one is the whole point of the action.
     */
    object ToggleSplit : ToolbarAction()
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
    val snackDispatcher: SnackDispatcher,
    private val historyFactory: History.Factory,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {
    // ---- Toolbar / UI State (single flat flow) ----
    private val _toolbarState = MutableStateFlow(ToolbarUiState())
    val toolbarState: StateFlow<ToolbarUiState> = _toolbarState.asStateFlow()

    init {
        viewModelScope.launch {
            ClipboardStore.content.collect { setHasClipboard(it != null) }
        }
        // The pen presets in AppSettings are the source of truth for per-pen color/size;
        // mirror them into ToolbarUiState.penSettings so the (non-Compose) drawing
        // pipeline sees changes from any writer — StrokeMenu here, or the settings
        // editor (step 6) while an editor is open. If the active preset was deleted,
        // reselect the first surviving one: without this, drawing would continue with
        // the stale base type and a hardcoded fallback setting, and no button would
        // read selected.
        viewModelScope.launch {
            snapshotFlow { GlobalAppSettings.current.toolbarPens }
                .collect { pens ->
                    _toolbarState.update { state ->
                        val active = pens.find { it.id == state.penPresetId }
                            ?: pens.firstOrNull()
                        state.copy(
                            penSettings = pens.associate { it.id to it.setting() },
                            pen = active?.pen ?: state.pen,
                            penPresetId = active?.id ?: state.penPresetId,
                        )
                    }
                }
        }
    }

    // ---- One-Time Events (Channels) ----
    private val uiEventChannel = Channel<EditorUiEvent>(Channel.BUFFERED)
    val uiEvents = uiEventChannel.receiveAsFlow()

    private val canvasCommandChannel = Channel<CanvasCommand>(Channel.BUFFERED)
    val canvasCommands = canvasCommandChannel.receiveAsFlow()

    // ---- Internal document context ----
    private val currentPageId: String get() = _toolbarState.value.location?.pageId.orEmpty()

    /**
     * The notebook in play, derived rather than stored.
     *
     * Was a `var` seeded from the editor's route and never moved, so page navigation walked that
     * notebook whichever pane had focus — putting one notebook in two panes (STATE-PLAN §2.3).
     * Reading it from the resolved location leaves exactly one writer.
     */
    private val bookId: String? get() = _toolbarState.value.location?.notebookId

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
        val pens = GlobalAppSettings.current.toolbarPens
        // Restore the last-used preset; fall back to the first preset if it was deleted
        // (or the cache predates presets and was discarded by its version gate).
        val preset = pens.find { it.id == settings?.penPresetId } ?: pens.firstOrNull()
        _toolbarState.update {
            it.copy(
                mode = settings?.mode ?: Mode.Draw,
                pen = preset?.pen ?: Pen.BALLPEN,
                penPresetId = preset?.id ?: it.penPresetId,
                eraser = settings?.eraser ?: Eraser.PEN,
                isToolbarOpen = settings?.isToolbarOpen ?: false,
                penSettings = pens.associate { p -> p.id to p.setting() }
                    .ifEmpty { DEFAULT_PEN_SETTINGS }
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

        // 4. Sync-on-close. syncFromPageId honors the "Sync when closing notes" setting. Run on the
        //    application scope so it survives this view's teardown. Downloading here is safe: the
        //    editor is closing, so nothing will overwrite a newer remote copy (P18).
        val closingPageId = currentPageId
        appScope.launch { syncOrchestrator.syncFromPageId(closingPageId) }
    }

    fun createHistory(page: PageView): History = historyFactory.create(page)

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

            is ToolbarAction.ChangePen -> handlePenChange(action.presetId)
            is ToolbarAction.ChangePenSetting ->
                handlePenSettingChange(action.presetId, action.setting)
            is ToolbarAction.ChangeEraser -> handleEraserChange(action.eraser)
            is ToolbarAction.ToggleMenu -> {
                _toolbarState.update { it.copy(isMenuOpen = !it.isMenuOpen) }
//                updateDrawingState() // on focus change is doing this
            }

            is ToolbarAction.ToggleEraserManu -> {
                _toolbarState.update { it.copy(isStrokeSelectionOpen = action.isOpen) }
//                updateDrawingState() // on focus change is doing this
            }

            is ToolbarAction.ToggleBackgroundSelector -> {
                _toolbarState.update { it.copy(isBackgroundSelectorModalOpen = action.isOpen) }
//                updateDrawingState() // on focus change is doing this
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

            is ToolbarAction.SetPagePickerOpen -> {
                _toolbarState.update {
                    it.copy(
                        isPagePickerOpen = action.isOpen,
                        isPagePickerForNewPane = action.isOpen && action.forNewPane,
                    )
                }
                // Raw drawing is global to the panel, so the pen has to be stood down while the
                // picker is up or strokes land on the page behind it.
                updateDrawingState()
            }

            ToolbarAction.ToggleSplit -> handleToggleSplit()
        }
    }

    // --------------------------------------------------------
    // Toolbar Action Handlers (private)
    // --------------------------------------------------------

    private fun handlePenChange(presetId: String) {
        val preset = GlobalAppSettings.current.toolbarPens.find { it.id == presetId } ?: return
        val state = _toolbarState.value
        if (state.mode == Mode.Draw && state.penPresetId == presetId) {
            _toolbarState.update { it.copy(isStrokeSelectionOpen = true) }
        } else {
            _toolbarState.update {
                it.copy(pen = preset.pen, penPresetId = presetId, mode = Mode.Draw)
            }
            saveToolbarState()
        }
        updateDrawingState()
        viewModelScope.launch {
            PaneRegistry.focused.refreshUi.emit(Unit)
        }
    }

    private fun handleEraserChange(eraser: Eraser) {
        _toolbarState.update { it.copy(eraser = eraser) }
        updateDrawingState()
        saveToolbarState()
    }

    /**
     * The preset is the setting: write it back to AppSettings. [ToolbarUiState.penSettings]
     * is updated directly so the drawing pipeline never reads a stale value; the init-block
     * snapshotFlow re-emits the same map (deduped by StateFlow equality) and exists for
     * *other* writers (the settings editor). [GlobalAppSettings] is updated synchronously
     * so rapid StrokeMenu slider changes stay ordered; only the DB write is async.
     */
    private fun handlePenSettingChange(presetId: String, setting: PenSetting) {
        val settings = GlobalAppSettings.current
        val updated = settings.copy(
            toolbarPens = settings.toolbarPens.map {
                if (it.id == presetId) it.copy(color = setting.color, size = setting.strokeSize)
                else it
            }
        )
        GlobalAppSettings.update(updated)
        _toolbarState.update {
            it.copy(penSettings = it.penSettings + (presetId to setting))
        }
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.kvProxy.setAppSettings(updated)
        }
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
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Image import failed: ${e.message}",
                        duration = 3000
                    )
                )
            }
        }
    }

    private fun handleExport(target: ExportTarget, format: ExportFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = exportEngine.export(target, format)
                snackDispatcher.showOrUpdateSnack(SnackConf(text = result, duration = 4000))
            } catch (e: Exception) {
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(
                        text = "Export failed: ${e.message}",
                        duration = 3000
                    )
                )
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

    // --------------------------------------------------------
    // Split view
    // --------------------------------------------------------

    /**
     * The page shown in the second pane, or null for a single pane.
     *
     * The *primary* pane's page is not held here — it stays owned by the editor's own `PageView`,
     * which survives a split so that splitting does not tear down the page being written in. Only
     * the second pane is created and destroyed.
     */
    private val _secondaryPageId = MutableStateFlow<String?>(null)
    val secondaryPageId: StateFlow<String?> = _secondaryPageId.asStateFlow()

    /**
     * A page the surviving pane must adopt after an unsplit, or null.
     *
     * A StateFlow rather than a signal: a zero-buffer `MutableSharedFlow` emit is dropped when
     * nothing is subscribed, which here would silently leave the user on the wrong page (see
     * CLAUDE.md, multi-pane failure mode 2). The view clears it via [onPrimaryPageAdopted] once
     * the load is under way.
     */
    private val _pageToAdoptIntoPrimary = MutableStateFlow<String?>(null)
    val pageToAdoptIntoPrimary: StateFlow<String?> = _pageToAdoptIntoPrimary.asStateFlow()

    // Which pane the user is looking at. Pushed from the view, because focus lives in PaneGroup and
    // the ViewModel has no panes in hand.
    private var activePaneIsSecondary = false
    private var activePanePageId: String? = null

    fun onActivePaneChanged(isSecondary: Boolean, pageId: String) {
        activePaneIsSecondary = isSecondary
        activePanePageId = pageId
        viewModelScope.launch(Dispatchers.IO) { syncToActivePane(pageId) }
    }

    /**
     * Point the toolbar and page navigation at the pane the user is looking at.
     *
     * [bookId] was set once from the editor's route and never moved, so next/previous page always
     * walked *that* notebook and delivered the result to whichever pane had focus. A device trace
     * caught the consequence: after splitting, turning a page in the second pane walked the first
     * pane's notebook into it — the same notebook open twice, which ROADMAP §8 forbids and
     * `PaneGroup` cannot catch, because it only checks page identity at the moment panes change.
     *
     * The notebook is resolved from the page record rather than from the pane's own
     * `OpenPage.notebookId`, which is null until that view's load finishes — acting on it during
     * that window would pick the wrong notebook just as silently.
     */
    private suspend fun syncToActivePane(pageId: String) {
        // Deliberately the same path the route takes, with no route notebook to offer: both derive
        // the notebook from the page record, so focusing a pane and opening the editor cannot
        // disagree about which notebook is in play. Also re-points the background controls, which
        // should act on the pane in focus for the same reason.
        runCatching { loadToolbarState(routeBookId = null, pageId = pageId) }
            .onFailure { log.w("Could not sync the toolbar to page $pageId", it) }
    }

    private fun handleToggleSplit() {
        if (_secondaryPageId.value != null) {
            closeSplit()
        } else {
            // Which note goes beside this one is the point of the action, so ask rather than guess.
            onToolbarAction(ToolbarAction.SetPagePickerOpen(isOpen = true, forNewPane = true))
        }
    }

    /** Create the second pane showing [pageId]. */
    fun openSecondPane(pageId: String) {
        log.i("Opening second pane on $pageId")
        _secondaryPageId.value = pageId
        _toolbarState.update { it.copy(isSplit = true) }
    }

    /**
     * Return to a single pane, keeping the one the user is looking at.
     *
     * When the active pane is the second one, the page it shows is handed to the surviving primary
     * pane — closing the split must not also discard what the user was working on. The primary
     * `PageView` changes page rather than being rebuilt, so its buffer and history survive.
     */
    fun closeSplit() {
        val adopt = activePanePageId?.takeIf { activePaneIsSecondary }
        log.i("Closing split, ${adopt?.let { "adopting $it" } ?: "keeping the primary page"}")
        if (adopt != null) _pageToAdoptIntoPrimary.value = adopt
        _secondaryPageId.value = null
        _toolbarState.update { it.copy(isSplit = false) }
        activePaneIsSecondary = false
    }

    /** The view has started loading the adopted page; clear it so it is not applied twice. */
    fun onPrimaryPageAdopted(pageId: String) {
        _pageToAdoptIntoPrimary.value = null
        // The surviving pane changed page without going through changePage on this ViewModel, so
        // nothing else re-points the toolbar at it — the counter would go on describing the page
        // that pane held before the unsplit.
        viewModelScope.launch(Dispatchers.IO) { syncToActivePane(pageId) }
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
        // It get called three times on canvas creation.
        val shouldBeDrawing = _toolbarState.value.isDrawingAllowed
        _toolbarState.update { it.copy(isDrawing = shouldBeDrawing) }
        log.d("updateDrawingState: Drawing state: $shouldBeDrawing")
        viewModelScope.launch {
            if (shouldBeDrawing)
                DeviceCompat.delayBeforeResumingDrawing()
            CanvasEventBus.isDrawing.emit(shouldBeDrawing)
        }
    }

    // --------------------------------------------------------
    // Book / Page Data
    // --------------------------------------------------------

    /**
     * Loads context data for the toolbar (page number, background info, etc.)
     */
    /**
     * Resolve a page's position from its notebook. The only place a [PageLocation] is built from
     * the database, so index, count and notebook can never be refreshed independently of one
     * another — which is how a stale count outlived its notebook and read "0/3".
     */
    private suspend fun locate(pageId: String, notebookId: String?): PageLocation {
        val pageIds = notebookId
            ?.let { runCatching { appRepository.bookRepository.getById(it) }.getOrNull() }
            ?.pageIds
            ?: emptyList()
        return PageLocation.of(pageId, notebookId, pageIds)
    }

    suspend fun loadToolbarState(routeBookId: String?, pageId: String) {
        log.v("loadBookData: routeBookId=$routeBookId, pageId=$pageId")

        val page = appRepository.pageRepository.getById(pageId)

        if (page == null) {
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = "Could not find page",
                    duration = 3000
                )
            )
            fixNotebook(routeBookId, pageId)
            return
        }

        // The page record is what says which notebook this is; the route only says which one the
        // editor was *opened* on. Resolving them together, from the record, is what stops the two
        // disagreeing — see PageLocation.
        val location = locate(page.id, page.notebookId)
        val bookId = location.notebookId

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
                location = location,
                backgroundType = page.backgroundType,
                backgroundPath = page.background,
                backgroundPageNumber = bgPageNumber
            )
        }

        // Check-on-open (P22): hint if the server has a newer version, so the user doesn't
        // unknowingly edit a stale copy and manufacture an avoidable conflict. Best-effort and
        // off the load path (a cheap conditional GET on the manifest).
        val bookIdForCheck = bookId
        if (bookIdForCheck != null) {
            appScope.launch {
                if (syncOrchestrator.isRemoteNewer(bookIdForCheck)) {
                    val snackId = "remote-newer-$bookIdForCheck"
                    snackDispatcher.showOrUpdateSnack(
                        SnackConf(
                            id = snackId,
                            text = "A newer version of this notebook is on the server. " +
                                    "Sync to get the latest before editing.",
                            duration = 8000,
                            // "Sync now" closes the notebook first (to its pages list), then syncs.
                            // Downloading into the open editor would clobber it (stale in-memory
                            // state); syncing as it closes mirrors the safe sync-on-close path.
                            actions = listOf(
                                "Sync now" to {
                                    snackDispatcher.removeSnack(snackId)
                                    sendUiEvent(EditorUiEvent.NavigateToPages(bookIdForCheck))
                                    appScope.launch {
                                        val progressId = "sync-notebook-$bookIdForCheck"
                                        snackDispatcher.showOrUpdateSnack(
                                            SnackConf(id = progressId, text = "Syncing notebook…", duration = null)
                                        )
                                        val result = syncOrchestrator.syncNotebook(bookIdForCheck)
                                        snackDispatcher.showOrUpdateSnack(
                                            SnackConf(
                                                id = progressId,
                                                text = if (result is AppResult.Success) "Notebook synced"
                                                else "Notebook sync failed",
                                                duration = 3000
                                            )
                                        )
                                    }
                                }
                            )
                        )
                    )
                }
            }
        }
    }

    private fun saveToolbarState() {
        val currentState = _toolbarState.value
        editorSettingCacheManager.setEditorSettings(
            EditorSettingCacheManager.EditorSettings(
                isToolbarOpen = currentState.isToolbarOpen,
                mode = currentState.mode,
                penPresetId = currentState.penPresetId,
                eraser = currentState.eraser,
            )
        )
    }

    /**
     * Attempts to repair potential inconsistencies in the notebook's data structure.
     */
    fun fixNotebook(bookId: String?, pageId: String) {
        log.i("Could not find page, prompting for repair")
        snackDispatcher.showOrUpdateSnack(
            SnackConf(
                text = "Could not find page",
                duration = 60000,
                actions = listOf(
                    "Remove bad page" to {
                        viewModelScope.launch(Dispatchers.IO) {
                            if (bookId != null) {
                                appRepository.bookRepository.removePage(bookId, pageId)
                            }
                            sendUiEvent(EditorUiEvent.NavigateToLibrary(null))
                        }
                    }
                )
            )
        )
    }

    // --------------------------------------------------------
    // Page navigation
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

        if (newPageId == currentPageId) {
            Log.d("EditorView", "Tried to change to same page!")
            val snack = SnackConf(text = "Tried to change to same page!", duration = 4000)
            snackDispatcher.showOrUpdateSnack(snack)
            PaneRegistry.focused.restoreCanvas.emit(Unit)
            return
        }

        Log.d("EditorView", "Page changed to $newPageId")
        val page = appRepository.pageRepository.getById(newPageId)
        val location = locate(newPageId, page?.notebookId)

        // Record where we left off in the notebook the page actually belongs to. This used to
        // write against whichever notebook was open *before* the change, which was harmless while
        // pages could only be turned within one notebook — and wrong as soon as the picker could
        // load a page from another, since it recorded a foreign page as that notebook's open one.
        location.notebookId?.let { appRepository.bookRepository.setOpenPageId(it, newPageId) }

        // Resolved whole. Setting the page id alone would leave the index and count describing the
        // previous notebook until something else refreshed them — the shape that read "0/3".
        _toolbarState.update { it.copy(location = location) }

        // Do NOT sync here: syncing the notebook that is open in the editor could download a
        // newer remote copy and rewrite Room underneath the live in-memory state (P19).
        // Sync is deferred to editor close (see onDispose).
    }

    /**
     * Changes the current page to the one with the specified [id].
     *
     * @param id The unique identifier of the page to switch to.
     */
    fun changePage(id: String) {
        log.d("Changing page to $id, from $currentPageId")
        viewModelScope.launch(Dispatchers.IO) {
            // Update the UI state
            updateOpenedPage(id)

            // Clean the selection state on Main to avoid snapshot violations during composition.
            withContext(Dispatchers.Main.immediate) {
                selectionState.reset()
            }
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

    fun setDrawingStateFromCanvas(isDrawing: Boolean) {
        _toolbarState.update { it.copy(isDrawing = isDrawing) }
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
        // Canonical values live in the default pen presets (ToolbarPen.DEFAULT_PENS) —
        // one source of truth. These are fallbacks only; persisted user presets win.
        val DEFAULT_PEN_SETTINGS = ToolbarPen.defaultPenSettings
    }


    fun showHint(message: String, durationMs: Int = 1500) {
        snackDispatcher.showOrUpdateSnack(
            SnackConf(text = message, duration = durationMs)
        )
    }
}
