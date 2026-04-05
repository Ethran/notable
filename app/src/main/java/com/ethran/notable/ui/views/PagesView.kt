package com.ethran.notable.ui.views

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.ui.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.editor.utils.setAnimationMode
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.FastScroller
import com.ethran.notable.ui.components.PageCard
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.dialogs.ShowSimpleConfirmationDialog
import com.ethran.notable.ui.viewmodels.PagesUiState
import com.ethran.notable.ui.viewmodels.PagesViewModel
import com.ethran.notable.utils.InsertionSlot
import com.ethran.notable.utils.ReorderableGridItem
import com.ethran.notable.utils.computeInsertionSlotRect
import com.ethran.notable.utils.rememberReorderableGridState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


object PagesDestination : NavigationDestination {
    override val route = "books"
    const val BOOK_ID_ARG = "bookId"

    // Route: books/{bookId}/pages
    val routeWithArgs = "$route/{$BOOK_ID_ARG}/pages"

    fun createRoute(bookId: String) = "$route/$bookId/pages"
}


@Composable
fun PagesView(
    bookId: String,
    goToLibrary: (String?) -> Unit,
    goToEditor: (String, String) -> Unit,
    viewModel: PagesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    PagesContent(
        state = state,
        onBack = goToLibrary,
        onOpenPage = { pageId -> goToEditor(pageId, bookId) },
        onGenerateThumbnails = viewModel::generateThumbnailsForCurrentBook,
        onReorder = { id, to -> viewModel.reorderPage(bookId, id, to) },
        onDeletePage = viewModel::deletePage,
        onDuplicatePage = viewModel::duplicatePage,
        onAddPageAfter = { viewModel.newPageInBook(bookId, it) })
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagesContent(
    state: PagesUiState,
    onBack: (String?) -> Unit,
    onOpenPage: (String) -> Unit,
    onGenerateThumbnails: () -> Unit,
    onReorder: (String, Int) -> Unit,
    onDeletePage: (String) -> Unit,
    onDuplicatePage: (String) -> Unit,
    onAddPageAfter: (Int) -> Unit
) {
    if (state.isLoading) return

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // --- 1. State for Edit Mode ---
    var isEditMode by rememberSaveable { mutableStateOf(false) }

    val reorderState = rememberReorderableGridState()
    val density = LocalDensity.current

    // Initial focus on current page
    LaunchedEffect(state.openPageId, state.pageIds) {
        val index = state.pageIds.indexOf(state.openPageId)
        if (index >= 0 && !reorderState.wareReordered) {
            Log.d("PagesView", "Initial focus on page $index")
            gridState.scrollToItem(index)
        }
    }


    var pendingDeletePageId by rememberSaveable { mutableStateOf<String?>(null) }

    pendingDeletePageId?.let { id ->
        ShowSimpleConfirmationDialog(
            title = "Confirm Deletion",
            message = "Are you sure you want to delete this page?",
            onConfirm = {
                onDeletePage(id)
                pendingDeletePageId = null
            },
            onCancel = {
                pendingDeletePageId = null
            })
    }

    Column(Modifier.fillMaxSize()) {
        Topbar {
            Row(
                Modifier
                    .padding(10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    BreadCrumb(folders = state.folderList) { onBack(it) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    GenerateThumbsSwitch(onClick = onGenerateThumbnails)

                    Spacer(modifier = Modifier.width(12.dp))

                    EditModeSwitch(isEditMode = isEditMode, onToggle = { isEditMode = it })

                    if (state.openPageId != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        JumpToCurrentPill {
                            val idx = state.pageIds.indexOf(state.openPageId)
                            if (idx >= 0) scope.launch { gridState.scrollToItem(idx) }
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .padding(10.dp)
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val r = coords.boundsInRoot()
                    reorderState.containerOriginInRoot =
                        IntOffset(r.left.roundToInt(), r.top.roundToInt())
                }) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 40.dp),
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        val r = coords.boundsInRoot()
                        reorderState.gridOriginInRoot =
                            IntOffset(r.left.roundToInt(), r.top.roundToInt())
                    }
                    .autoEInkAnimationOnScroll()) {
                itemsIndexed(
                    items = state.pageIds, key = { _, id -> id }) { pageIndex, pageId ->
                    val isOpen = pageId == state.openPageId

                    ReorderableGridItem(
                        itemId = pageId,
                        index = pageIndex,
                        gridState = gridState,
                        state = reorderState,
                        // --- 3. Disable reordering when not in edit mode ---
                        isEnabled = isEditMode,
                        onDrop = { _, to, droppedId ->
                            onReorder(droppedId, to)
                        }) { touchMod ->
                        PageCard(
                            pageId = pageId,
                            pageIndex = pageIndex,
                            isOpen = isOpen,
                            // --- 4. Pass edit mode state to PageCard ---
                            isEditMode = isEditMode,
                            isReorderDragging = reorderState.draggingId == null,
                            touchModifier = touchMod,
                            onOpen = {
                                onOpenPage(pageId)
                            },
                            onDelete = { pendingDeletePageId = pageId },
                            onDuplicate = { onDuplicatePage(pageId) },
                            onAddAfter = {
                                onAddPageAfter(pageIndex + 1)
                            })
                    }
                }
            }

            // Insertion highlight overlay (single, global)
            val insertionIndex = reorderState.hoverInsertionIndex
            val slot: InsertionSlot? =
                if (reorderState.draggingId != null && insertionIndex >= 0) computeInsertionSlotRect(
                    gridState.layoutInfo,
                    insertionIndex,
                    barThicknessPx = with(density) { 3.dp.toPx() }.roundToInt()
                ) else null

            if (slot != null) {
                val localOffset = IntOffset(
                    x = slot.offset.x + reorderState.gridOriginInRoot.x - reorderState.containerOriginInRoot.x,
                    y = slot.offset.y + reorderState.gridOriginInRoot.y - reorderState.containerOriginInRoot.y
                )
                val slotWidthDp = with(density) { slot.size.width.toDp() }
                val slotHeightDp = with(density) { slot.size.height.toDp() }

                Box(modifier = Modifier
                    .offset { localOffset }
                    .size(width = slotWidthDp, height = slotHeightDp)
                    .background(Color.DarkGray))
            }

            // Drag proxy overlay — match on-screen size
            val draggingId = reorderState.draggingId
            if (draggingId != null) {
                val originPair = reorderState.itemBounds[draggingId]
                if (originPair != null) {
                    val (originRoot, sizePx) = originPair

                    val sizeOfDragging = 0.5f
                    val wDp = with(density) { sizePx.width.toDp() } * sizeOfDragging
                    val hDp = with(density) { sizePx.height.toDp() } * sizeOfDragging
                    val isDraggingPageOpen = draggingId == state.openPageId

                    val localStartX =
                        originRoot.x - reorderState.containerOriginInRoot.x + reorderState.dragDelta.x
                    val localStartY =
                        originRoot.y - reorderState.containerOriginInRoot.y + reorderState.dragDelta.y

                    Box(modifier = Modifier
                        .offset { IntOffset(localStartX, localStartY) }
                        .size(width = wDp, height = hDp)
                        .background(Color.White)) {
                        // PagePreview fills the proxy without adding extra outer borders
                        PagePreview(
                            modifier = Modifier
                                .matchParentSize()
                                .border(
                                    if (isDraggingPageOpen) 2.dp else 1.dp,
                                    Color.Black,
                                    RectangleShape
                                ), pageId = draggingId
                        )
                    }

                    // Auto-scroll near edges while dragging
                    val pointerY = originRoot.y + reorderState.dragDelta.y
                    val viewportTop = reorderState.gridOriginInRoot.y
                    val viewportBottom = viewportTop + gridState.layoutInfo.viewportSize.height
                    val edge = 64
                    LaunchedEffect(pointerY) {
                        when {
                            pointerY < viewportTop + edge -> gridState.scrollBy(-40f)
                            pointerY > viewportBottom - edge -> gridState.scrollBy(40f)
                        }
                    }
                }
            }

            if (state.pageIds.size > 30) {
                FastScroller(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    state = gridState,
                    itemCount = state.pageIds.size,
                    getVisibleIndex = { gridState.firstVisibleItemIndex },
                    onDragStart = { setAnimationMode(true) },
                    onDragEnd = { setAnimationMode(false) })
            }
        }
    }
}

@Composable
private fun GenerateThumbsSwitch(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, Color.Black, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("Generate previews", color = Color.Black)
    }
}

