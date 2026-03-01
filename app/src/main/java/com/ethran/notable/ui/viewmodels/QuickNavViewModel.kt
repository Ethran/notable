// QuickNavViewModel.kt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.components.getFolderList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


data class QuickNavUiState( // where do you propose to put it?
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
    val bookPageIds: List<String> = emptyList()
)


class QuickNavViewModel(
    private val appRepository: AppRepository,
) : ViewModel() {
    private val pageRepository = appRepository.pageRepository
    private val bookRepository = appRepository.bookRepository
    private val kv = appRepository.kvProxy

    private val _uiState = MutableStateFlow(QuickNavUiState())
    val uiState: StateFlow<QuickNavUiState> = _uiState.asStateFlow()


    // Initialize data when the ViewModel is created or when a new page is opened
    fun loadPageData(currentPageId: String?) {
        if (currentPageId == null) return

        _uiState.update { it.copy(isLoading = true, currentPageId = currentPageId) }

        viewModelScope.launch(Dispatchers.IO) {
            val page = runCatching { pageRepository.getById(currentPageId) }.getOrNull()
            val folderList = getFolderList(appRepository, page)

            // Read favorites from your database/preferences
            val currentSettings = GlobalAppSettings.current
            val favorites = currentSettings.quickNavPages
            val isFavorite = favorites.contains(currentPageId)

            val favoritePagesDb = appRepository.pageRepository.getByIds(favorites)

            _uiState.update { state ->
                state.copy(
                    folderId = page?.parentFolderId,
                    bookId = page?.notebookId,
                    isCurrentPageFavorite = isFavorite,
                    favoritePages = favoritePagesDb,
                    isLoading = false
                )
            }

            // Load Scrubber data if it belongs to a book
            page?.notebookId?.let { loadBookData(it, currentPageId, favorites) }


            viewModelScope.launch(Dispatchers.IO) {

                _uiState.update { state ->
                    state.copy(
                        folderId = page?.parentFolderId,
                        breadcrumbFolders = folderList, // <-- PASS TO STATE
                        bookId = page?.notebookId,
                        isCurrentPageFavorite = isFavorite,
                        favoritePages = favoritePagesDb,
                        isLoading = false
                    )
                }
            }
        }



    }

    private fun loadBookData(
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
            _uiState.update { it.copy(favoritePages = updatedFavoritePages) }
        }
    }

    // --- Scrubber Actions ---

    fun onScrubStart() {
        CanvasEventBus.saveCurrent.tryEmit(Unit)
    }

    fun onScrubPreview(index: Int) {
        val pageIds = _uiState.value.bookPageIds
        if (index in pageIds.indices) {
            CanvasEventBus.previewPage.tryEmit(pageIds[index])
        }
    }

    fun onScrubEnd(index: Int) {
        CanvasEventBus.restoreCanvas.tryEmit(Unit)
        val pageIds = _uiState.value.bookPageIds
        if (index in pageIds.indices) {
            CanvasEventBus.changePage.tryEmit(pageIds[index])
        }
    }

    fun onReturnClick(quickNavSourcePageId: String?) {
        if (quickNavSourcePageId == null) {
            SnackState.globalSnackFlow.tryEmit(
                SnackConf(text = "Can't go back, no QuickNav source page", duration = 4000)
            )
        } else {
            CanvasEventBus.changePage.tryEmit(quickNavSourcePageId)
        }
    }
}