package com.ethran.notable.db

import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.createBitmap
import com.ethran.notable.TAG
import com.ethran.notable.classes.DrawCanvas
import com.ethran.notable.classes.PageView
import com.ethran.notable.utils.EditorState
import com.ethran.notable.utils.PlacementMode
import com.ethran.notable.utils.SelectPointPosition
import com.ethran.notable.utils.SimplePointF
import com.ethran.notable.utils.divideStrokesFromCut
import com.ethran.notable.utils.drawImage
import com.ethran.notable.utils.drawStroke
import com.ethran.notable.utils.imageBoundsInt
//import com.ethran.notable.utils.pageAreaToCanvasArea
import com.ethran.notable.utils.pointsToPath
import com.ethran.notable.utils.selectImagesFromPath
import com.ethran.notable.utils.selectStrokesFromPath
import com.ethran.notable.utils.strokeBounds
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

//TODO: clean up this code, there is a lot of duplication

// allows selection of all images and strokes in given rectangle
fun selectImagesAndStrokes(
    scope: CoroutineScope,
    page: PageView,
    editorState: EditorState,
    imagesToSelect: List<Image>,
    strokesToSelect: List<Stroke>
) {
    //handle selection:
    val pageBounds = Rect()

    if (imagesToSelect.isNotEmpty())
        pageBounds.union(imageBoundsInt(imagesToSelect))
    if (strokesToSelect.isNotEmpty())
        pageBounds.union(strokeBounds(strokesToSelect))

    // padding inside the dashed selection square
    // - if there are strokes selected, add some padding;
    // - for image-only selections, use a tight fit.
    val padding = if (strokesToSelect.isNotEmpty()) 30 else 0

    pageBounds.inset(-padding, -padding)

    // create bitmap and draw images and strokes
    val selectedBitmap= page.toScreenCoordinates(pageBounds).let { boundsScreen->
        Log.w(TAG, "bounds(PageView): $pageBounds, bounds (screen) $boundsScreen")
        createBitmap(boundsScreen.width(), boundsScreen.height())
    }
    val selectedCanvas = Canvas(selectedBitmap)
    selectedCanvas.scale(page.zoomLevel, page.zoomLevel)

    imagesToSelect.forEach {
        drawImage(
            page.context,
            selectedCanvas,
            it,
            IntOffset(-pageBounds.left, -pageBounds.top)
        )
    }
    strokesToSelect.forEach {
        drawStroke(
            selectedCanvas,
            it,
            IntOffset(-pageBounds.left, -pageBounds.top)
        )
    }
    Log.e(TAG, "selectionRect(page): $pageBounds")
    // set state
    editorState.selectionState.selectedImages = imagesToSelect
    editorState.selectionState.selectedStrokes = strokesToSelect
    editorState.selectionState.selectedBitmap = selectedBitmap
    editorState.selectionState.selectionRect = pageBounds
    editorState.selectionState.selectionDisplaceOffset = IntOffset(0, 0)
    editorState.selectionState.placementMode = PlacementMode.Move
    page.drawAreaPageCoordinates(
        pageBounds,
        ignoredImageIds = imagesToSelect.map { it.id },
        ignoredStrokeIds = strokesToSelect.map { it.id })

    scope.launch {
        DrawCanvas.refreshUi.emit(Unit)
        editorState.isDrawing = false
    }
}


/**
 * Selects a single image (and deselects all strokes) on the page.
 */
fun selectImage(
    scope: CoroutineScope,
    page: PageView,
    editorState: EditorState,
    imageToSelect: Image
) {
    selectImagesAndStrokes(scope, page, editorState, listOf(imageToSelect), emptyList())
}


