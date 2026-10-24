package com.ethran.notable.editor.state

import android.graphics.Rect
import com.ethran.notable.R
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.imageBoundsInt
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.utils.logCallStack
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CompletableDeferred

private const val MAX_UNDO_STEPS: Int = 20 // Maximum number of operations stored in the undo/redo history.

sealed class Operation {
    data class DeleteStroke(val strokeIds: List<String>) : Operation()
    data class AddStroke(val strokes: List<Stroke>) : Operation()
    data class AddImage(val images: List<Image>) : Operation()
    data class DeleteImage(val imageIds: List<String>) : Operation()

    // In-place update of existing entities (same ids): move, resize, recolour, … Its inverse is
    // another Update carrying the previous values, so undo/redo never delete-then-reinsert the same
    // id (which raced to a UNIQUE(Image.id) crash).
    data class UpdateStroke(val strokes: List<Stroke>) : Operation()
    data class UpdateImage(val images: List<Image>) : Operation()
}

typealias OperationBlock = List<Operation>
typealias OperationList = MutableList<OperationBlock>

enum class UndoRedoType {
    Undo,
    Redo
}

sealed class HistoryBusActions {
    data class RegisterHistoryOperationBlock(val pageId: String, val operationBlock: OperationBlock) :
        HistoryBusActions()

    data class MoveHistory(val type: UndoRedoType) : HistoryBusActions()
}

/**
 * Undo and redo for the page shown. The history belongs to one page: it starts over once edits or
 * undo happen on another page. A step reaching the history just after its page was left, as drawn
 * strokes do, still joins that page's history, unless another page's has started meanwhile. So
 * undo never applies one page's operations to another, nor skips a step.
 */
class History @AssistedInject constructor(
    @Assisted private val pageView: PageView,
    private val appEventBus: AppEventBus
) {
    private var undoList: OperationList = mutableListOf()
    private var redoList: OperationList = mutableListOf()
    private val pageModel = pageView

    // The page [undoList] and [redoList] are for.
    private var historyPageId: String? = null

    suspend fun handleHistoryBusActions(actions: HistoryBusActions) {
        when (actions) {
            is HistoryBusActions.MoveHistory -> {
                // Wait for commit to history to complete
                if (actions.type == UndoRedoType.Undo) {
                    CanvasEventBus.commitCompletion = CompletableDeferred()
                    CanvasEventBus.commitHistorySignalImmediately.emit(Unit)
                    CanvasEventBus.commitCompletion.await()
                }
                val zoneAffected = undoRedo(type = actions.type)
                if (zoneAffected != null) {
                    pageModel.drawAreaPageCoordinates(zoneAffected)
                    //moved to refresh after drawing
                    CanvasEventBus.refreshUi.emit(Unit)
                } else {
                    val message = when (actions.type) {
                        UndoRedoType.Undo -> pageView.context.getString(R.string.no_undo_to_perform)
                        UndoRedoType.Redo -> pageView.context.getString(R.string.no_redo_to_perform)
                    }
                    appEventBus.emit(AppEvent.ActionHint(message, 3000))
                }
            }

            is HistoryBusActions.RegisterHistoryOperationBlock -> {
                addOperationsToHistory(actions.pageId, actions.operationBlock)
            }

        }
    }

    suspend fun undo() {
        handleHistoryBusActions(HistoryBusActions.MoveHistory(UndoRedoType.Undo))
    }

    suspend fun redo() {
        handleHistoryBusActions(HistoryBusActions.MoveHistory(UndoRedoType.Redo))
    }


    // Starts the history over if it is for another page than [pageId].
    private fun useHistoryOf(pageId: String) {
        if (historyPageId == pageId) return
        undoList.clear()
        redoList.clear()
        historyPageId = pageId
    }

    private fun treatOperation(operation: Operation): Pair<Operation, Rect> {
        when (operation) {
            is Operation.AddStroke -> {
                pageModel.addStrokes(operation.strokes)
                return Operation.DeleteStroke(strokeIds = operation.strokes.map { it.id }) to strokeBounds(
                    operation.strokes
                )
            }

            is Operation.DeleteStroke -> {
                val strokes = pageModel.getStrokes(operation.strokeIds).filterNotNull()
                pageModel.removeStrokes(operation.strokeIds)
                return Operation.AddStroke(strokes = strokes) to strokeBounds(strokes)
            }

            is Operation.AddImage -> {
                pageModel.addImage(operation.images)
                return Operation.DeleteImage(imageIds = operation.images.map { it.id }) to imageBoundsInt(
                    operation.images
                )
            }

            is Operation.DeleteImage -> {
                val images = pageModel.getImages(operation.imageIds).filterNotNull()
                pageModel.removeImages(operation.imageIds)
                return Operation.AddImage(images = images) to imageBoundsInt(images)
            }

            is Operation.UpdateStroke -> {
                // Snapshot current values first — that's the inverse. Then apply the new values.
                val previous = pageModel.getStrokes(operation.strokes.map { it.id }).filterNotNull()
                pageModel.updateStrokes(operation.strokes)
                return Operation.UpdateStroke(strokes = previous) to
                        strokeBounds(operation.strokes + previous)
            }

            is Operation.UpdateImage -> {
                val previous = pageModel.getImages(operation.images.map { it.id }).filterNotNull()
                pageModel.updateImages(operation.images)
                return Operation.UpdateImage(images = previous) to
                        imageBoundsInt(operation.images + previous)
            }
        }
    }

    private fun undoRedo(type: UndoRedoType): Rect? {
        useHistoryOf(pageView.currentPageId)
        val originList =
            if (type == UndoRedoType.Undo) undoList else redoList
        val targetList =
            if (type == UndoRedoType.Undo) redoList else undoList

        if (originList.isEmpty()) return null

        val operationBlock = originList.removeAt(originList.lastIndex)
        val revertOperations = mutableListOf<Operation>()
        val zoneAffected = Rect()
        for (operation in operationBlock) {
            val (cancelOperation, thisZoneAffected) = treatOperation(operation = operation)
            revertOperations.add(cancelOperation)
            zoneAffected.union(thisZoneAffected)
        }
        targetList.add(revertOperations.reversed())

        // update the affected zone
        return zoneAffected
    }

    /** Records drawing [strokes] as one step, for each page they were drawn on. */
    fun addDrawnStrokes(strokes: List<Stroke>) {
        strokes.groupBy { it.pageId }.forEach { (pageId, drawn) ->
            addOperationsToHistory(pageId, listOf(Operation.DeleteStroke(drawn.map { it.id })))
        }
    }

    /**
     * Records [operations], made on [pageId]: in its history, or in a new one if [pageId] is shown.
     * Dropped if another page's history has started meanwhile.
     */
    fun addOperationsToHistory(pageId: String, operations: OperationBlock) {
        if (operations.isEmpty()) {
            logCallStack("History: No operations to add to history")
            return
        }
        if (pageId != historyPageId) {
            // Only the page shown starts a history of its own.
            if (pageId != pageView.currentPageId) return
            useHistoryOf(pageId)
        }
        undoList.add(operations)
        if (undoList.size > MAX_UNDO_STEPS) undoList.removeAt(0)
        redoList.clear()
    }

    @AssistedFactory
    interface Factory {
        fun create(pageView: PageView): History
    }
}