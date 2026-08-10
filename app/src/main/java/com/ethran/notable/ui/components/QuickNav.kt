package com.ethran.notable.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import com.ethran.notable.data.db.Notebook
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
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
import androidx.compose.material.icons.filled.Image
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
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.viewmodels.QuickNavUiState
import com.ethran.notable.ui.viewmodels.QuickNavViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.shipbook.shipbooksdk.ShipBook

private val logQuickNav = ShipBook.getLogger("QuickNav")

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickNavEntryPoint {
    fun thumbnailBackfillQueue(): ThumbnailBackfillQueue
    fun snackDispatcher(): SnackDispatcher
}



@Composable
fun QuickNav(
    appRepository: AppRepository,
    currentPageId: String?,
    quickNavSourcePageId: String?,
    onClose: () -> Unit,
    goToPage: (String) -> Unit,
    goToFolder: (String?) -> Unit,
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPoints.get(context.applicationContext, QuickNavEntryPoint::class.java)
    }
    val thumbnailBackfillQueue = entryPoint.thumbnailBackfillQueue()

    // Provide the ViewModel using a custom Factory to inject appRepository
    val viewModel: QuickNavViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuickNavViewModel(
                appRepository = appRepository,
                thumbnailBackfillQueue = thumbnailBackfillQueue,
                snackDispatcher = entryPoint.snackDispatcher()
            ) as T
        }
    })

    // Observe the UI State lifecycle-safely
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Load data when the page changes
    LaunchedEffect(currentPageId) {
        viewModel.loadPageData(currentPageId)
    }

    QuickNavContent(
        appRepository = appRepository,
        uiState = uiState,
        onClose = {
            logQuickNav.d("outside tap -> close")
            onClose()
        },
        onNavigateBreadcrumb = { goToFolder(it) },
        onToggleFavorite = viewModel::toggleFavorite,
        onGenerateBookPreviews = viewModel::generateThumbnailsForCurrentBook,
        onScrubStart = viewModel::onScrubStart,
        onScrubPreview = viewModel::onScrubPreview,
        onScrubEnd = viewModel::onScrubEnd,
        onReturnClick = { viewModel.onReturnClick(quickNavSourcePageId) },
        goToPage = { goToPage(it) },
    )
}

/**
 * @param header Optional row rendered at the top of the sheet, above the breadcrumb. The
 *   editor-hosted picker puts its target-pane selector here; the app-level QuickNav has no panes
 *   to choose between and leaves it empty.
 */
@Composable
fun QuickNavContent(
    appRepository: AppRepository?,
    uiState: QuickNavUiState,
    onClose: () -> Unit,
    onNavigateBreadcrumb: (String?) -> Unit,
    onToggleFavorite: () -> Unit,
    onGenerateBookPreviews: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubPreview: (Int) -> Unit,
    onScrubEnd: (Int) -> Unit,
    onReturnClick: () -> Unit,
    goToPage: (String) -> Unit,
    header: (@Composable () -> Unit)? = null,
    showReturn: Boolean = true,
    showScrubber: Boolean = true,
    /**
     * Notebooks offered as destinations, and what to do when one is chosen. Null hides the row.
     *
     * The in-editor picker needs this: without it the only way to reach a *different* document was
     * the breadcrumb into the library, which navigates and rebuilds the editor — so a second pane
     * could never be created from the picker at all.
     */
    notebooks: List<Notebook> = emptyList(),
    onSelectNotebook: ((Notebook) -> Unit)? = null,
    /**
     * Create a new document and open it in the target. Null hides the affordance.
     *
     * Both kinds are offered, and both from every context. Which document types you can make
     * should not depend on which one you happen to have open.
     */
    onCreateQuickPage: (() -> Unit)? = null,
    onCreateNotebook: (() -> Unit)? = null,
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

            header?.invoke()

            // Header row: Breadcrumb on the left, Favorite toggle on the right
            QuickNavHeaderRow(
                folders = uiState.breadcrumbFolders,
                isFavorite = uiState.isCurrentPageFavorite,
                canToggleFavorite = uiState.currentPageId != null,
                canGeneratePreviews = uiState.bookPageIds.isNotEmpty(),
                onNavigateBreadcrumb = onNavigateBreadcrumb,
                onToggleFavorite = onToggleFavorite,
                onGenerateBookPreviews = onGenerateBookPreviews
            )

            // Nothing below is drawn until the state describes the *current* page.
            //
            // This ViewModel is scoped to the nav entry and outlives the sheet, so reopening it
            // renders the previous session's lists for as long as the reload takes — a trace showed
            // 76ms of the wrong notebook and the wrong page count. Cheaper on e-ink to paint the
            // rows once, correct, than to paint them wrong and then correct them.
            if (uiState.isLoading) return@Column

            if (appRepository != null && uiState.favoritePages.isNotEmpty()) {
                ShowPagesRow(
                    appRepository = appRepository,
                    pages = uiState.favoritePages,
                    currentPageId = uiState.currentPageId,
                    title = "Favorite pages",
                    onSelectPage = { goToPage(it) }
                )
            }

            // Quick pages and notebooks are rendered *differently*, not just labelled
            // differently: page thumbnails versus titled chips. A quick page and a notebook are
            // different kinds of thing, and a row of lookalike thumbnails would make you read a
            // caption to tell which is which every time.
            if (appRepository != null && onCreateQuickPage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                ShowPagesRow(
                    appRepository = appRepository,
                    pages = uiState.quickPages,
                    currentPageId = uiState.currentPageId,
                    title = "Quick pages",
                    onSelectPage = { goToPage(it) },
                    showAddQuickPage = true,
                    onCreateNewQuickPage = onCreateQuickPage,
                )
            }

            if (onSelectNotebook != null) {
                NotebookRow(
                    notebooks = notebooks,
                    onSelect = onSelectNotebook,
                    onCreate = onCreateNotebook,
                )
            }

            // Scrubber block only renders if we have a valid book
            if (showScrubber && uiState.bookPageCount >= 2) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    PageHorizontalSliderWithReturn(
                        pageCount = uiState.bookPageCount,
                        currentIndex = uiState.currentBookIndex,
                        favIndexes = uiState.favoriteIndexesInBook,
                        onDragStart = onScrubStart,
                        onPreviewIndexChanged = onScrubPreview,
                        onDragEnd = onScrubEnd,
                        onReturnClick = onReturnClick,
                        showReturn = showReturn
                    )
                }
            }
        }
    }
}

