package com.ethran.notable.classes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.imageBoundsInt
import com.ethran.notable.utils.offsetImage
import com.ethran.notable.utils.offsetStroke
import com.ethran.notable.utils.pageAreaToCanvasArea
import com.ethran.notable.utils.strokeBounds
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private val history: History,
    val state: EditorState
) {

    fun onSingleFingerVerticalSwipe(startPosition: SimplePointF, delta: Int) {
        if (state.mode == Mode.Select) {
            if (state.selectionState.firstPageCut != null) {
                onOpenPageCut(delta)
            } else {
                onPageScroll(-delta)
            }
        } else {
            onPageScroll(-delta)
        }

        scope.launch { DrawCanvas.refreshUi.emit(Unit) }

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

    private fun onPageScroll(delta: Int) {
        page.updateScroll(delta)
    }

    //Now we can have selected images or selected strokes
    fun applySelectionDisplace() {

        if (state.selectionState.selectionDisplaceOffset == null) return
        if (state.selectionState.selectionRect == null) return

        val selectedStrokes = state.selectionState.selectedStrokes
        val selectedImages = state.selectionState.selectedImages
        val offset = state.selectionState.selectionDisplaceOffset!!
        val finalZone = Rect(state.selectionState.selectionRect!!)
        finalZone.offset(offset.x, offset.y)

        // collect undo operations for strokes and images together, as a single change
        val operationList = mutableListOf<Operation>()

        if (selectedStrokes != null) {

            val displacedStrokes = selectedStrokes.map {
                offsetStroke(it, offset = offset.toOffset())
            }

            if (state.selectionState.placementMode == PlacementMode.Move)
                page.removeStrokes(selectedStrokes.map { it.id })

            page.addStrokes(displacedStrokes)
            page.drawArea(finalZone)


            if (offset.x > 0 || offset.y > 0) {
                // A displacement happened, we can create a history for this
                operationList += Operation.DeleteStroke(displacedStrokes.map { it.id })
                // in case we are on a move operation, this history point re-adds the original strokes
                if (state.selectionState.placementMode == PlacementMode.Move)
                    operationList += Operation.AddStroke(selectedStrokes)
            }
        }
        if (selectedImages != null) {
            Log.i(TAG, "Commit images to history.")

            val displacedImages = selectedImages.map {
                offsetImage(it, offset = offset.toOffset())
            }
            if (state.selectionState.placementMode == PlacementMode.Move)
                page.removeImages(selectedImages.map { it.id })

            page.addImage(displacedImages)
            page.drawArea(finalZone)

            if (offset.x != 0 || offset.y != 0) {
                // TODO: find why sometimes we add two times same operation.
                // A displacement happened, we can create a history for this
                // To undo changes we first remove image
                operationList += Operation.DeleteImage(displacedImages.map { it.id })
                // then add the original images, only if we intended to move it.
                if (state.selectionState.placementMode == PlacementMode.Move)
                    operationList += Operation.AddImage(selectedImages)
            }
        }

        if (operationList.isNotEmpty()) {
            history.addOperationsToHistory(operationList)
        }

        scope.launch {
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    fun deleteSelection() {
        val selectedImages = state.selectionState.selectedImages
        if (!selectedImages.isNullOrEmpty()) {
            val imageIds: List<String> = selectedImages.map { it.id }
            Log.i(TAG, "removing images")
            page.removeImages(imageIds)
        }
        val selectedStrokes = state.selectionState.selectedStrokes
        if (!selectedStrokes.isNullOrEmpty()) {
            val strokeIds: List<String> = selectedStrokes.map { it.id }
            Log.i(TAG, "removing strokes")
            page.removeStrokes(strokeIds)
            history.addOperationsToHistory(
                operations = listOf(
                    Operation.AddStroke(selectedStrokes)
                )
            )
        }

        state.selectionState.reset()
        state.isDrawing = true
        scope.launch {
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    fun changeSizeOfSelection(scale: Int) {
        val selectedImages = state.selectionState.selectedImages?.map { image ->
            image.copy(
                height = image.height + (image.height * scale / 100),
                width = image.width + (image.width * scale / 100)
            )
        }

        // Ensure selected images are not null or empty
        if (selectedImages.isNullOrEmpty()) {
            showHint("For now, strokes cannot be resized")
            return
        }

        state.selectionState.selectedImages = selectedImages
        // Adjust displacement offset by half the size change
        val sizeChange = selectedImages.firstOrNull()?.let { image ->
            IntOffset(
                x = (image.width * scale / 200),
                y = (image.height * scale / 200)
            )
        } ?: IntOffset.Zero

        val pageBounds = imageBoundsInt(selectedImages)
        state.selectionState.selectionRect = pageAreaToCanvasArea(pageBounds, page.scroll)

        state.selectionState.selectionDisplaceOffset =
            state.selectionState.selectionDisplaceOffset?.let { it - sizeChange }
                ?: IntOffset.Zero

        val selectedBitmap = Bitmap.createBitmap(
            pageBounds.width(), pageBounds.height(),
            Bitmap.Config.ARGB_8888
        )
        val selectedCanvas = Canvas(selectedBitmap)
        selectedImages.forEach {
            drawImage(
                page.context,
                selectedCanvas,
                it,
                IntOffset(-it.x, -it.y)
            )
        }

        // set state
        state.selectionState.selectedBitmap = selectedBitmap

        // Emit a refresh signal to update UI
        scope.launch {
            DrawCanvas.refreshUi.emit(Unit)
        }
    }

    fun copySelection() {
        // finish ongoing movement
        applySelectionDisplace()

        // set operation to paste only
        state.selectionState.placementMode = PlacementMode.Paste
        if (!state.selectionState.selectedStrokes.isNullOrEmpty())
        // change the selected stokes' ids - it's a copy
            state.selectionState.selectedStrokes = state.selectionState.selectedStrokes!!.map {
                it.copy(
                    id = UUID
                        .randomUUID()
                        .toString(),
                    createdAt = Date()
                )
            }
        if (!state.selectionState.selectedImages.isNullOrEmpty())
            state.selectionState.selectedImages = state.selectionState.selectedImages!!.map {
                it.copy(
                    id = UUID
                        .randomUUID()
                        .toString(),
                    createdAt = Date()
                )
            }
        // move the selection a bit, to show the copy
        state.selectionState.selectionDisplaceOffset = IntOffset(
            x = state.selectionState.selectionDisplaceOffset!!.x + 50,
            y = state.selectionState.selectionDisplaceOffset!!.y + 50,
        )
    }

    fun cutSelectionToClipboard() {
        val now = Date()

        // Remove the current scroll offset when copying items to the clipboard. When pasting, we
        //   reapply the active scroll offset. This ensures items are not pasted off-screen.
        val scrollPos = page.scroll;
        val removePageScroll = IntOffset(0, -scrollPos).toOffset();

        // Copy selected strokes to clipboard
        val strokes = state.selectionState.selectedStrokes?.map {
            offsetStroke(it, offset = removePageScroll)
        }

        // Copy selected images to clipboard
        val images = state.selectionState.selectedImages?.map {
            it.copy(y = it.y - scrollPos)
        }

        this.state.clipboard = ClipboardContent(
            strokes = strokes ?: emptyList(),
            images = images ?: emptyList(),
        );

        // After copying the selected strokes and images to the clipboard, delete them using the
        // default deletion handler. This makes undo/redo work.
        deleteSelection();

        showHint("Content cut to clipboard")
    }

    fun pasteFromClipboard() {
        // finish ongoing movement
        applySelectionDisplace();

        val (strokes, images) = state.clipboard ?: return;

        val now = Date()
        val scrollPos = page.scroll;
        val addPageScroll = IntOffset(0, scrollPos).toOffset();

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
        };

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

        selectImagesAndStrokes(scope, page, state, pastedImages, pastedStrokes);
        state.selectionState.placementMode = PlacementMode.Paste;

        showHint("Pasted content from clipboard");
    }
    private fun showHint(text: String) {
        scope.launch {
            SnackState.globalSnackFlow.emit(
                SnackConf(
                    text = text,
                    duration = 3000,
                )
            )
        }
    }

}

