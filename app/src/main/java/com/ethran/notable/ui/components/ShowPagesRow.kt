package com.ethran.notable.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.editor.ui.PageMenu
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.ui.noRippleClickable
import com.onyx.android.sdk.extension.isNullOrEmpty
import compose.icons.FeatherIcons
import compose.icons.feathericons.FilePlus
import io.shipbook.shipbooksdk.ShipBook

private val logPagesRow = ShipBook.getLogger("QuickNav")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowPagesRow(
    singlePages: List<Page>?,
    navController: NavController,
    appRepository: AppRepository,
    folderId: String?,
    showAddQuickPage: Boolean = true,
    currentPageId: String? = null,
    title: String? = "Quick Pages"
) {

    fun onSelectPage(pageId: String) {
        // Navigate to selected page
        val bookId = runCatching {
            appRepository.pageRepository.getById(pageId)?.notebookId
        }.onFailure {
            logPagesRow.d(
                "failed to resolve bookId for $pageId",
                it
            )
        }.getOrNull()

        val url = if (bookId == null) {
            "pages/$pageId"
        } else {
            "books/$bookId/pages/$pageId"
        }
        logPagesRow.d("navigate -> $url")
        navController.navigate(url)
    }

    if (title != null) {
        Text(text = title)
        Spacer(Modifier.height(10.dp))
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        // Add the "Add quick page" button
        if (showAddQuickPage) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(100.dp)
                        .aspectRatio(3f / 4f)
                        .border(1.dp, Color.Gray, RectangleShape)
                        .noRippleClickable {
                            val page = Page(
                                notebookId = null,
                                background = GlobalAppSettings.current.defaultNativeTemplate,
                                backgroundType = BackgroundType.Native.key,
                                parentFolderId = folderId
                            )
                            appRepository.pageRepository.create(page)
                            navController.navigate("pages/${page.id}")
                        }) {
                    Icon(
                        imageVector = FeatherIcons.FilePlus,
                        contentDescription = "Add Quick Page",
                        tint = Color.Gray,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
        // Render existing pages
        if (!singlePages.isNullOrEmpty()) {
            items(singlePages.reversed()) { page ->
                val pageId = page.id
                var isPageSelected by remember { mutableStateOf(false) }
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Box {
                        PagePreview(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        onSelectPage(pageId)
                                    },
                                    onLongClick = {
                                        isPageSelected = true
                                    },
                                )
                                .width(100.dp)
                                .aspectRatio(3f / 4f)
                                .border(
                                    if (currentPageId == pageId) 4.dp else 1.dp,
                                    Color.Black,
                                    RectangleShape
                                ),
                            pageId = pageId
                        )
                        if (isPageSelected) PageMenu(
                            pageId = pageId, canDelete = true, onClose = { isPageSelected = false })
                    }
                    if (!page.name.isNullOrBlank()) {
                        Text(
                            text = page.name,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(100.dp)
                        )
                    }
                }
            }
        }
    }
}