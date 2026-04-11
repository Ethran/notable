package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.editor.utils.selectImagesAndStrokes
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private var history: History,
    private val viewModel: EditorViewModel,
    private val clipboardStore: ClipboardStore,
) {
    private var scrollInProgress = Mutex()
    private var scrollJob: Job? = null
    private val logEditorControlTower = ShipBook.getLogger("EditorControlTower")
    private var changePageObserverJob: Job? = null

    fun registerObservers() {
        if (changePageObserverJob?.isActive == true) return

        changePageObserverJob = scope.launch {
            CanvasEventBus.changePage.collect { pageId ->
                logEditorControlTower.d("Change to page $pageId")
                switchPage(pageId)
//                page.changePage(pageId)
                refreshScreen()
//                CanvasEventBus.refreshUi.emit(Unit)
            }
        }
    }

    // TODO: remove it, change to proper solution
    fun unregisterObservers() {
        changePageObserverJob?.cancel()
        changePageObserverJob = null
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
                if (viewModel.toolbarState.value.mode == Mode.Select) {
                    if (viewModel.selectionState.firstPageCut != null) {
                        onOpenPageCut(scaledDelta)
                    } else {
                        onPageScroll(-delta)
                    }
                } else {
                    onPageScroll(-delta)
                }
            }
            CanvasEventBus.refreshUiImmediately.emit(Unit)
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
    private suspend fun switchPage(id: String) {
        // Switch to Main thread for Compose state mutations
        withContext(Dispatchers.Main) {
            viewModel.changePage(id)
            history.cleanHistory()
        }

//        // Switch to (or ensure we are on) IO thread for Database operations
//        withContext(Dispatchers.IO) {
//            page.changePage(id)
//        }
    }

    fun setIsDrawing(value: Boolean) {
        if (viewModel.toolbarState.value.isDrawing == value) {
            logEditorControlTower.w("IsDrawing already set to $value")
            return
        }
        scope.launch { CanvasEventBus.isDrawing.emit(value) }
    }

    fun toggleTool() {
        val mode = viewModel.toolbarState.value.mode
        viewModel.onToolbarAction(ToolbarAction.ChangeMode(if (mode == Mode.Draw) Mode.Erase else Mode.Draw))
    }

    fun toggleZen() {
        viewModel.onToolbarAction(ToolbarAction.ToggleToolbar)
    }

    fun getSnapshotOfSelectionState(): SelectionState {
        return viewModel.selectionState
    }

    fun getSelectedBitmap(): Bitmap {
        return requireNotNull(viewModel.selectionState.selectedBitmap)
    }

    fun goToNextPage() {
        logEditorControlTower.i("Going to next page")
        viewModel.goToNextPage()
        history.cleanHistory()
    }

    fun goToPreviousPage() {
        logEditorControlTower.i("Going to previous page")
        viewModel.goToPreviousPage()
        history.cleanHistory()
    }

    fun undo() {
        scope.launch {
            logEditorControlTower.i("Undo called")
            history.undo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun redo() {
        scope.launch {
            logEditorControlTower.i("Redo called")
            history.redo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun onPinchToZoom(delta: Float, center: Offset?) {
        if (!page.isTransformationAllowed) return
        if (viewModel.toolbarState.value.mode == Mode.Select)
            return
        scope.launch {
            scrollInProgress.withLock {
                if (GlobalAppSettings.current.simpleRendering || !GlobalAppSettings.current.continuousZoom)
                    page.simpleUpdateZoom(delta)
                else
                    page.updateZoom(delta, center)
            }
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    fun resetZoomAndScroll() {
        scope.launch {
            page.scroll = Offset(0f, page.scroll.y)
            page.applyZoomAndRedraw(1f)
            // Request UI update
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    private fun onOpenPageCut(offset: Offset) {
        val cutLine = viewModel.selectionState.firstPageCut ?: return
        val result = page.applyPageCutOffset(cutLine, offset) ?: return

        // commit to history
        history.addOperationsToHistory(
            listOf(
                Operation.DeleteStroke(result.movedStrokes.map { it.id }),
                Operation.AddStroke(result.previousStrokes)
            )
        )

        viewModel.selectionState.reset()
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
        viewModel.selectionState.applySelectionDisplaceAndCommit(page, history)
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun deleteSelection() {
        viewModel.selectionState.deleteSelectionAndCommit(page, history)
        setIsDrawing(true)
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun changeSizeOfSelection(scale: Int) {
        if (!viewModel.selectionState.selectedImages.isNullOrEmpty())
            viewModel.selectionState.resizeImages(scale, page)
        if (!viewModel.selectionState.selectedStrokes.isNullOrEmpty())
            viewModel.selectionState.resizeStrokes(scale, scope, page)
        // Emit a refresh signal to update UI
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun duplicateSelection() {
        // finish ongoing movement
        applySelectionDisplace()
        viewModel.selectionState.duplicateSelection()

    }

    fun cutSelectionToClipboard(context: Context) {
        clipboardStore.set(viewModel.selectionState.selectionToClipboard(page.scroll, context))
        deleteSelection()
        showHint("Content cut to clipboard")
    }

    fun copySelectionToClipboard(context: Context) {
        clipboardStore.set(viewModel.selectionState.selectionToClipboard(page.scroll, context))
    }


    fun pasteFromClipboard() {
        // finish ongoing movement
        applySelectionDisplace()

        val (strokes, images) = clipboardStore.get() ?: return

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

        selectImagesAndStrokes(
            scope = scope,
            page = page,
            viewModel = viewModel,
            imagesToSelect = pastedImages,
            strokesToSelect = pastedStrokes
        )
        viewModel.selectionState.placementMode = PlacementMode.Paste

        showHint("Pasted content from clipboard")
    }

    fun showHint(text: String) = viewModel.showHint(text)
}