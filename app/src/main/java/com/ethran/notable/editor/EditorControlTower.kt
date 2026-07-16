package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
import com.ethran.notable.gestures.GestureActions
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
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
) : GestureActions {
    private var scrollInProgress = Mutex()
    private val logEditorControlTower = ShipBook.getLogger("EditorControlTower")
    private var changePageObserverJob: Job? = null

    // Accumulated, not-yet-rendered scroll delta in screen coordinates. Input events add
    // into this; a single consumer coroutine drains and renders it. StateFlow conflation
    // means a burst of input collapses to one render pass per frame the renderer can keep
    // up with.
    private val pendingScroll = MutableStateFlow(Offset.Zero)
    private var scrollConsumerJob: Job? = null

    fun registerObservers() {
        startScrollConsumer()
        if (changePageObserverJob?.isActive == true) return

        changePageObserverJob = scope.launch {
            CanvasEventBus.changePage.collect { pageId ->
                logEditorControlTower.d("Change to page $pageId")

                // Switch to Main thread for Compose state mutations
                withContext(Dispatchers.Main) {
                    viewModel.changePage(pageId)
                    history.cleanHistory()
                }
                // no need for this, we are listening for change of current page,
                // in EditorView
//                page.changePage(pageId)
                refreshScreen()
            }
        }
    }

    // TODO: remove it, change to proper solution
    fun unregisterObservers() {
        changePageObserverJob?.cancel()
        changePageObserverJob = null
        scrollConsumerJob?.cancel()
        scrollConsumerJob = null
    }

    /**
     * Submit a scroll/drag delta (screen coordinates) for rendering. Non-blocking: the
     * delta is accumulated and consumed by [startScrollConsumer]; bursts coalesce automatically.
     */
    override fun requestScroll(delta: Offset) {
        if (delta == Offset.Zero) return
        if (!page.isTransformationAllowed) return
        pendingScroll.update { it + delta }
    }

    /**
     * Single consumer that drains [pendingScroll] and performs the actual bitmap shift.
     * Draining atomically resets the accumulator to zero, so any input that arrives while
     * a render is in flight piles onto a fresh zero and is picked up on the next pass —
     * coalescing a flood of touch samples into one shift per render.
     */
    private fun startScrollConsumer() {
        if (scrollConsumerJob?.isActive == true) return
        scrollConsumerJob = scope.launch(Dispatchers.Main.immediate) {
            pendingScroll.collect {
                val delta = pendingScroll.getAndUpdate { Offset.Zero }
                if (delta == Offset.Zero) return@collect
                scrollInProgress.withLock {
                    if (viewModel.toolbarState.value.mode == Mode.Select &&
                        viewModel.selectionState.firstPageCut != null
                    ) {
                        onOpenPageCut(delta / page.zoomLevel.value)
                    } else {
                        onPageScroll(-delta)
                    }
                }
                CanvasEventBus.refreshUiImmediately.emit(Unit)
            }
        }
    }


    override fun setIsDrawing(value: Boolean) {
        if (viewModel.toolbarState.value.isDrawing == value) {
            logEditorControlTower.w("IsDrawing already set to $value")
            return
        }
        scope.launch { CanvasEventBus.isDrawing.emit(value) }
    }

    override fun toggleTool() {
        val mode = viewModel.toolbarState.value.mode
        viewModel.onToolbarAction(ToolbarAction.ChangeMode(if (mode == Mode.Draw) Mode.Erase else Mode.Draw))
    }

    override fun toggleZen() {
        viewModel.onToolbarAction(ToolbarAction.ToggleToolbar)
    }

    fun getSnapshotOfSelectionState(): SelectionState {
        return viewModel.selectionState
    }

    fun getSelectedBitmap(): Bitmap {
        return requireNotNull(viewModel.selectionState.selectedBitmap)
    }

    override fun goToNextPage() {
        logEditorControlTower.i("Going to next page")
        viewModel.goToNextPage()
        history.cleanHistory()
    }

    override fun goToPreviousPage() {
        logEditorControlTower.i("Going to previous page")
        viewModel.goToPreviousPage()
        history.cleanHistory()
    }

    override fun undo() {
        scope.launch {
            logEditorControlTower.i("Undo called")
            history.undo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    override fun redo() {
        scope.launch {
            logEditorControlTower.i("Redo called")
            history.redo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    override fun onPinchToZoom(delta: Float, center: Offset?) {
        if (!page.isTransformationAllowed) return
        // Zooming under an active selection (floating bitmap, in-progress page
        // cut) would desync the screen-space overlay from the strokes below it;
        // with the select tool merely armed, zooming is fine.
        if (viewModel.selectionState.isNonEmpty() || viewModel.selectionState.firstPageCut != null)
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

    override fun showHint(text: String) = viewModel.showHint(text)

    override fun selectRectangle(rect: Rect) {
        // Take shared ownership of the EPD animation mode now, before the
        // gesture receiver releases its handle: the selection flow runs
        // asynchronously, and the overlap keeps fast refresh on across the
        // hand-over. Released in SelectionState.reset() or by Select.kt if
        // the rectangle selects nothing.
        viewModel.selectionState.holdRefresh()
        scope.launch {
            CanvasEventBus.rectangleToSelectByGesture.emit(rect)
        }
    }

    override fun redrawCanvas() {
        scope.launch {
            CanvasEventBus.forceUpdate.emit(null)
        }
    }
}
