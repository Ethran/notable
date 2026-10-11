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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** Scrubber data for a notebook with 2+ pages; null in [QuickNavUiState] means no scrubber. */
data class ScrubberState(
    val pageIds: List<String>,
    val favoriteIndexes: List<Int> = emptyList(),
) {
    fun pageAt(index: Int): String? = pageIds.getOrNull(index)
}

/** Everything QuickNav shows about one page, loaded and published together. */
data class QuickNavUiState(
    /** The page this state describes. May lag the page on screen. */
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
    private var requestedPageId: String? = null

    // Publish the whole state in one write; cancelling the previous job guarantees a stale load never lands.
    fun loadPageData(pageId: String?) {
        requestedPageId = pageId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = withContext(Dispatchers.IO) {
                try {
                    loadState(pageId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e("Failed to load QuickNav state for page $pageId: ${e.message}")
                    QuickNavUiState()
                }
            }
        }
    }

    private suspend fun loadState(pageId: String?): QuickNavUiState {
        val page = pageId?.let { runCatching { pageRepository.getById(it) }.getOrNull() }
        val favorites = GlobalAppSettings.current.quickNavPages

        return QuickNavUiState(
            pageId = page?.id,
            folderId = page?.parentFolderId,
            breadcrumbFolders = getFolderList(appRepository, page),
            bookId = page?.notebookId,
            isCurrentPageFavorite = page?.id in favorites,
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

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val settings = GlobalAppSettings.current
                val currentFavorites = settings.quickNavPages
                val newFavorites = if (pageId in currentFavorites) {
                    currentFavorites.filterNot { it == pageId }
                } else {
                    currentFavorites + pageId
                }
                kv.setAppSettings(settings.copy(quickNavPages = newFavorites))
            }
            // Reloading settings.
            loadPageData(requestedPageId)
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
        val targetPageId = _uiState.value.scrubber?.pageAt(index)

        viewModelScope.launch {
            CanvasEventBus.isScrubbing.emit(false)
            if (targetPageId == null) {
                // Nothing to commit; redraw the current page over the last preview.
                CanvasEventBus.restoreCanvas.emit(Unit)
                return@launch
            }

            log.v("onScrubEnd: $index")

            // moved, to be only run if we are changing page to the current page
//            CanvasEventBus.restoreCanvas.emit(Unit)

            // Gesture end callbacks can fire more than once; ignore repeated commit for same target.
            if (targetPageId == lastScrubEndTargetPageId) {
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