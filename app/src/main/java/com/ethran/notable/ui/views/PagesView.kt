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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.newPage
import com.ethran.notable.data.deletePage
import com.ethran.notable.editor.ui.toolbar.Topbar
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.editor.utils.setAnimationMode
import com.ethran.notable.ui.components.BreadCrumb
import com.ethran.notable.ui.components.FastScroller
import com.ethran.notable.ui.components.PageCard
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.utils.InsertionSlot
import com.ethran.notable.utils.ReorderableGridItem
import com.ethran.notable.utils.computeInsertionSlotRect
import com.ethran.notable.utils.rememberReorderableGridState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagesView(navController: NavController, bookId: String) {
    val appRepository = AppRepository(LocalContext.current)
    val context = LocalContext.current
    val book by appRepository.bookRepository.getByIdLive(bookId).observeAsState()
    if (book == null) return

    val pageIds = book!!.pageIds
    val openPageId = book?.openPageId
    val bookFolder = book?.parentFolderId

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()


    val reorderState = rememberReorderableGridState()
    val density = LocalDensity.current

    // Initial focus on current page
    LaunchedEffect(openPageId, pageIds) {
        val index = pageIds.indexOf(openPageId)
        if (index >= 0 && !reorderState.wareReordered) {
            Log.d("PagesView", "Initial focus on page $index")
            gridState.scrollToItem(index)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Topbar {
            Row(
                Modifier
                    .padding(10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BreadCrumb(folderId = bookFolder) {
                    navController.navigate("library" + if (it == null) "" else "?folderId=$it")
                }
                Spacer(modifier = Modifier.weight(1f))
                val openId = openPageId
                if (openId != null) {
                    JumpToCurrentPill {
                        val idx = pageIds.indexOf(openId)
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
                }
        ) {
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
                    .autoEInkAnimationOnScroll()
            ) {
                itemsIndexed(
                    items = pageIds,
                    key = { _, id -> id }
                ) { pageIndex, pageId ->
                    val isOpen = pageId == openPageId

                    ReorderableGridItem(
                        itemId = pageId,
                        index = pageIndex,
                        gridState = gridState,
                        state = reorderState,
                        onDrop = { _, to, id ->
                            appRepository.bookRepository.changePageIndex(bookId, id, to)
                        }
                    ) { touchMod ->
                        PageCard(
                            pageId = pageId,
                            pageIndex = pageIndex,
                            isOpen = isOpen,
                            isReorderDragging = reorderState.draggingId == null,
                            touchModifier = touchMod,
                            onOpen = { navController.navigate("books/$bookId/pages/$pageId") },
                            onDelete = { deletePage(context, pageId) },
                            onDuplicate = { appRepository.duplicatePage(pageId) },
                            onAddAfter = {
                                val bookNow =
                                    appRepository.bookRepository.getById(bookId) ?: return@PageCard
                                val newPg = bookNow.newPage()
                                appRepository.pageRepository.create(newPg)
                                appRepository.bookRepository.addPage(
                                    bookId,
                                    newPg.id,
                                    pageIndex + 1
                                )
                            }
                        )
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

                Box(
                    modifier = Modifier
                        .offset { localOffset }
                        .size(width = slotWidthDp, height = slotHeightDp)
                        .background(Color.DarkGray)
                )
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

            if (pageIds.size > 30) {
                FastScroller(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    state = gridState,
                    itemCount = pageIds.size,
                    getVisibleIndex = { gridState.firstVisibleItemIndex },
                    onDragStart = { setAnimationMode(true) },
                    onDragEnd = { setAnimationMode(false) }
                )
            }
        }
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
            ) { onClick() }
    ) {
        Text("Jump to current", color = Color.White)
    }
}