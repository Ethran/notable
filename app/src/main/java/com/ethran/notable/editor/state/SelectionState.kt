package com.ethran.notable.editor.state

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import androidx.core.graphics.createBitmap
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.utils.imageBoundsInt
import com.ethran.notable.editor.utils.EpdRefreshArbiter
import com.ethran.notable.editor.utils.offsetImage
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.io.copyBitmapToClipboard
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.onError
import com.ethran.notable.utils.plus
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import java.util.Date
import java.util.UUID

private val log = ShipBook.getLogger("SelectionState")

/**
 * Represents the current state of a selection within the editor, managing the lifecycle and
 * transformations of selected items such as strokes and images.
 *
 * This class tracks the geometric boundaries, visual representation (bitmap), and
 * spatial offsets of the selected content. It provides methods for manipulating
 * the selection, including translation (moving), resizing, duplication, and
 * integration with the undo/redo history system.
 *
 * All coordinate-based properties within this class are intended to be in page coordinates
 * unless otherwise specified.
 */
class SelectionState {
    // all coordinates should be in page coordinates
    var firstPageCut by mutableStateOf<List<SimplePointF>?>(null)
    var secondPageCut by mutableStateOf<List<SimplePointF>?>(null)
    var selectedStrokes by mutableStateOf<List<Stroke>?>(null)
    var selectedImages by mutableStateOf<List<Image>?>(null)

    // Pre-edit snapshot of a Move selection (original size AND position), captured when the
    // selection is created. resizeImages/resizeStrokes mutate selectedStrokes/Images in place, so
    // those no longer hold the originals — the Move's undo baseline must come from here, or undoing
    // a resize would restore the position but keep the new size.
    var initialSelectedStrokes: List<Stroke>? = null
    var initialSelectedImages: List<Image>? = null

    // TODO: Bitmap should be change, if scale changes.
    var selectedBitmap by mutableStateOf<Bitmap?>(null)

    var selectionStartOffset by mutableStateOf<IntOffset?>(null)
    var selectionDisplaceOffset by mutableStateOf<IntOffset?>(null)
    var selectionRect by mutableStateOf<Rect?>(null)
    var placementMode by mutableStateOf<PlacementMode?>(null)

    // Keeps EPD animation mode on for the lifetime of the selection, so
    // moving the selected bitmap stays on fast refresh.
    private var refreshHandle: EpdRefreshArbiter.Handle? = null

    /** Acquire the animation-mode handle for this selection (idempotent). */
    fun holdRefresh() {
        if (refreshHandle == null)
            refreshHandle = EpdRefreshArbiter.acquire("selection")
    }

    /** Release the selection's animation-mode handle (idempotent). */
    fun releaseRefresh() {
        refreshHandle?.release()
        refreshHandle = null
    }

    fun reset() {
        log.v("reset")
        selectedStrokes = null
        selectedImages = null
        initialSelectedStrokes = null
        initialSelectedImages = null
        secondPageCut = null
        firstPageCut = null
        selectedBitmap = null
        selectionRect = null
        selectionStartOffset = null
        selectionDisplaceOffset = null
        placementMode = null
        releaseRefresh()
    }

    fun isNonEmpty(): Boolean {
        return !selectedStrokes.isNullOrEmpty() || !selectedImages.isNullOrEmpty()
    }

    fun isResizable(): Boolean {
        return selectedImages?.count() == 1 && selectedStrokes.isNullOrEmpty()
    }

