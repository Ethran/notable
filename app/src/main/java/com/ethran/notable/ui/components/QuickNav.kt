package com.ethran.notable.ui.components

import QuickNavUiState
import QuickNavViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.ui.noRippleClickable
import io.shipbook.shipbooksdk.ShipBook

private val logQuickNav = ShipBook.getLogger("QuickNav")



@Composable
fun QuickNav(
    currentPageId: String?,
    quickNavSourcePageId: String?,
    onClose: () -> Unit,
    goToPage: (String) -> Unit,
    goToFolder: (String?) -> Unit,
) {
    val context = LocalContext.current
    val appRepository = remember { AppRepository(context) }

    // Provide the ViewModel using a custom Factory to inject appRepository
    val viewModel: QuickNavViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuickNavViewModel(appRepository) as T
        }
    })

    // Observe the UI State lifecycle-safely
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Load data when the page changes
    LaunchedEffect(currentPageId) {
        viewModel.loadPageData(currentPageId)
    }

    QuickNavContent(
        uiState = uiState,
        onClose = {
            logQuickNav.d("outside tap -> close")
            onClose()
        },
        onNavigateBreadcrumb = { goToFolder(it) },
        onToggleFavorite = viewModel::toggleFavorite,
        onScrubStart = viewModel::onScrubStart,
        onScrubPreview = viewModel::onScrubPreview,
        onScrubEnd = viewModel::onScrubEnd,
        onReturnClick = { viewModel.onReturnClick(quickNavSourcePageId) },
        goToPage = { goToPage(it) },
    )
}

@Composable
fun QuickNavContent(
    uiState: QuickNavUiState,
    onClose: () -> Unit,
    onNavigateBreadcrumb: (String?) -> Unit,
    onToggleFavorite: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubPreview: (Int) -> Unit,
    onScrubEnd: (Int) -> Unit,
    onReturnClick: () -> Unit,
    goToPage: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        // Tap outside to dismiss
        Spacer(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .noRippleClickable(onClick = onClose)
        )

        // Top divider above the sheet
        Box(
            modifier = Modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(Color.Black)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(10.dp)
        ) {

            // Header row: Breadcrumb on the left, Favorite toggle on the right
            QuickNavHeaderRow(
                folders = uiState.breadcrumbFolders,
                isFavorite = uiState.isCurrentPageFavorite,
                canToggleFavorite = uiState.currentPageId != null,
                onNavigateBreadcrumb = onNavigateBreadcrumb,
                onToggleFavorite = onToggleFavorite
            )

            ShowPagesRow(
                pages = uiState.favoritePages,
                currentPageId = uiState.currentPageId,
                title = "Favorite pages",
                onSelectPage = { goToPage(it) }
            )

            // Scrubber block only renders if we have a valid book
            if (uiState.bookPageCount >= 2) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    PageHorizontalSliderWithReturn(
                        pageCount = uiState.bookPageCount,
                        currentIndex = uiState.currentBookIndex,
                        favIndexes = uiState.favoriteIndexesInBook,
                        onDragStart = onScrubStart,
                        onPreviewIndexChanged = onScrubPreview,
                        onDragEnd = onScrubEnd,
                        onReturnClick = onReturnClick
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickNavHeaderRow(
    folders: List<Folder>,
    isFavorite: Boolean,
    canToggleFavorite: Boolean,
    onNavigateBreadcrumb: (String?) -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        BreadCrumb(
            folders = folders, fontSize = 16, onSelectFolderId = onNavigateBreadcrumb
        )

        Spacer(modifier = Modifier.weight(1f))

        ToolbarButton(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            onSelect = {
                if (canToggleFavorite) {
                    onToggleFavorite()
                } else {
                    logQuickNav.w("favorite toggle ignored, pageId=null")
                }
            })
    }
}


@Preview(showBackground = true)
@Composable
fun QuickNavContentPreview() {
    QuickNavContent(
        uiState = QuickNavUiState(
            favoritePages = listOf(Page(id = "page1")),
            isLoading = false,
            currentPageId = "page1",
            folderId = "folder1",
            isCurrentPageFavorite = true,
            bookPageCount = 10,
            currentBookIndex = 4,
            favoriteIndexesInBook = listOf(0, 4, 9)
        ),
        onClose = {},
        onNavigateBreadcrumb = {},
        onToggleFavorite = {},
        onScrubStart = {},
        onScrubPreview = {},
        onScrubEnd = {},
        onReturnClick = {},
        goToPage = {},
    )
}