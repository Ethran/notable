package com.ethran.notable.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.EditorSettingCacheManager
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.ScrollIndicator
import com.ethran.notable.editor.ui.SelectedBitmap
import com.ethran.notable.editor.ui.toolbar.PositionedToolbar
import com.ethran.notable.gestures.EditorGestureReceiver
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.exportToLinkedFile
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.convertDpToPixel
import com.ethran.notable.ui.theme.InkaTheme
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull

private val log = ShipBook.getLogger("EditorView")

object EditorDestination : NavigationDestination {
    override val route = "editor"

    const val PAGE_ID_ARG = "pageId"
    const val BOOK_ID_ARG = "bookId"

    // Unified route: editor/{pageId}?bookId={bookId}
    val routeWithArgs = "$route/{$PAGE_ID_ARG}?$BOOK_ID_ARG={$BOOK_ID_ARG}"

    /**
     * Helper to create the path. If bookId is null, it just won't be appended.
     */
    fun createRoute(pageId: String, bookId: String? = null): String {
        return "$route/$pageId" + if (bookId != null) "?$BOOK_ID_ARG=$bookId" else ""
    }
}



@Composable
fun EditorView(
    initialPageId: String,
    bookId: String?,
    isQuickNavOpen: Boolean,

    // navigation callbacks
    onPageChange: (String) -> Unit,
    goToLibrary: (folderId: String?) -> Unit,
    goToPages: (bookId: String) -> Unit,
    goToBugReport: () -> Unit,

    // TODO: remove those arguments
    editorSettingCacheManager: EditorSettingCacheManager,
    exportEngine: ExportEngine,
    appRepository: AppRepository,

    viewModel: EditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackManager = LocalSnackContext.current
    val scope = rememberCoroutineScope()

    // Single point of entry for loading book data based on the pageId from Navigation
    // Should not be used for regular page switching
    LaunchedEffect(initialPageId) {
        log.v("EditorView: pageId changed to $initialPageId, loading data")
        viewModel.loadToolbarState(bookId, initialPageId)
    }

    // Sync isQuickNavOpen to ViewModel
    LaunchedEffect(isQuickNavOpen) {
        viewModel.onToolbarAction(ToolbarAction.UpdateQuickNavOpen(isQuickNavOpen))
    }


    BoxWithConstraints {
        val height = convertDpToPixel(this.maxHeight, context).toInt()
        val width = convertDpToPixel(this.maxWidth, context).toInt()

        // Here we load initial page into the memory
        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                appRepository = appRepository,
                currentPageId = initialPageId,
                viewWidth = width,
                viewHeight = height,
                snackManager = snackManager,
            )
        }

        val history = remember {
            History(page)
        }

        // Create EditorState wrapper for backward compatibility
        val editorState = remember(viewModel, page) {
            EditorState(viewModel)
        }

        val editorControlTower = remember {
            EditorControlTower(scope, page, history, editorState)
        }


        // Initialize ViewModel with persisted settings on first composition
        LaunchedEffect(Unit) {
            viewModel.initFromPersistedSettings(editorSettingCacheManager.getEditorSettings())
            viewModel.updateDrawingState()
        }

        DisposableEffect(editorControlTower) {
            editorControlTower.registerObservers()
            onDispose {
                editorControlTower.unregisterObservers()
            }
        }

        // Collect UI Events from ViewModel (navigation )
        LaunchedEffect(Unit) {
            viewModel.uiEvents.collect { event ->
                when (event) {
                    is EditorUiEvent.NavigateToLibrary -> {
                        goToLibrary(event.folderId)
                    }

                    is EditorUiEvent.NavigateToPages -> {
                        goToPages(event.bookId)
                    }

                    EditorUiEvent.NavigateToBugReport -> {
                        goToBugReport()
                    }
                }
            }
        }

        // Collect Canvas Commands from ViewModel
        LaunchedEffect(Unit) {
            viewModel.canvasCommands.collect { command ->
                when (command) {
                    CanvasCommand.Undo -> editorControlTower.undo()
                    CanvasCommand.Redo -> editorControlTower.redo()
                    CanvasCommand.Paste -> editorControlTower.pasteFromClipboard()
                    CanvasCommand.ResetView -> editorControlTower.resetZoomAndScroll()
                    CanvasCommand.ClearAllStrokes -> {
                        CanvasEventBus.clearPageSignal.emit(Unit)
                        snackManager.displaySnack(SnackConf(text = "Cleared all strokes"))
                    }

                    CanvasCommand.RefreshCanvas -> {
                        CanvasEventBus.reloadFromDb.emit(Unit)
                    }

                    is CanvasCommand.CopyImageToCanvas -> {
                        CanvasEventBus.addImageByUri.value = command.uri
                    }
                }
            }
        }

        // Handle Canvas signals in UI
        LaunchedEffect(Unit) {
            CanvasEventBus.closeMenusSignal.collect {
                log.d("Closing all menus")
                viewModel.onToolbarAction(ToolbarAction.CloseAllMenus)
            }
        }

        // Handle focus changes from Canvas
        LaunchedEffect(Unit) {
            CanvasEventBus.onFocusChange.collect { hasFocus ->
                log.d("Canvas has focus: $hasFocus")
                viewModel.onFocusChanged(hasFocus)
            }
        }

        // Collect toolbar state and sync EditorState (keeps snapshotFlow observers in canvas alive)
        val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()
        LaunchedEffect(toolbarState) {
            editorState.syncFrom(toolbarState)
        }

        // Observe pageId changes from ViewModel state for navigation
        LaunchedEffect(viewModel) {
            snapshotFlow { toolbarState.pageId }
                .filterNotNull()
                .distinctUntilChanged()
                .drop(1) // Skip initial emission from loadBookData
                .collect { newPageId ->
                    log.v("EditorView: snapshotFlow detected pageId change to $newPageId, triggering onPageChange")
                    // update the PageView
                    page.changePage(newPageId)

                    // update the navigation state
                    onPageChange(newPageId)
                }
        }

        // Sync PageView state to ViewModel for Toolbar rendering
        val zoomLevel by page.zoomLevel.collectAsStateWithLifecycle()
        val selectionActive = viewModel.selectionState.isNonEmpty()
        LaunchedEffect(
            zoomLevel,
            selectionActive
        ) {
            log.v("EditorView: zoomLevel=$zoomLevel, selectionActive=$selectionActive")
            viewModel.setShowResetView(zoomLevel != 1.0f)
            viewModel.setSelectionActive(selectionActive)
        }

        DisposableEffect(Unit) {
            onDispose {
                // finish selection operation
                viewModel.selectionState.applySelectionDisplace(page)
                if (bookId != null) exportToLinkedFile(
                    exportEngine,
                    bookId,
                    appRepository.bookRepository
                )
                page.disposeOldPage()
            }
        }

        // Persist editor settings when they change
        LaunchedEffect(
            toolbarState.isToolbarOpen,
            toolbarState.pen,
            toolbarState.penSettings,
            toolbarState.mode,
            toolbarState.eraser
        ) {
            log.i("EditorView: saving editor settings")
            editorSettingCacheManager.setEditorSettings(
                EditorSettingCacheManager.EditorSettings(
                    isToolbarOpen = toolbarState.isToolbarOpen,
                    mode = toolbarState.mode,
                    pen = toolbarState.pen,
                    eraser = toolbarState.eraser,
                    penSettings = toolbarState.penSettings
                )
            )
        }


        InkaTheme {
            EditorGestureReceiver(controlTower = editorControlTower)
            EditorSurface(
                appRepository = appRepository, state = editorState, page = page, history = history
            )
            SelectedBitmap(
                context = context, controlTower = editorControlTower
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                ScrollIndicator(viewModel = viewModel, page = page)
            }
            PositionedToolbar(
                viewModel = viewModel, onDrawingStateCheck = { viewModel.updateDrawingState() })
            HorizontalScrollIndicator(viewModel = viewModel, page = page)
        }
    }
}