    fun resizeImages(scale: Int, page: PageView): AppResult<Unit, DomainError> {
        log.v("resizeImages: scale=$scale")

        val selectedImagesCopy = selectedImages?.map { image ->
            image.copy(
                height = image.height + (image.height * scale / 100),
                width = image.width + (image.width * scale / 100)
            )
        }

        if (selectedImagesCopy.isNullOrEmpty()) {
            return AppResult.Error(DomainError.UnexpectedState("No images selected for resizing."))
        }

        selectedImages = selectedImagesCopy
        // Adjust displacement offset by half the size change
        val sizeChange = selectedImagesCopy.firstOrNull()?.let { image ->
            IntOffset(
                x = (image.width * scale / 200), y = (image.height * scale / 200)
            )
        } ?: IntOffset.Zero

        val pageBounds = imageBoundsInt(selectedImagesCopy)
        // selectionRect must be in PAGE coordinates (as set by selectImagesAndStrokes and expected
        // by SelectorBitmap + applySelectionDisplace's drawAreaPageCoordinates). Storing screen
        // coords here mis-transformed the commit redraw zone, so after a resize the image's bounds
        // no longer intersected the redraw area and it wasn't repainted — it vanished until
        // re-selected.
        selectionRect = pageBounds

        selectionDisplaceOffset = selectionDisplaceOffset?.let { it - sizeChange } ?: IntOffset.Zero

        // 1. Safe Bitmap Creation
        val selectedBitmapNew = try {
            // createBitmap can throw IllegalArgumentException if width/height <= 0
            // or OutOfMemoryError if the scale is massive.
            createBitmap(pageBounds.width(), pageBounds.height())
        } catch (e: Exception) {
            log.e("Failed to create resized bitmap", e)
            return AppResult.Error(DomainError.DrawingError("Failed to allocate memory for resized image."))
        } catch (e: OutOfMemoryError) {
            log.e("OOM on resized bitmap", e)
            return AppResult.Error(DomainError.DrawingError("Image is too large to resize."))
        }

        val selectedCanvas = Canvas(selectedBitmapNew)

        // 2. The Accumulator
        var accumulatedError: DomainError? = null

        // 3. The Loop
        selectedImagesCopy.forEach { image ->
            drawImage(
                page.context, selectedCanvas, image, Offset(-image.x.toFloat(), -image.y.toFloat())
            ).onError { error ->
                accumulatedError = accumulatedError?.let { it + error } ?: error
            }
        }

        // 4. Final State Update & Return
        selectedBitmap = selectedBitmapNew

        return accumulatedError?.let { AppResult.Error(it) } ?: AppResult.Success(Unit)
    }

    @Suppress("UNUSED_PARAMETER")
    fun resizeStrokes(scale: Int, scope: CoroutineScope, page: PageView) {
        log.v("resizeStrokes: scale=$scale")
        //TODO: implement this
    }

    /**
     * Deletes the currently selected strokes and images from the page.
     *
     * This function identifies the selected images and strokes, removes them from the given [page],
     * and creates a list of undo [Operation]s. After deletion, it resets the selection state.
     *
     * @param page The [PageView] from which the selected items should be removed.
     * @return A list of [Operation]s that can be used to undo the deletion (e.g., re-adding the deleted items).
     */
    fun deleteSelection(page: PageView): List<Operation> {
        log.v("deleteSelection: images=${selectedImages?.size}, strokes=${selectedStrokes?.size}")
        val operationList = mutableListOf<Operation>()
        val selectedImagesToRemove = selectedImages
        if (!selectedImagesToRemove.isNullOrEmpty()) {
            val imageIds: List<String> = selectedImagesToRemove.map { it.id }
            log.i("removing images")
            page.removeImages(imageIds)
            operationList += Operation.AddImage(selectedImagesToRemove)
        }
        val selectedStrokesToRemove = selectedStrokes
        if (!selectedStrokesToRemove.isNullOrEmpty()) {
            val strokeIds: List<String> = selectedStrokesToRemove.map { it.id }
            log.i("removing strokes")
            page.removeStrokes(strokeIds)
            operationList += Operation.AddStroke(selectedStrokesToRemove)
        }
        reset()
        return operationList
    }

