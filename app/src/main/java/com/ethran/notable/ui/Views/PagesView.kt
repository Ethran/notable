package com.ethran.notable.ui.Views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.deletePage
import com.ethran.notable.editor.ui.toolbar.Topbar
import com.ethran.notable.editor.utils.setAnimationMode
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.InsertionSlot
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.ReorderableGridItem
import com.ethran.notable.ui.components.ReorderableState
import com.ethran.notable.ui.components.computeInsertionSlotRect
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.PlusCircle
import compose.icons.feathericons.Trash
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@ExperimentalFoundationApi
@Composable
fun PagesView(navController: NavController, bookId: String) {
    val appRepository = AppRepository(LocalContext.current)
    val context = LocalContext.current
    val book by appRepository.bookRepository.getByIdLive(bookId).observeAsState()
    if (book == null) return

    val pageIds = book!!.pageIds
    val openPageId = book?.openPageId
    val bookFolder = book?.parentFolderId

    // Grid + e‑ink animation handling
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    fun handleAnimations(scrolling: Boolean) {
        if (scrolling) {
            setAnimationMode(true)
            scrollJob?.cancel()
        } else {
            scrollJob = scope.launch {
                delay(500)
                setAnimationMode(false)
            }
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            handleAnimations(scrolling)
        }
    }

    // Initial focus on current page (immediate)
    LaunchedEffect(openPageId, pageIds) {
        val index = pageIds.indexOf(openPageId)
        if (index >= 0) gridState.scrollToItem(index)
    }

    // DnD reorder state
    val reorderState = remember { ReorderableState() }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize()) {
        Topbar {
            Row(
                Modifier
                    .padding(10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BreadCrumb(folderId = bookFolder) {
                    navController.navigate("library" + if (it == null) "" else "?folderId=${it}")
                }
                Spacer(modifier = Modifier.weight(1f))
                if (openPageId != null) {
                    JumpToCurrentPill {
                        val idx = pageIds.indexOf(openPageId)
                        if (idx >= 0) scope.launch { gridState.scrollToItem(idx) }
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
                modifier = Modifier.onGloballyPositioned { coords ->
                    val r = coords.boundsInRoot()
                    reorderState.gridOriginInRoot =
                        IntOffset(r.left.roundToInt(), r.top.roundToInt())
                }) {
                items(pageIds.size) { pageIndex ->
                    val pageId = pageIds[pageIndex]
                    val isOpen = pageId == openPageId

                    ReorderableGridItem(
                        itemId = pageId,
                        index = pageIndex,
                        gridState = gridState,
                        reorderState = reorderState,
                        onDrop = { _, to, id ->
                            appRepository.bookRepository.changePageIndex(bookId, id, to)
                        }) { touchMod ->
                        Box {
                            PagePreview(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(3f / 4f)
                                    .border(if (isOpen) 2.dp else 1.dp, Color.Black, RectangleShape)
                                    .clickable(
                                        enabled = reorderState.draggingId == null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        navController.navigate("books/$bookId/pages/$pageId")
                                    }
                                    .then(touchMod), pageId
                            )

                            // Current page header styling
                            if (isOpen) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .fillMaxWidth()
                                        .background(Color.Black)
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(text = (pageIndex + 1).toString(), color = Color.White)
                                }
                            } else {
                                Text(
                                    text = (pageIndex + 1).toString(),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .background(Color.Black)
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    color = Color.White
                                )
                            }

                            // Bottom-right actions
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconPill(icon = FeatherIcons.Trash, contentDesc = "Delete page") {
                                    deletePage(context, pageId)
                                }
                                IconPill(icon = FeatherIcons.Copy, contentDesc = "Duplicate page") {
                                    appRepository.duplicatePage(pageId)
                                }
                                IconPill(
                                    icon = FeatherIcons.PlusCircle, contentDesc = "Add page after"
                                ) {
                                    val bookNow = appRepository.bookRepository.getById(bookId)
                                        ?: return@IconPill
                                    val newPg = bookNow.newPage()
                                    appRepository.pageRepository.create(newPg)
                                    appRepository.bookRepository.addPage(
                                        bookId, newPg.id, pageIndex + 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Single, global "in-between" insertion highlight overlay
            val insertionIndex = reorderState.hoverInsertionIndex
            val slot: InsertionSlot? =
                if (reorderState.draggingId != null && insertionIndex >= 0) computeInsertionSlotRect(
                    gridState.layoutInfo,
                    insertionIndex,
                    barThicknessPx = with(density) { 3.dp.toPx() }.roundToInt()
                )
                else null

            if (slot != null) {
                val localOffset = IntOffset(
                    x = slot.offset.x + reorderState.gridOriginInRoot.x - reorderState.containerOriginInRoot.x,
                    y = slot.offset.y + reorderState.gridOriginInRoot.y - reorderState.containerOriginInRoot.y
                )
                val slotWidthDp = with(density) { slot.size.width.toDp() }
                val slotHeightDp = with(density) { slot.size.height.toDp() }

                Box(
                    modifier = Modifier
                        .offset { localOffset }
                        .size(width = slotWidthDp, height = slotHeightDp)
                        .background(Color.DarkGray)
                )
            }

            // Drag proxy overlay — match the exact on-screen size (px -> dp), no extra borders
            val draggingId = reorderState.draggingId
            if (draggingId != null) {
                val originPair = reorderState.itemBounds[draggingId]
                if (originPair != null) {
                    val (originRoot, sizePx) = originPair

                    val sizeOfDragging = 0.5f
                    val wDp = with(density) { sizePx.width.toDp() } * sizeOfDragging
                    val hDp = with(density) { sizePx.height.toDp() } * sizeOfDragging
                    val isDraggingPageOpen = draggingId == openPageId

                    val localStartX =
                        originRoot.x - reorderState.containerOriginInRoot.x + reorderState.dragDelta.x
                    val localStartY =
                        originRoot.y - reorderState.containerOriginInRoot.y + reorderState.dragDelta.y

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(localStartX, localStartY) }
                            .size(width = wDp, height = hDp)
                            .background(Color.White)
                    ) {
                        // PagePreview fills the proxy without adding extra outer borders
                        PagePreview(
                            modifier = Modifier
                                .matchParentSize()
                                .border(
                                    if (isDraggingPageOpen) 2.dp else 1.dp,
                                    Color.Black,
                                    RectangleShape
                                ),
                            pageId = draggingId
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

            // Fast scroller (unchanged)
            if (pageIds.size > 30) {
                FastScroller(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    state = gridState,
                    itemCount = pageIds.size,
                    getVisibleIndex = { gridState.firstVisibleItemIndex },
                    onDragStart = { handleAnimations(true) },
                    onDragEnd = { handleAnimations(false) }
                )
            }
        }
    }
}

/**
 * Small e‑ink-friendly icon pill button.
 */
@Composable
private fun IconPill(
    icon: ImageVector, contentDesc: String, onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFFFFFFF))
            .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
            .then(Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp))
            .padding(6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon, contentDescription = contentDesc, tint = Color.Black
        )
    }
}

/**
 * Top-right persistent jump pill (no animation on click).
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
            ) { onClick() }
    ) {
        Text("Jump to current", color = Color.White)
    }
}

/**
 * Right-side fast scroller with a draggable thumb (compact, e‑ink friendly).
 */
@Composable
private fun BoxScope.FastScroller(
    modifier: Modifier = Modifier,
    state: LazyGridState,
    itemCount: Int,
    getVisibleIndex: () -> Int,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    trackWidth: Dp = 36.dp,
    thumbHeight: Dp = 56.dp,
    quantization: Int = 2
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var dragIndex by remember { mutableIntStateOf(getVisibleIndex()) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    val thumbHeightPx = with(density) { thumbHeight.toPx() }
    val fraction: Float =
        if (itemCount <= 1) 0f else (getVisibleIndex().toFloat() / (itemCount - 1).toFloat()).coerceIn(
            0f, 1f
        )
    val thumbOffsetYPx: Float = (containerHeightPx - thumbHeightPx).coerceAtLeast(0f) * fraction

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(trackWidth)
            .padding(vertical = 8.dp)
            .background(Color(0x11000000))
            .onGloballyPositioned { coords ->
                containerHeightPx = coords.size.height
            }
            .pointerInput(itemCount) {
                detectVerticalDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }) { change, _ ->
                    change.consume()
                    val localY = change.position.y.coerceIn(0f, containerHeightPx.toFloat())
                    val frac =
                        if (containerHeightPx == 0) 0f else (localY / containerHeightPx.toFloat())
                    val rawIndex = (frac * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
                    val quantized = (rawIndex / quantization) * quantization
                    if (quantized != dragIndex) {
                        dragIndex = quantized
                        scope.launch { state.scrollToItem(dragIndex) }
                    }
                }
            }
            .align(Alignment.CenterEnd)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 2.dp)
                .height(thumbHeight)
                .fillMaxWidth()
                .offset { IntOffset(0, thumbOffsetYPx.toInt()) }
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Color.Black, RoundedCornerShape(8.dp))
        )
    }
}