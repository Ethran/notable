package com.ethran.notable.ui.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.BookRepository
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.db.PageRepository
import com.ethran.notable.editor.DrawCanvas
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.noRippleClickable
import io.shipbook.shipbooksdk.Log
import io.shipbook.shipbooksdk.ShipBook

private val logQuickNav = ShipBook.getLogger("QuickNav")


@Composable
fun QuickNav(
    navController: NavController,
    currentPageId: String?,
    quickNavSourcePageId: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val appRepository = remember { AppRepository(context) }
    val pageRepository = remember { PageRepository(context) }
    val bookRepository = remember { BookRepository(context) }
    val kv = appRepository.kvProxy

    // Read current settings once, but keep a local state list for responsiveness
    val settings = remember { GlobalAppSettings.current }
    var favorites by remember { mutableStateOf(settings.quickNavPages) }

    // Load current page (if pageId exists) and derive folderId
    val pageFromDb = remember(currentPageId) {
        runCatching { currentPageId?.let { pageRepository.getById(it) } }.onFailure {
            logQuickNav.w(
                "failed to load page for pageId=$currentPageId", it
            )
        }.getOrNull()
    }
    val folderId = pageFromDb?.parentFolderId
    val bookId = pageFromDb?.notebookId

    logQuickNav.d("currentPageId = $currentPageId, folderId=$folderId, favoritesCount=${favorites.size}")

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
                .noRippleClickable {
                    logQuickNav.d("outside tap -> close")
                    onClose()
                })

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
                folderId = folderId,
                navController = navController,
                currentPageId = currentPageId,
                favorites = favorites,
                onFavoritesChange = { newList -> favorites = newList },
                kv = kv,
                settings = settings
            )

            ShowPagesRow(
                appRepository.pageRepository.getByIds(favorites),
                navController,
                appRepository,
                folderId = folderId,
                showAddQuickPage = false,
                currentPageId = currentPageId,
                title = "Favorite pages"
            )

            if (bookId != null && currentPageId != null) {
                BookScrubberBlock(
                    appRepository = appRepository,
                    bookRepository = bookRepository,
                    favorites = favorites,
                    bookId = bookId,
                    currentPageId = currentPageId,
                    quickNavSourcePageId = quickNavSourcePageId
                )
            }
        }
    }

}


@Composable
private fun QuickNavHeaderRow(
    folderId: String?,
    navController: NavController,
    currentPageId: String?,
    favorites: List<String>,
    onFavoritesChange: (List<String>) -> Unit,
    kv: KvProxy,
    settings: AppSettings
) {
    // Header row: Breadcrumb on the left, Favorite toggle on the right
    Row(modifier = Modifier.fillMaxWidth()) {
        logQuickNav.d("header row, folderId=$folderId")

        BreadCrumb(
            folderId = folderId, fontSize = 16
        ) { targetFolderId ->
            val route = "library" + if (targetFolderId == null) "" else "?folderId=$targetFolderId"
            logQuickNav.d("breadcrumb navigate -> $route")
            navController.navigate(route)
        }

        Spacer(modifier = Modifier.weight(1f))

        // Favorite toggle for the current page (right side)
        val isFavorite = currentPageId != null && favorites.contains(currentPageId)
        ToolbarButton(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            onSelect = {
                if (currentPageId == null) {
                    logQuickNav.w("favorite toggle ignored, pageId=null")
                    return@ToolbarButton
                }
                // TODO: Here we create unnecessary copy of the list
                val newList = if (isFavorite) favorites.filterNot { it == currentPageId }
                else favorites + currentPageId
                onFavoritesChange(newList)
                // Persist immediately
                kv.setAppSettings(settings.copy(quickNavPages = newList))
                logQuickNav.d(
                    "toggled favorite for pageId=$currentPageId, nowFavorite=${!isFavorite}, total=${newList.size}"
                )
            })
    }
}


@Composable
private fun BookScrubberBlock(
    appRepository: AppRepository,
    bookRepository: BookRepository,
    favorites: List<String>,
    bookId: String,
    currentPageId: String,
    quickNavSourcePageId: String?
) {
    val book = bookRepository.getById(bookId) ?: return
    if (book.pageIds.size < 2) return

    val favoriteIndexesInBook: List<Int> = book.pageIds
        .mapIndexedNotNull { idx, id -> if (favorites.contains(id)) idx else null }

    fun getPageIdFromIndex(index: Int): String {
        return book.pageIds[index]
    }


    // Spacer before the page scrubber area
    Spacer(modifier = Modifier.height(12.dp))

    // Page scrubber placed at the bottom of this dialog (UI only for now)
    Row(modifier = Modifier.fillMaxWidth()) {
        PageHorizontalSliderWithReturn(
            pageCount = book.pageIds.size,
            // Assuming getPageNumber is zero-based; adjust if it returns 1-based
            currentIndex = appRepository.getPageNumber(bookId, currentPageId),
            onDragStart = {
                // Persist current so preview can load from disk
                DrawCanvas.saveCurrent.tryEmit(Unit)
            },
            favIndexes = favoriteIndexesInBook,
            onPreviewIndexChanged = { idx ->
                // Live preview of persisted snapshot (or “No preview available”)
                Log.d("QuickNav", "onPreviewIndexChanged: $idx")
                DrawCanvas.previewPage.tryEmit(getPageIdFromIndex(idx))
            },
            onDragEnd = { idx ->
                // Restore canvas to correct state
                DrawCanvas.restoreCanvas.tryEmit(Unit)
                // Commit: switch PageView to the chosen page
                EditorControlTower.changePage.tryEmit(getPageIdFromIndex(idx))
            },
            onReturnClick = {
                if (quickNavSourcePageId == null)
                    SnackState.globalSnackFlow.tryEmit(
                        SnackConf(
                            text = "Can't go back, no QuickNav source page",
                            duration = 4000
                        )
                    )
                else
                // Go back to the page where QuickNav was opened
                    EditorControlTower.changePage.tryEmit(quickNavSourcePageId)
            })
    }

}