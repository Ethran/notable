package com.ethran.notable.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ethran.notable.editor.utils.setAnimationMode
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Shared state for drag-and-drop reordering in a LazyVerticalGrid.
 */
class ReorderableState {
    var draggingId by mutableStateOf<String?>(null)
    var fromIndex by mutableIntStateOf(-1)
    var dragDelta by mutableStateOf(IntOffset.Zero)

    // -1 means "no-op" (drop before self or after self -> no change)
    var hoverInsertionIndex by mutableIntStateOf(-1)

    // Per-item bounds in root (visible items only)
    val itemBounds: MutableMap<String, Pair<IntOffset, IntSize>> = mutableStateMapOf()

    // Coordinate conversion helpers
    var gridOriginInRoot by mutableStateOf(IntOffset.Zero)
    var containerOriginInRoot by mutableStateOf(IntOffset.Zero)
}

/**
 * Attach to a grid item to enable long-press drag. It updates [reorderState] and
 * calls [onDrop] with a "between items" insertion index (0..N) on release.
 *
 * Use [computeInsertionSlotRect] to render the in-between highlight elsewhere.
 */
@Composable
fun ReorderableGridItem(
    modifier: Modifier = Modifier,
    itemId: String,
    index: Int,
    gridState: LazyGridState,
    reorderState: ReorderableState,
    onDrop: (fromIndex: Int, toInsertionIndex: Int, itemId: String) -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    var lastItemOriginInRoot by remember { mutableStateOf<IntOffset?>(null) }
    var lastItemSize by remember { mutableStateOf<IntSize?>(null) }

    val gestureMod = Modifier
        .onGloballyPositioned { coords ->
            val b = coords.boundsInRoot()
            val origin = IntOffset(b.left.roundToInt(), b.top.roundToInt())
            val size = IntSize(b.width.roundToInt(), b.height.roundToInt())
            reorderState.itemBounds[itemId] = origin to size
            lastItemOriginInRoot = origin
            lastItemSize = size
        }
        .pointerInput(itemId) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    reorderState.draggingId = itemId
                    reorderState.fromIndex = index
                    reorderState.dragDelta = IntOffset.Zero
                    // Start with a bar right before this item (visually clearer)
                    reorderState.hoverInsertionIndex = index
                    setAnimationMode(true)
                },
                onDragCancel = {
                    reorderState.draggingId = null
                    reorderState.fromIndex = -1
                    reorderState.dragDelta = IntOffset.Zero
                    reorderState.hoverInsertionIndex = -1
                    setAnimationMode(false)
                },
                onDragEnd = {
                    val id = reorderState.draggingId
                    val from = reorderState.fromIndex
                    val to = reorderState.hoverInsertionIndex
                    // -1 means "no-op" (drop at original location)
                    if (id != null && from >= 0 && to >= 0 && to != from && to != from + 1) {
                        onDrop(from, to, id)
                    }
                    reorderState.draggingId = null
                    reorderState.fromIndex = -1
                    reorderState.dragDelta = IntOffset.Zero
                    reorderState.hoverInsertionIndex = -1
                    setAnimationMode(false)
                }
            ) { change, dragAmount ->
                change.consume()
                reorderState.dragDelta += IntOffset(
                    dragAmount.x.roundToInt(),
                    dragAmount.y.roundToInt()
                )

                // Update insertion index from pointer center
                val origin = lastItemOriginInRoot
                val size = lastItemSize
                if (origin != null && size != null) {
                    val dropCenter = IntOffset(
                        x = origin.x + reorderState.dragDelta.x + size.width / 2,
                        y = origin.y + reorderState.dragDelta.y + size.height / 2
                    )
                    val insertion = computeInsertionIndex(
                        gridState = gridState,
                        gridOriginInRoot = reorderState.gridOriginInRoot,
                        pointInRoot = dropCenter,
                        itemCount = gridState.layoutInfo.totalItemsCount
                    )
                    val from = reorderState.fromIndex
                    // No-op if before self or after self
                    reorderState.hoverInsertionIndex =
                        if (insertion == from || insertion == from + 1) -1 else insertion
                }
            }
        }

    content(modifier.then(gestureMod))
}

