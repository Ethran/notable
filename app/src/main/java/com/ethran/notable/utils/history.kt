package com.ethran.notable.utils

import android.graphics.Rect
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.PageView
import com.ethran.notable.classes.SnackConf
import com.ethran.notable.classes.SnackState
import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch


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

class History(coroutineScope: CoroutineScope, pageView: PageView) {

    private var undoList: OperationList = mutableListOf()
    private var redoList: OperationList = mutableListOf()
    private val pageModel = pageView

    // TODO maybe not in a companion object ?
    companion object {
        val historyBus = MutableSharedFlow<HistoryBusActions>()
        suspend fun registerHistoryOperationBlock(operationBlock: OperationBlock) {
            historyBus.emit(HistoryBusActions.RegisterHistoryOperationBlock(operationBlock))
        }

        suspend fun moveHistory(type: UndoRedoType) {
            historyBus.emit(HistoryBusActions.MoveHistory(type))
        }
    }


    init {
        coroutineScope.launch {
            historyBus.collect {
                when (it) {
                    is HistoryBusActions.MoveHistory -> {
                        // Wait for commit to history to complete
                        if(it.type == UndoRedoType.Undo){
                            DrawCanvas.commitCompletion = CompletableDeferred()
                            DrawCanvas.commitHistorySignalImmediately.emit(Unit)
                            DrawCanvas.commitCompletion.await()
                        }
                        val zoneAffected = undoRedo(type = it.type)
                        if (zoneAffected != null) {
                            pageView.drawAreaPageCoordinates(zoneAffected)
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
                        addOperationsToHistory(it.operationBlock)
                    }

                    else -> {}
                }
            }
        }
    }
    @Suppress("unused")
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

            else -> {
                throw (java.lang.Error("Unhandled history operation"))
            }
        }
    }

    private fun undoRedo(type: UndoRedoType): Rect? {
        val originList =
            if (type == UndoRedoType.Undo) undoList else redoList
        val targetList =
            if (type == UndoRedoType.Undo) redoList else undoList

        if (originList.size == 0) return null

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
        undoList.add(operations)
        if (undoList.size > 5) undoList.removeAt(0)
        redoList.clear()
    }
}