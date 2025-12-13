package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.HistoryBusActions
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.state.UndoRedoType
import com.ethran.notable.editor.utils.divideStrokesFromCut
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.editor.utils.selectImagesAndStrokes
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.sync.SyncEngine
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.ui.showHint
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.UUID

class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private var history: History,
    private val state: EditorState,
    private val context: Context
) {
    private var scrollInProgress = Mutex()
    private var scrollJob: Job? = null
    private val logEditorControlTower = ShipBook.getLogger("EditorControlTower")


    companion object {
        val changePage = MutableSharedFlow<String>(extraBufferCapacity = 1)

    }

    fun registerObservers() {
        scope.launch {
            changePage.collect { pageId ->
                logEditorControlTower.d("Change to page $pageId")
                switchPage(pageId)
                page.changePage(pageId)
                refreshScreen()
            }
        }
    }

    // returns delta if could not scroll, to be added to next request,
    // this ensures that smooth scroll works reliably even if rendering takes to long
    fun processScroll(delta: Offset): Offset {
        if (delta == Offset.Zero) return Offset.Zero
        if (!page.isTransformationAllowed) return Offset.Zero
        if (scrollInProgress.isLocked) {
            logEditorControlTower.w("Scroll in progress -- skipping")
            return delta
        } // Return unhandled part

        scrollJob = scope.launch(Dispatchers.Main.immediate) {
            scrollInProgress.withLock {
                val scaledDelta = (delta / page.zoomLevel.value)
                if (state.mode == Mode.Select) {
                    if (state.selectionState.firstPageCut != null) {
                        onOpenPageCut(scaledDelta)
                    } else {
                        onPageScroll(-delta)
                    }
                } else {
                    onPageScroll(-delta)
                }
            }
            DrawCanvas.refreshUiImmediately.emit(Unit)
        }
        return Offset.Zero // All handled
    }

    /**
     * Switches the currently active page.
     *
     * This function updates the editor state, clears the undo/redo history,
     * and instructs the page view to load and display the content of the new page.
     *
     * @param id The unique identifier of the page to switch to.
     */
    private fun switchPage(id: String) {
        // Trigger sync on the page we're leaving (if enabled)
        val settings = GlobalAppSettings.current
        if (settings.syncSettings.syncEnabled && settings.syncSettings.syncOnNoteClose) {
            val oldPageId = state.currentPageId
            scope.launch(Dispatchers.IO) {
                triggerSyncForPage(oldPageId)
            }
        }

        state.changePage(id)
        history.cleanHistory()
        page.changePage(id)
    }

    /**
     * Trigger sync for a specific page's notebook.
     */
    private suspend fun triggerSyncForPage(pageId: String?) {
        if (pageId == null) return

        try {
            val appRepository = com.ethran.notable.data.AppRepository(context)
            val pageEntity = appRepository.pageRepository.getById(pageId) ?: return
            pageEntity.notebookId?.let { notebookId ->
                SyncLogger.i("EditorSync", "Auto-syncing on page close")
                SyncEngine(context).syncNotebook(notebookId)
            }
        } catch (e: Exception) {
            SyncLogger.e("EditorSync", "Auto-sync failed: ${e.message}")
        }
    }

    fun setIsDrawing(value: Boolean) {
        if (state.isDrawing == value) {
            logEditorControlTower.w("IsDrawing already set to $value")
            return
        }
        state.isDrawing = value
    }

    fun toggleTool() {
        state.mode = if (state.mode == Mode.Draw) Mode.Erase else Mode.Draw
    }

    fun toggleZen() {
        state.isToolbarOpen = !state.isToolbarOpen
    }

    fun getSnapshotOfSelectionState(): SelectionState {
        return state.selectionState
    }

    fun getSelectedBitmap(): Bitmap {
        return state.selectionState.selectedBitmap!!
    }

    fun goToNextPage() {
        logEditorControlTower.i("Going to next page")
        val next = state.getNextPage()
        if (next != null)
            switchPage(next)

    }

    fun goToPreviousPage() {
        logEditorControlTower.i("Going to previous page")
        val previous = state.getPreviousPage()
        if (previous != null)
            switchPage(previous)
    }

    fun undo() {
        scope.launch {
            logEditorControlTower.i("Undo called")
            history.handleHistoryBusActions(HistoryBusActions.MoveHistory(UndoRedoType.Undo))
//            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    fun redo() {
        scope.launch {
            logEditorControlTower.i("Redo called")
            history.handleHistoryBusActions(HistoryBusActions.MoveHistory(UndoRedoType.Redo))
//            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    fun onPinchToZoom(delta: Float, center: Offset?) {
        if (!page.isTransformationAllowed) return
        if (state.mode == Mode.Select)
            return
        scope.launch {
            scrollInProgress.withLock {
                if (GlobalAppSettings.current.simpleRendering || !GlobalAppSettings.current.continuousZoom)
                    page.simpleUpdateZoom(delta)
                else
                    page.updateZoom(delta, center)
            }
            DrawCanvas.refreshUiImmediately.emit(Unit)
        }
    }

    fun resetZoomAndScroll() {
        scope.launch {
            page.scroll = Offset(0f, page.scroll.y)
            page.applyZoomAndRedraw(1f)
            // Request UI update
            DrawCanvas.refreshUiImmediately.emit(Unit)
        }
    }

    // TODO: add description
    private fun onOpenPageCut(offset: Offset) {
        if (offset.x < 0 || offset.y < 0) return
        val cutLine = state.selectionState.firstPageCut!!

        val (_, previousStrokes) = divideStrokesFromCut(page.strokes, cutLine)

        // calculate new strokes to add to the page
        val nextStrokes = previousStrokes.map { stroke ->
            stroke.copy(
                points = stroke.points.map { point ->
                    point.copy(x = point.x + offset.x, y = point.y + offset.y)
                }, top = stroke.top + offset.y, bottom = stroke.bottom + offset.y,
                left = stroke.left + offset.x, right = stroke.right + offset.x
            )
        }

        // remove and paste
        page.removeStrokes(strokeIds = previousStrokes.map { it.id })
        page.addStrokes(nextStrokes)

        // commit to history
        history.addOperationsToHistory(
            listOf(
                Operation.DeleteStroke(nextStrokes.map { it.id }),
                Operation.AddStroke(previousStrokes)
            )
        )

        state.selectionState.reset()
        page.drawAreaScreenCoordinates(strokeBounds(previousStrokes + nextStrokes))
    }

    private suspend fun onPageScroll(dragDelta: Offset) {
        // scroll is in Page coordinates
        if (GlobalAppSettings.current.simpleRendering)
            page.simpleUpdateScroll(dragDelta)
        else
            page.updateScroll(dragDelta)
    }


    // when selection is moved, we need to redraw canvas
    fun applySelectionDisplace() {
        val operationList = state.selectionState.applySelectionDisplace(page)
        if (!operationList.isNullOrEmpty()) {
            history.addOperationsToHistory(operationList)
        }
        scope.launch {
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    fun deleteSelection() {
        val operationList = state.selectionState.deleteSelection(page)
        history.addOperationsToHistory(operationList)
        state.isDrawing = true
        scope.launch {
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    fun changeSizeOfSelection(scale: Int) {
        if (!state.selectionState.selectedImages.isNullOrEmpty())
            state.selectionState.resizeImages(scale, scope, page)
        if (!state.selectionState.selectedStrokes.isNullOrEmpty())
            state.selectionState.resizeStrokes(scale, scope, page)
        // Emit a refresh signal to update UI
        scope.launch {
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    fun duplicateSelection() {
        // finish ongoing movement
        applySelectionDisplace()
        state.selectionState.duplicateSelection()

    }

    fun cutSelectionToClipboard(context: Context) {
        state.clipboard = state.selectionState.selectionToClipboard(page.scroll, context)
        deleteSelection()
        showHint("Content cut to clipboard", scope)
    }

    fun copySelectionToClipboard(context: Context) {
        state.clipboard = state.selectionState.selectionToClipboard(page.scroll, context)
    }


    fun pasteFromClipboard() {
        // finish ongoing movement
        applySelectionDisplace()

        val (strokes, images) = state.clipboard ?: return

        val now = Date()
        val scrollPos = page.scroll

        val pastedStrokes = strokes.map {
            offsetStroke(it, offset = scrollPos).copy(
                // change the pasted strokes' ids - it's a copy
                id = UUID
                    .randomUUID()
                    .toString(),
                createdAt = now,
                // set the pageId to the current page
                pageId = this.page.currentPageId
            )
        }

        val pastedImages = images.map {
            it.copy(
                // change the pasted images' ids - it's a copy
                id = UUID
                    .randomUUID()
                    .toString(),
                x = it.x + scrollPos.x.toInt(),
                y = it.y + scrollPos.y.toInt(),
                createdAt = now,
                // set the pageId to the current page
                pageId = this.page.currentPageId
            )
        }

        selectImagesAndStrokes(scope, page, state, pastedImages, pastedStrokes)
        state.selectionState.placementMode = PlacementMode.Paste

        showHint("Pasted content from clipboard", scope)
    }
}