    fun duplicateSelection() {
        log.v("duplicateSelection")
        // set operation to paste only
        placementMode = PlacementMode.Paste
        if (!selectedStrokes.isNullOrEmpty())
        // change the selected stokes' ids - it's a copy
            selectedStrokes = selectedStrokes!!.map {
                it.copy(
                    id = UUID.randomUUID().toString(), createdAt = Date()
                )
            }
        if (!selectedImages.isNullOrEmpty()) selectedImages = selectedImages!!.map {
            it.copy(
                id = UUID.randomUUID().toString(), createdAt = Date()
            )
        }
        // move the selection a bit, to show the copy
        selectionDisplaceOffset = IntOffset(
            x = (selectionDisplaceOffset?.x ?: 0) + 50,
            y = (selectionDisplaceOffset?.y ?: 0) + 50,
        )
    }

    // Moves strokes, and redraws canvas.
    fun applySelectionDisplace(page: PageView): List<Operation>? {
        log.v("applySelectionDisplace: offset=$selectionDisplaceOffset, mode=$placementMode")

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

            if (placementMode == PlacementMode.Move) {
                page.updateStrokes(displacedStrokes)
            } else {
                page.addStrokes(displacedStrokes)
            }

            if (offset.x != 0 || offset.y != 0 || placementMode == PlacementMode.Paste) {
                // History (inverse of this change): undo a Move by restoring the pre-edit originals
                // in place (Update, not delete+add) — using the snapshot, since a resize already
                // mutated selectedStrokesCopy; undo a Paste by deleting the pasted strokes.
                operationList += if (placementMode == PlacementMode.Move)
                    Operation.UpdateStroke(initialSelectedStrokes ?: selectedStrokesCopy)
                else
                    Operation.DeleteStroke(displacedStrokes.map { it.id })
            }
        }
        if (!selectedImagesCopy.isNullOrEmpty()) {
            log.i("Commit images to history.")

            val displacedImages = selectedImagesCopy.map {
                offsetImage(it, offset = offset.toOffset())
            }

            // Mirror the stroke path above: a Move updates the existing rows in place (same ids);
            // a Paste adds new rows (paste already assigned fresh ids upstream). The old code did
            // removeImages + addImage for Move, which delete-then-reinserted the same id via two
            // unordered coroutines and raced to a UNIQUE(Image.id) crash (P3).
            if (placementMode == PlacementMode.Move) {
                page.updateImages(displacedImages)
            } else {
                page.addImage(displacedImages)
            }

            if (offset.x != 0 || offset.y != 0 || placementMode == PlacementMode.Paste) {
                // History (inverse): undo a Move by restoring the pre-edit originals in place
                // (Update, not delete+add — that's what raced to the UNIQUE crash) using the
                // snapshot, since a resize already mutated selectedImagesCopy; undo a Paste deletes.
                operationList += if (placementMode == PlacementMode.Move)
                    Operation.UpdateImage(initialSelectedImages ?: selectedImagesCopy)
                else
                    Operation.DeleteImage(displacedImages.map { it.id })
            }
        }
        page.drawAreaPageCoordinates(finalZone)
        return operationList
    }

    fun applySelectionDisplaceAndCommit(page: PageView, history: History): Boolean {
        val operationList = applySelectionDisplace(page)
        if (operationList.isNullOrEmpty()) return false
        history.addOperationsToHistory(page.currentPageId, operationList)
        return true
    }

    fun deleteSelectionAndCommit(page: PageView, history: History) {
        val operationList = deleteSelection(page)
        history.addOperationsToHistory(page.currentPageId, operationList)
    }

    fun selectionToClipboard(scrollPos: Offset, context: Context): ClipboardContent {
        log.v("selectionToClipboard: scrollPos=$scrollPos, images=${selectedImages?.size}, strokes=${selectedStrokes?.size}")

        val strokes = selectedStrokes?.map {
            offsetStroke(it, offset = -scrollPos)
        }

        val images = selectedImages?.map {
            it.copy(x = it.x - scrollPos.x.toInt(), y = it.y - scrollPos.y.toInt())
        }

        selectedBitmap?.let {
            copyBitmapToClipboard(context, it)
        }
        return ClipboardContent(
            strokes = strokes ?: emptyList(), images = images ?: emptyList()
        )
    }
}
