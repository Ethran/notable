package com.ethran.notable.editor.state

import android.graphics.Rect
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.utils.imageBoundsInt
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.utils.logCallStack
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CompletableDeferred


sealed class Operation {
    data class DeleteStroke(val strokeIds: List<String>) : Operation()
    data class AddStroke(val strokes: List<Stroke>) : Operation()
    data class AddImage(val images: List<Image>) : Operation()
    data class DeleteImage(val imageIds: List<String>) : Operation()
}

typealias OperationBlock = List<Operation>
typealias OperationList = MutableList<OperationBlock>

enum class UndoRedoType {
    Undo,
    Redo
}

sealed class HistoryBusActions {
    data class RegisterHistoryOperationBlock(val operationBlock: OperationBlock) :
        HistoryBusActions()

    data class MoveHistory(val type: UndoRedoType) : HistoryBusActions()
}

class History(pageView: PageView) {
    private var undoList: OperationList = mutableListOf()
    private var redoList: OperationList = mutableListOf()
    private val pageModel = pageView

    suspend fun handleHistoryBusActions(actions: HistoryBusActions) {
        when (actions) {
            is HistoryBusActions.MoveHistory -> {
                // Wait for commit to history to complete
                if (actions.type == UndoRedoType.Undo) {
                    DrawCanvas.commitCompletion = CompletableDeferred()
                    DrawCanvas.commitHistorySignalImmediately.emit(Unit)
                    DrawCanvas.commitCompletion.await()
                }
                val zoneAffected = undoRedo(type = actions.type)
                if (zoneAffected != null) {
                    pageModel.drawAreaPageCoordinates(zoneAffected)
                    //moved to refresh after drawing
                    DrawCanvas.refreshUi.emit(Unit)
                } else {
                    SnackState.globalSnackFlow.emit(
                        SnackConf(
                            text = "Nothing to undo/redo",
                            duration = 3000,
                        )
                    )
                }
            }

            is HistoryBusActions.RegisterHistoryOperationBlock -> {
                addOperationsToHistory(actions.operationBlock)
            }

        }
    }


    fun cleanHistory() {
        undoList.clear()
        redoList.clear()
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
        }
    }

    private fun undoRedo(type: UndoRedoType): Rect? {
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

    fun addOperationsToHistory(operations: OperationBlock) {
        if (operations.isEmpty()) {
            logCallStack("History: No operations to add to history")
            return
        }
        undoList.add(operations)
        if (undoList.size > 5) undoList.removeAt(0)
        redoList.clear()
    }
}