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
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.ScrollIndicator
import com.ethran.notable.editor.ui.SelectedBitmap
import com.ethran.notable.editor.ui.toolbar.PositionedToolbar
import com.ethran.notable.gestures.EditorGestureReceiver
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

/** Show a second pane side by side. Temporary switch while the split is being built out. */
private const val DUAL_PANE_PREVIEW = true

/**
 * The pane layout for one editor session, settled before anything is built.
 *
 * [secondPageId] is null when there is no second pane: a loose page, the last page of a notebook,
 * or the preview switched off. Two panes on one document are not supported — see [PaneGroup].
 */
private data class PaneLayout(val secondPageId: String?)

/**
 * Resolves the layout once per (notebook, page), returning null while the lookup is in flight.
 *
 * Deliberately resolved up front. Composing a single-pane canvas and rebuilding it once the second
 * page arrived left observer registries bound to the discarded canvas, so the live pane's signal
 * bus had no subscribers and undo hung on a commit handshake nobody answered.
 */
@Composable
private fun resolvePaneLayout(
    viewModel: EditorViewModel,
    bookId: String?,
    initialPageId: String,
): PaneLayout? {
    val layout by produceState<PaneLayout?>(null, bookId, initialPageId) {
        value = when {
            !DUAL_PANE_PREVIEW -> {
                log.i("Single pane: dual pane off")
                PaneLayout(null)
            }
            bookId == null -> {
                log.i("Single pane: not in a notebook")
                PaneLayout(null)
            }
            else -> {
                val next = viewModel.pageDataManager.getNextPageId(bookId, initialPageId)
                    ?.takeIf { it != initialPageId }
                if (next == null) log.i("Single pane: no page after $initialPageId in $bookId")
                else log.i("Second pane will open $next")
                PaneLayout(next)
            }
        }
    }
    return layout
}

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

        // Resolve the pane layout BEFORE building anything.
        //
        // Everything below — pages, panes, the control tower, the canvas — is constructed from
        // this, so it must be settled first. Building a single-pane canvas and rebuilding it when
        // the second page arrived left registries attached to the discarded canvas, and the live
        // pane's signal bus with zero subscribers.
        val resolved = resolvePaneLayout(viewModel, bookId, initialPageId)
        if (resolved == null) {
            // Still resolving. One frame at most, and drawing nothing beats drawing a layout
            // that is about to change underneath the canvas.
            return@BoxWithConstraints
        }

        // Here we load initial page into the memory
        val page = remember {
            PageView(
                context = context,
                coroutineScope = scope,
                pageDataManager = viewModel.pageDataManager,
                initialPageId = initialPageId,
                viewWidth = width,
                viewHeight = height,
                snackManager = snackManager,
            )
        }

        val history = remember(page) { viewModel.createHistory(page) }

        // One pane today. DrawCanvas takes a list so a second needs no restructuring; all panes
        // share one surface because the Onyx firmware allows a single raw-drawing owner.
        val pane = remember(page, history) { Pane(page, history) }

        // Second pane, shown side by side. Fixed 50/50 with no draggable divider yet — the
        // divider is a separate change, and landing geometry and routing first keeps the number of
        // things that can be wrong small. Flip DUAL_PANE_PREVIEW to return to a single pane.
        //
        // Opens the page after this one. Two views of the *same* document are not supported —
        // each pane owns its own bitmap and History, so they cannot be kept coherent — and
        // PaneGroup rejects that outright. It must not use the shared window-bitmap cache, which
        // is keyed by page id and would hand both views the same Bitmap.
        val secondPane = resolved.secondPageId?.let { secondId ->
            remember(secondId) {
            val secondPage = PageView(
                context = context,
                coroutineScope = scope,
                pageDataManager = viewModel.pageDataManager,
                initialPageId = secondId,
                viewWidth = width / 2,
                viewHeight = height,
                snackManager = snackManager,
                useSharedBitmapCache = false,
            )
            Pane(secondPage, viewModel.createHistory(secondPage))
            }
        }

        val paneGroup = remember(pane, secondPane) { PaneGroup(listOfNotNull(pane, secondPane)) }



        val editorControlTower = remember {
            EditorControlTower(
                scope = scope,
                paneGroup = paneGroup,
                viewModel = viewModel,
                clipboardStore = ClipboardStore,
            )
        }


        // Initialize ViewModel with persisted settings on first composition
        LaunchedEffect(Unit) {
            viewModel.initFromPersistedSettings()
            viewModel.updateDrawingState()
        }

//         val editorControlTower = remember {
//             EditorControlTower(scope, page, history, editorState, context, appRepository).apply { registerObservers() }
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
                        page.events.clearPageSignal.emit(Unit)
                        snackManager.displaySnack(
                            SnackConf(
                                text = "Cleared all strokes",
                                duration = 2000
                            )
                        )
                    }

                    CanvasCommand.RefreshCanvas -> {
                        page.events.reloadFromDb.emit(Unit)
                    }

                    is CanvasCommand.CopyImageToCanvas -> {
                        page.events.addImageByUri.value = command.uri
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
//        LaunchedEffect(Unit) {
//            CanvasEventBus.onFocusChange.collect { hasFocus ->
//                log.d("Canvas has focus: $hasFocus")
//                if (hasFocus)
//                    viewModel.updateDrawingState()
//
//            }
//        }

        val toolbarState by viewModel.toolbarState.collectAsStateWithLifecycle()

        // Observe pageId changes from ViewModel state for navigation
        LaunchedEffect(viewModel) {
            snapshotFlow { toolbarState.pageId }.filterNotNull().distinctUntilChanged()
                .drop(1) // Skip initial emission from loadBookData
                .collect { newPageId ->
                    log.v("EditorView: snapshotFlow detected pageId change to $newPageId, triggering onPageChange")
                    // The pane that received the change has already loaded it (see
                    // EditorControlTower). Applying it to the primary pane here as well would drag
                    // pane one onto a page chosen for pane two.
                    //
                    // UNDER TRACE: EditorControlTower already called changePage on the pane that
                    // received the selection, and for the *active* pane it then calls
                    // viewModel.changePage, whose state update lands here — so this may be a second
                    // load of the page just loaded. changePage has no same-id guard, so that would
                    // re-run onExit against the page being loaded from a second IO coroutine.
                    // Compare the [bus:paneN] and [snapshotFlow] tags in logcat before changing it.
                    paneGroup.active.page.changePage(newPageId, reason = "snapshotFlow")

                    // update the navigation state
                    onPageChange(newPageId)
                }
        }

        // Sync PageView state to ViewModel for Toolbar rendering
        val zoomLevel by page.zoomLevel.collectAsStateWithLifecycle()
        val selectionActive = viewModel.selectionState.isNonEmpty()
        LaunchedEffect(
            zoomLevel, selectionActive
        ) {
            log.v("EditorView: zoomLevel=$zoomLevel, selectionActive=$selectionActive")
            viewModel.setShowResetView(zoomLevel != 1.0f)
            viewModel.setSelectionActive(selectionActive)
        }

        DisposableEffect(Unit) {
            onDispose {
                viewModel.onDispose(page)
            }
        }



        InkaTheme {
            EditorGestureReceiver(actions = editorControlTower)
            // Built exactly once. The pane layout is resolved before this composes, so the canvas
            // is never torn down and rebuilt — rebuilding it stranded observer registries on the
            // discarded instance, leaving the live pane's signal bus with zero subscribers, and
            // undo then hung forever on a commit handshake nobody answered.
            EditorSurface(
                viewModel = viewModel,
                paneGroup = paneGroup,
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
