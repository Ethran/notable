package com.ethran.notable.ui.viewmodels


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.components.getFolderList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * The pages of a notebook, for the scrubber. Only built for a notebook of two or more pages, so
 * its presence is what says there is something to scrub.
 */
data class ScrubberState(
    val pageIds: List<String>,
    val favoriteIndexes: List<Int> = emptyList(),
) {
    fun pageAt(index: Int): String? = pageIds.getOrNull(index)
}

/** Everything QuickNav shows about one page, loaded and published together. */
data class QuickNavUiState(
    /** The page this state describes. Lags the page on screen until the load for it lands. */
    val pageId: String? = null,
    val folderId: String? = null,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val bookId: String? = null,
    val isCurrentPageFavorite: Boolean = false,
    val favoritePages: List<Page> = emptyList(),
    val scrubber: ScrubberState? = null,
)


class QuickNavViewModel(
    private val appRepository: AppRepository,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    private val snackDispatcher: SnackDispatcher,
) : ViewModel() {
    private val pageRepository = appRepository.pageRepository
    private val bookRepository = appRepository.bookRepository
    private val kv = appRepository.kvProxy
    private val log = ShipBook.getLogger("QuickNavViewModel")

    private val _uiState = MutableStateFlow(QuickNavUiState())
    val uiState: StateFlow<QuickNavUiState> = _uiState.asStateFlow()
    private var lastScrubEndTargetPageId: String? = null

    private var loadJob: Job? = null

    // Loads everything that describes the page, then publishes it as one value, so the state never
    // mixes two pages. A new page cancels the load in flight. The publish runs on the main thread,
    // as does this function, and withContext discards its result once the job is cancelled, so a
    // superseded load never lands.
    fun loadPageData(pageId: String?) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = withContext(Dispatchers.IO) { loadState(pageId) }
        }
    }

    private suspend fun loadState(pageId: String?): QuickNavUiState {
        val page = pageId?.let { runCatching { pageRepository.getById(it) }.getOrNull() }
        val favorites = GlobalAppSettings.current.quickNavPages

        return QuickNavUiState(
            pageId = pageId,
            folderId = page?.parentFolderId,
            breadcrumbFolders = getFolderList(appRepository, page),
            bookId = page?.notebookId,
            isCurrentPageFavorite = pageId in favorites,
            favoritePages = pageRepository.getByIds(favorites),
            scrubber = page?.notebookId?.let { loadScrubber(it, favorites) },
        )
    }

    private suspend fun loadScrubber(bookId: String, favorites: List<String>): ScrubberState? {
        val book = bookRepository.getById(bookId)
        if (book == null || book.pageIds.size < 2) return null

        val favIndexes = book.pageIds.mapIndexedNotNull { idx, id ->
            if (favorites.contains(id)) idx else null
        }
        return ScrubberState(pageIds = book.pageIds, favoriteIndexes = favIndexes)
    }

    fun toggleFavorite() {
        val pageId = _uiState.value.pageId ?: return

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

            // Update UI State locally immediately, unless a load for another page landed meanwhile
            _uiState.update { if (it.pageId == pageId) it.copy(isCurrentPageFavorite = !isFav) else it }

            // Re-fetch the rich page objects for the ShowPagesRow
            val updatedFavoritePages = appRepository.pageRepository.getByIds(newFavorites)
            _uiState.update { it.copy(favoritePages = updatedFavoritePages) }
        }
    }

    // --- Scrubber Actions ---

    fun onScrubStart() {
        viewModelScope.launch {
            lastScrubEndTargetPageId = null
            CanvasEventBus.saveCurrent.emit(Unit)
            CanvasEventBus.isScrubbing.emit(true)
        }
    }

    fun onScrubPreview(index: Int) {
        val pageId = _uiState.value.scrubber?.pageAt(index) ?: return
        viewModelScope.launch { CanvasEventBus.previewPage.tryEmit(pageId) }
    }

    fun onScrubEnd(index: Int) {
        val targetPageId = _uiState.value.scrubber?.pageAt(index) ?: return

        viewModelScope.launch {
            log.v("onScrubEnd: $index")

            // moved, to be only run if we are changing page to the current page
//            CanvasEventBus.restoreCanvas.emit(Unit)

            CanvasEventBus.isScrubbing.emit(false)

            // Gesture end callbacks can fire more than once; ignore repeated commit for same target.
            if (targetPageId == lastScrubEndTargetPageId)
            {
                log.e("Events can be send multiple times, really")
                return@launch
            }
            lastScrubEndTargetPageId = targetPageId


            CanvasEventBus.changePage.emit(targetPageId)
        }
    }

    fun onReturnClick(quickNavSourcePageId: String?) {
        if (quickNavSourcePageId == null) {
            snackDispatcher.showOrUpdateSnack(
                SnackConf(text = "Can't go back, no QuickNav source page", duration = 4000)
            )
        } else {
            CanvasEventBus.changePage.tryEmit(quickNavSourcePageId)
        }
    }

    fun generateThumbnailsForCurrentBook() {
        val pageIds = _uiState.value.scrubber?.pageIds ?: return
        thumbnailBackfillQueue.enqueue(pageIds)
    }
}