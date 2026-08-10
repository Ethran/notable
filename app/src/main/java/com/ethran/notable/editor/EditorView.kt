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
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.ui.EditorSurface
import com.ethran.notable.editor.ui.HorizontalScrollIndicator
import com.ethran.notable.editor.ui.PanePagePicker
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

        // The second pane is user-controlled: the editor opens single-pane and splits when asked.
        // It used to auto-open the next page in the same notebook, which ROADMAP §8 now forbids —
        // a notebook may be open in at most one pane.
        val secondaryPageId by viewModel.secondaryPageId.collectAsStateWithLifecycle()

        // Here we load initial page into the memory.
        //
        // Deliberately not keyed on the split: the primary pane survives splitting and unsplitting,
        // so the page being written in is never torn down and rebuilt. Only the second pane is
        // created and destroyed.
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

        // All panes share one surface because the Onyx firmware allows a single raw-drawing owner.
        val pane = remember(page, history) { Pane(page, history) }

        // Second pane, shown side by side. Fixed 50/50 with no draggable divider yet — the
        // divider is a separate change, and landing geometry and routing first keeps the number of
        // things that can be wrong small.
        //
        // Two views of the *same* document are not supported — each pane owns its own bitmap and
        // History, so they cannot be kept coherent — and PaneGroup rejects that outright. It must
        // not use the shared window-bitmap cache, which is keyed by page id and would hand both
        // views the same Bitmap.
        val secondPane = secondaryPageId?.let { secondId ->
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

        // Hands the second pane's window bitmap back and releases its page pin. Without this the
        // page it showed stays pinned against eviction for the life of the editor — the primary
        // pane's disposal below covers only itself.
        DisposableEffect(secondPane) {
            onDispose { secondPane?.page?.disposeOldPage() }
        }

        // One group for the editor's life. Splitting mutates it rather than replacing it: a new
        // group meant rebuilding the canvas to match, which destroyed the SurfaceView, and the
        // replacement surface took six seconds to arrive — blank screen until a rotation forced it.
        val paneGroup = remember { PaneGroup(listOf(pane)) }

        // Applied to paneGroup inside EditorSurface's update block, so the group is current
        // before the canvas reconciles against it.
        val panes = listOfNotNull(pane, secondPane)


        // Focus lives in PaneGroup; the ViewModel needs it to decide which pane an unsplit keeps.
        LaunchedEffect(paneGroup, paneGroup.active) {
            viewModel.onActivePaneChanged(
                isSecondary = paneGroup.active !== pane,
                pageId = paneGroup.active.pageId,
            )
        }

        // After an unsplit that kept the second pane's page, the surviving primary pane adopts it.
        val pageToAdopt by viewModel.pageToAdoptIntoPrimary.collectAsStateWithLifecycle()
        LaunchedEffect(pageToAdopt) {
            pageToAdopt?.let { adopted ->
                page.changePage(adopted, reason = "unsplit-adopt")
                viewModel.onPrimaryPageAdopted()
            }
        }

        // Keyed on paneGroup. Previously this had no keys at all, so it captured the first
        // PaneGroup permanently — correct only while the pane set could never change. Splitting
        // makes it change, and a stale control tower would keep its per-pane changePage observers
        // pointed at the discarded group.
        val editorControlTower = remember(paneGroup) {
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

        // The control tower outlives a split now, so its per-pane changePage observers have to be
        // rebound by hand. Without this a pane added by splitting has nothing collecting its bus,
        // and a page chosen for it would silently never load.
        LaunchedEffect(panes, editorControlTower) {
            editorControlTower.rebindPaneObservers()
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

        // Collect Canvas Commands from ViewModel.
        //
        // Keyed on the control tower, not Unit: it is rebuilt when the pane set changes, and a
        // collector started against the old one would drive undo, redo and paste on the discarded
        // PaneGroup — silently acting on the wrong pane.
        LaunchedEffect(editorControlTower) {
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
            snapshotFlow { toolbarState.location?.pageId }.filterNotNull().distinctUntilChanged()
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
            // Built once and never rebuilt. It reconciles to the pane set through its update
            // block instead — see EditorSurface.
            //
            // This was briefly a key(paneGroup) wrapper, so that a changed pane set discarded the
            // canvas and Compose ordered disposal before registration for free. It cost the
            // surface: a device trace showed surfaceDestroyed at 11:51:48.804 and the replacement
            // surfaceCreated only at 11:51:54.808 — six seconds of blank screen, and then only
            // because a rotation forced it. Whatever a rebuild buys is not worth that.
            EditorSurface(
                viewModel = viewModel,
                paneGroup = paneGroup,
                panes = panes,
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

            // Composed last so it sits above the toolbar and the canvas chrome. Drawing is stood
            // down while it is open (see ToolbarUiState.isDrawingAllowed) because raw drawing is
            // global to the panel — without that, pen strokes land on the page behind the sheet.
            if (toolbarState.isPagePickerOpen) {
                PanePagePicker(
                    paneGroup = paneGroup,
                    forNewPane = toolbarState.isPagePickerForNewPane,
                    onClose = {
                        viewModel.onToolbarAction(ToolbarAction.SetPagePickerOpen(false))
                    },
                    onCreatePane = viewModel::openSecondPane,
                    goToFolder = goToLibrary,
                )
            }
        }
    }
}