/**
 * Top-right persistent jump pill.
 */
@Composable
private fun JumpToCurrentPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF111111))
            .border(1.dp, Color.Black, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { onClick() }) {
        Text("Jump to current", color = Color.White)
    }
}

/**
 * A simple switch for toggling edit mode.
 */
@Composable
private fun EditModeSwitch(
    isEditMode: Boolean, onToggle: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isEditMode) Color.Black else Color.White)
            .border(1.dp, Color.Black, RoundedCornerShape(16.dp))
            .clickable { onToggle(!isEditMode) }
            .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("Edit Mode", color = if (isEditMode) Color.White else Color.Black)
        Spacer(Modifier.width(8.dp))
        // Simple visual indicator for the switch state
        Box(
            Modifier
                .size(16.dp)
                .background(if (isEditMode) Color.Green else Color.White, CircleShape)
                .border(1.dp, Color.Black, CircleShape)
        )
    }
}


@Preview(showBackground = true)
@Composable
fun PagesPreview() {
    PagesContent(
        state = PagesUiState(
        pageIds = listOf("p1", "p2", "p3"), openPageId = "p2", isLoading = false
    ),
        onBack = {},
        onOpenPage = {},
        onGenerateThumbnails = {},
        onReorder = { _, _ -> },
        onDeletePage = {},
        onDuplicatePage = {},
        onAddPageAfter = {})
}