/**
 * Compute a "between items" insertion index (0..itemCount) from a pointer in root coords.
 * Heuristic:
 * 1) Find nearest visible item by center distance.
 * 2) If pointer is to the left of that center -> insert before; otherwise after.
 * 3) Clamp to [0, itemCount].
 */
private fun computeInsertionIndex(
    gridState: LazyGridState,
    gridOriginInRoot: IntOffset,
    pointInRoot: IntOffset,
    itemCount: Int
): Int {
    val items = gridState.layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return 0

    val localPoint = IntOffset(
        x = pointInRoot.x - gridOriginInRoot.x,
        y = pointInRoot.y - gridOriginInRoot.y
    )

    var best: LazyGridItemInfo? = null
    var bestDist = Float.MAX_VALUE
    for (info in items) {
        val cx = info.offset.x + info.size.width / 2f
        val cy = info.offset.y + info.size.height / 2f
        val d = hypot(localPoint.x - cx, localPoint.y - cy)
        if (d < bestDist) {
            bestDist = d
            best = info
        }
    }
    if (best == null) return 0

    val cx = best.offset.x + best.size.width / 2f
    val insertBefore = localPoint.x < cx
    val candidate = if (insertBefore) best.index else best.index + 1
    return candidate.coerceIn(0, itemCount)
}

/**
 * A rectangle (in grid viewport-local coordinates) where the insertion highlight should be drawn.
 * If vertical == true, draw a vertical bar; if false, draw a horizontal bar across the row.
 */
data class InsertionSlot(
    val offset: IntOffset,
    val size: IntSize,
    val vertical: Boolean
)

/**
 * Compute a visual rectangle for the insertion slot corresponding to [insertionIndex].
 * Returns null if not enough info is visible to compute a reliable slot.
 *
 * Strategy:
 * - If there are neighbors on the same row: draw a vertical bar centered in the gap between them.
 * - Else draw a horizontal bar between rows across the grid viewport width.
 */
fun computeInsertionSlotRect(
    layoutInfo: LazyGridLayoutInfo,
    insertionIndex: Int,
    barThicknessPx: Int = 6
): InsertionSlot? {
    if (insertionIndex < 0) return null
    val items = layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return null

    val viewportW = layoutInfo.viewportSize.width
    val bar = barThicknessPx.coerceAtLeast(2)

    // Helper to get visible info by index
    fun getInfo(idx: Int): LazyGridItemInfo? = items.firstOrNull { it.index == idx }

    val before = getInfo(insertionIndex - 1)
    val after = getInfo(insertionIndex)

    // Case: between two visible items on the same row -> vertical bar in the middle of the gap
    if (before != null && after != null && before.offset.y == after.offset.y) {
        val gapStart = before.offset.x + before.size.width
        val gapEnd = after.offset.x
        val center = (gapStart + gapEnd) / 2
        val x = center - bar / 2
        val y = minOf(before.offset.y, after.offset.y)
        val h = maxOf(before.size.height, after.size.height)
        return InsertionSlot(
            offset = IntOffset(x.coerceAtLeast(0), y),
            size = IntSize(bar, h),
            vertical = true
        )
    }

    // Case: between rows (after last in row or before first in next row) -> horizontal bar
    // Prefer using 'after' if visible; otherwise use 'before'
    if (after != null) {
        val y = after.offset.y
        return InsertionSlot(
            offset = IntOffset(0, y),
            size = IntSize(viewportW, bar),
            vertical = false
        )
    }
    if (before != null) {
        val y = before.offset.y + before.size.height
        return InsertionSlot(
            offset = IntOffset(0, y),
            size = IntSize(viewportW, bar),
            vertical = false
        )
    }

    // Fallback: draw at top of viewport
    return InsertionSlot(offset = IntOffset(0, 0), size = IntSize(viewportW, bar), vertical = false)
}