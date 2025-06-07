package com.ethran.notable.classes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import androidx.core.graphics.createBitmap
import com.ethran.notable.TAG
import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke
import com.ethran.notable.utils.Operation
import com.ethran.notable.utils.PlacementMode
import com.ethran.notable.utils.SimplePointF
import com.ethran.notable.utils.copyBitmapToClipboard
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.imageBoundsInt
import com.ethran.notable.utils.offsetImage
import com.ethran.notable.utils.offsetStroke
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import java.util.Date
import java.util.UUID


class SelectionState {
    // all coordinates should be in page coordinates
    var firstPageCut by mutableStateOf<List<SimplePointF>?>(null)
    var secondPageCut by mutableStateOf<List<SimplePointF>?>(null)
    var selectedStrokes by mutableStateOf<List<Stroke>?>(null)
    var selectedImages by mutableStateOf<List<Image>?>(null)

    // TODO: Bitmap should be change, if scale changes.
    var selectedBitmap by mutableStateOf<Bitmap?>(null)

    var selectionStartOffset by mutableStateOf<IntOffset?>(null)
    var selectionDisplaceOffset by mutableStateOf<IntOffset?>(null)
    var selectionRect by mutableStateOf<Rect?>(null)
    var placementMode by mutableStateOf<PlacementMode?>(null)

    fun reset() {
        selectedStrokes = null
        selectedImages = null
        secondPageCut = null
        firstPageCut = null
        selectedBitmap = null
        selectionRect = null
        selectionStartOffset = null
        selectionDisplaceOffset = null
        placementMode = null
    }

    fun isNonEmpty(): Boolean {
        return !selectedStrokes.isNullOrEmpty() || !selectedImages.isNullOrEmpty()
    }
    fun isResizable(): Boolean {
        return selectedImages?.count() == 1 && selectedStrokes.isNullOrEmpty()
    }

    fun resizeImages(scale: Int, scope: CoroutineScope, page: PageView) {
        val selectedImagesCopy = selectedImages?.map { image ->
            image.copy(
                height = image.height + (image.height * scale / 100),
                width = image.width + (image.width * scale / 100)
            )
        }

        // Ensure selected images are not null or empty
        if (selectedImagesCopy.isNullOrEmpty()) {
            showHint("For now, strokes cannot be resized", scope)
            return
        }

        selectedImages = selectedImagesCopy
        // Adjust displacement offset by half the size change
        val sizeChange = selectedImagesCopy.firstOrNull()?.let { image ->
            IntOffset(
                x = (image.width * scale / 200),
                y = (image.height * scale / 200)
            )
        } ?: IntOffset.Zero

        val pageBounds = imageBoundsInt(selectedImagesCopy)
        selectionRect = page.toScreenCoordinates(pageBounds)

        selectionDisplaceOffset =
            selectionDisplaceOffset?.let { it - sizeChange }
                ?: IntOffset.Zero

        val selectedBitmapNew = createBitmap(pageBounds.width(), pageBounds.height())
        val selectedCanvas = Canvas(selectedBitmapNew)
        selectedImagesCopy.forEach {
            drawImage(
                page.context,
                selectedCanvas,
                it,
                IntOffset(-it.x, -it.y)
            )
        }

        // set state
        selectedBitmap = selectedBitmapNew
    }

    @Suppress("UNUSED_PARAMETER")
    fun resizeStrokes(scale: Int, scope: CoroutineScope, page: PageView) {
        //TODO: implement this
    }

    fun deleteSelection(page: PageView): List<Operation> {
        val operationList = listOf<Operation>()
        val selectedImagesToRemove = selectedImages
        if (!selectedImagesToRemove.isNullOrEmpty()) {
            val imageIds: List<String> = selectedImagesToRemove.map { it.id }
            Log.i(TAG, "removing images")
            page.removeImages(imageIds)
        }
        val selectedStrokesToRemove = selectedStrokes
        if (!selectedStrokesToRemove.isNullOrEmpty()) {
            val strokeIds: List<String> = selectedStrokesToRemove.map { it.id }
            Log.i(TAG, "removing strokes")
            page.removeStrokes(strokeIds)
            operationList.plus(Operation.AddStroke(selectedStrokesToRemove))
        }
        reset()
        return operationList
    }

