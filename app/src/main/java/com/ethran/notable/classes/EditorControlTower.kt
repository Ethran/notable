package com.ethran.notable.classes

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import com.ethran.notable.TAG
import com.ethran.notable.db.selectImagesAndStrokes
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.History
import com.ethran.notable.utils.Mode
import com.ethran.notable.utils.Operation
import com.ethran.notable.utils.PlacementMode
import com.ethran.notable.utils.SimplePointF
import com.ethran.notable.utils.divideStrokesFromCut
import com.ethran.notable.utils.offsetStroke
import com.ethran.notable.utils.pageAreaToCanvasArea
import com.ethran.notable.utils.strokeBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.UUID

class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private val history: History,
    val state: EditorState
) {
    private var scrollInProgress = Mutex()


    fun onSingleFingerVerticalSwipe(startPosition: SimplePointF, delta: Int): Int {
        if (!page.scrolable) return 0
        if (scrollInProgress.isLocked) {
            Log.w(TAG, "Scroll in progress -- skipping")
            return delta
        } // Return unhandled part

        scope.launch {
            scrollInProgress.withLock {
                if (state.mode == Mode.Select) {
                    if (state.selectionState.firstPageCut != null) {
                        onOpenPageCut(delta)
                    } else {
                        onPageScroll(-delta)
                    }
                } else {
                    onPageScroll(-delta)
                }
            }
            DrawCanvas.refreshUi.emit(Unit)
        }
        return 0 // All handled
    }

    private fun onOpenPageCut(offset: Int) {
        if (offset < 0) return
        val cutLine = state.selectionState.firstPageCut!!

        val (_, previousStrokes) = divideStrokesFromCut(page.strokes, cutLine)

        // calculate new strokes to add to the page
        val nextStrokes = previousStrokes.map {
            it.copy(points = it.points.map {
                it.copy(x = it.x, y = it.y + offset)
            }, top = it.top + offset, bottom = it.bottom + offset)
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
        page.drawArea(
            pageAreaToCanvasArea(
                strokeBounds(previousStrokes + nextStrokes), page.scroll
            )
        )
    }

    private suspend fun onPageScroll(delta: Int) {
        page.updateScroll(delta)
    }

    //Now we can have selected images or selected strokes
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
        val addPageScroll = IntOffset(0, scrollPos).toOffset()

        val pastedStrokes = strokes.map {
            offsetStroke(it, offset = addPageScroll).copy(
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
                y = it.y + scrollPos,
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

