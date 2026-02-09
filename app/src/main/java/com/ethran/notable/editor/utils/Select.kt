package com.ethran.notable.editor.utils

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toOffset
import androidx.core.graphics.createBitmap
import com.ethran.notable.TAG
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.CanvasEventBus
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.drawing.drawImage
import com.ethran.notable.editor.drawing.drawStroke
import com.ethran.notable.editor.state.EditorState
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

//TODO: clean up this code, there is a lot of duplication


enum class SelectPointPosition {
    LEFT,
    RIGHT,
    CENTER
}


fun selectStrokesFromPath(strokes: List<Stroke>, path: Path): List<Stroke> {
    val bounds = RectF()
    path.computeBounds(bounds, true)

    //region is only 16 bit, so we need to move our region
    val translatedPath = Path(path)
    translatedPath.offset(0f, -bounds.top)
    val region = pathToRegion(translatedPath)

    return strokes.filter {
        strokeBounds(it).intersect(bounds)
    }.filter { it.points.any { region.contains(it.x.toInt(), (it.y - bounds.top).toInt()) } }
}

fun selectImagesFromPath(images: List<Image>, path: Path): List<Image> {
    val bounds = RectF()
    path.computeBounds(bounds, true)

    //region is only 16 bit, so we need to move our region
    val translatedPath = Path(path)
    translatedPath.offset(0f, -bounds.top)
    val region = pathToRegion(translatedPath)

    return images.filter {
        imageBounds(it).intersect(bounds)
    }.filter {
        // include image if all its corners are within region
        imagePoints(it).all { region.contains(it.x, (it.y - bounds.top).toInt()) }
    }
}


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
    val selectedBitmap = page.toScreenCoordinates(pageBounds).let { boundsScreen ->
        createBitmap(boundsScreen.width(), boundsScreen.height())
    }
    val selectedCanvas = Canvas(selectedBitmap)
    selectedCanvas.scale(page.zoomLevel.value, page.zoomLevel.value)

    imagesToSelect.forEach {
        drawImage(
            page.context,
            selectedCanvas,
            it,
            -pageBounds.takeTopLeftCornel().toOffset()
        )
    }
    strokesToSelect.forEach {
        drawStroke(
            selectedCanvas,
            it,
            -pageBounds.takeTopLeftCornel().toOffset()
        )
    }
    val startOffset = IntOffset(pageBounds.left, pageBounds.top) - page.scroll.toIntOffset()

    // set state
    editorState.selectionState.selectedImages = imagesToSelect
    editorState.selectionState.selectedStrokes = strokesToSelect
    editorState.selectionState.selectedBitmap = selectedBitmap
    editorState.selectionState.selectionRect = pageBounds
    editorState.selectionState.selectionStartOffset = startOffset
    editorState.selectionState.selectionDisplaceOffset = IntOffset(0, 0)
    editorState.selectionState.placementMode = PlacementMode.Move
    setAnimationMode(true)
    page.drawAreaPageCoordinates(
        pageBounds,
        ignoredImageIds = imagesToSelect.map { it.id },
        ignoredStrokeIds = strokesToSelect.map { it.id })

    scope.launch {
        CanvasEventBus.refreshUi.emit(Unit)
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
        if (points.first().x < 50) SelectPointPosition.LEFT else if (points.first().x > page.viewWidth - 50) SelectPointPosition.RIGHT else SelectPointPosition.CENTER
    val lastPointPosition =
        if (points.last().x < 50) SelectPointPosition.LEFT else if (points.last().x > page.viewWidth - 50) SelectPointPosition.RIGHT else SelectPointPosition.CENTER

    if (firstPointPosition != SelectPointPosition.CENTER && lastPointPosition != SelectPointPosition.CENTER && firstPointPosition != lastPointPosition) {
        // Page cut situation
        val correctedPoints =
            if (firstPointPosition === SelectPointPosition.LEFT) points else points.reversed()
        // lets make this end to end
        val completePoints =
            listOf(SimplePointF(0f, correctedPoints.first().y)) + correctedPoints + listOf(
                SimplePointF(page.viewWidth.toFloat(), correctedPoints.last().y)
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


/**
 * handles selection, and decide if we should exit the animation mode
 */
suspend fun selectRectangle(page: PageView, coroutineScope: CoroutineScope, state: EditorState, rectToSelect: Rect) {
    val inPageCoordinates = toPageCoordinates(rectToSelect, page.zoomLevel.value, page.scroll)

    val imagesToSelect =
        PageDataManager.getImagesInRectangle(inPageCoordinates, page.currentPageId)
    val strokesToSelect =
        PageDataManager.getStrokesInRectangle(inPageCoordinates, page.currentPageId)
    if (imagesToSelect != null && strokesToSelect != null) {
        CanvasEventBus.rectangleToSelectByGesture.value = null
        if (imagesToSelect.isNotEmpty() || strokesToSelect.isNotEmpty()) {
            selectImagesAndStrokes(coroutineScope, page, state, imagesToSelect, strokesToSelect)
        } else {
            setAnimationMode(false)
            SnackState.globalSnackFlow.emit(
                SnackConf(
                    text = "There isn't anything.",
                    duration = 3000,
                )
            )
        }
    } else SnackState.globalSnackFlow.emit(
        SnackConf(
            text = "Page is empty!",
            duration = 3000,
        )
    )

}