    fun duplicateSelection() {
        // set operation to paste only
        placementMode = PlacementMode.Paste
        if (!selectedStrokes.isNullOrEmpty())
        // change the selected stokes' ids - it's a copy
            selectedStrokes = selectedStrokes!!.map {
                it.copy(
                    id = UUID
                        .randomUUID()
                        .toString(),
                    createdAt = Date()
                )
            }
        if (!selectedImages.isNullOrEmpty())
            selectedImages = selectedImages!!.map {
                it.copy(
                    id = UUID
                        .randomUUID()
                        .toString(),
                    createdAt = Date()
                )
            }
        // move the selection a bit, to show the copy
        selectionDisplaceOffset = IntOffset(
            x = selectionDisplaceOffset!!.x + 50,
            y = selectionDisplaceOffset!!.y + 50,
        )
    }

    // Moves strokes, and redraws canvas.
    fun applySelectionDisplace(page: PageView): List<Operation>? {

        if (selectionDisplaceOffset == null) return null
        if (selectionRect == null) return null

        // get snapshot of the selection
        val selectedStrokesCopy = selectedStrokes
        val selectedImagesCopy = selectedImages
        val offset = selectionDisplaceOffset!!
        val finalZone = selectionRect!!
        finalZone.offset(offset.x, offset.y)

        // collect undo operations for strokes and images together, as a single change
        val operationList = mutableListOf<Operation>()

        if (!selectedStrokesCopy.isNullOrEmpty()) {
            val displacedStrokes = selectedStrokesCopy.map {
                offsetStroke(it, offset = offset.toOffset())
            }

            if (placementMode == PlacementMode.Move)
                page.removeStrokes(selectedStrokesCopy.map { it.id })

            page.addStrokes(displacedStrokes)


            if (offset.x > 0 || offset.y > 0) {
                // A displacement happened, we can create a history for this
                operationList += Operation.DeleteStroke(displacedStrokes.map { it.id })
                // in case we are on a move operation, this history point re-adds the original strokes
                if (placementMode == PlacementMode.Move)
                    operationList += Operation.AddStroke(selectedStrokesCopy)
            }
        }
        if (!selectedImagesCopy.isNullOrEmpty()) {
            Log.i(TAG, "Commit images to history.")

            val displacedImages = selectedImagesCopy.map {
                offsetImage(it, offset = offset.toOffset())
            }
            if (placementMode == PlacementMode.Move)
                page.removeImages(selectedImagesCopy.map { it.id })

            page.addImage(displacedImages)

            if (offset.x != 0 || offset.y != 0) {
                // TODO: find why sometimes we add two times same operation.
                // A displacement happened, we can create a history for this
                // To undo changes we first remove image
                operationList += Operation.DeleteImage(displacedImages.map { it.id })
                // then add the original images, only if we intended to move it.
                if (placementMode == PlacementMode.Move)
                    operationList += Operation.AddImage(selectedImagesCopy)
            }
        }
        page.drawAreaPageCoordinates(finalZone)
        return operationList
    }

    fun selectionToClipboard(scrollPos: Int, context: Context): ClipboardContent {
        val removePageScroll = IntOffset(0, -scrollPos).toOffset()

        val strokes = selectedStrokes?.map {
            offsetStroke(it, offset = removePageScroll)
        }

        val images = selectedImages?.map {
            it.copy(y = it.y - scrollPos)
        }

        selectedBitmap?.let {
            copyBitmapToClipboard(context, it)
        }
        return ClipboardContent(
            strokes = strokes ?: emptyList(),
            images = images ?: emptyList()
        )
    }
}