/**
 * Notebooks to open, as a scrolling row of titles.
 *
 * Titles rather than thumbnails: this is a list of *documents*, and a page preview of whichever
 * page happens to be open says little about which notebook it is. It is also far cheaper to
 * repaint on e-ink than a row of bitmaps.
 */
@Composable
private fun NotebookRow(
    notebooks: List<Notebook>,
    onSelect: (Notebook) -> Unit,
    onCreate: (() -> Unit)? = null,
) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = "Notebooks", fontWeight = FontWeight.Light)
    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        // First, and always present, so its position does not shift with the list contents — and
        // so "create one" is offered in the very case where the list is empty.
        //
        // Verb-first, and a plus rather than a book. "New notebook" is the *default title* of a
        // newly created notebook (see Notebook.title), so a chip labelled that way is
        // indistinguishable from a real entry — and a book icon reads as "a notebook" rather than
        // "make one", which is the same confusion twice over.
        if (onCreate != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .border(1.dp, Color.Gray, RectangleShape)
                    .noRippleClickable { onCreate() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = "Create notebook",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Create notebook", color = Color.DarkGray)
            }
        }

        if (notebooks.isEmpty() && onCreate == null) {
            // Says why there is nothing here. An empty gap would read as a failure to load.
            Text(
                text = "No other notebook available",
                fontWeight = FontWeight.Light,
                color = Color.DarkGray,
            )
        }

        notebooks.forEach { notebook ->
            Text(
                text = notebook.title,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .border(1.dp, Color.Black, RectangleShape)
                    .noRippleClickable { onSelect(notebook) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun QuickNavHeaderRow(
    folders: List<Folder>,
    isFavorite: Boolean,
    canToggleFavorite: Boolean,
    canGeneratePreviews: Boolean,
    onNavigateBreadcrumb: (String?) -> Unit,
    onToggleFavorite: () -> Unit,
    onGenerateBookPreviews: () -> Unit
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

        ToolbarButton(
            imageVector = Icons.Filled.Image,
            onSelect = {
                if (canGeneratePreviews) {
                    onGenerateBookPreviews()
                } else {
                    logQuickNav.w("generate previews ignored, no book pages")
                }
            }
        )
    }
}


@Preview(showBackground = true)
@Composable
fun QuickNavContentPreview() {
    QuickNavContent(
        appRepository = null,
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
        onGenerateBookPreviews = {},
        onScrubStart = {},
        onScrubPreview = {},
        onScrubEnd = {},
        onReturnClick = {},
        goToPage = {},
    )
}