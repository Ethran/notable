package com.ethran.notable.views

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ethran.notable.classes.AppRepository
import com.ethran.notable.components.BreadCrumb
import com.ethran.notable.components.PageMenu
import com.ethran.notable.components.PagePreview
import com.ethran.notable.components.Topbar

@ExperimentalFoundationApi
@Composable
fun PagesView(navController: NavController, bookId: String) {
    val appRepository = AppRepository(LocalContext.current)
    val book by appRepository.bookRepository.getByIdLive(bookId).observeAsState()
    if (book == null) return

    val pageIds = book!!.pageIds
    val openPageId = book?.openPageId
    val bookFolder = book?.parentFolderId

    var selectedPageId by remember {
        mutableStateOf<String?>(null)
    }

    Column(
        Modifier
            .fillMaxSize()
    ) {
        Topbar{
            Row(
                Modifier
                    .padding(10.dp)
            ) {
                BreadCrumb(bookFolder) { navController.navigate("library" + if (it == null) "" else "?folderId=${it}") }
            }
        }
        Column(
            Modifier
                .padding(10.dp)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pageIds.size) { pageIndex ->
                    val pageId = pageIds[pageIndex]
                    val isOpen = pageId == openPageId
                    Box {
                        PagePreview(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .border(if (isOpen) 2.dp else 1.dp, Color.Black, RectangleShape)
                                .combinedClickable(
                                    onClick = {
                                        navController.navigate("books/$bookId/pages/$pageId")
                                    },
                                    onLongClick = {
                                        selectedPageId = pageId
                                    },
                                ),
                            pageId
                        )
                        Text(
                            text = (pageIndex + 1).toString(),
                            modifier = Modifier
                                .background(Color.Black)
                                .padding(5.dp),
                            color = Color.White
                        )
                        if (selectedPageId == pageId) PageMenu(
                            bookId,
                            pageId,
                            pageIndex,
                            canDelete = pageIds.size > 1
                        ) {
                            selectedPageId = null
                        }

                    }
                }
            }
        }
    }
}
