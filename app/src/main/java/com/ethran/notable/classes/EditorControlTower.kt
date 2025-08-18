package com.ethran.notable.classes

import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.TAG
import com.ethran.notable.db.selectImagesAndStrokes
import com.ethran.notable.modals.GlobalAppSettings
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.History
import com.ethran.notable.utils.Mode
import com.ethran.notable.utils.Operation
import com.ethran.notable.utils.PlacementMode
import com.ethran.notable.utils.divideStrokesFromCut
import com.ethran.notable.utils.offsetStroke
import com.ethran.notable.utils.strokeBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.UUID

class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private var history: History,
    val state: EditorState
) {
    private var scrollInProgress = Mutex()
    private var scrollJob: Job? = null


    // returns delta if could not scroll, to be added to next request,
    // this ensures that smooth scroll works reliably even if rendering takes to long
    fun processScroll(delta: Offset): Offset {
        if (delta == Offset.Zero) return Offset.Zero
        if (!page.scrollable) return Offset.Zero
        if (scrollInProgress.isLocked) {
            Log.w(TAG, "Scroll in progress -- skipping")
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
            DrawCanvas.refreshUi.emit(Unit)
        }
        return Offset.Zero // All handled
    }

    fun switchPage(id:String)
    {
        state.changePage(id)
        history.cleanHistory()
        page.updatePageID(id)
    }

    fun onPinchToZoom(delta: Float, center: Offset?) {
        // TODO: use center for drawing in right place.
        //      For it to work, it needs to know scroll y -- so we need to transform scroll into OffSet
        scope.launch {
            scrollInProgress.withLock {
                if (GlobalAppSettings.current.simpleRendering)
                    page.simpleUpdateZoom(delta)
                else
                    page.updateZoom(delta, center)
            }
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    // TODO: add description
    private fun onOpenPageCut(offset: Offset) {
        if (offset.x < 0 || offset.y <0) return
        val cutLine = state.selectionState.firstPageCut!!

        val (_, previousStrokes) = divideStrokesFromCut(page.strokes, cutLine)

        // calculate new strokes to add to the page
        val nextStrokes = previousStrokes.map { stroke ->
            stroke.copy(points = stroke.points.map { point ->
                point.copy(x = point.x + offset.x, y = point.y + offset.y)
            }, top = stroke.top + offset.y, bottom = stroke.bottom + offset.y,
            left = stroke.left + offset.x, right = stroke.right + offset.x)
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
                pageId = this.page.id
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
                pageId = this.page.id
            )
        }

        history.addOperationsToHistory(
            operations = listOf(
                Operation.DeleteImage(pastedImages.map { it.id }),
                Operation.DeleteStroke(pastedStrokes.map { it.id }),
            )
        )

        selectImagesAndStrokes(scope, page, state, pastedImages, pastedStrokes)
        state.selectionState.placementMode = PlacementMode.Paste

        showHint("Pasted content from clipboard", scope)
    }
}

