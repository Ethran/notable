package com.ethran.notable.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.PageSaver
import com.ethran.notable.data.db.Folder
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.components.getFolderList
import com.ethran.notable.utils.onSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject


data class PagesUiState(
    val bookId: String = "",
    val pageIds: List<String> = emptyList(),
    val openPageId: String? = null,
    val folderList: List<Folder> = emptyList(),
    val isLoading: Boolean = true,
    // True once the observed notebook is gone (deleted under an open screen, or never existed).
    // The screen should stop showing pages and navigate back instead of dereferencing a null book.
    val bookMissing: Boolean = false
)

@HiltViewModel
class PagesViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val pageSaver: PageSaver,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val log = ShipBook.getLogger("PagesViewModel")

    private val _uiState = MutableStateFlow(PagesUiState())
    val uiState: StateFlow<PagesUiState> = _uiState.asStateFlow()

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            appRepository.bookRepository.getByIdLive(bookId).asFlow().collect { book ->
                if (book == null) {
                    // Room re-emits on every write to the table; a null here means the row is gone.
                    // Don't dereference it (that was the NPE). Diagnose why it's null: log
                    // the id and whether the row currently exists in the DB at all.
                    val existsNow = appRepository.bookRepository.getById(bookId) != null
                    log.w("Observed notebook '$bookId' is null (existsNow=$existsNow) — treating as deleted")
                    _uiState.update { it.copy(bookId = bookId, isLoading = false, bookMissing = true) }
                    return@collect
                }
                val folderList = getFolderList(appRepository, book.parentFolderId)
                _uiState.update { it.copy(
                    bookId = bookId,
                    pageIds = book.pageIds,
                    openPageId = book.openPageId,
                    folderList = folderList,
                    isLoading = false,
                    bookMissing = false
                ) }
            }
        }
    }

    fun reorderPage(bookId: String, pageId: String, toIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.bookRepository.changePageIndex(bookId, pageId, toIndex)
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.ethran.notable.data.deletePage(appRepository, pageId, context.filesDir)
        }
    }

    fun duplicatePage(pageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            pageSaver.saveAll().onSuccess { appRepository.duplicatePage(pageId) }
        }
    }

    fun newPageInBook(bookId: String, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.newPageInBook(bookId, index)
        }
    }

    fun generateThumbnailsForCurrentBook() {
        val pageIds = _uiState.value.pageIds
        if (pageIds.isEmpty()) return
        thumbnailBackfillQueue.enqueue(pageIds)
    }
}