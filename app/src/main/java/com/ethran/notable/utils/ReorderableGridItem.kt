package com.ethran.notable.utils

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
@Stable
class ReorderableGridState internal constructor() {
    var draggingId by mutableStateOf<String?>(null)
        internal set
    var fromIndex by mutableIntStateOf(-1)
        internal set
    var dragDelta by mutableStateOf(IntOffset.Zero)
        internal set

    // -1 => no valid insertion (drop before/after itself)
    var hoverInsertionIndex by mutableIntStateOf(-1)
        internal set

    var wareReordered by mutableStateOf(false)

    // Per-item bounds in root (visible items only)
    internal val itemBounds: MutableMap<String, Pair<IntOffset, IntSize>> = mutableStateMapOf()

    // Coordinate conversion helpers
    var gridOriginInRoot by mutableStateOf(IntOffset.Zero)
    var containerOriginInRoot by mutableStateOf(IntOffset.Zero)

    fun reset() {
        draggingId = null
        fromIndex = -1
        dragDelta = IntOffset.Zero
        hoverInsertionIndex = -1
        wareReordered = true
    }
}

@Composable
fun rememberReorderableGridState(): ReorderableGridState = remember { ReorderableGridState() }

/**
 * Minimal item wrapper that exposes a Modifier you attach to the item root.
 * It updates [state] and invokes [onDrop] with between-items insertion index.
 */
@Composable
fun ReorderableGridItem(
    itemId: String,
    index: Int,
    gridState: LazyGridState,
    state: ReorderableGridState,
    onDrop: (fromIndex: Int, toInsertionIndex: Int, itemId: String) -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    // Prevent stale lambda capture inside pointerInput
    val currentOnDrop = rememberUpdatedState(onDrop)

    var lastItemOriginInRoot: IntOffset? by remember { mutableStateOf(null) }
    var lastItemSize: IntSize? by remember { mutableStateOf(null) }

    val gestureMod = Modifier
        .onGloballyPositioned { coords ->
            val b = coords.boundsInRoot()
            val origin = IntOffset(b.left.roundToInt(), b.top.roundToInt())
            val size = IntSize(b.width.roundToInt(), b.height.roundToInt())
            state.itemBounds[itemId] = origin to size
            lastItemOriginInRoot = origin
            lastItemSize = size
        }
        .pointerInput(itemId) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    state.draggingId = itemId
                    state.fromIndex = index
                    state.dragDelta = IntOffset.Zero
                    state.hoverInsertionIndex = index // Start with bar before this
                    setAnimationMode(true)
                },
                onDragCancel = {
                    state.reset()
                    setAnimationMode(false)
                },
                onDragEnd = {
                    val id = state.draggingId
                    val from = state.fromIndex
                    val to = state.hoverInsertionIndex
                    if (id != null && from >= 0 && to >= 0 && to != from && to != from + 1) {
                        currentOnDrop.value.invoke(from, to, id)
                    }
                    state.reset()
                    setAnimationMode(false)
                }
            ) { change, dragAmount ->
                change.consume()
                state.dragDelta += IntOffset(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())

                // Update insertion index from pointer center
                val origin = lastItemOriginInRoot
                val size = lastItemSize
                if (origin != null && size != null) {
                    val dropCenter = IntOffset(
                        x = origin.x + state.dragDelta.x + size.width / 2,
                        y = origin.y + state.dragDelta.y + size.height / 2
                    )
                    val insertion = computeInsertionIndex(
                        gridState = gridState,
                        gridOriginInRoot = state.gridOriginInRoot,
                        pointInRoot = dropCenter,
                        itemCount = gridState.layoutInfo.totalItemsCount
                    )
                    val from = state.fromIndex
                    state.hoverInsertionIndex =
                        if (insertion == from || insertion == from + 1) -1 else insertion
                }
            }
        }

    content(gestureMod)
}

/**
 * Compute a "between items" insertion index (0..itemCount) from a pointer in root coords.
 * Heuristic:
 * 1) Find nearest visible item by center distance.
 * 2) If pointer is to the left of that center -> insert before; otherwise after.
 * 3) Clamp to [0, itemCount].
 */
fun computeInsertionIndex(
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