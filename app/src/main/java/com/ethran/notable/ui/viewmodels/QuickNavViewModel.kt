package com.ethran.notable.ui.viewmodels


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.canvas.PaneEventBus
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.components.getFolderList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private const val QUICK_PAGE_LIMIT = 24

data class QuickNavUiState(
    val isLoading: Boolean = true,
    val currentPageId: String? = null,
    val folderId: String? = null,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val bookId: String? = null,
    val isCurrentPageFavorite: Boolean = false,
    val favoritePages: List<Page> = emptyList(),

    // Scrubber specific state
    val bookPageCount: Int = 0,
    val currentBookIndex: Int = 0,
    val favoriteIndexesInBook: List<Int> = emptyList(),
    val bookPageIds: List<String> = emptyList(),

    /**
     * Notebooks the target pane is allowed to open, for choosing a *different* document.
     *
     * Without this the only way out of the picker was the breadcrumb into the library, and the
     * library selects via EditorDestination.createRoute — which rebuilds the whole editor on that
     * page, so a split could never be created from it.
     */
    val notebooks: List<Notebook> = emptyList(),

    /** Loose pages — the library calls these "quick pages" — that the target pane may open. */
    val quickPages: List<Page> = emptyList()
)


class QuickNavViewModel(
    private val appRepository: AppRepository,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    private val snackDispatcher: SnackDispatcher,
    /**
     * The pane this picker acts on, resolved at emit time.
     *
     * A provider rather than a value because the target moves: focus changes while the picker is
     * open, and the editor-hosted picker lets the user aim at the other pane. The app-level default
     * keeps the previous behaviour for callers outside the editor, which have no pane in hand.
     *
     * Reassignable, not just a constructor argument. This ViewModel is scoped to the nav entry and
     * outlives the picker, but the picker's target selector is composable state that is discarded
     * when the sheet closes. Captured once, the provider would go on reading the *first* session's
     * state after a reopen — so a page chosen for one pane could load into the other. The picker
     * re-points this on every composition.
     */
    var targetBus: () -> PaneEventBus = { CanvasEventBus.active },
) : ViewModel() {
    private val pageRepository = appRepository.pageRepository
    private val bookRepository = appRepository.bookRepository
    private val kv = appRepository.kvProxy
    private val log = ShipBook.getLogger("QuickNavViewModel")

    private val _uiState = MutableStateFlow(QuickNavUiState())
    val uiState: StateFlow<QuickNavUiState> = _uiState.asStateFlow()
    private var lastScrubEndTargetPageId: String? = null

    // Held so re-fetches (toggleFavorite) filter the same way the initial load did, rather than
    // quietly reintroducing a page the target pane may not open.
    private var exclusion: PaneExclusion = PaneExclusion.NONE

    /**
     * What the target pane may not open, because another pane already has it.
     *
     * A notebook may be open in at most one pane (ROADMAP §8), and no two panes may show the same
     * page. Rather than resolve a collision after the fact, the picker does not offer one.
     */
    data class PaneExclusion(val notebookId: String?, val pageId: String?) {
        fun allows(page: Page): Boolean {
            if (pageId != null && page.id == pageId) return false
            // Loose pages have no notebook, so they collide by page id only.
            if (notebookId != null && page.notebookId == notebookId) return false
            return true
        }

        companion object {
            /** Nothing excluded — a single pane, or a caller outside the editor. */
            val NONE = PaneExclusion(null, null)
        }
    }

    // Initialize data when the ViewModel is created or when a new page is opened
    fun loadPageData(currentPageId: String?, excludePageId: String? = null) {
        if (currentPageId == null) return

        // Clear the scrubber up front rather than letting the next load overwrite it.
        //
        // loadBookData only *writes* these when the book has two or more pages, so switching to a
        // one-page notebook — or to a quick page, which has no notebook at all — left the previous
        // notebook's values in place. That showed as "0/3" on a one-page notebook: index 0 of a
        // page count belonging to a document no longer in view.
        _uiState.update {
            it.copy(
                isLoading = true,
                currentPageId = currentPageId,
                bookPageCount = 0,
                currentBookIndex = 0,
                favoriteIndexesInBook = emptyList(),
                bookPageIds = emptyList(),
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val page = runCatching { pageRepository.getById(currentPageId) }.getOrNull()
            val folderList = getFolderList(appRepository, page)

            // Resolve the other pane's notebook from its page record rather than from
            // `Pane.notebookId`, which reads OpenPage and is null until that view's load finishes.
            // Trusting the cached copy meant a picker opened in that window excluded nothing, and
            // the same notebook could be opened in both panes.
            val excludedNotebookId = excludePageId
                ?.let { runCatching { pageRepository.getById(it)?.notebookId }.getOrNull() }
            val exclusion = PaneExclusion(excludedNotebookId, excludePageId)
            this@QuickNavViewModel.exclusion = exclusion

            // Read favorites from your database/preferences
            val currentSettings = GlobalAppSettings.current
            val favorites = currentSettings.quickNavPages
            val isFavorite = favorites.contains(currentPageId)

            val favoritePagesDb = appRepository.pageRepository.getByIds(favorites)
                .filter(exclusion::allows)

            // A notebook already open in another pane is not offered (ROADMAP §8), and neither is
            // one with no pages to open.
            val selectableBooks = runCatching { bookRepository.getAll() }.getOrDefault(emptyList())
                .filter { it.id != exclusion.notebookId && it.pageIds.isNotEmpty() }
                .sortedBy { it.title.lowercase() }

            // Capped: this is a horizontal row on a fixed-height sheet, not a browser. The library
            // is the tool for a long list — see ROADMAP §6.
            val quickPagesDb = runCatching { pageRepository.getAllSinglePages() }
                .getOrDefault(emptyList())
                .filter(exclusion::allows)
                .take(QUICK_PAGE_LIMIT)

            _uiState.update { state ->
                state.copy(
                    folderId = page?.parentFolderId,
                    breadcrumbFolders = folderList,
                    bookId = page?.notebookId,
                    isCurrentPageFavorite = isFavorite,
                    favoritePages = favoritePagesDb,
                    notebooks = selectableBooks,
                    quickPages = quickPagesDb,
                )
            }

            // Load Scrubber data if it belongs to a book.
            //
            // Not filtered by the exclusion: this scrubs the *target pane's own* notebook, which
            // the other pane cannot have open, so every page in it is a legal destination.
            page?.notebookId?.let { loadBookData(it, currentPageId, favorites) }

            // Only now is the sheet fully described. Reporting loaded before the scrubber is
            // resolved makes the sheet grow a row a moment after it appears — two repaints on a
            // panel where each one is visible.
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadBookData(
        bookId: String, currentPageId: String, favorites: List<String>
    ) {
        val book = bookRepository.getById(bookId)
        if (book != null && book.pageIds.size >= 2) {
            val currentIdx = appRepository.getPageNumber(bookId, currentPageId)
            val favIndexes = book.pageIds.mapIndexedNotNull { idx, id ->
                if (favorites.contains(id)) idx else null
            }

            _uiState.update { state ->
                state.copy(
                    bookPageCount = book.pageIds.size,
                    currentBookIndex = currentIdx,
                    favoriteIndexesInBook = favIndexes,
                    bookPageIds = book.pageIds
                )
            }
        }
    }

    fun toggleFavorite() {
        val currentState = _uiState.value
        val pageId = currentState.currentPageId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val currentFavorites = settings.quickNavPages
            val isFav = currentFavorites.contains(pageId)

            val newFavorites = if (isFav) {
                currentFavorites.filterNot { it == pageId }
            } else {
                currentFavorites + pageId
            }

            // Save to DB
            kv.setAppSettings(settings.copy(quickNavPages = newFavorites))

            // Update UI State locally immediately
            _uiState.update { it.copy(isCurrentPageFavorite = !isFav) }

            // Re-fetch the rich page objects for the ShowPagesRow
            val updatedFavoritePages = appRepository.pageRepository.getByIds(newFavorites)
                .filter(exclusion::allows)
            _uiState.update { it.copy(favoritePages = updatedFavoritePages) }
        }
    }

    // --- Scrubber Actions ---

    fun onScrubStart() {
        viewModelScope.launch {
            lastScrubEndTargetPageId = null
            targetBus().saveCurrent.emit(Unit)
            targetBus().isScrubbing.emit(true)
        }
    }

    fun onScrubPreview(index: Int) {
        val pageIds = _uiState.value.bookPageIds
        viewModelScope.launch {
            if (index in pageIds.indices) {
                targetBus().previewPage.tryEmit(pageIds[index])
            }
        }
    }

    fun onScrubEnd(index: Int) {
        val pageIds = _uiState.value.bookPageIds
        val targetPageId = pageIds.getOrNull(index) ?: return

        viewModelScope.launch {
            log.v("onScrubEnd: $index")

            // moved, to be only run if we are changing page to the current page
//            targetBus().restoreCanvas.emit(Unit)

            targetBus().isScrubbing.emit(false)

            // Gesture end callbacks can fire more than once; ignore repeated commit for same target.
            if (targetPageId == lastScrubEndTargetPageId)
            {
                log.e("Events can be send multiple times, really")
                return@launch
            }
            lastScrubEndTargetPageId = targetPageId


            targetBus().changePage.emit(targetPageId)
        }
    }

    /**
     * Load [pageId] into the target pane, as a state change rather than a navigation.
     *
     * The app-level QuickNav's favourites row instead calls `NotableNavigator.goToPage`, which
     * navigates to the editor route and rebuilds it — taking both panes with it. That is fine from
     * the library, where there is no editor to preserve, and wrong from inside one.
     */
    fun onPageSelected(pageId: String) {
        viewModelScope.launch {
            targetBus().changePage.emit(pageId)
        }
    }

    /**
     * Create a quick page and hand back its id, or null if creation failed.
     *
     * Creation lives here rather than in the caller so the picker's "new" affordances and its
     * existing selections travel the same path — a created page is opened exactly as a chosen one.
     */
    suspend fun createQuickPage(parentFolderId: String?): String? =
        withContext(Dispatchers.IO) {
            appRepository.createNewQuickPage(parentFolderId)
        }

    /**
     * Create a notebook and hand back the page to open in it.
     *
     * `BookRepository.create` seeds the notebook with a first page and records it as the open one,
     * so there is always something to show.
     */
    suspend fun createNotebook(parentFolderId: String?): String? =
        withContext(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val notebook = Notebook(
                parentFolderId = parentFolderId,
                defaultBackground = settings.defaultNativeTemplate,
                defaultBackgroundType = BackgroundType.Native.key,
            )
            runCatching {
                bookRepository.create(notebook)
                bookRepository.getById(notebook.id)?.openPageId
            }.onFailure { log.e("Failed to create notebook", it) }.getOrNull()
        }

    fun onReturnClick(quickNavSourcePageId: String?) {
        if (quickNavSourcePageId == null) {
            snackDispatcher.showOrUpdateSnack(
                SnackConf(text = "Can't go back, no QuickNav source page", duration = 4000)
            )
        } else {
            targetBus().changePage.tryEmit(quickNavSourcePageId)
        }
    }

    fun generateThumbnailsForCurrentBook() {
        val pageIds = _uiState.value.bookPageIds
        if (pageIds.isEmpty()) return
        thumbnailBackfillQueue.enqueue(pageIds)
    }
}