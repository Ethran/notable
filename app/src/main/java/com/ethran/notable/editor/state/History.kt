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
private const val MAX_PAGES_WITH_HISTORY: Int = 3

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

private class PageHistory {
    val undoList: OperationList = mutableListOf()
    val redoList: OperationList = mutableListOf()
}

/**
 * Undo and redo for the page shown. Each page has a history of its own, so undo never applies one
 * page's operations to another, and a step reaching the history just after its page was left, as
 * drawn strokes do, still joins that page's history. Only the [MAX_PAGES_WITH_HISTORY] pages most
 * recently edited or undone on keep theirs.
 */
class History @AssistedInject constructor(
    @Assisted private val pageView: PageView,
    private val appEventBus: AppEventBus
) {
    private val pageModel = pageView

    // In access order, so the eldest entry is the page least recently used.
    private val pageHistories =
        object : LinkedHashMap<String, PageHistory>(MAX_PAGES_WITH_HISTORY + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageHistory>) =
                size > MAX_PAGES_WITH_HISTORY
        }

    private suspend fun moveHistory(type: UndoRedoType) {
        // Wait for commit to history to complete
        if (type == UndoRedoType.Undo) {
            CanvasEventBus.commitCompletion = CompletableDeferred()
            CanvasEventBus.commitHistorySignalImmediately.emit(Unit)
            CanvasEventBus.commitCompletion.await()
        }
        // A step reads and writes the page's strokes, so it waits until they are loaded.
        if (!pageView.isPageDataLoaded) return
        val zoneAffected = undoRedo(type = type)
        if (zoneAffected != null) {
            pageModel.drawAreaPageCoordinates(zoneAffected)
            //moved to refresh after drawing
            CanvasEventBus.refreshUi.emit(Unit)
        } else {
            val message = when (type) {
                UndoRedoType.Undo -> pageView.context.getString(R.string.no_undo_to_perform)
                UndoRedoType.Redo -> pageView.context.getString(R.string.no_redo_to_perform)
            }
            appEventBus.emit(AppEvent.ActionHint(message, 3000))
        }
    }

    suspend fun undo() = moveHistory(UndoRedoType.Undo)

    suspend fun redo() = moveHistory(UndoRedoType.Redo)


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
        val history = pageHistories[pageView.currentPageId] ?: return null
        val originList =
            if (type == UndoRedoType.Undo) history.undoList else history.redoList
        val targetList =
            if (type == UndoRedoType.Undo) history.redoList else history.undoList

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

    /** Records [operations], made on [pageId], in that page's history. */
    fun addOperationsToHistory(pageId: String, operations: OperationBlock) {
        if (operations.isEmpty()) {
            logCallStack("History: No operations to add to history")
            return
        }
        val history = pageHistories.getOrPut(pageId) { PageHistory() }
        history.undoList.add(operations)
        if (history.undoList.size > MAX_UNDO_STEPS) history.undoList.removeAt(0)
        history.redoList.clear()
    }

    @AssistedFactory
    interface Factory {
        fun create(pageView: PageView): History
    }
}