/** Written by GPT:
 * Handles selection of strokes and areas on a page, enabling either lasso selection or
 * page-cut-based selection for further manipulation or operations.
 *
 * This function performs the following steps:
 *
 * 1. **Page Cut Selection**:
 *    - Identifies if the selection points cross the left or right edge of the page (`Page cut` case).
 *    - Determines the direction of the cut and creates a complete selection area spanning the page.
 *    - For the first page cut, it registers the cut coordinates.
 *    - For the second page cut, it orders the cuts, divides the strokes into sections based on these cuts,
 *      and assigns the strokes in the middle section to `selectedStrokes`.
 *
 * 2. **Lasso Selection**:
 *    - For non-page-cut cases, it performs lasso selection using the provided points.
 *    - Creates a `Path` from the selection points and identifies strokes within this lasso area.
 *    - Computes the bounding box (`pageBounds`) for the selected strokes and expands it with padding.
 *    - Maps the page-relative bounds to the canvas coordinate space.
 *    - Renders the selected strokes onto a new bitmap using the calculated bounds.
 *    - Updates the editor's selection state with:
 *      - The selected strokes.
 *      - The created bitmap and its position on the canvas.
 *      - The selection rectangle and displacement offset.
 *      - Enabling the "Move" placement mode for manipulation.
 *    - Optionally, redraws the affected area without the selected strokes.
 *
 * 3. **UI Refresh**:
 *    - Notifies the UI to refresh and disables the drawing mode.
 *
 * @param scope The `CoroutineScope` used to perform asynchronous operations, such as UI refresh.
 * @param page The `PageView` object representing the current page, including its strokes and dimensions.
 * @param editorState The `EditorState` object storing the current state of the editor, such as selected strokes.
 * @param points A list of `SimplePointF` objects defining the user's selection path in page coordinates.
 * points is in page coordinates
 */
fun handleSelect(
    scope: CoroutineScope,
    page: PageView,
    editorState: EditorState,
    points: List<SimplePointF>
) {
    val state = editorState.selectionState

    val firstPointPosition =
        if (points.first().x < 50) SelectPointPosition.LEFT else if (points.first().x > page.width - 50) SelectPointPosition.RIGHT else SelectPointPosition.CENTER
    val lastPointPosition =
        if (points.last().x < 50) SelectPointPosition.LEFT else if (points.last().x > page.width - 50) SelectPointPosition.RIGHT else SelectPointPosition.CENTER

    if (firstPointPosition != SelectPointPosition.CENTER && lastPointPosition != SelectPointPosition.CENTER && firstPointPosition != lastPointPosition) {
        // Page cut situation
        val correctedPoints =
            if (firstPointPosition === SelectPointPosition.LEFT) points else points.reversed()
        // lets make this end to end
        val completePoints =
            listOf(SimplePointF(0f, correctedPoints.first().y)) + correctedPoints + listOf(
                SimplePointF(page.width.toFloat(), correctedPoints.last().y)
            )
        if (state.firstPageCut == null) {
            // this is the first page cut
            state.firstPageCut = completePoints
            Log.i(TAG, "Registered first curt")
        } else {
            // this is the second page cut, we can also select the strokes
            // first lets have the cuts in the right order
            if (completePoints[0].y > state.firstPageCut!![0].y) state.secondPageCut =
                completePoints
            else {
                state.secondPageCut = state.firstPageCut
                state.firstPageCut = completePoints
            }
            // let's get stroke selection from that
            val (_, after) = divideStrokesFromCut(page.strokes, state.firstPageCut!!)
            val (middle, _) = divideStrokesFromCut(after, state.secondPageCut!!)
            state.selectedStrokes = middle
        }
    } else {
        // lasso selection

        // recreate the lasso selection
        val selectionPath = pointsToPath(points)
        selectionPath.close()

        // get the selected strokes and images
        val selectedStrokes = selectStrokesFromPath(page.strokes, selectionPath)
        val selectedImages = selectImagesFromPath(page.images, selectionPath)

        if (selectedStrokes.isEmpty() && selectedImages.isEmpty()) return

        selectImagesAndStrokes(scope, page, editorState, selectedImages, selectedStrokes)

        // TODO collocate with control tower ?
    }
}
